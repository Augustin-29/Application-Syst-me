/**
 * @file    tp_lis3dh.c
 * @brief   ST LIS3DH driver on I2C1 (SDO=GND -> 7-bit addr 0x18 -> HAL 0x30).
 */
#include "tp_lis3dh.h"
#include "main.h"

#include <math.h>
#include <stdlib.h>

#define LIS3DH_I2C_ADDR         (0x18U << 1)
#define LIS3DH_WHO_AM_I_VAL     0x33U
#define LIS3DH_AUTO_INC         0x80U

/* Registers */
#define REG_WHO_AM_I            0x0FU
#define REG_CTRL_REG1           0x20U
#define REG_CTRL_REG2           0x21U
#define REG_CTRL_REG3           0x22U
#define REG_CTRL_REG4           0x23U
#define REG_CTRL_REG5           0x24U
#define REG_CTRL_REG6           0x25U
#define REG_REFERENCE           0x26U
#define REG_STATUS_REG          0x27U
#define REG_OUT_X_L             0x28U
#define REG_INT1_CFG            0x30U
#define REG_INT1_SRC            0x31U
#define REG_INT1_THS            0x32U
#define REG_INT1_DURATION       0x33U

static bool    g_present      = false;
static uint8_t g_range_g      = 2U;
static int16_t g_ref_ax_mg    = 0;
static int16_t g_ref_ay_mg    = 0;
static int16_t g_ref_az_mg    = 1000;

static bool write_reg(uint8_t reg, uint8_t val)
{
    return (HAL_I2C_Mem_Write(&hi2c1, LIS3DH_I2C_ADDR, reg,
                              I2C_MEMADD_SIZE_8BIT, &val, 1U, 50U) == HAL_OK);
}

static bool read_reg(uint8_t reg, uint8_t *val)
{
    return (HAL_I2C_Mem_Read(&hi2c1, LIS3DH_I2C_ADDR, reg,
                             I2C_MEMADD_SIZE_8BIT, val, 1U, 50U) == HAL_OK);
}

bool tp_lis3dh_init(void)
{
    uint8_t who = 0U;
    g_present = false;

    if (!read_reg(REG_WHO_AM_I, &who) || who != LIS3DH_WHO_AM_I_VAL) {
        return false;
    }

    g_present = true;
    /* Default: 10 Hz low power, ±2g, interrupts disabled until mode selected */
    (void)write_reg(REG_CTRL_REG3, 0x00U);
    (void)write_reg(REG_CTRL_REG5, 0x00U);
    (void)write_reg(REG_CTRL_REG6, 0x00U);
    (void)write_reg(REG_CTRL_REG4, 0x80U); /* BDU=1, ±2g */
    (void)write_reg(REG_CTRL_REG1, 0x2FU); /* 10 Hz, LPen=1, XYZ=1 */
    g_range_g = 2U;
    return true;
}

bool tp_lis3dh_is_present(void)
{
    return g_present;
}

bool tp_lis3dh_config_antitheft(uint8_t thresh_steps_16mg)
{
    if (!g_present) {
        return false;
    }

    if (thresh_steps_16mg < 1U)   thresh_steps_16mg = 1U;
    if (thresh_steps_16mg > 127U) thresh_steps_16mg = 127U;

    /* Capture resting gravity orientation before enabling HPF */
    tp_accel_mg_t rest = {0, 0, 1000};
    (void)write_reg(REG_CTRL_REG2, 0x00U);
    (void)write_reg(REG_CTRL_REG4, 0x80U); /* ±2g */
    (void)write_reg(REG_CTRL_REG1, 0x2FU); /* 10 Hz LP */
    g_range_g = 2U;
    HAL_Delay(15U);
    if (tp_lis3dh_read_mg(&rest)) {
        g_ref_ax_mg = rest.ax_mg;
        g_ref_ay_mg = rest.ay_mg;
        g_ref_az_mg = rest.az_mg;
    }

    /* Enable High-Pass Filter on IA1 (eliminates static 1g gravity) */
    bool ok = true;
    ok &= write_reg(REG_CTRL_REG2, 0x09U); /* HPM=00 normal, HP_IA1=1, FDS=0 */
    ok &= write_reg(REG_CTRL_REG3, 0x40U); /* I1_IA1 -> INT1 (PA1) */
    ok &= write_reg(REG_CTRL_REG4, 0x80U); /* BDU=1, FS=±2g (16 mg/LSB for INT1_THS) */
    ok &= write_reg(REG_CTRL_REG5, 0x08U); /* LIR_INT1 = 1 (latch until INT1_SRC read) */
    ok &= write_reg(REG_CTRL_REG6, 0x00U); /* Active HIGH on INT1 (matches EXTI1 rising) */
    ok &= write_reg(REG_INT1_THS, thresh_steps_16mg);
    ok &= write_reg(REG_INT1_DURATION, 0x01U); /* 100 ms @ 10 Hz */

    /* Dummy read of REFERENCE to latch HPF baseline */
    uint8_t dummy = 0U;
    (void)read_reg(REG_REFERENCE, &dummy);
    (void)read_reg(REG_INT1_SRC, &dummy);

    /* Enable XHIE | YHIE | ZHIE (OR combination -> any axis shock/movement) */
    ok &= write_reg(REG_INT1_CFG, 0x2AU);
    return ok;
}

bool tp_lis3dh_config_track(uint8_t rate_hz, uint8_t range_g)
{
    if (!g_present) {
        return false;
    }

    uint8_t odr_bits;
    if (rate_hz <= 10U) {
        odr_bits = 0x20U; /* 10 Hz */
    } else if (rate_hz <= 25U) {
        odr_bits = 0x30U; /* 25 Hz */
    } else if (rate_hz <= 50U) {
        odr_bits = 0x40U; /* 50 Hz */
    } else {
        odr_bits = 0x50U; /* 100 Hz */
    }

    uint8_t fs_bits;
    if (range_g <= 2U) {
        fs_bits = 0x00U;
        g_range_g = 2U;
    } else if (range_g <= 4U) {
        fs_bits = 0x10U;
        g_range_g = 4U;
    } else if (range_g <= 8U) {
        fs_bits = 0x20U;
        g_range_g = 8U;
    } else {
        fs_bits = 0x30U;
        g_range_g = 16U;
    }

    bool ok = true;
    ok &= write_reg(REG_INT1_CFG,  0x00U);                  /* Disable INT1 during track */
    ok &= write_reg(REG_CTRL_REG2, 0x00U);                  /* HPF OFF for sustained G */
    ok &= write_reg(REG_CTRL_REG3, 0x00U);
    ok &= write_reg(REG_CTRL_REG5, 0x00U);
    ok &= write_reg(REG_CTRL_REG4, (uint8_t)(0x88U | fs_bits)); /* BDU=1, HR=1 */
    ok &= write_reg(REG_CTRL_REG1, (uint8_t)(odr_bits | 0x07U)); /* Normal/HR, XYZ=1 */
    return ok;
}

bool tp_lis3dh_power_down(void)
{
    if (!g_present) {
        return false;
    }
    return write_reg(REG_CTRL_REG1, 0x00U);
}

bool tp_lis3dh_read_mg(tp_accel_mg_t *out)
{
    if (!g_present || out == NULL) {
        return false;
    }

    uint8_t buf[6] = {0};
    if (HAL_I2C_Mem_Read(&hi2c1, LIS3DH_I2C_ADDR, (uint8_t)(REG_OUT_X_L | LIS3DH_AUTO_INC),
                         I2C_MEMADD_SIZE_8BIT, buf, 6U, 50U) != HAL_OK) {
        return false;
    }

    /* Raw 16-bit left-justified two's complement (-32768..+32767 = -range_g..+range_g) */
    int32_t rx = (int16_t)((uint16_t)buf[1] << 8 | buf[0]);
    int32_t ry = (int16_t)((uint16_t)buf[3] << 8 | buf[2]);
    int32_t rz = (int16_t)((uint16_t)buf[5] << 8 | buf[4]);

    /* Convert full-scale left-justified reading to milli-g */
    int32_t scale_mg = (int32_t)g_range_g * 1000L;
    out->ax_mg = (int16_t)((rx * scale_mg) / 32768L);
    out->ay_mg = (int16_t)((ry * scale_mg) / 32768L);
    out->az_mg = (int16_t)((rz * scale_mg) / 32768L);
    return true;
}

bool tp_lis3dh_read_motion_event(tp_motion_event_t *evt)
{
    if (!g_present || evt == NULL) {
        return false;
    }

    uint8_t src = 0U;
    if (!read_reg(REG_INT1_SRC, &src)) {
        return false;
    }

    /* Sample a quick burst (5 readings over 50 ms) to capture peak G */
    uint32_t max_mag_mg = 0U;
    bool tilt_detected  = false;
    tp_accel_mg_t sample = {0};

    for (uint8_t i = 0U; i < 5U; i++) {
        if (tp_lis3dh_read_mg(&sample)) {
            int32_t dx = (int32_t)sample.ax_mg - (int32_t)g_ref_ax_mg;
            int32_t dy = (int32_t)sample.ay_mg - (int32_t)g_ref_ay_mg;
            int32_t dz = (int32_t)sample.az_mg - (int32_t)g_ref_az_mg;
            float mag = sqrtf((float)(dx * dx + dy * dy + dz * dz));
            if ((uint32_t)mag > max_mag_mg) {
                max_mag_mg = (uint32_t)mag;
            }
            /* Tilt > ~20 degrees corresponds to >340 mg vector shift from rest */
            if (abs((int)dx) > 340 || abs((int)dy) > 340 || abs((int)dz) > 340) {
                tilt_detected = true;
            }
        }
        HAL_Delay(10U);
    }

    uint8_t mask = 0U;
    if (src & 0x03U) mask |= 0x01U; /* X */
    if (src & 0x0CU) mask |= 0x02U; /* Y */
    if (src & 0x30U) mask |= 0x04U; /* Z */
    if (tilt_detected) mask |= 0x08U; /* tamper_tilt */
    if (mask == 0U) mask = 0x01U;

    evt->int1_src  = src;
    evt->axis_mask = mask;
    evt->peak_mg   = (max_mag_mg > 65535UL) ? 65535U : (uint16_t)max_mag_mg;
    return true;
}

bool tp_lis3dh_rearm_int1(void)
{
    if (!g_present) {
        return false;
    }
    uint8_t dummy = 0U;
    (void)read_reg(REG_REFERENCE, &dummy);
    return read_reg(REG_INT1_SRC, &dummy);
}
