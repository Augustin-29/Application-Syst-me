/**
 * @file    tp_bma456.c
 * @brief   Bosch BMA456 16-bit accelerometer driver with 1 KB Hardware FIFO & Low-Power Guard.
 *          Register addresses & timings per Bosch BST-BMA456-DS000-09 Rev 3.6.
 */
#include "tp_bma456.h"
#include "tp_config.h"
#include "main.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

/* ---- I2C Address & Identity ---- */
#define BMA456_I2C_ADDR             (0x18U << 1) /* SDO=GND -> 0x18 (HAL 0x30) */
#define BMA456_CHIP_ID_VAL          0x16U        /* Primary BMA456_AN chip ID */
#define BMA456_CHIP_ID_SEC          0x1AU        /* Secondary BMA456_AN chip ID (bma456_an.h V2.29.0) */

/* ---- Register Map (Datasheet §5.2) ---- */
#define REG_CHIP_ID                 0x00U
#define REG_ERR_REG                 0x02U
#define REG_STATUS                  0x03U
#define REG_DATA_8                  0x12U /* ACC_X_LSB .. ACC_Z_MSB (6 bytes) */
#define REG_EVENT                   0x1BU
#define REG_INT_STATUS_0            0x1CU /* Feature interrupts */
#define REG_INT_STATUS_1            0x1DU /* b0:ffull, b1:fwm, b7:acc_drdy */
#define REG_TEMPERATURE             0x22U
#define REG_FIFO_LENGTH_0           0x24U /* LSB */
#define REG_FIFO_LENGTH_1           0x25U /* MSB bits [5:0] */
#define REG_FIFO_DATA               0x26U
#define REG_INTERNAL_STATUS         0x2AU /* bits [4:0]: 0x01 = init_ok */
#define REG_ACC_CONF                0x40U
#define REG_ACC_RANGE               0x41U
#define REG_FIFO_DOWNS              0x45U
#define REG_FIFO_WTM_0              0x46U
#define REG_FIFO_WTM_1              0x47U
#define REG_FIFO_CONFIG_0           0x48U
#define REG_FIFO_CONFIG_1           0x49U
#define REG_INT1_IO_CTRL            0x53U
#define REG_INT_LATCH               0x55U
#define REG_INT1_MAP                0x56U
#define REG_INT_MAP_DATA            0x58U
#define REG_INIT_CTRL               0x59U
#define REG_INIT_ADDR_0             0x5BU
#define REG_INIT_ADDR_1             0x5CU
#define REG_FEATURES_IN             0x5EU
#define REG_IF_CONF                 0x6BU
#define REG_NV_CONF                 0x70U
#define REG_PWR_CONF                0x7CU
#define REG_PWR_CTRL                0x7DU
#define REG_CMD                     0x7EU

/* Commands (REG_CMD 0x7E) */
#define CMD_FIFO_FLUSH              0xB0U
#define CMD_SOFTRESET               0xB6U

/* PWR_CONF bits (0x7C) */
#define PWR_CONF_APS_BIT            0x01U /* adv_power_save */
#define PWR_CONF_FSW_BIT            0x02U /* fifo_self_wakeup */

/* PWR_CTRL bits (0x7D) */
#define PWR_CTRL_ACC_EN             0x04U /* aux_en MUST remain 0 */

/* ACC_CONF ODR values (bits 3:0) */
#define ODR_6P25_HZ                 0x04U
#define ODR_12P5_HZ                 0x05U
#define ODR_25_HZ                   0x06U
#define ODR_50_HZ                   0x07U
#define ODR_100_HZ                  0x08U

/* ACC_CONF BWP values (bits 6:4) */
#define BWP_LP_AVG2                 (0x01U << 4)
#define BWP_PERF_NORM               (0x02U << 4)
#define ACC_PERF_MODE_BIT           0x80U

/* ACC_RANGE values (0x41) */
#define RANGE_2G_CODE               0x00U
#define RANGE_4G_CODE               0x01U
#define RANGE_8G_CODE               0x02U
#define RANGE_16G_CODE              0x03U

/* INT1_IO_CTRL (0x53): output_en=1, push-pull (od=0), active_high (lvl=1) */
#define INT1_IO_PUSH_PULL_HIGH      0x0AU

/* INT_MAP_DATA (0x58): b1 = int1_fwm */
#define INT_MAP_INT1_FWM            0x02U

/* Bosch BMA456_AN Feature Engine constants (verified against official bma456_an.h V2.29.0) */
#define BMA456_AN_FEATURE_SIZE      12U
#define BMA456_AN_ANY_MOT_OFFSET    0U
#define BMA456_AN_NO_MOT_OFFSET     4U
#define BMA456_AN_ANY_MOT_INT       0x20U  /* Bit 5 in INT1_MAP (0x56) & INT_STATUS_0 (0x1C) */
#define BMA456_AN_EN_ALL_AXIS       0x07U  /* X | Y | Z */
#define BMA456_AN_DIS_ALL_AXIS      0x00U
#define BMA456_AN_DEFAULT_DUR_50HZ  5U     /* 5 * 20 ms = 100 ms consecutive motion */

/* FIFO burst staging buffer (max 64 samples = 384 bytes per I2C burst) */
#define FIFO_BURST_MAX_SAMPLES      64U
#define FIFO_BURST_BUF_BYTES        (FIFO_BURST_MAX_SAMPLES * 6U)

/* Anti-theft FIFO Watermark: 50 samples * 6 B = 300 B (4.0 s @ 12.5 Hz) */
#define ANTITHEFT_WTM_SAMPLES       50U
#define ANTITHEFT_WTM_BYTES         (ANTITHEFT_WTM_SAMPLES * 6U)

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
/* Optional: link Bosch Sensortec official bma456an_config_file[] from BMA456_SensorAPI */
extern const uint8_t  bma456an_config_file[];
extern const uint16_t bma456an_config_file_len;
#endif

static bool     g_present          = false;
static bool     g_aps_enabled      = true;
#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
static bool     g_asic_loaded      = false;
#endif
static uint8_t  g_range_g          = 2U;
static uint16_t g_track_dt_ms      = 20U;
static uint16_t g_motion_thresh_mg = 160U;

/* Resting gravity baseline (milli-g) for anti-theft tilt & dynamic shock detection */
static int16_t  g_ref_ax_mg        = 0;
static int16_t  g_ref_ay_mg        = 0;
static int16_t  g_ref_az_mg        = 1000;
static bool     g_ref_valid        = false;

static uint8_t  g_fifo_raw[FIFO_BURST_BUF_BYTES];

/* ---- Low-Level I2C Helpers (Datasheet §6.5 & §6.6 timing compliant) ---- */

static bool bma_write_reg(uint8_t reg, uint8_t val)
{
    HAL_StatusTypeDef st = HAL_I2C_Mem_Write(&hi2c1, BMA456_I2C_ADDR, reg,
                                             I2C_MEMADD_SIZE_8BIT, &val, 1U, 50U);
    if (g_aps_enabled) {
        /* Datasheet §6.5 p.83: tIDLE_wacc_nm = 1000 us in low-power / suspend mode */
        HAL_Delay(2U);
    }
    return (st == HAL_OK);
}

static bool bma_write_burst(uint8_t reg, const uint8_t *buf, uint16_t len)
{
    HAL_StatusTypeDef st = HAL_I2C_Mem_Write(&hi2c1, BMA456_I2C_ADDR, reg,
                                             I2C_MEMADD_SIZE_8BIT, (uint8_t *)buf, len, 150U);
    if (g_aps_enabled) {
        HAL_Delay(2U);
    }
    return (st == HAL_OK);
}

static bool bma_read_reg(uint8_t reg, uint8_t *val)
{
    return (HAL_I2C_Mem_Read(&hi2c1, BMA456_I2C_ADDR, reg,
                             I2C_MEMADD_SIZE_8BIT, val, 1U, 50U) == HAL_OK);
}

static bool bma_read_burst(uint8_t reg, uint8_t *buf, uint16_t len)
{
    return (HAL_I2C_Mem_Read(&hi2c1, BMA456_I2C_ADDR, reg,
                             I2C_MEMADD_SIZE_8BIT, buf, len, 200U) == HAL_OK);
}

static bool bma_set_aps(bool enable_aps, bool enable_fsw)
{
    uint8_t val = (uint8_t)((enable_fsw ? PWR_CONF_FSW_BIT : 0U) |
                            (enable_aps ? PWR_CONF_APS_BIT : 0U));
    bool ok = (HAL_I2C_Mem_Write(&hi2c1, BMA456_I2C_ADDR, REG_PWR_CONF,
                                 I2C_MEMADD_SIZE_8BIT, &val, 1U, 50U) == HAL_OK);
    g_aps_enabled = enable_aps;
    /* Datasheet §4.2 p.16: wait >= 450 us after changing adv_power_save */
    HAL_Delay(2U);
    return ok;
}

static void convert_raw_to_mg(const uint8_t raw[6], tp_accel_mg_t *out)
{
    int32_t rx = (int16_t)((uint16_t)raw[1] << 8 | raw[0]);
    int32_t ry = (int16_t)((uint16_t)raw[3] << 8 | raw[2]);
    int32_t rz = (int16_t)((uint16_t)raw[5] << 8 | raw[4]);

    /* Exact 16-bit sensitivity: S = 32768 / range_g LSB/g (Datasheet p.9) */
    int32_t scale_mg = (int32_t)g_range_g * 1000L;
    out->ax_mg = (int16_t)((rx * scale_mg) / 32768L);
    out->ay_mg = (int16_t)((ry * scale_mg) / 32768L);
    out->az_mg = (int16_t)((rz * scale_mg) / 32768L);
}

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
/**
 * Datasheet §4.2 (p.16) ASIC Feature Engine initialization loader.
 * Writes Bosch Sensortec official bma456an_config_file[] in 32-byte chunks to FEATURES_IN.
 */
static bool bma_load_bosch_feature_blob(void)
{
    if (!bma_set_aps(false, true)) return false;
    if (!bma_write_reg(REG_INIT_CTRL, 0x00U)) return false;

    const uint16_t chunk = 32U; /* Must be even */
    for (uint16_t off = 0U; off < bma456an_config_file_len; off = (uint16_t)(off + chunk)) {
        uint16_t len = (uint16_t)(bma456an_config_file_len - off);
        if (len > chunk) len = chunk;

        uint16_t base_words = (uint16_t)(off / 2U);
        uint8_t addr_regs[2] = {
            (uint8_t)(base_words & 0x0FU),
            (uint8_t)((base_words >> 4) & 0xFFU)
        };
        if (!bma_write_burst(REG_INIT_ADDR_0, addr_regs, 2U)) return false;
        if (!bma_write_burst(REG_FEATURES_IN, &bma456an_config_file[off], len)) return false;
    }

    if (!bma_write_reg(REG_INIT_CTRL, 0x01U)) return false;

    /* Datasheet §3 p.12 & §4.2 p.16: wait up to 150 ms for INTERNAL_STATUS.message == 0x01 */
    for (uint8_t tries = 0U; tries < 20U; tries++) {
        HAL_Delay(10U);
        uint8_t st = 0U;
        if (bma_read_reg(REG_INTERNAL_STATUS, &st) && ((st & 0x1FU) == 0x01U)) {
            return true;
        }
    }
    return false;
}
#endif

/* ---- Public API ---- */

bool tp_bma456_init(void)
{
    uint8_t chip_id = 0U;
    g_present     = false;
#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
    g_asic_loaded = false;
#endif
    g_aps_enabled = true;
    g_ref_valid   = false;

    /* Datasheet §1 p.8: Power-Up Time ts_up <= 1 ms */
    HAL_Delay(2U);

    if (!bma_read_reg(REG_CHIP_ID, &chip_id) ||
        (chip_id != BMA456_CHIP_ID_VAL && chip_id != BMA456_CHIP_ID_SEC)) {
        return false;
    }

    /* Clean soft-reset sequence (Datasheet §4.12 p.39) */
    (void)bma_set_aps(true, false);
    (void)bma_write_reg(REG_PWR_CTRL, 0x00U);
    (void)bma_write_reg(REG_CMD, CMD_SOFTRESET);
    HAL_Delay(5U);
    g_aps_enabled = true;

    if (!bma_read_reg(REG_CHIP_ID, &chip_id) ||
        (chip_id != BMA456_CHIP_ID_VAL && chip_id != BMA456_CHIP_ID_SEC)) {
        return false;
    }
    g_present = true;

    /* Enter Configuration Mode (adv_power_save = 0) before register setup */
    (void)bma_set_aps(false, true);

    /* Enable 40 ms I2C Watchdog (NV_CONF: i2c_wdt_en=1, i2c_wdt_sel=1, spi_en=0) */
    (void)bma_write_reg(REG_NV_CONF, 0x06U);

    /* Auxiliary magnetometer interface MUST remain disabled (Datasheet §4.3 p.18) */
    (void)bma_write_reg(REG_IF_CONF, 0x00U);

    /* Configure INT1 pin: push-pull, active HIGH, latched (matches STM32 PA1 EXTI1 rising) */
    (void)bma_write_reg(REG_INT1_IO_CTRL, INT1_IO_PUSH_PULL_HIGH);
    (void)bma_write_reg(REG_INT_LATCH, 0x01U);
    (void)bma_write_reg(REG_INT1_MAP, 0x00U);
    (void)bma_write_reg(REG_INT_MAP_DATA, 0x00U);

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
    g_asic_loaded = bma_load_bosch_feature_blob();
#endif

    /* Default safe low-power operating state: 12.5 Hz Avg2, +/-2g (~7 uA) */
    g_range_g = 2U;
    (void)bma_write_reg(REG_ACC_RANGE, RANGE_2G_CODE);
    (void)bma_write_reg(REG_ACC_CONF, (uint8_t)(BWP_LP_AVG2 | ODR_12P5_HZ));
    (void)bma_write_reg(REG_FIFO_CONFIG_1, 0x00U); /* FIFO off in idle */
    (void)bma_write_reg(REG_PWR_CTRL, PWR_CTRL_ACC_EN);
    (void)bma_set_aps(true, true);

    /* Clear POR flag & any stale interrupts */
    uint8_t dummy = 0U;
    (void)bma_read_reg(REG_EVENT, &dummy);
    (void)bma_read_reg(REG_INT_STATUS_0, &dummy);
    (void)bma_read_reg(REG_INT_STATUS_1, &dummy);

    return true;
}

bool tp_bma456_is_present(void)
{
    return g_present;
}

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
/**
 * Configure BMA456_AN Any-Motion parameters in FEATURES_IN (0x5E, 12-byte read-modify-write)
 * per official bma456_an_set_any_mot_config() (V2.29.0). Caller must have adv_power_save = 0.
 */
static bool bma_set_anymotion_feature(uint16_t thresh_mg, uint16_t dur_samples_50hz, uint8_t axes_en)
{
    uint8_t feat[BMA456_AN_FEATURE_SIZE];
    if (!bma_read_burst(REG_FEATURES_IN, feat, BMA456_AN_FEATURE_SIZE)) {
        return false;
    }

    /* Threshold in 5.11g format (range 0..1g -> 0..0x07FF, 1g = 2048) */
    uint32_t th_5_11 = ((uint32_t)thresh_mg * 2048UL) / 1000UL;
    if (th_5_11 < 16UL)     th_5_11 = 16UL;
    if (th_5_11 > 0x07FFUL) th_5_11 = 0x07FFUL;

    uint16_t dur_word = (uint16_t)((dur_samples_50hz & 0x1FFFU) |
                                   (((uint16_t)(axes_en & 0x07U)) << 13));

    /* Offset 0..3: Any-Motion config */
    feat[BMA456_AN_ANY_MOT_OFFSET + 0U] = (uint8_t)(th_5_11 & 0xFFU);
    feat[BMA456_AN_ANY_MOT_OFFSET + 1U] = (uint8_t)((th_5_11 >> 8) & 0x07U);
    feat[BMA456_AN_ANY_MOT_OFFSET + 2U] = (uint8_t)(dur_word & 0xFFU);
    feat[BMA456_AN_ANY_MOT_OFFSET + 3U] = (uint8_t)((dur_word >> 8) & 0xFFU);

    /* Offset 4..7: Keep No-Motion disabled (axes_en = 0) */
    feat[BMA456_AN_NO_MOT_OFFSET + 3U] &= 0x1FU;

    return bma_write_burst(REG_FEATURES_IN, feat, BMA456_AN_FEATURE_SIZE);
}
#endif

bool tp_bma456_config_antitheft(uint8_t thresh_steps_16mg)
{
    if (!g_present) {
        return false;
    }

    if (thresh_steps_16mg < 2U)   thresh_steps_16mg = 2U;
    if (thresh_steps_16mg > 120U) thresh_steps_16mg = 120U;
    g_motion_thresh_mg = (uint16_t)thresh_steps_16mg * 16U;

    /* 1. Enter configuration mode and capture clean static gravity vector (+/-2g, 50 Hz) */
    if (!bma_set_aps(false, true)) return false;

    g_range_g = 2U;
    (void)bma_write_reg(REG_INT_MAP_DATA, 0x00U);
    (void)bma_write_reg(REG_INT1_MAP, 0x00U);
    (void)bma_write_reg(REG_ACC_RANGE, RANGE_2G_CODE);
    (void)bma_write_reg(REG_ACC_CONF, (uint8_t)(ACC_PERF_MODE_BIT | BWP_PERF_NORM | ODR_50_HZ));
    (void)bma_write_reg(REG_PWR_CTRL, PWR_CTRL_ACC_EN);
    HAL_Delay(30U);

    int32_t sx = 0, sy = 0, sz = 0;
    uint8_t ok_cnt = 0U;
    for (uint8_t i = 0U; i < 8U; i++) {
        tp_accel_mg_t s;
        if (tp_bma456_read_mg(&s)) {
            sx += s.ax_mg;
            sy += s.ay_mg;
            sz += s.az_mg;
            ok_cnt++;
        }
        HAL_Delay(20U);
    }
    if (ok_cnt > 0U) {
        g_ref_ax_mg = (int16_t)(sx / (int32_t)ok_cnt);
        g_ref_ay_mg = (int16_t)(sy / (int32_t)ok_cnt);
        g_ref_az_mg = (int16_t)(sz / (int32_t)ok_cnt);
        g_ref_valid = true;
    }

    bool ok = true;

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
    if (g_asic_loaded) {
        /* Official Bosch BMA456_AN On-Chip Any-Motion Engine:
           50 Hz Low-Power Avg2 (~14-18 uA sensor), ZERO STM32 Stop 2 wakeups while parked! */
        ok &= bma_write_reg(REG_ACC_CONF, (uint8_t)(BWP_LP_AVG2 | ODR_50_HZ));
        ok &= bma_write_reg(REG_FIFO_CONFIG_1, 0x00U);
        ok &= bma_set_anymotion_feature(g_motion_thresh_mg,
                                        BMA456_AN_DEFAULT_DUR_50HZ,
                                        BMA456_AN_EN_ALL_AXIS);
        ok &= bma_write_reg(REG_INT1_IO_CTRL, INT1_IO_PUSH_PULL_HIGH);
        ok &= bma_write_reg(REG_INT_LATCH, 0x01U);
        ok &= bma_write_reg(REG_INT_MAP_DATA, 0x00U);
        ok &= bma_write_reg(REG_INT1_MAP, BMA456_AN_ANY_MOT_INT); /* 0x20 -> INT1 */
        ok &= bma_write_reg(REG_PWR_CTRL, PWR_CTRL_ACC_EN);

        uint8_t dummy = 0U;
        (void)bma_read_reg(REG_INT_STATUS_0, &dummy);
        (void)bma_read_reg(REG_INT_STATUS_1, &dummy);
        ok &= bma_set_aps(true, true);
        return ok;
    }
#endif

    /* 2. Standalone Low-Power Hardware FIFO Watermark Motion Guard (~7 uA BMA456):
          12.5 Hz Avg2, headerless 6 B/sample, 300 B watermark = 50 samples (4.0 s window). */
    ok &= bma_write_reg(REG_ACC_CONF, (uint8_t)(BWP_LP_AVG2 | ODR_12P5_HZ));
    ok &= bma_write_reg(REG_FIFO_DOWNS, 0x80U);      /* Filtered data, 1x */
    ok &= bma_write_reg(REG_FIFO_CONFIG_0, 0x00U);   /* Streaming mode, no sensortime */
    ok &= bma_write_reg(REG_FIFO_CONFIG_1, 0x40U);   /* fifo_acc_en=1, headerless=0 */

    uint8_t wtm[2] = {
        (uint8_t)(ANTITHEFT_WTM_BYTES & 0xFFU),
        (uint8_t)((ANTITHEFT_WTM_BYTES >> 8) & 0x1FU)
    };
    ok &= bma_write_burst(REG_FIFO_WTM_0, wtm, 2U);
    ok &= bma_write_reg(REG_INT1_IO_CTRL, INT1_IO_PUSH_PULL_HIGH);
    ok &= bma_write_reg(REG_INT_LATCH, 0x01U);
    ok &= bma_write_reg(REG_INT1_MAP, 0x00U);
    ok &= bma_write_reg(REG_INT_MAP_DATA, INT_MAP_INT1_FWM);
    ok &= bma_write_reg(REG_CMD, CMD_FIFO_FLUSH);
    ok &= bma_write_reg(REG_PWR_CTRL, PWR_CTRL_ACC_EN);

    /* Clear any pending interrupt before entering Advanced Power Save + FIFO Self-Wakeup */
    uint8_t dummy = 0U;
    (void)bma_read_reg(REG_INT_STATUS_1, &dummy);
    ok &= bma_set_aps(true, true);
    return ok;
}

bool tp_bma456_config_track(uint8_t rate_hz, uint8_t range_g)
{
    if (!g_present) {
        return false;
    }

    uint8_t odr_code;
    if (rate_hz <= 15U) {
        odr_code      = ODR_12P5_HZ;
        g_track_dt_ms = 80U;
    } else if (rate_hz <= 35U) {
        odr_code      = ODR_25_HZ;
        g_track_dt_ms = 40U;
    } else if (rate_hz <= 75U) {
        odr_code      = ODR_50_HZ;
        g_track_dt_ms = 20U;
    } else {
        odr_code      = ODR_100_HZ;
        g_track_dt_ms = 10U;
    }

    uint8_t range_code;
    if (range_g <= 2U) {
        range_code = RANGE_2G_CODE;  g_range_g = 2U;
    } else if (range_g <= 4U) {
        range_code = RANGE_4G_CODE;  g_range_g = 4U;
    } else if (range_g <= 8U) {
        range_code = RANGE_8G_CODE;  g_range_g = 8U;
    } else {
        range_code = RANGE_16G_CODE; g_range_g = 16U;
    }

    /* Performance Mode (adv_power_save=0) + 1024-byte streaming FIFO for lossless capture */
    bool ok = true;
    ok &= bma_set_aps(false, true);
    ok &= bma_write_reg(REG_INT_MAP_DATA, 0x00U);
    ok &= bma_write_reg(REG_INT1_MAP, 0x00U);
#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
    if (g_asic_loaded) {
        (void)bma_set_anymotion_feature(g_motion_thresh_mg,
                                        BMA456_AN_DEFAULT_DUR_50HZ,
                                        BMA456_AN_DIS_ALL_AXIS);
    }
#endif
    ok &= bma_write_reg(REG_ACC_RANGE, range_code);
    ok &= bma_write_reg(REG_ACC_CONF, (uint8_t)(ACC_PERF_MODE_BIT | BWP_PERF_NORM | odr_code));
    ok &= bma_write_reg(REG_FIFO_DOWNS, 0x80U);    /* Filtered data, no downsampling */
    ok &= bma_write_reg(REG_FIFO_CONFIG_0, 0x00U); /* Streaming mode */
    ok &= bma_write_reg(REG_FIFO_CONFIG_1, 0x40U); /* fifo_acc_en=1, headerless 6 B/sample */
    ok &= bma_write_reg(REG_CMD, CMD_FIFO_FLUSH);
    ok &= bma_write_reg(REG_PWR_CTRL, PWR_CTRL_ACC_EN);
    return ok;
}

uint16_t tp_bma456_get_actual_dt_ms(void)
{
    return g_track_dt_ms;
}

bool tp_bma456_suspend(void)
{
    if (!g_present) {
        return false;
    }
    bool ok = bma_set_aps(false, false);
    ok &= bma_write_reg(REG_INT_MAP_DATA, 0x00U);
    ok &= bma_write_reg(REG_INT1_MAP, 0x00U);
    ok &= bma_write_reg(REG_PWR_CTRL, 0x00U);
    ok &= bma_set_aps(true, false); /* 3.5 uA Suspend Mode (Datasheet §1 p.8) */
    return ok;
}

bool tp_bma456_read_mg(tp_accel_mg_t *out)
{
    if (!g_present || out == NULL) {
        return false;
    }
    uint8_t buf[6] = {0};
    if (!bma_read_burst(REG_DATA_8, buf, 6U)) {
        return false;
    }
    convert_raw_to_mg(buf, out);
    return true;
}

bool tp_bma456_fifo_flush(void)
{
    if (!g_present) {
        return false;
    }
    return bma_write_reg(REG_CMD, CMD_FIFO_FLUSH);
}

uint16_t tp_bma456_fifo_read(tp_accel_mg_t *out_samples, uint16_t max_samples)
{
    if (!g_present || out_samples == NULL || max_samples == 0U) {
        return 0U;
    }

    uint8_t len_buf[2] = {0};
    if (!bma_read_burst(REG_FIFO_LENGTH_0, len_buf, 2U)) {
        return 0U;
    }
    uint16_t avail_bytes = (uint16_t)len_buf[0] | ((uint16_t)(len_buf[1] & 0x3FU) << 8);
    uint16_t avail_samples = avail_bytes / 6U;
    if (avail_samples == 0U) {
        return 0U;
    }

    if (avail_samples > max_samples) {
        avail_samples = max_samples;
    }

    /* Read FIFO in aligned chunks (multiples of 6 bytes so no partial frame repeats, §4.5 p.28) */
    uint16_t read_total = 0U;
    while (read_total < avail_samples) {
        uint16_t batch = (uint16_t)(avail_samples - read_total);
        if (batch > FIFO_BURST_MAX_SAMPLES) {
            batch = FIFO_BURST_MAX_SAMPLES;
        }
        if (!bma_read_burst(REG_FIFO_DATA, g_fifo_raw, (uint16_t)(batch * 6U))) {
            break;
        }
        for (uint16_t i = 0U; i < batch; i++) {
            const uint8_t *f = &g_fifo_raw[i * 6U];
            /* Datasheet §4.5 p.28: headerless overread returns 0x8000 on axes */
            if (f[0] == 0x00U && f[1] == 0x80U && f[2] == 0x00U && f[3] == 0x80U) {
                return read_total;
            }
            convert_raw_to_mg(f, &out_samples[read_total++]);
        }
    }
    return read_total;
}

bool tp_bma456_process_antitheft_wakeup(tp_motion_event_t *evt)
{
    if (!g_present) {
        return false;
    }

#ifdef TP_BMA456_USE_BOSCH_ANYMOTION_BLOB
    if (g_asic_loaded) {
        uint8_t st0 = 0U;
        if (!bma_read_reg(REG_INT_STATUS_0, &st0) || ((st0 & BMA456_AN_ANY_MOT_INT) == 0U)) {
            if (HAL_GPIO_ReadPin(ACC_INT1_GPIO_Port, ACC_INT1_Pin) == GPIO_PIN_SET) {
                (void)tp_bma456_rearm_int1();
            }
            return false;
        }
        /* Burst-read 5 samples (~100 ms @ 50 Hz) to quantify peak acceleration & tilt */
        uint32_t max_mg = 0U;
        uint8_t  mask   = 0U;
        tp_accel_mg_t s;
        for (uint8_t i = 0U; i < 5U; i++) {
            if (tp_bma456_read_mg(&s)) {
                int32_t dx = (int32_t)s.ax_mg - (int32_t)g_ref_ax_mg;
                int32_t dy = (int32_t)s.ay_mg - (int32_t)g_ref_ay_mg;
                int32_t dz = (int32_t)s.az_mg - (int32_t)g_ref_az_mg;
                uint32_t mag = (uint32_t)sqrtf((float)(dx * dx + dy * dy + dz * dz));
                if (mag > max_mg) max_mg = mag;
                if (abs((int)dx) >= (int)g_motion_thresh_mg) mask |= 0x01U;
                if (abs((int)dy) >= (int)g_motion_thresh_mg) mask |= 0x02U;
                if (abs((int)dz) >= (int)g_motion_thresh_mg) mask |= 0x04U;
                if (abs((int)dx) > 340 || abs((int)dy) > 340 || abs((int)dz) > 340) mask |= 0x08U;
            }
            HAL_Delay(20U);
        }
        if (evt != NULL) {
            evt->peak_mg    = (max_mg > 65535UL) ? 65535U : (uint16_t)max_mg;
            evt->axis_mask  = (mask != 0U) ? mask : 0x01U;
            evt->int_status = st0;
        }
        return true;
    }
#endif

    /* FIFO Watermark Low-Power Motion Guard:
       Datasheet §4.3 p.17: with fifo_self_wakeup=1, FIFO is readable immediately on watermark
       interrupt in one burst; afterwards read INT_STATUS_1 to clear latched INT1. */
    uint8_t len_buf[2] = {0};
    if (!bma_read_burst(REG_FIFO_LENGTH_0, len_buf, 2U)) {
        return false;
    }
    uint16_t avail_samples = ((uint16_t)len_buf[0] | ((uint16_t)(len_buf[1] & 0x3FU) << 8)) / 6U;
    bool overflow_backlog  = (avail_samples > FIFO_BURST_MAX_SAMPLES);
    uint16_t n_samples     = overflow_backlog ? FIFO_BURST_MAX_SAMPLES : avail_samples;

    uint32_t peak_mg       = 0U;
    uint8_t  axis_mask     = 0U;
    uint16_t trigger_count = 0U;
    bool     violent_shock = false;
    int32_t  sum_x = 0, sum_y = 0, sum_z = 0;
    uint16_t valid_cnt     = 0U;

    if (n_samples > 0U && bma_read_burst(REG_FIFO_DATA, g_fifo_raw, (uint16_t)(n_samples * 6U))) {
        tp_accel_mg_t prev = { g_ref_ax_mg, g_ref_ay_mg, g_ref_az_mg };

        for (uint16_t i = 0U; i < n_samples; i++) {
            const uint8_t *f = &g_fifo_raw[i * 6U];
            if (f[0] == 0x00U && f[1] == 0x80U && f[2] == 0x00U && f[3] == 0x80U) {
                break;
            }
            tp_accel_mg_t cur;
            convert_raw_to_mg(f, &cur);

            if (!g_ref_valid) {
                g_ref_ax_mg = cur.ax_mg;
                g_ref_ay_mg = cur.ay_mg;
                g_ref_az_mg = cur.az_mg;
                g_ref_valid = true;
                prev        = cur;
            }

            sum_x += cur.ax_mg;
            sum_y += cur.ay_mg;
            sum_z += cur.az_mg;
            valid_cnt++;

            /* Static deviation from resting gravity vector (detects tilt/lift/tow) */
            int32_t dx = (int32_t)cur.ax_mg - (int32_t)g_ref_ax_mg;
            int32_t dy = (int32_t)cur.ay_mg - (int32_t)g_ref_ay_mg;
            int32_t dz = (int32_t)cur.az_mg - (int32_t)g_ref_az_mg;
            uint32_t dev_mg = (uint32_t)sqrtf((float)(dx * dx + dy * dy + dz * dz));

            /* Sample-to-sample dynamic jerk (detects sharp impacts/knocks) */
            int32_t jx = (int32_t)cur.ax_mg - (int32_t)prev.ax_mg;
            int32_t jy = (int32_t)cur.ay_mg - (int32_t)prev.ay_mg;
            int32_t jz = (int32_t)cur.az_mg - (int32_t)prev.az_mg;
            uint32_t jerk_mg = (uint32_t)sqrtf((float)(jx * jx + jy * jy + jz * jz));
            prev = cur;

            uint32_t metric = (dev_mg > jerk_mg) ? dev_mg : jerk_mg;
            if (metric > peak_mg) {
                peak_mg = metric;
            }

            if (metric >= g_motion_thresh_mg) {
                trigger_count++;
                if (metric >= (uint32_t)g_motion_thresh_mg * 2UL) {
                    violent_shock = true;
                }
                if (abs((int)dx) >= (int)g_motion_thresh_mg || abs((int)jx) >= (int)g_motion_thresh_mg) {
                    axis_mask |= 0x01U; /* X */
                }
                if (abs((int)dy) >= (int)g_motion_thresh_mg || abs((int)jy) >= (int)g_motion_thresh_mg) {
                    axis_mask |= 0x02U; /* Y */
                }
                if (abs((int)dz) >= (int)g_motion_thresh_mg || abs((int)jz) >= (int)g_motion_thresh_mg) {
                    axis_mask |= 0x04U; /* Z */
                }
                /* Tilt > ~20 deg corresponds to > 340 mg vector shift from resting gravity */
                if (abs((int)dx) > 340 || abs((int)dy) > 340 || abs((int)dz) > 340) {
                    axis_mask |= 0x08U; /* tamper_tilt */
                }
            }
        }
    }

    /* If FIFO had backlog > 64 samples, flush remainder so fill level drops below watermark */
    if (overflow_backlog) {
        (void)bma_set_aps(false, true);
        (void)bma_write_reg(REG_CMD, CMD_FIFO_FLUSH);
        (void)bma_set_aps(true, true);
    }

    /* Read INT_STATUS_1 AFTER draining FIFO so latched INT1 drops LOW (§4.5 p.29) */
    uint8_t st1 = 0U;
    (void)bma_read_reg(REG_INT_STATUS_1, &st1);

    /* Guarantee INT1 line is released LOW so next EXTI1 rising edge cannot be missed */
    if (HAL_GPIO_ReadPin(ACC_INT1_GPIO_Port, ACC_INT1_Pin) == GPIO_PIN_SET) {
        (void)tp_bma456_rearm_int1();
    }

    /* Confirm genuine movement: >= 2 samples above threshold OR single violent shock (>= 2x) */
    bool motion_confirmed = (trigger_count >= 2U) || violent_shock;

    if (!motion_confirmed && valid_cnt > 0U) {
        /* Calm batch: gently adapt gravity baseline (1/8 EMA) to track slow temperature drift */
        int16_t mx = (int16_t)(sum_x / (int32_t)valid_cnt);
        int16_t my = (int16_t)(sum_y / (int32_t)valid_cnt);
        int16_t mz = (int16_t)(sum_z / (int32_t)valid_cnt);
        g_ref_ax_mg = (int16_t)(g_ref_ax_mg + ((mx - g_ref_ax_mg) / 8));
        g_ref_ay_mg = (int16_t)(g_ref_ay_mg + ((my - g_ref_ay_mg) / 8));
        g_ref_az_mg = (int16_t)(g_ref_az_mg + ((mz - g_ref_az_mg) / 8));
        return false;
    }

    if (motion_confirmed && evt != NULL) {
        evt->peak_mg    = (peak_mg > 65535UL) ? 65535U : (uint16_t)peak_mg;
        evt->axis_mask  = (axis_mask != 0U) ? axis_mask : 0x01U;
        evt->int_status = st1;
    }
    return motion_confirmed;
}

bool tp_bma456_rearm_int1(void)
{
    if (!g_present) {
        return false;
    }
    bool prev_aps = g_aps_enabled;
    if (prev_aps) {
        (void)bma_set_aps(false, true);
    }
    (void)bma_write_reg(REG_CMD, CMD_FIFO_FLUSH);
    uint8_t d0 = 0U, d1 = 0U;
    (void)bma_read_reg(REG_INT_STATUS_0, &d0);
    (void)bma_read_reg(REG_INT_STATUS_1, &d1);
    if (prev_aps) {
        (void)bma_set_aps(true, true);
    }
    return true;
}

bool tp_bma456_read_temp_c(int8_t *temp_c)
{
    if (!g_present || temp_c == NULL) {
        return false;
    }
    uint8_t raw = 0x80U;
    if (!bma_read_reg(REG_TEMPERATURE, &raw) || raw == 0x80U) {
        return false;
    }
    /* Datasheet §4.4 p.20 & §5.2 p.54: 2's complement 1 K/LSB, 0x00 = 23 °C */
    *temp_c = (int8_t)((int8_t)raw + 23);
    return true;
}
