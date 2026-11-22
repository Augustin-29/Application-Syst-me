/**
 * @file    tp_lis3dh.h
 * @brief   ST LIS3DH 3-axis accelerometer driver on I2C1 (addr 0x18, ACC_INT1 PA1).
 */
#ifndef TP_LIS3DH_H
#define TP_LIS3DH_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int16_t ax_mg;
    int16_t ay_mg;
    int16_t az_mg;
} tp_accel_mg_t;

typedef struct {
    uint16_t peak_mg;
    uint8_t  axis_mask; /* b0:X, b1:Y, b2:Z, b3:tamper_tilt */
    uint8_t  int1_src;
} tp_motion_event_t;

/** Probe WHO_AM_I (0x33) and configure default safe standby state. */
bool tp_lis3dh_init(void);

/** True if LIS3DH responded correctly on I2C1. */
bool tp_lis3dh_is_present(void);

/**
 * Configure Anti-Theft Low-Power 10 Hz mode (~3.5 uA) with HPF motion
 * interrupt routed to ACC_INT1 (PA1, active high, latched).
 * @param thresh_steps_16mg  Threshold in 16 mg steps (1..127, e.g. 10 = 160 mg).
 */
bool tp_lis3dh_config_antitheft(uint8_t thresh_steps_16mg);

/**
 * Configure Track Recording High-Frequency mode (HPF off, BDU + HR enabled).
 * @param rate_hz   Target ODR (10, 25, 50, or 100 Hz)
 * @param range_g   Full scale (2, 4, 8, or 16 g)
 */
bool tp_lis3dh_config_track(uint8_t rate_hz, uint8_t range_g);

/** Put LIS3DH into Power-Down mode (~0.5 uA). */
bool tp_lis3dh_power_down(void);

/** Read current 3-axis acceleration in milli-g. */
bool tp_lis3dh_read_mg(tp_accel_mg_t *out);

/** Read & clear latched INT1_SRC register, compute peak-G and axis/tilt mask. */
bool tp_lis3dh_read_motion_event(tp_motion_event_t *evt);

/** Clear latched INT1 and refresh HPF reference to re-arm anti-theft trigger. */
bool tp_lis3dh_rearm_int1(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_LIS3DH_H */
