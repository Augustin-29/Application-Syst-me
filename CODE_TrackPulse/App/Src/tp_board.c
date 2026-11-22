/**
 * @file    tp_board.c
 * @brief   Board-level services for TrackPulse STM32L433CCT6.
 */
#include "tp_board.h"
#include "tp_config.h"
#include "main.h"
#include "usbd_cdc_if.h"
#include "usbd_def.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

extern USBD_HandleTypeDef hUsbDeviceFS;

static tp_device_identity_t g_identity;
static volatile bool g_rtc_wakeup_flag = false;
static volatile bool g_acc_int1_flag   = false;
static bool     g_rtc_synced           = false;
static uint32_t g_boot_rtc_epoch       = 0U;
static uint32_t g_uptime_base_sec      = 0U;

/* Button state machine */
typedef struct {
    bool     raw_prev;
    bool     stable;
    uint32_t last_change_ms;
    uint32_t press_start_ms;
    bool     long_fired;
} btn_state_t;

static btn_state_t g_bp1 = {0};
static btn_state_t g_bp2 = {0};
static bool        g_both_long_fired = false;
static uint32_t    g_both_start_ms   = 0U;

/* ---- Calendar <-> Unix Epoch helpers (UTC, 2000..2099) ---- */

static bool is_leap_year(uint32_t year)
{
    return ((year % 4U == 0U) && (year % 100U != 0U)) || (year % 400U == 0U);
}

static uint8_t days_in_month(uint32_t year, uint8_t month)
{
    static const uint8_t k_dim[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (month < 1U || month > 12U) {
        return 30U;
    }
    uint8_t d = k_dim[month - 1U];
    if (month == 2U && is_leap_year(year)) {
        d = 29U;
    }
    return d;
}

static uint32_t ymd_hms_to_epoch(uint32_t year, uint8_t mon, uint8_t day,
                                 uint8_t hour, uint8_t min, uint8_t sec)
{
    uint32_t days = 0U;
    if (year < 1970U) {
        year = 2026U;
    }
    for (uint32_t y = 1970U; y < year; y++) {
        days += is_leap_year(y) ? 366U : 365U;
    }
    for (uint8_t m = 1U; m < mon; m++) {
        days += days_in_month(year, m);
    }
    if (day > 0U) {
        days += (uint32_t)(day - 1U);
    }
    return (((days * 24U) + hour) * 60U + min) * 60U + sec;
}

static void epoch_to_ymd_hms(uint32_t epoch, uint32_t *year, uint8_t *mon, uint8_t *day,
                             uint8_t *hour, uint8_t *min, uint8_t *sec, uint8_t *wday)
{
    uint32_t rem = epoch;
    *sec  = (uint8_t)(rem % 60U);
    rem  /= 60U;
    *min  = (uint8_t)(rem % 60U);
    rem  /= 60U;
    *hour = (uint8_t)(rem % 24U);
    uint32_t days = rem / 24U;

    /* 1970-01-01 was Thursday (4). STM32 RTC_WEEKDAY: Mon=1..Sun=7 */
    uint8_t wd = (uint8_t)((days + 3U) % 7U) + 1U;
    *wday = wd;

    uint32_t y = 1970U;
    while (true) {
        uint32_t yd = is_leap_year(y) ? 366U : 365U;
        if (days < yd) {
            break;
        }
        days -= yd;
        y++;
    }

    uint8_t m = 1U;
    while (m <= 12U) {
        uint8_t md = days_in_month(y, m);
        if (days < md) {
            break;
        }
        days -= md;
        m++;
    }

    *year = y;
    *mon  = m;
    *day  = (uint8_t)(days + 1U);
}

/* ---- Public API ---- */

void tp_board_init(void)
{
    uint32_t uid0 = HAL_GetUIDw0();
    uint32_t uid1 = HAL_GetUIDw1();
    uint32_t uid2 = HAL_GetUIDw2();

    uint16_t short_id = (uint16_t)((uid0 ^ (uid0 >> 16) ^ uid2) & 0xFFFFU);
    if (short_id == 0U) {
        short_id = 0x8F3AU;
    }

    snprintf(g_identity.device_id_upper, sizeof(g_identity.device_id_upper),
             "%04X", (unsigned)short_id);
    snprintf(g_identity.device_id_lower, sizeof(g_identity.device_id_lower),
             "%04x", (unsigned)short_id);
    snprintf(g_identity.ble_name, sizeof(g_identity.ble_name),
             "TrackPulse-%04X", (unsigned)short_id);

    /* Deterministic 6-digit factory default pairing PIN from UID */
    uint32_t key_num = ((uid1 ^ (uid2 * 2654435761UL)) % 900000UL) + 100000UL;
    snprintf(g_identity.default_key, sizeof(g_identity.default_key),
             "%06lu", (unsigned long)key_num);

    g_boot_rtc_epoch = tp_rtc_get_epoch();
    g_uptime_base_sec = 0U;

    /* Check if RTC was already synced across warm reset */
    if (HAL_RTCEx_BKUPRead(&hrtc, RTC_BKP_DR0) == TP_RTC_BKP_MAGIC) {
        g_rtc_synced = true;
    }
}

const tp_device_identity_t *tp_board_get_identity(void)
{
    return &g_identity;
}

void tp_led_set(bool on)
{
    HAL_GPIO_WritePin(LED1_GPIO_Port, LED1_Pin, on ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

void tp_led_toggle(void)
{
    HAL_GPIO_TogglePin(LED1_GPIO_Port, LED1_Pin);
}

bool tp_btn_bp1_is_pressed(void)
{
    return (HAL_GPIO_ReadPin(BP1_GPIO_Port, BP1_Pin) == GPIO_PIN_RESET);
}

bool tp_btn_bp2_is_pressed(void)
{
    return (HAL_GPIO_ReadPin(BP2_GPIO_Port, BP2_Pin) == GPIO_PIN_RESET);
}

static bool update_btn(btn_state_t *b, bool raw, uint32_t now_ms, bool *released_short, bool *fired_long)
{
    *released_short = false;
    *fired_long = false;

    if (raw != b->raw_prev) {
        b->raw_prev = raw;
        b->last_change_ms = now_ms;
    }
    if ((now_ms - b->last_change_ms) >= TP_BTN_DEBOUNCE_MS) {
        if (raw != b->stable) {
            b->stable = raw;
            if (b->stable) {
                b->press_start_ms = now_ms;
                b->long_fired = false;
            } else {
                if (!b->long_fired && !g_both_long_fired) {
                    *released_short = true;
                }
            }
        }
    }
    if (b->stable && !b->long_fired && ((now_ms - b->press_start_ms) >= TP_BTN_LONG_PRESS_MS)) {
        b->long_fired = true;
        *fired_long = true;
    }
    return b->stable;
}

tp_btn_event_t tp_btn_poll(uint32_t now_ms)
{
    bool bp1_short = false, bp1_long = false;
    bool bp2_short = false, bp2_long = false;

    bool s1 = update_btn(&g_bp1, tp_btn_bp1_is_pressed(), now_ms, &bp1_short, &bp1_long);
    bool s2 = update_btn(&g_bp2, tp_btn_bp2_is_pressed(), now_ms, &bp2_short, &bp2_long);

    if (s1 && s2) {
        if (g_both_start_ms == 0U) {
            g_both_start_ms = now_ms;
        }
        if (!g_both_long_fired && ((now_ms - g_both_start_ms) >= TP_BTN_LONG_PRESS_MS)) {
            g_both_long_fired = true;
            g_bp1.long_fired = true;
            g_bp2.long_fired = true;
            return TP_BTN_EVENT_BOTH_LONG;
        }
        return TP_BTN_EVENT_NONE;
    } else {
        g_both_start_ms = 0U;
        if (!s1 && !s2) {
            g_both_long_fired = false;
        }
    }

    if (bp1_long)  return TP_BTN_EVENT_BP1_LONG;
    if (bp2_long)  return TP_BTN_EVENT_BP2_LONG;
    if (bp1_short) return TP_BTN_EVENT_BP1_SHORT;
    if (bp2_short) return TP_BTN_EVENT_BP2_SHORT;
    return TP_BTN_EVENT_NONE;
}

uint32_t tp_rtc_get_epoch(void)
{
    RTC_TimeTypeDef sTime = {0};
    RTC_DateTypeDef sDate = {0};

    /* Reading Time MUST be followed by reading Date to unlock shadow registers */
    if (HAL_RTC_GetTime(&hrtc, &sTime, RTC_FORMAT_BIN) != HAL_OK) {
        return g_boot_rtc_epoch + (HAL_GetTick() / 1000U);
    }
    if (HAL_RTC_GetDate(&hrtc, &sDate, RTC_FORMAT_BIN) != HAL_OK) {
        return g_boot_rtc_epoch + (HAL_GetTick() / 1000U);
    }

    return ymd_hms_to_epoch(2000U + (uint32_t)sDate.Year, sDate.Month, sDate.Date,
                            sTime.Hours, sTime.Minutes, sTime.Seconds);
}

bool tp_rtc_set_epoch(uint32_t epoch_sec)
{
    /* Only accept plausible UTC timestamps (year 2020..2099) */
    if (epoch_sec < 1577836800UL) {
        return false;
    }

    /* Preserve elapsed uptime across RTC jump */
    uint32_t prev_uptime = tp_board_get_uptime_sec();

    uint32_t year;
    uint8_t mon, day, hour, min, sec, wday;
    epoch_to_ymd_hms(epoch_sec, &year, &mon, &day, &hour, &min, &sec, &wday);
    if (year < 2000U || year > 2099U) {
        return false;
    }

    RTC_TimeTypeDef sTime = {0};
    RTC_DateTypeDef sDate = {0};

    sTime.Hours          = hour;
    sTime.Minutes        = min;
    sTime.Seconds        = sec;
    sTime.DayLightSaving = RTC_DAYLIGHTSAVING_NONE;
    sTime.StoreOperation = RTC_STOREOPERATION_RESET;

    sDate.WeekDay = wday;
    sDate.Month   = mon;
    sDate.Date    = day;
    sDate.Year    = (uint8_t)(year - 2000U);

    HAL_PWR_EnableBkUpAccess();
    if (HAL_RTC_SetTime(&hrtc, &sTime, RTC_FORMAT_BIN) != HAL_OK) {
        return false;
    }
    if (HAL_RTC_SetDate(&hrtc, &sDate, RTC_FORMAT_BIN) != HAL_OK) {
        return false;
    }

    HAL_RTCEx_BKUPWrite(&hrtc, RTC_BKP_DR0, TP_RTC_BKP_MAGIC);
    g_rtc_synced      = true;
    g_uptime_base_sec = prev_uptime;
    g_boot_rtc_epoch  = epoch_sec;
    return true;
}

bool tp_rtc_is_synced(void)
{
    return g_rtc_synced;
}

uint32_t tp_board_get_uptime_sec(void)
{
    uint32_t now_epoch = tp_rtc_get_epoch();
    if (now_epoch >= g_boot_rtc_epoch) {
        return g_uptime_base_sec + (now_epoch - g_boot_rtc_epoch);
    }
    return g_uptime_base_sec + (HAL_GetTick() / 1000U);
}

void tp_power_enter_stop2(uint16_t wake_seconds)
{
    /* Turn off LED to avoid ~1.3 mA drain during sleep */
    tp_led_set(false);

    /* Configure LPUART1 wakeup from Stop 2 on start bit (HSI16 clock source) */
    UART_WakeUpTypeDef wakeup_cfg = {0};
    wakeup_cfg.WakeUpEvent = UART_WAKEUP_ON_STARTBIT;
    (void)HAL_UARTEx_StopModeWakeUpSourceConfig(&hlpuart1, wakeup_cfg);
    (void)HAL_UARTEx_EnableStopMode(&hlpuart1);

    /* Configure RTC WakeUp Timer on 1 Hz ck_spre clock (1..65535 s) */
    (void)HAL_RTCEx_DeactivateWakeUpTimer(&hrtc);
    if (wake_seconds > 0U) {
        (void)HAL_RTCEx_SetWakeUpTimer_IT(&hrtc, (uint32_t)(wake_seconds - 1U),
                                          RTC_WAKEUPCLOCK_CK_SPRE_16BITS);
    }

    /* Clear wakeup flags before entering Stop 2 */
    __HAL_PWR_CLEAR_FLAG(PWR_FLAG_WU);

    /* Suspend SysTick so it does not wake CPU immediately after 1 ms */
    HAL_SuspendTick();

    /* Enter Stop 2 mode (~1.4 uA MCU, LSE + ACC_INT1 + LPUART1 active) */
    HAL_PWREx_EnterSTOP2Mode(PWR_STOPENTRY_WFI);

    /* MCU wakes on MSI 4 MHz: restore 80 MHz PLL and resume SysTick */
    SystemClock_Config();
    HAL_ResumeTick();

    (void)HAL_UARTEx_DisableStopMode(&hlpuart1);
    (void)HAL_RTCEx_DeactivateWakeUpTimer(&hrtc);
}

void tp_board_notify_rtc_wakeup_isr(void)
{
    g_rtc_wakeup_flag = true;
}

void tp_board_notify_acc_int1_isr(void)
{
    g_acc_int1_flag = true;
}

bool tp_board_consume_rtc_wakeup(void)
{
    if (g_rtc_wakeup_flag) {
        g_rtc_wakeup_flag = false;
        return true;
    }
    return false;
}

bool tp_board_consume_acc_int1(void)
{
    if (g_acc_int1_flag) {
        g_acc_int1_flag = false;
        return true;
    }
    return false;
}

void tp_log(const char *fmt, ...)
{
    if (hUsbDeviceFS.dev_state != USBD_STATE_CONFIGURED) {
        return;
    }

    static char log_buf[160];
    va_list args;
    va_start(args, fmt);
    int len = vsnprintf(log_buf, sizeof(log_buf), fmt, args);
    va_end(args);

    if (len <= 0) {
        return;
    }
    if (len >= (int)sizeof(log_buf)) {
        len = (int)sizeof(log_buf) - 1;
    }
    (void)CDC_Transmit_FS((uint8_t *)log_buf, (uint16_t)len);
}
