/**
 * @file    tp_modem_sim7080g.c
 * @brief   SIMCom SIM7080G AT driver (GNSS, A-GPS, LTE-M Cat-M1/NB-IoT, Binary MQTT v3.1.1).
 */
#include "tp_modem_sim7080g.h"
#include "tp_board.h"
#include "tp_config.h"
#include "main.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static volatile uint8_t  g_rx_ring[TP_MODEM_RX_BUF_SIZE];
static volatile uint16_t g_rx_head = 0U;
static volatile uint16_t g_rx_tail = 0U;
static uint8_t           g_rx_byte = 0U;

static bool          g_powered        = false;
static bool          g_gnss_on        = false;
static bool          g_lte_attached   = false;
static bool          g_mqtt_connected = false;
static bool          g_agps_valid     = false;
static tp_cmd_agps_t g_agps_cache;
static tp_gnss_fix_t g_last_fix;
static tp_parser_t   g_mqtt_parser;

/* ---- UART Ring Buffer ---- */

static void ring_push_isr(uint8_t b)
{
    uint16_t next = (uint16_t)((g_rx_head + 1U) % TP_MODEM_RX_BUF_SIZE);
    if (next != g_rx_tail) {
        g_rx_ring[g_rx_head] = b;
        g_rx_head = next;
    }
}

static bool ring_pop(uint8_t *b)
{
    if (g_rx_tail == g_rx_head) {
        return false;
    }
    *b = g_rx_ring[g_rx_tail];
    g_rx_tail = (uint16_t)((g_rx_tail + 1U) % TP_MODEM_RX_BUF_SIZE);
    return true;
}

static void ring_flush(void)
{
    g_rx_tail = g_rx_head;
}

static void ensure_rx_armed(void)
{
    if (huart1.RxState == HAL_UART_STATE_READY) {
        (void)HAL_UART_Receive_IT(&huart1, &g_rx_byte, 1U);
    }
}

void tp_modem_uart_rx_isr(void)
{
    ring_push_isr(g_rx_byte);
    (void)HAL_UART_Receive_IT(&huart1, &g_rx_byte, 1U);
}

void tp_modem_uart_err_isr(void)
{
    __HAL_UART_CLEAR_OREFLAG(&huart1);
    __HAL_UART_CLEAR_FEFLAG(&huart1);
    __HAL_UART_CLEAR_NEFLAG(&huart1);
    (void)HAL_UART_Receive_IT(&huart1, &g_rx_byte, 1U);
}

/* ---- AT Command Engine ---- */

static bool at_send_wait(const char *cmd, const char *expect,
                         char *resp_buf, uint16_t resp_cap, uint32_t timeout_ms)
{
    ensure_rx_armed();
    ring_flush();

    if (cmd != NULL && cmd[0] != '\0') {
        if (HAL_UART_Transmit(&huart1, (uint8_t *)cmd, (uint16_t)strlen(cmd), 200U) != HAL_OK) {
            return false;
        }
        static const uint8_t crlf[2] = {'\r', '\n'};
        if (HAL_UART_Transmit(&huart1, (uint8_t *)crlf, 2U, 50U) != HAL_OK) {
            return false;
        }
    }

    char local_buf[256];
    char *dst = (resp_buf != NULL && resp_cap > 0U) ? resp_buf : local_buf;
    uint16_t cap = (resp_buf != NULL && resp_cap > 0U) ? resp_cap : (uint16_t)sizeof(local_buf);
    uint16_t idx = 0U;
    dst[0] = '\0';

    uint32_t start = HAL_GetTick();
    while ((HAL_GetTick() - start) < timeout_ms) {
        uint8_t b;
        while (ring_pop(&b)) {
            if (idx < (cap - 1U)) {
                dst[idx++] = (char)b;
                dst[idx]   = '\0';
            }
            if (expect != NULL && strstr(dst, expect) != NULL) {
                return true;
            }
            if (strstr(dst, "\r\nERROR\r\n") != NULL || strstr(dst, "+CME ERROR") != NULL) {
                return false;
            }
        }
        HAL_Delay(5U);
    }
    return false;
}

/* ---- Fixed-Point Decimal Coordinate Parser (e.g. "47.956012" -> 479560120) ---- */

static int32_t parse_deg_e7(const char *s)
{
    if (s == NULL || s[0] == '\0') return 0;
    bool neg = false;
    if (*s == '-') { neg = true; s++; }
    else if (*s == '+') { s++; }

    int64_t whole = 0;
    while (*s >= '0' && *s <= '9') {
        whole = whole * 10 + (*s - '0');
        s++;
    }
    int64_t frac = 0;
    int digits = 0;
    if (*s == '.') {
        s++;
        while (*s >= '0' && *s <= '9' && digits < 7) {
            frac = frac * 10 + (*s - '0');
            digits++;
            s++;
        }
    }
    while (digits < 7) {
        frac *= 10;
        digits++;
    }
    int64_t res = whole * 10000000LL + frac;
    return (int32_t)(neg ? -res : res);
}

static int32_t parse_scaled100(const char *s)
{
    if (s == NULL || s[0] == '\0') return 0;
    bool neg = false;
    if (*s == '-') { neg = true; s++; }
    int32_t whole = 0;
    while (*s >= '0' && *s <= '9') {
        whole = whole * 10 + (*s - '0');
        s++;
    }
    int32_t frac = 0;
    int digits = 0;
    if (*s == '.') {
        s++;
        while (*s >= '0' && *s <= '9' && digits < 2) {
            frac = frac * 10 + (*s - '0');
            digits++;
            s++;
        }
    }
    while (digits < 2) {
        frac *= 10;
        digits++;
    }
    int32_t res = whole * 100L + frac;
    return neg ? -res : res;
}

static uint32_t parse_cgns_utc_epoch(const char *utc_str)
{
    /* Format: YYYYMMDDhhmmss.sss (at least 14 digits) */
    if (utc_str == NULL || strlen(utc_str) < 14U) {
        return 0U;
    }
    char tmp[5];
    memcpy(tmp, &utc_str[0], 4); tmp[4] = '\0';
    uint32_t y = (uint32_t)atoi(tmp);
    tmp[2] = '\0';
    memcpy(tmp, &utc_str[4], 2);  uint8_t mo = (uint8_t)atoi(tmp);
    memcpy(tmp, &utc_str[6], 2);  uint8_t da = (uint8_t)atoi(tmp);
    memcpy(tmp, &utc_str[8], 2);  uint8_t hh = (uint8_t)atoi(tmp);
    memcpy(tmp, &utc_str[10], 2); uint8_t mm = (uint8_t)atoi(tmp);
    memcpy(tmp, &utc_str[12], 2); uint8_t ss = (uint8_t)atoi(tmp);

    if (y < 2020U || mo < 1U || mo > 12U || da < 1U || da > 31U) {
        return 0U;
    }

    static const uint8_t k_dim[12] = {31,28,31,30,31,30,31,31,30,31,30,31};
    uint32_t days = 0U;
    for (uint32_t yr = 1970U; yr < y; yr++) {
        bool leap = ((yr % 4U == 0U) && (yr % 100U != 0U)) || (yr % 400U == 0U);
        days += leap ? 366U : 365U;
    }
    for (uint8_t m = 1U; m < mo; m++) {
        days += k_dim[m - 1U];
        if (m == 2U && (((y % 4U == 0U) && (y % 100U != 0U)) || (y % 400U == 0U))) {
            days++;
        }
    }
    days += (uint32_t)(da - 1U);
    return (((days * 24U) + hh) * 60U + mm) * 60U + ss;
}

/* ---- Public API ---- */

void tp_modem_init(void)
{
    memset(&g_last_fix, 0, sizeof(g_last_fix));
    memset(&g_agps_cache, 0, sizeof(g_agps_cache));
    tp_parser_reset(&g_mqtt_parser);
    ring_flush();

    HAL_GPIO_WritePin(PWRKEY_GPIO_Port, PWRKEY_Pin, GPIO_PIN_RESET);
    HAL_GPIO_WritePin(DTR_SIM7080G_GPIO_Port, DTR_SIM7080G_Pin, GPIO_PIN_RESET);
    g_powered        = false;
    g_gnss_on        = false;
    g_lte_attached   = false;
    g_mqtt_connected = false;
    g_agps_valid     = false;

    ensure_rx_armed();
}

void tp_modem_uart_sleep(bool sleep)
{
    HAL_GPIO_WritePin(DTR_SIM7080G_GPIO_Port, DTR_SIM7080G_Pin,
                      sleep ? GPIO_PIN_SET : GPIO_PIN_RESET);
    if (!sleep) {
        HAL_Delay(50U);
    }
}

bool tp_modem_power_on(void)
{
    tp_modem_uart_sleep(false);

    /* Quick probe in case modem is already awake */
    if (at_send_wait("AT", "OK", NULL, 0U, 300U)) {
        g_powered = true;
    } else {
        /* SIM7080G power-on pulse: PWRKEY asserted for 1.2 s */
        HAL_GPIO_WritePin(PWRKEY_GPIO_Port, PWRKEY_Pin, GPIO_PIN_SET);
        HAL_Delay(1200U);
        HAL_GPIO_WritePin(PWRKEY_GPIO_Port, PWRKEY_Pin, GPIO_PIN_RESET);

        /* Wait up to 4 s for AT prompt */
        g_powered = false;
        for (uint8_t tries = 0U; tries < 8U; tries++) {
            HAL_Delay(400U);
            if (at_send_wait("AT", "OK", NULL, 0U, 400U)) {
                g_powered = true;
                break;
            }
        }
    }

    if (!g_powered) {
        return false;
    }

    (void)at_send_wait("ATE0", "OK", NULL, 0U, 400U);
    (void)at_send_wait("AT+CMEE=1", "OK", NULL, 0U, 400U);
    (void)at_send_wait("AT+CSCLK=1", "OK", NULL, 0U, 400U);

    /* Push cached A-GPS time if available */
    if (g_agps_valid) {
        (void)tp_modem_gnss_inject_agps(&g_agps_cache);
    }
    return true;
}

void tp_modem_power_off(void)
{
    if (g_mqtt_connected) {
        tp_modem_mqtt_disconnect();
    }
    if (g_gnss_on) {
        (void)tp_modem_gnss_enable(false);
    }
    if (g_powered) {
        tp_modem_uart_sleep(false);
        (void)at_send_wait("AT+CPOWD=1", "NORMAL POWER DOWN", NULL, 0U, 1500U);
    }
    g_powered        = false;
    g_gnss_on        = false;
    g_lte_attached   = false;
    g_mqtt_connected = false;
    HAL_GPIO_WritePin(PWRKEY_GPIO_Port, PWRKEY_Pin, GPIO_PIN_RESET);
    HAL_GPIO_WritePin(DTR_SIM7080G_GPIO_Port, DTR_SIM7080G_Pin, GPIO_PIN_RESET);
}

bool tp_modem_is_powered(void)       { return g_powered; }
bool tp_modem_is_lte_attached(void)  { return g_lte_attached; }
bool tp_modem_is_mqtt_connected(void){ return g_mqtt_connected; }
bool tp_modem_is_agps_valid(void)    { return g_agps_valid; }
const tp_gnss_fix_t *tp_modem_get_last_fix(void) { return &g_last_fix; }

bool tp_modem_gnss_enable(bool enable)
{
    if (enable && !g_powered) {
        if (!tp_modem_power_on()) {
            return false;
        }
    }
    if (!g_powered) {
        g_gnss_on = false;
        return !enable;
    }

    tp_modem_uart_sleep(false);
    if (enable) {
        /* Enable GPS + Galileo constellations */
        (void)at_send_wait("AT+CGNSMOD=1,0,0,1,0", "OK", NULL, 0U, 500U);
        if (at_send_wait("AT+CGNSPWR=1", "OK", NULL, 0U, 1000U)) {
            g_gnss_on = true;
            if (g_agps_valid) {
                /* Warm start with injected RTC/position reference */
                (void)at_send_wait("AT+CGNSWARM", "OK", NULL, 0U, 500U);
            }
            return true;
        }
        return false;
    } else {
        (void)at_send_wait("AT+CGNSPWR=0", "OK", NULL, 0U, 1000U);
        g_gnss_on = false;
        return true;
    }
}

bool tp_modem_gnss_inject_agps(const tp_cmd_agps_t *agps)
{
    if (agps == NULL) {
        return false;
    }
    g_agps_cache = *agps;
    g_agps_valid = true;

    /* Update internal STM32 RTC immediately when valid UTC time is provided */
    if ((agps->flags & 0x02U) != 0U && agps->utc_epoch_sec > 1577836800UL) {
        (void)tp_rtc_set_epoch(agps->utc_epoch_sec);
    }

    /* Seed last known position immediately if no live GNSS fix yet */
    if ((agps->flags & 0x01U) != 0U && !g_last_fix.has_fix &&
        (agps->lat_e7 != 0 || agps->lon_e7 != 0)) {
        g_last_fix.lat_e7        = agps->lat_e7;
        g_last_fix.lon_e7        = agps->lon_e7;
        g_last_fix.alt_dm        = agps->alt_dm;
        g_last_fix.utc_epoch_sec = agps->utc_epoch_sec;
    }

    /* If SIM7080G is currently powered, push UTC time & reference assistance */
    if (g_powered) {
        tp_modem_uart_sleep(false);
        RTC_TimeTypeDef t = {0};
        RTC_DateTypeDef d = {0};
        if (HAL_RTC_GetTime(&hrtc, &t, RTC_FORMAT_BIN) == HAL_OK &&
            HAL_RTC_GetDate(&hrtc, &d, RTC_FORMAT_BIN) == HAL_OK) {
            char cmd[64];
            snprintf(cmd, sizeof(cmd),
                     "AT+CCLK=\"%02u/%02u/%02u,%02u:%02u:%02u+00\"",
                     (unsigned)d.Year, (unsigned)d.Month, (unsigned)d.Date,
                     (unsigned)t.Hours, (unsigned)t.Minutes, (unsigned)t.Seconds);
            (void)at_send_wait(cmd, "OK", NULL, 0U, 400U);
        }
        /* Enable GNSS XTRA/assistance if supported by SIM7080G firmware */
        (void)at_send_wait("AT+CGNSXTRA=1", "OK", NULL, 0U, 400U);
    }
    return true;
}

bool tp_modem_gnss_poll(tp_gnss_fix_t *out_fix)
{
    if (!g_powered || !g_gnss_on) {
        if (out_fix != NULL) *out_fix = g_last_fix;
        return false;
    }

    char resp[256];
    if (!at_send_wait("AT+CGNSINF", "OK", resp, sizeof(resp), 600U)) {
        if (out_fix != NULL) *out_fix = g_last_fix;
        return false;
    }

    char *p = strstr(resp, "+CGNSINF:");
    if (p == NULL) {
        if (out_fix != NULL) *out_fix = g_last_fix;
        return false;
    }
    p += 9;
    while (*p == ' ') p++;

    /* Split comma-separated fields of +CGNSINF:
       0:run 1:fix 2:utc 3:lat 4:lon 5:alt 6:speed_kmh 7:course ... 14:sats_view 15:sats_used */
    char *fields[20];
    uint8_t nfields = 0U;
    fields[nfields++] = p;
    while (*p != '\0' && *p != '\r' && *p != '\n' && nfields < 20U) {
        if (*p == ',') {
            *p = '\0';
            fields[nfields++] = p + 1;
        }
        p++;
    }
    *p = '\0';

    if (nfields < 7U) {
        if (out_fix != NULL) *out_fix = g_last_fix;
        return false;
    }

    bool fix_ok = (fields[1][0] == '1');
    g_last_fix.has_fix = fix_ok;

    if (nfields > 15U && fields[15][0] != '\0') {
        g_last_fix.sats = (uint8_t)atoi(fields[15]);
    } else if (nfields > 14U && fields[14][0] != '\0') {
        g_last_fix.sats = (uint8_t)atoi(fields[14]);
    }

    if (fix_ok) {
        g_last_fix.lat_e7 = parse_deg_e7(fields[3]);
        g_last_fix.lon_e7 = parse_deg_e7(fields[4]);
        int32_t alt_cm    = parse_scaled100(fields[5]);
        g_last_fix.alt_dm = (int16_t)(alt_cm / 10L);

        /* Speed in +CGNSINF is km/h -> cm/s = kmh_x100 * 100 / 360 = kmh_x100 * 5 / 18 */
        int32_t spd_kmh_x100 = parse_scaled100(fields[6]);
        if (spd_kmh_x100 < 0) spd_kmh_x100 = 0;
        uint32_t cms = ((uint32_t)spd_kmh_x100 * 5UL) / 18UL;
        g_last_fix.speed_cms = (cms > 65535UL) ? 65535U : (uint16_t)cms;

        uint32_t utc = parse_cgns_utc_epoch(fields[2]);
        if (utc > 0U) {
            g_last_fix.utc_epoch_sec = utc;
            if (!tp_rtc_is_synced()) {
                (void)tp_rtc_set_epoch(utc);
            }
        }
    }

    if (out_fix != NULL) {
        *out_fix = g_last_fix;
    }
    return fix_ok;
}

bool tp_modem_acquire_fix(uint16_t timeout_sec, tp_gnss_fix_t *out_fix)
{
    if (!tp_modem_gnss_enable(true)) {
        return false;
    }
    uint32_t start = HAL_GetTick();
    uint32_t limit = (uint32_t)timeout_sec * 1000UL;
    while ((HAL_GetTick() - start) < limit) {
        if (tp_modem_gnss_poll(out_fix)) {
            return true;
        }
        HAL_Delay(1000U);
    }
    return false;
}

/* ---- 4G LTE-M + Binary MQTT v3.1.1 ---- */

bool tp_modem_mqtt_connect(const char *dev_id_lower)
{
    if (dev_id_lower == NULL) {
        return false;
    }
    if (!g_powered && !tp_modem_power_on()) {
        return false;
    }
    if (g_mqtt_connected) {
        return true;
    }

    tp_modem_uart_sleep(false);

    /* Ensure RF enabled, LTE Cat-M1 / NB-IoT configured */
    (void)at_send_wait("AT+CFUN=1", "OK", NULL, 0U, 2000U);
    (void)at_send_wait("AT+CNMP=38", "OK", NULL, 0U, 500U);
    (void)at_send_wait("AT+CMNB=3", "OK", NULL, 0U, 500U);

    char cmd[96];
    snprintf(cmd, sizeof(cmd), "AT+CGDCONT=1,\"IP\",\"%s\"", TP_LTE_APN);
    (void)at_send_wait(cmd, "OK", NULL, 0U, 1000U);

    /* Wait up to 12 s for LTE EPS registration (+CEREG: x,1 or x,5) */
    g_lte_attached = false;
    for (uint8_t i = 0U; i < 12U; i++) {
        char resp[96];
        if (at_send_wait("AT+CEREG?", "OK", resp, sizeof(resp), 600U)) {
            if (strstr(resp, ",1") != NULL || strstr(resp, ",5") != NULL) {
                g_lte_attached = true;
                break;
            }
        }
        HAL_Delay(1000U);
    }
    if (!g_lte_attached) {
        return false;
    }

    /* Activate PDP context 0 */
    (void)at_send_wait("AT+CNACT=0,1", "OK", NULL, 0U, 5000U);

    /* Configure MQTT v3.1.1 session */
    snprintf(cmd, sizeof(cmd), "AT+SMCONF=\"URL\",\"%s\",%u",
             TP_MQTT_BROKER_HOST, (unsigned)TP_MQTT_BROKER_PORT);
    (void)at_send_wait(cmd, "OK", NULL, 0U, 500U);

    snprintf(cmd, sizeof(cmd), "AT+SMCONF=\"CLIENTID\",\"tp-mcu-%s\"", dev_id_lower);
    (void)at_send_wait(cmd, "OK", NULL, 0U, 500U);

    snprintf(cmd, sizeof(cmd), "AT+SMCONF=\"KEEPTIME\",%u", (unsigned)TP_MQTT_KEEPALIVE_SEC);
    (void)at_send_wait(cmd, "OK", NULL, 0U, 500U);
    (void)at_send_wait("AT+SMCONF=\"CLEANSS\",1", "OK", NULL, 0U, 500U);

    if (!at_send_wait("AT+SMCONN", "OK", NULL, 0U, 8000U)) {
        return false;
    }

    /* Subscribe to compact downlink topic tp/<id>/d with QoS 1 */
    snprintf(cmd, sizeof(cmd), "AT+SMSUB=\"tp/%s/d\",1", dev_id_lower);
    (void)at_send_wait(cmd, "OK", NULL, 0U, 3000U);

    tp_parser_reset(&g_mqtt_parser);
    g_mqtt_connected = true;
    return true;
}

void tp_modem_mqtt_disconnect(void)
{
    if (!g_powered) {
        g_mqtt_connected = false;
        return;
    }
    if (g_mqtt_connected) {
        (void)at_send_wait("AT+SMDISC", "OK", NULL, 0U, 2000U);
        g_mqtt_connected = false;
    }
    (void)at_send_wait("AT+CNACT=0,0", "OK", NULL, 0U, 2000U);
}

bool tp_modem_mqtt_publish_frame(const char *topic, uint8_t qos, uint8_t retain,
                                 uint8_t opcode, uint8_t seq,
                                 const void *payload, uint16_t payload_len)
{
    if (!g_mqtt_connected || topic == NULL) {
        return false;
    }

    uint8_t frame[TP_MAX_FRAME_LEN];
    uint16_t flen = tp_frame_encode(frame, sizeof(frame), opcode, seq,
                                    TP_FLAG_IS_4G_CHANNEL, payload, payload_len);
    if (flen == 0U) {
        return false;
    }

    char cmd[80];
    snprintf(cmd, sizeof(cmd), "AT+SMPUB=\"%s\",%u,%u,%u",
             topic, (unsigned)flen, (unsigned)(qos & 1U), (unsigned)(retain & 1U));

    if (!at_send_wait(cmd, ">", NULL, 0U, 2000U)) {
        return false;
    }

    if (HAL_UART_Transmit(&huart1, frame, flen, 500U) != HAL_OK) {
        return false;
    }

    return at_send_wait(NULL, "OK", NULL, 0U, 5000U);
}

bool tp_modem_poll_mqtt_frame(tp_frame_t *out_frame, uint32_t now_ms)
{
    if (!g_mqtt_connected || out_frame == NULL) {
        return false;
    }

    ensure_rx_armed();
    tp_parser_check_timeout(&g_mqtt_parser, now_ms);

    uint8_t b;
    while (ring_pop(&b)) {
        if (tp_parser_feed(&g_mqtt_parser, b, now_ms)) {
            *out_frame = g_mqtt_parser.frame;
            out_frame->flags |= TP_FLAG_IS_4G_CHANNEL;
            return true;
        }
    }
    return false;
}
