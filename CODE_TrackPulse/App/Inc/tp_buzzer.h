/**
 * @file    tp_buzzer.h
 * @brief   Non-blocking PWM buzzer driver on TIM2_CH1 (PA0, CMT-8530S-SMT).
 */
#ifndef TP_BUZZER_H
#define TP_BUZZER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    TP_MELODY_STOP           = 0,
    TP_MELODY_PING_2730HZ    = 1,
    TP_MELODY_STARTUP_JINGLE = 2,
    TP_MELODY_SIREN_ALERT    = 3,
    TP_MELODY_SOS_MORSE      = 4,
    TP_MELODY_VICTORY        = 5
} tp_buzzer_melody_t;

/** Ensure TIM2_CH1 output is safely off (CCR1 = 0, MOSFET Q2 blocked). */
void tp_buzzer_init(void);

/**
 * Start playing a protocol melody asynchronously.
 * @param melody_id    0=STOP .. 5=VICTORY
 * @param repeat_count 1..10 (0 = continuous up to 30 s hard safety limit)
 * @param tempo_scale  5..20 (10 = x1.0 speed; higher = faster)
 */
void tp_buzzer_play(uint8_t melody_id, uint8_t repeat_count, uint8_t tempo_scale);

/** Play a single short tone (for Easter Egg SFX / button feedback). */
void tp_buzzer_beep(uint16_t freq_hz, uint16_t duration_ms);

/** Immediately silence buzzer (forces CCR1 = 0 before stopping PWM). */
void tp_buzzer_stop(void);

/** Non-blocking tick; call frequently from main loop. */
void tp_buzzer_tick(uint32_t now_ms);

/** True while a melody or beep is active. */
bool tp_buzzer_is_active(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_BUZZER_H */
