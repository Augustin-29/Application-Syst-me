/**
 * @file    tp_bma456.h
 * @brief   Bosch BMA456 16-bit triaxial accelerometer driver on I2C1 (addr 0x18, INT1 PA1).
 *
 * Hardware wiring (schematic U6 BMA456):
 *   - SDO (pin 1)  = GND   -> 7-bit I2C address 0x18 (HAL 0x30)
 *   - CSB (pin 10) = +3V3  -> I2C mode permanently selected on POR
 *   - SCX (pin 12) = PB8 (I2C1_SCL, 4.7k pull-up)
 *   - SDX (pin 2)  = PB9 (I2C1_SDA, 4.7k pull-up)
 *   - INT1 (pin 5) = PA1 (ACC_INT1, EXTI1 rising edge, push-pull active HIGH)
 *   - ASCL/ASDA    = NC    -> Auxiliary magnetometer interface disabled
 *
 * Exploits the BMA456 ASIC Feature Engine & 1024-byte Hardware FIFO:
 *   1. Anti-Theft Low-Power Mode (Bosch BMA456_AN V2.29.0 Any-Motion ASIC, enabled by default):
 *      50 Hz Low-Power Avg2 (~14-18 uA sensor), 1002-byte official config stream uploaded once at
 *      init. ZERO STM32 Stop 2 wakeups while vehicle is parked; wakes PA1 (INT1) within 100 ms
 *      (5 consecutive samples) on genuine motion above threshold. Automatic fallback to 12.5 Hz
 *      FIFO Watermark guard (~7 uA sensor, 4 s window) if ASIC init ever fails.
 *   2. Track Recording Performance Mode (12.5..100 Hz, +/-2..16g, 1 KB streaming FIFO):
 *      Lossless hardware buffering of up to 170 samples (3.4 s @ 50 Hz) preventing any dropped
 *      telemetry points during E-Paper refreshes or QSPI Flash sector erases.
 */
#ifndef TP_BMA456_H
#define TP_BMA456_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TP_BMA456_FIFO_MAX_SAMPLES   170U /* 1024 B / 6 B per headerless XYZ frame */

typedef struct {
    int16_t ax_mg;
    int16_t ay_mg;
    int16_t az_mg;
} tp_accel_mg_t;

typedef struct {
    uint16_t peak_mg;
    uint8_t  axis_mask; /* b0:X, b1:Y, b2:Z, b3:tamper_tilt */
    uint8_t  int_status;
} tp_motion_event_t;

/** Probe CHIP_ID (0x16), soft-reset, enable I2C watchdog, and enter low-power standby. */
bool tp_bma456_init(void);

/** True if BMA456 responded with CHIP_ID = 0x16 on I2C1. */
bool tp_bma456_is_present(void);

/**
 * Configure Anti-Theft Low-Power mode (+/-2g 16-bit).
 * Uses Bosch BMA456_AN ASIC Any-Motion (50 Hz LP Avg2, 0 MCU wakeups) -> INT1 (PA1),
 * with automatic fallback to 12.5 Hz FIFO Watermark guard if ASIC is unavailable.
 * @param thresh_steps_16mg  Protocol threshold in 16 mg steps (1..127, e.g. 10 = 160 mg).
 */
bool tp_bma456_config_antitheft(uint8_t thresh_steps_16mg);

/**
 * Configure Track Recording Performance mode (12.5..100 Hz, +/-2..16g) with 1 KB streaming FIFO.
 * @param rate_hz   Target rate (mapped to 12.5, 25, 50, or 100 Hz)
 * @param range_g   Full scale (2, 4, 8, or 16 g)
 */
bool tp_bma456_config_track(uint8_t rate_hz, uint8_t range_g);

/** Nominal sample period in ms for the currently configured track ODR (80, 40, 20, or 10 ms). */
uint16_t tp_bma456_get_actual_dt_ms(void);

/** Put BMA456 into Suspend mode (~3.5 uA, retains registers & FIFO). */
bool tp_bma456_suspend(void);

/** Read instantaneous 3-axis 16-bit acceleration in milli-g (DATA_8..DATA_13). */
bool tp_bma456_read_mg(tp_accel_mg_t *out);

/** Drain available headerless XYZ samples from the 1024-byte hardware FIFO. Returns sample count. */
uint16_t tp_bma456_fifo_read(tp_accel_mg_t *out_samples, uint16_t max_samples);

/** Flush BMA456 hardware FIFO (CMD = 0xB0). */
bool tp_bma456_fifo_flush(void);

/**
 * Evaluate an INT1 (ACC_INT1) wakeup in Anti-Theft Stop 2 mode.
 * Verifies BMA456_AN Any-Motion interrupt (or drains FIFO burst in fallback mode),
 * computes peak acceleration & tilt axis mask, and clears latched INT1.
 * @return true ONLY when genuine vehicle movement/tamper exceeds threshold.
 */
bool tp_bma456_process_antitheft_wakeup(tp_motion_event_t *evt);

/** Clear latched interrupt status & flush FIFO to re-arm INT1 rising edge. */
bool tp_bma456_rearm_int1(void);

/** Read BMA456 internal die temperature sensor in degrees Celsius (Reg 0x22). */
bool tp_bma456_read_temp_c(int8_t *temp_c);

#ifdef __cplusplus
}
#endif

#endif /* TP_BMA456_H */
