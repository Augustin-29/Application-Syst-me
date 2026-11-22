/**
 * @file    tp_battery.h
 * @brief   Li-Ion 1S battery monitor (ADC1_IN9 PA4, BAT_STAT PB6, FAULT PB7).
 */
#ifndef TP_BATTERY_H
#define TP_BATTERY_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint16_t voltage_mv;
    uint8_t  percent;
    bool     is_charging;
    bool     has_usb_fault;
    bool     low_for_flash;
} tp_battery_status_t;

/** Run ADC1 single-ended calibration and take initial filtered reading. */
void tp_battery_init(void);

/** Perform single-shot averaged ADC1 measurement and update cached state. */
void tp_battery_sample(void);

/** Get latest battery status (refreshes GPIO charging/fault flags immediately). */
tp_battery_status_t tp_battery_get_status(void);

/** True if battery voltage < TP_FLASH_MIN_BATT_MV (3350 mV). */
bool tp_battery_is_low_for_flash(void);

/** Convert Li-Ion 1S millivolts to percentage (0..100). */
uint8_t tp_battery_mv_to_percent(uint16_t mv);

#ifdef __cplusplus
}
#endif

#endif /* TP_BATTERY_H */
