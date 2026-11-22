/**
 * @file    tp_board.h
 * @brief   Board-level services: Device ID, LED, Buttons, RTC UTC Epoch, Stop 2 & USB Log.
 */
#ifndef TP_BOARD_H
#define TP_BOARD_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    TP_BTN_EVENT_NONE = 0,
    TP_BTN_EVENT_BP1_SHORT,
    TP_BTN_EVENT_BP1_LONG,
    TP_BTN_EVENT_BP2_SHORT,
    TP_BTN_EVENT_BP2_LONG,
    TP_BTN_EVENT_BOTH_LONG
} tp_btn_event_t;

typedef struct {
    char device_id_upper[9]; /* e.g. "8F3A" */
    char device_id_lower[9]; /* e.g. "8f3a" */
    char default_key[10];    /* e.g. "492015" */
    char ble_name[20];       /* e.g. "TrackPulse-8F3A" */
} tp_device_identity_t;

/** Initialize board identity, LED, buttons, and RTC backup state. */
void tp_board_init(void);

/** Get derived immutable hardware identity from STM32 96-bit UID. */
const tp_device_identity_t *tp_board_get_identity(void);

/** Control user LED1 (PB4, active HIGH). */
void tp_led_set(bool on);
void tp_led_toggle(void);

/** Raw debounced button levels (true = pressed, active LOW hardware). */
bool tp_btn_bp1_is_pressed(void);
bool tp_btn_bp2_is_pressed(void);

/** Poll button state machine; returns latest edge/long-press event. */
tp_btn_event_t tp_btn_poll(uint32_t now_ms);

/** TrackPulse uptime in seconds (survives Stop 2 using RTC delta). */
uint32_t tp_board_get_uptime_sec(void);

/** Convert current STM32 RTC calendar to Unix UTC epoch seconds. */
uint32_t tp_rtc_get_epoch(void);

/** Set STM32 RTC calendar from Unix UTC epoch seconds. */
bool tp_rtc_set_epoch(uint32_t epoch_sec);

/** Check whether RTC has been synchronized by A-GPS or GNSS. */
bool tp_rtc_is_synced(void);

/**
 * Enter STM32L433 Stop 2 deep sleep (~1.4 uA MCU) with optional RTC WakeUp
 * timer (1..65535 s). Restores 80 MHz PLL and SysTick upon wakeup.
 */
void tp_power_enter_stop2(uint16_t wake_seconds);

/** Clear / query Stop 2 wakeup sources (set from ISR callbacks). */
void tp_board_notify_rtc_wakeup_isr(void);
void tp_board_notify_acc_int1_isr(void);
bool tp_board_consume_rtc_wakeup(void);
bool tp_board_consume_acc_int1(void);

/** Non-blocking formatted debug log over USB CDC VCP (no-op if USB detached). */
void tp_log(const char *fmt, ...);

#ifdef __cplusplus
}
#endif

#endif /* TP_BOARD_H */
