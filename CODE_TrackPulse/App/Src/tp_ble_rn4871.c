/**
 * @file    tp_ble_rn4871.c
 * @brief   Microchip RN4871 BLE driver on LPUART1 (PB10/PB11, RTS PB12, CTS PB13, RST PH0).
 */
#include "tp_ble_rn4871.h"
#include "tp_config.h"
#include "main.h"

#include <stdio.h>
#include <string.h>

static volatile uint8_t  g_rx_ring[TP_BLE_RX_BUF_SIZE];
static volatile uint16_t g_rx_head = 0U;
static volatile uint16_t g_rx_tail = 0U;
static uint8_t           g_rx_byte = 0U;

static tp_parser_t g_parser;
static bool        g_connected      = false;
static uint32_t    g_last_rx_ms     = 0U;
static bool        g_crc_err_flag   = false;
static uint8_t     g_crc_err_op     = 0U;
static uint8_t     g_crc_err_seq    = 0U;

/* Lightweight detector for RN4871 async status strings (%CONNECT,...%, %DISCONNECT%, %STREAM_OPEN%) */
static char    g_status_tok[32];
static uint8_t g_status_idx = 0U;
static bool    g_in_status  = false;

static void ring_push_isr(uint8_t b)
{
    uint16_t next = (uint16_t)((g_rx_head + 1U) % TP_BLE_RX_BUF_SIZE);
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
    g_rx_tail = (uint16_t)((g_rx_tail + 1U) % TP_BLE_RX_BUF_SIZE);
    return true;
}

static void ring_flush(void)
{
    g_rx_tail = g_rx_head;
}

void tp_ble_ensure_rx_armed(void)
{
    if (hlpuart1.RxState == HAL_UART_STATE_READY) {
        (void)HAL_UART_Receive_IT(&hlpuart1, &g_rx_byte, 1U);
    }
}

void tp_ble_uart_rx_isr(void)
{
    ring_push_isr(g_rx_byte);
    (void)HAL_UART_Receive_IT(&hlpuart1, &g_rx_byte, 1U);
}

void tp_ble_uart_err_isr(void)
{
    __HAL_UART_CLEAR_OREFLAG(&hlpuart1);
    __HAL_UART_CLEAR_FEFLAG(&hlpuart1);
    __HAL_UART_CLEAR_NEFLAG(&hlpuart1);
    (void)HAL_UART_Receive_IT(&hlpuart1, &g_rx_byte, 1U);
}

static bool ble_wait_token(const char *expected, uint32_t timeout_ms)
{
    char buf[64];
    uint8_t idx = 0U;
    uint32_t start = HAL_GetTick();
    memset(buf, 0, sizeof(buf));

    while ((HAL_GetTick() - start) < timeout_ms) {
        uint8_t b;
        while (ring_pop(&b)) {
            if (idx < (sizeof(buf) - 1U)) {
                buf[idx++] = (char)b;
                buf[idx]   = '\0';
            } else {
                memmove(buf, &buf[1], sizeof(buf) - 2U);
                buf[sizeof(buf) - 2U] = (char)b;
            }
            if (strstr(buf, expected) != NULL) {
                return true;
            }
        }
        HAL_Delay(2U);
    }
    return false;
}

static void ble_send_raw(const char *str)
{
    (void)HAL_UART_Transmit(&hlpuart1, (uint8_t *)str, (uint16_t)strlen(str), 100U);
}

static void inspect_rn4871_status_byte(uint8_t b)
{
    if (b == (uint8_t)'%') {
        if (!g_in_status) {
            g_in_status  = true;
            g_status_idx = 0U;
        } else {
            g_status_tok[g_status_idx] = '\0';
            g_in_status = false;
            if (strncmp(g_status_tok, "CONNECT", 7U) == 0 ||
                strncmp(g_status_tok, "STREAM_OPEN", 11U) == 0) {
                g_connected = true;
            } else if (strncmp(g_status_tok, "DISCONNECT", 10U) == 0 ||
                       strncmp(g_status_tok, "REBOOT", 6U) == 0) {
                g_connected = false;
            }
        }
        return;
    }

    if (g_in_status) {
        if (b >= 0x20U && b <= 0x7EU && g_status_idx < (sizeof(g_status_tok) - 1U)) {
            g_status_tok[g_status_idx++] = (char)b;
        } else {
            /* Binary byte encountered -> not an ASCII status delimiter */
            g_in_status  = false;
            g_status_idx = 0U;
        }
    }
}

bool tp_ble_init(const char *ble_adv_name)
{
    tp_parser_reset(&g_parser);
    ring_flush();
    g_connected  = false;
    g_in_status  = false;
    g_status_idx = 0U;

    /* Hardware reset pulse on RST_BLE (PH0, active low; stays HIGH at rest) */
    HAL_GPIO_WritePin(RST_BLE_GPIO_Port, RST_BLE_Pin, GPIO_PIN_RESET);
    HAL_Delay(10U);
    HAL_GPIO_WritePin(RST_BLE_GPIO_Port, RST_BLE_Pin, GPIO_PIN_SET);

    tp_ble_ensure_rx_armed();
    (void)ble_wait_token("REBOOT", 250U);

    /* Enter RN4871 command mode ($$$) and configure Transparent UART + advertising name */
    ring_flush();
    ble_send_raw("$$$");
    if (!ble_wait_token("CMD>", 250U)) {
        /* Module absent on bench or already streaming; leave RX armed and return */
        return false;
    }

    char cmd[40];
    if (ble_adv_name != NULL && ble_adv_name[0] != '\0') {
        snprintf(cmd, sizeof(cmd), "SN,%s\r", ble_adv_name);
        ble_send_raw(cmd);
        (void)ble_wait_token("AOK", 150U);
    }

    /* SS,C0: Device Information + Transparent UART service (PROTOCOL.md §2.1) */
    ble_send_raw("SS,C0\r");
    (void)ble_wait_token("AOK", 150U);

    /* SR,0000: Standard peripheral mode with hardware flow control */
    ble_send_raw("SR,0000\r");
    (void)ble_wait_token("AOK", 150U);

    /* Reboot into Transparent UART data mode */
    ble_send_raw("R,1\r");
    (void)ble_wait_token("REBOOT", 300U);
    ring_flush();
    tp_parser_reset(&g_parser);
    return true;
}

bool tp_ble_poll_frame(tp_frame_t *out_frame, uint32_t now_ms)
{
    if (out_frame == NULL) {
        return false;
    }

    tp_ble_ensure_rx_armed();
    tp_parser_check_timeout(&g_parser, now_ms);

    uint8_t b;
    while (ring_pop(&b)) {
        inspect_rn4871_status_byte(b);
        if (tp_parser_feed(&g_parser, b, now_ms)) {
            *out_frame   = g_parser.frame;
            g_connected  = true;
            g_last_rx_ms = now_ms;
            return true;
        }
        if (g_parser.last_crc_error) {
            g_crc_err_flag = true;
            g_crc_err_op   = g_parser.frame.opcode;
            g_crc_err_seq  = g_parser.frame.seq;
        }
    }

    return false;
}

bool tp_ble_send_frame(uint8_t opcode, uint8_t seq, uint8_t flags,
                       const void *payload, uint16_t payload_len)
{
    uint8_t tx_buf[TP_MAX_FRAME_LEN];
    uint16_t len = tp_frame_encode(tx_buf, sizeof(tx_buf), opcode, seq,
                                   (uint8_t)(flags & ~TP_FLAG_IS_4G_CHANNEL),
                                   payload, payload_len);
    if (len == 0U) {
        return false;
    }
    return (HAL_UART_Transmit(&hlpuart1, tx_buf, len, 250U) == HAL_OK);
}

bool tp_ble_is_connected(void)
{
    return g_connected;
}

bool tp_ble_consume_crc_error(uint8_t *out_opcode, uint8_t *out_seq)
{
    if (!g_crc_err_flag) {
        return false;
    }
    g_crc_err_flag = false;
    if (out_opcode != NULL) *out_opcode = g_crc_err_op;
    if (out_seq != NULL)    *out_seq    = g_crc_err_seq;
    return true;
}

void tp_ble_enter_low_power(void)
{
    /* Keep RST_BLE HIGH (avoids 330 uA pull-up loss) and ask RN4871 to stay low-power */
    HAL_GPIO_WritePin(RST_BLE_GPIO_Port, RST_BLE_Pin, GPIO_PIN_SET);
    g_connected = false;
}
