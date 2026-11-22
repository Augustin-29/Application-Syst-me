/**
 * @file    tp_buzzer.c
 * @brief   Non-blocking PWM melody engine for CMT-8530S-SMT on TIM2_CH1 (PA0).
 *          TIM2 runs at 1 MHz (PCLK1 = 80 MHz, PSC = 79).
 */
#include "tp_buzzer.h"
#include "tp_config.h"
#include "main.h"

typedef struct {
    uint16_t freq_hz;    /* 0 = rest (silence) */
    uint16_t dur_ms;
} buzzer_step_t;

/* Melody tables */
static const buzzer_step_t k_melody_ping[] = {
    {2730, 120}, {0, 80}, {2730, 120}, {0, 280}
};

static const buzzer_step_t k_melody_startup[] = {
    {1047, 90}, {0, 25}, {1319, 90}, {0, 25},
    {1568, 90}, {0, 25}, {2093, 160}, {0, 80}
};

static const buzzer_step_t k_melody_siren[] = {
    {1800, 110}, {2100, 110}, {2400, 110}, {2730, 140},
    {2400, 110}, {2100, 110}, {0, 60}
};

static const buzzer_step_t k_melody_sos[] = {
    /* S (...) */
    {2730, 90}, {0, 90}, {2730, 90}, {0, 90}, {2730, 90}, {0, 240},
    /* O (---) */
    {2730, 270}, {0, 90}, {2730, 270}, {0, 90}, {2730, 270}, {0, 240},
    /* S (...) */
    {2730, 90}, {0, 90}, {2730, 90}, {0, 90}, {2730, 90}, {0, 500}
};

static const buzzer_step_t k_melody_victory[] = {
    {1047, 80}, {0, 20}, {1319, 80}, {0, 20}, {1568, 80}, {0, 20},
    {2093, 180}, {0, 30}, {1568, 90}, {0, 20}, {2093, 280}, {0, 100}
};

static const buzzer_step_t *g_seq          = NULL;
static uint8_t              g_seq_len      = 0U;
static uint8_t              g_step_idx     = 0U;
static uint8_t              g_repeats_left = 0U;
static bool                 g_infinite     = false;
static uint8_t              g_tempo_scale  = 10U;
static uint32_t             g_step_end_ms  = 0U;
static uint32_t             g_run_start_ms = 0U;
static bool                 g_active       = false;
static bool                 g_pwm_started  = false;
static buzzer_step_t        g_single_beep[2];

static void hw_set_freq(uint16_t freq_hz)
{
    if (freq_hz == 0U) {
        __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, 0U);
        return;
    }
    if (!g_pwm_started) {
        (void)HAL_TIM_PWM_Start(&htim2, TIM_CHANNEL_1);
        g_pwm_started = true;
    }
    uint32_t arr = (1000000UL / (uint32_t)freq_hz) - 1UL;
    __HAL_TIM_SET_AUTORELOAD(&htim2, arr);
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, (arr + 1UL) / 2UL);
}

static uint32_t scaled_duration(uint16_t base_ms)
{
    uint32_t d = ((uint32_t)base_ms * 10UL) / (uint32_t)g_tempo_scale;
    return (d < 15UL) ? 15UL : d;
}

void tp_buzzer_init(void)
{
    g_active      = false;
    g_pwm_started = false;
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, 0U);
    (void)HAL_TIM_PWM_Stop(&htim2, TIM_CHANNEL_1);
}

void tp_buzzer_stop(void)
{
    /* Force CCR1 = 0 first so PA0 stays LOW and Q2 (AO3400A) never stays ON */
    __HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, 0U);
    if (g_pwm_started) {
        (void)HAL_TIM_PWM_Stop(&htim2, TIM_CHANNEL_1);
        g_pwm_started = false;
    }
    g_active = false;
    g_seq    = NULL;
}

void tp_buzzer_play(uint8_t melody_id, uint8_t repeat_count, uint8_t tempo_scale)
{
    if (melody_id == TP_MELODY_STOP) {
        tp_buzzer_stop();
        return;
    }

    switch (melody_id) {
        case TP_MELODY_PING_2730HZ:
            g_seq     = k_melody_ping;
            g_seq_len = (uint8_t)(sizeof(k_melody_ping) / sizeof(k_melody_ping[0]));
            break;
        case TP_MELODY_STARTUP_JINGLE:
            g_seq     = k_melody_startup;
            g_seq_len = (uint8_t)(sizeof(k_melody_startup) / sizeof(k_melody_startup[0]));
            break;
        case TP_MELODY_SIREN_ALERT:
            g_seq     = k_melody_siren;
            g_seq_len = (uint8_t)(sizeof(k_melody_siren) / sizeof(k_melody_siren[0]));
            break;
        case TP_MELODY_SOS_MORSE:
            g_seq     = k_melody_sos;
            g_seq_len = (uint8_t)(sizeof(k_melody_sos) / sizeof(k_melody_sos[0]));
            break;
        case TP_MELODY_VICTORY:
            g_seq     = k_melody_victory;
            g_seq_len = (uint8_t)(sizeof(k_melody_victory) / sizeof(k_melody_victory[0]));
            break;
        default:
            tp_buzzer_stop();
            return;
    }

    if (tempo_scale < 5U)  tempo_scale = 5U;
    if (tempo_scale > 20U) tempo_scale = 20U;

    g_tempo_scale  = tempo_scale;
    g_infinite     = (repeat_count == 0U);
    g_repeats_left = (repeat_count > 10U) ? 10U : repeat_count;
    g_step_idx     = 0U;
    g_run_start_ms = HAL_GetTick();
    g_active       = true;

    hw_set_freq(g_seq[0].freq_hz);
    g_step_end_ms = g_run_start_ms + scaled_duration(g_seq[0].dur_ms);
}

void tp_buzzer_beep(uint16_t freq_hz, uint16_t duration_ms)
{
    if (freq_hz == 0U || duration_ms == 0U) {
        tp_buzzer_stop();
        return;
    }
    g_single_beep[0].freq_hz = freq_hz;
    g_single_beep[0].dur_ms  = duration_ms;
    g_single_beep[1].freq_hz = 0U;
    g_single_beep[1].dur_ms  = 10U;

    g_seq          = g_single_beep;
    g_seq_len      = 2U;
    g_step_idx     = 0U;
    g_repeats_left = 1U;
    g_infinite     = false;
    g_tempo_scale  = 10U;
    g_run_start_ms = HAL_GetTick();
    g_active       = true;

    hw_set_freq(freq_hz);
    g_step_end_ms = g_run_start_ms + duration_ms;
}

void tp_buzzer_tick(uint32_t now_ms)
{
    if (!g_active || g_seq == NULL) {
        return;
    }

    /* PROTOCOL.md §5.2: mandatory 30 s safety cutoff */
    if ((now_ms - g_run_start_ms) >= TP_BUZZER_MAX_RUN_MS) {
        tp_buzzer_stop();
        return;
    }

    if ((int32_t)(now_ms - g_step_end_ms) < 0) {
        return;
    }

    g_step_idx++;
    if (g_step_idx >= g_seq_len) {
        if (g_infinite) {
            g_step_idx = 0U;
        } else if (g_repeats_left > 1U) {
            g_repeats_left--;
            g_step_idx = 0U;
        } else {
            tp_buzzer_stop();
            return;
        }
    }

    hw_set_freq(g_seq[g_step_idx].freq_hz);
    g_step_end_ms = now_ms + scaled_duration(g_seq[g_step_idx].dur_ms);
}

bool tp_buzzer_is_active(void)
{
    return g_active;
}
