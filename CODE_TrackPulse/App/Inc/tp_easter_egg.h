/**
 * @file    tp_easter_egg.h
 * @brief   Playable Easter Egg mini-games on Waveshare 1.54" E-Paper V2 + BP1/BP2 + Buzzer.
 *
 * Games (PROTOCOL.md §4.4):
 *   1 = PULSE_RUNNER (BP1=Jump, BP2=Slide/Duck)
 *   2 = TRACK_DODGE  (BP1=Left lane, BP2=Right lane)
 */
#ifndef TP_EASTER_EGG_H
#define TP_EASTER_EGG_H

#include "tp_protocol.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Start a mini-game (game_id = 1 or 2). */
void tp_easter_egg_start(uint8_t game_id, bool sound_enabled, bool invert_bw);

/** Stop mini-game cleanly. */
void tp_easter_egg_stop(void);

/** True while a mini-game session is active. */
bool tp_easter_egg_is_running(void);

/**
 * Tick game loop (reads BP1/BP2, updates physics/collisions, renders E-Paper partial frames).
 * @param out_score_ready  Set to true when a match finishes and high scores should be synced.
 * @param out_exit_req     Set to true if user pressed BP2 on Game Over screen to exit.
 */
void tp_easter_egg_tick(uint32_t now_ms, bool *out_score_ready, bool *out_exit_req);

/** Get persisted score structure for game_id (1=PULSE_RUNNER, 2=TRACK_DODGE). */
tp_evt_easter_egg_score_t tp_easter_egg_get_score(uint8_t game_id);

/** Reset stored high score & stats for game_id in Flash Sector 0. */
void tp_easter_egg_reset_score(uint8_t game_id);

#ifdef __cplusplus
}
#endif

#endif /* TP_EASTER_EGG_H */
