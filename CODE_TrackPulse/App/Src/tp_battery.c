/**
 * @file    tp_battery.c
 * @brief   Li-Ion 1S battery measurement with 1M/1M divider on ADC1_IN9 (PA4).
 */
#include "tp_battery.h"
#include "tp_config.h"
#include "main.h"

#define BATT_AVG_SAMPLES    8U
#define BATT_EMA_ALPHA_NUM  1U
#define BATT_EMA_ALPHA_DEN  4U

static uint16_t g_filtered_mv = 3900U;
static bool     g_initialized = false;

static uint16_t adc_read_single_mv(void)
{
    uint32_t acc = 0U;
    uint8_t  ok  = 0U;

    for (uint8_t i = 0U; i < BATT_AVG_SAMPLES; i++) {
        if (HAL_ADC_Start(&hadc1) == HAL_OK) {
            if (HAL_ADC_PollForConversion(&hadc1, 10U) == HAL_OK) {
                acc += HAL_ADC_GetValue(&hadc1);
                ok++;
            }
            (void)HAL_ADC_Stop(&hadc1);
        }
    }

    if (ok == 0U) {
        return g_filtered_mv;
    }

    uint32_t avg_raw = acc / (uint32_t)ok;
    /* VDDA = 3300 mV, R11=1M / R13=1M => 2x divider => 6600 mV full-scale */
    return (uint16_t)((avg_raw * 6600UL) / 4095UL);
}

void tp_battery_init(void)
{
    (void)HAL_ADCEx_Calibration_Start(&hadc1, ADC_SINGLE_ENDED);
    g_filtered_mv = adc_read_single_mv();
    g_initialized = true;
}

void tp_battery_sample(void)
{
    uint16_t measured = adc_read_single_mv();
    if (!g_initialized) {
        g_filtered_mv = measured;
        g_initialized = true;
    } else {
        /* Exponential moving average (alpha = 0.25) to suppress RF/buzzer dips */
        int32_t delta = (int32_t)measured - (int32_t)g_filtered_mv;
        g_filtered_mv = (uint16_t)((int32_t)g_filtered_mv +
                        (delta * (int32_t)BATT_EMA_ALPHA_NUM) / (int32_t)BATT_EMA_ALPHA_DEN);
    }
}

uint8_t tp_battery_mv_to_percent(uint16_t mv)
{
    /* Piecewise-linear Li-Ion 1S OCV discharge curve (3300 mV = 0% .. 4200 mV = 100%) */
    static const uint16_t k_mv[]  = { 3300, 3500, 3650, 3750, 3850, 3980, 4100, 4200 };
    static const uint8_t  k_pct[] = {    0,   10,   25,   45,   65,   82,   94,  100 };
    const uint8_t n = (uint8_t)(sizeof(k_mv) / sizeof(k_mv[0]));

    if (mv <= k_mv[0]) {
        return 0U;
    }
    if (mv >= k_mv[n - 1U]) {
        return 100U;
    }

    for (uint8_t i = 0U; i < (n - 1U); i++) {
        if (mv >= k_mv[i] && mv <= k_mv[i + 1U]) {
            uint32_t num = (uint32_t)(mv - k_mv[i]) * (uint32_t)(k_pct[i + 1U] - k_pct[i]);
            uint32_t den = (uint32_t)(k_mv[i + 1U] - k_mv[i]);
            return (uint8_t)(k_pct[i] + (num / den));
        }
    }
    return 50U;
}

tp_battery_status_t tp_battery_get_status(void)
{
    tp_battery_status_t st;
    st.voltage_mv    = g_filtered_mv;
    st.percent       = tp_battery_mv_to_percent(g_filtered_mv);
    st.is_charging   = (HAL_GPIO_ReadPin(BAT_STAT_GPIO_Port, BAT_STAT_Pin) == GPIO_PIN_RESET);
    st.has_usb_fault = (HAL_GPIO_ReadPin(FAULT_GPIO_Port, FAULT_Pin) == GPIO_PIN_RESET);
    st.low_for_flash = (g_filtered_mv > 1000U) && (g_filtered_mv < TP_FLASH_MIN_BATT_MV);
    return st;
}

bool tp_battery_is_low_for_flash(void)
{
    /* Ignore <1000 mV (USB bench power without battery attached) */
    return (g_filtered_mv > 1000U) && (g_filtered_mv < TP_FLASH_MIN_BATT_MV);
}
