/**
 * @file    tp_easter_egg.c
 * @brief   PULSE_RUNNER & TRACK_DODGE playable 200x200 E-Paper mini-games with TIM2 SFX.
 */
#include "tp_easter_egg.h"
#include "tp_board.h"
#include "tp_buzzer.h"
#include "tp_epaper.h"
#include "tp_qspi_flash.h"
#include "main.h"

#include <stdio.h>
#include <string.h>

#define EE_FRAME_MS             340U  /* Tuned for SSD1681 ~0.3 s partial refresh */
#define EE_MAX_OBSTACLES        4U

typedef struct {
    bool    active;
    int16_t x;
    int16_t y;
    uint8_t kind; /* Runner: 0=ground cone, 1=overhead gantry | Dodge: lane 0..2 */
} ee_obs_t;

static bool     g_running       = false;
static bool     g_game_over     = false;
static bool     g_new_record    = false;
static uint8_t  g_game_id       = TP_EE_GAME_PULSE_RUNNER;
static bool     g_sound         = true;
static bool     g_invert        = false;
static uint32_t g_last_frame_ms = 0U;
static uint32_t g_start_ms      = 0U;
static uint32_t g_score         = 0U;
static uint32_t g_rng_state     = 0x12345678UL;
static uint16_t g_tick_count    = 0U;

/* Pulse Runner state */
static int16_t  g_runner_y      = 0; /* height above ground (0..52) */
static int16_t  g_runner_vy     = 0;
static bool     g_runner_duck   = false;

/* Track Dodge state */
static uint8_t  g_dodge_lane    = 1U; /* 0=left, 1=center, 2=right */
static bool     g_bp1_latch     = false;
static bool     g_bp2_latch     = false;

static ee_obs_t g_obs[EE_MAX_OBSTACLES];

static uint32_t ee_rand(void)
{
    g_rng_state ^= g_rng_state << 13;
    g_rng_state ^= g_rng_state >> 17;
    g_rng_state ^= g_rng_state << 5;
    return g_rng_state;
}

static uint8_t score_slot(uint8_t game_id)
{
    return (game_id == TP_EE_GAME_TRACK_DODGE) ? 1U : 0U;
}

static void reset_round(void)
{
    g_game_over     = false;
    g_new_record    = false;
    g_score         = 0U;
    g_tick_count    = 0U;
    g_start_ms      = HAL_GetTick();
    g_last_frame_ms = 0U;
    g_rng_state     = HAL_GetTick() ^ 0xA55A5AA5UL;
    if (g_rng_state == 0U) g_rng_state = 0xCAFEBABEUL;

    g_runner_y    = 0;
    g_runner_vy   = 0;
    g_runner_duck = false;
    g_dodge_lane  = 1U;
    g_bp1_latch   = tp_btn_bp1_is_pressed();
    g_bp2_latch   = tp_btn_bp2_is_pressed();
    memset(g_obs, 0, sizeof(g_obs));
}

static void commit_match_score(void)
{
    tp_superblock_t *sb = tp_flash_get_superblock();
    uint8_t slot = score_slot(g_game_id);
    tp_evt_easter_egg_score_t *sc = &sb->ee_scores[slot];

    sc->game_id    = g_game_id;
    sc->last_score = g_score;
    if (g_score > sc->high_score) {
        sc->high_score = g_score;
        g_new_record   = true;
    }
    if (sc->games_played < 65535U) {
        sc->games_played++;
    }
    uint32_t dur_sec = (HAL_GetTick() - g_start_ms) / 1000UL;
    if (dur_sec == 0U) dur_sec = 1U;
    if ((uint32_t)sc->play_time_sec + dur_sec > 65535UL) {
        sc->play_time_sec = 65535U;
    } else {
        sc->play_time_sec = (uint16_t)(sc->play_time_sec + dur_sec);
    }

    (void)tp_flash_save_superblock();
}

/* ---- Pulse Runner (Game 1) ---- */

static void step_pulse_runner(bool bp1_down, bool bp2_down, bool *crashed)
{
    *crashed = false;

    /* Jump on BP1 when on ground */
    if (bp1_down && g_runner_y == 0) {
        g_runner_vy = 26;
        g_runner_duck = false;
        if (g_sound) tp_buzzer_beep(1319U, 45U);
    }
    g_runner_duck = (bp2_down && g_runner_y == 0);
    if (g_runner_duck && (g_tick_count & 3U) == 0U && g_sound) {
        tp_buzzer_beep(880U, 25U);
    }

    /* Simple gravity integration */
    if (g_runner_y > 0 || g_runner_vy > 0) {
        g_runner_y = (int16_t)(g_runner_y + g_runner_vy);
        g_runner_vy = (int16_t)(g_runner_vy - 10);
        if (g_runner_y <= 0) {
            g_runner_y  = 0;
            g_runner_vy = 0;
        }
    }

    int16_t speed = (int16_t)(16 + (g_score / 120U));
    if (speed > 28) speed = 28;

    /* Move existing obstacles */
    int16_t rightmost = 0;
    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        g_obs[i].x = (int16_t)(g_obs[i].x - speed);
        if (g_obs[i].x < -24) {
            g_obs[i].active = false;
        } else if (g_obs[i].x > rightmost) {
            rightmost = g_obs[i].x;
        }
    }

    /* Spawn new obstacle with fair spacing */
    if (rightmost < 115 && (ee_rand() % 3U) == 0U) {
        for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
            if (!g_obs[i].active) {
                g_obs[i].active = true;
                g_obs[i].x      = 200;
                g_obs[i].kind   = (uint8_t)((ee_rand() % 3U == 0U) ? 1U : 0U);
                g_obs[i].y      = (g_obs[i].kind == 0U) ? 140 : 122;
                break;
            }
        }
    }

    /* Player hitbox (x=24..42) */
    int16_t py_bot = (int16_t)(158 - g_runner_y);
    int16_t py_top = (int16_t)(py_bot - (g_runner_duck ? 12 : 24));

    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        int16_t ox1 = g_obs[i].x;
        int16_t ox2 = (int16_t)(ox1 + 14);
        int16_t oy1 = (g_obs[i].kind == 0U) ? 140 : 114;
        int16_t oy2 = (g_obs[i].kind == 0U) ? 158 : 138;

        if (42 > ox1 && 24 < ox2 && py_bot > oy1 && py_top < oy2) {
            *crashed = true;
            break;
        }
    }

    if (!(*crashed)) {
        g_score += 10U;
        if (g_sound && (g_score % 200U) == 0U) {
            tp_buzzer_beep(2093U, 50U);
        }
    }
}

static void render_pulse_runner(void)
{
    const tp_evt_easter_egg_score_t sc = tp_easter_egg_get_score(TP_EE_GAME_PULSE_RUNNER);
    uint32_t best = (g_score > sc.high_score) ? g_score : sc.high_score;

    tp_epaper_clear(TP_EPD_WHITE);
    tp_epaper_fill_rect(0, 0, TP_EPD_WIDTH, 22, TP_EPD_BLACK);
    char hdr[32];
    snprintf(hdr, sizeof(hdr), "RUNNER  %04lu HI:%04lu", (unsigned long)g_score, (unsigned long)best);
    tp_epaper_draw_text(6, 7, hdr, 1U, TP_EPD_WHITE);

    /* Ground line + scrolling dashes */
    tp_epaper_draw_hline(0, 158, TP_EPD_WIDTH, TP_EPD_BLACK);
    tp_epaper_draw_hline(0, 159, TP_EPD_WIDTH, TP_EPD_BLACK);
    int16_t dash_off = (int16_t)((g_tick_count * 12U) % 24U);
    for (int16_t x = (int16_t)(-dash_off); x < 200; x += 24) {
        tp_epaper_draw_hline(x, 165, 10, TP_EPD_BLACK);
    }

    /* Runner sprite */
    int16_t py_bot = (int16_t)(158 - g_runner_y);
    if (g_runner_duck) {
        tp_epaper_fill_rect(22, (int16_t)(py_bot - 12), 22, 10, TP_EPD_BLACK);
        tp_epaper_fill_rect(26, (int16_t)(py_bot - 4), 5, 4, TP_EPD_BLACK);
        tp_epaper_fill_rect(36, (int16_t)(py_bot - 4), 5, 4, TP_EPD_BLACK);
    } else {
        /* Helmet + kart body */
        tp_epaper_fill_rect(28, (int16_t)(py_bot - 24), 10, 9, TP_EPD_BLACK);
        tp_epaper_fill_rect(24, (int16_t)(py_bot - 14), 18, 10, TP_EPD_BLACK);
        tp_epaper_fill_rect(25, (int16_t)(py_bot - 4), 5, 4, TP_EPD_BLACK);
        tp_epaper_fill_rect(36, (int16_t)(py_bot - 4), 5, 4, TP_EPD_BLACK);
    }

    /* Obstacles */
    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        if (g_obs[i].kind == 0U) {
            /* Track cone / tire stack */
            tp_epaper_fill_rect(g_obs[i].x, 140, 14, 18, TP_EPD_BLACK);
            tp_epaper_draw_hline((int16_t)(g_obs[i].x + 2), 146, 10, TP_EPD_WHITE);
            tp_epaper_draw_hline((int16_t)(g_obs[i].x + 2), 152, 10, TP_EPD_WHITE);
        } else {
            /* Overhead pit gantry sign (duck under!) */
            tp_epaper_fill_rect(g_obs[i].x, 114, 16, 24, TP_EPD_BLACK);
            tp_epaper_draw_rect((int16_t)(g_obs[i].x + 2), 116, 12, 20, TP_EPD_WHITE);
        }
    }

    tp_epaper_draw_text_centered(182, "BP1:JUMP   BP2:DUCK", 1U, TP_EPD_BLACK);
}

/* ---- Track Dodge (Game 2) ---- */

static void step_track_dodge(bool bp1_edge, bool bp2_edge, bool *crashed)
{
    *crashed = false;

    if (bp1_edge && g_dodge_lane > 0U) {
        g_dodge_lane--;
        if (g_sound) tp_buzzer_beep(1175U, 35U);
    } else if (bp2_edge && g_dodge_lane < 2U) {
        g_dodge_lane++;
        if (g_sound) tp_buzzer_beep(1568U, 35U);
    }

    int16_t dy = (int16_t)(18 + (g_score / 150U));
    if (dy > 30) dy = 30;

    int16_t highest_y = 200;
    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        g_obs[i].y = (int16_t)(g_obs[i].y + dy);
        if (g_obs[i].y > 190) {
            g_obs[i].active = false;
        } else if (g_obs[i].y < highest_y) {
            highest_y = g_obs[i].y;
        }
    }

    /* Spawn oncoming rival kart in random lane with guaranteed gap */
    if (highest_y > 74) {
        for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
            if (!g_obs[i].active) {
                g_obs[i].active = true;
                g_obs[i].kind   = (uint8_t)(ee_rand() % 3U); /* lane 0..2 */
                g_obs[i].y      = 24;
                break;
            }
        }
    }

    /* Player car occupies y = 142..170 in lane g_dodge_lane */
    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        if (g_obs[i].kind == g_dodge_lane &&
            (g_obs[i].y + 22) > 142 && g_obs[i].y < 170) {
            *crashed = true;
            break;
        }
    }

    if (!(*crashed)) {
        g_score += 10U;
        if (g_sound && (g_score % 200U) == 0U) {
            tp_buzzer_beep(2093U, 50U);
        }
    }
}

static void render_track_dodge(void)
{
    static const int16_t k_lane_cx[3] = { 54, 100, 146 };
    const tp_evt_easter_egg_score_t sc = tp_easter_egg_get_score(TP_EE_GAME_TRACK_DODGE);
    uint32_t best = (g_score > sc.high_score) ? g_score : sc.high_score;

    tp_epaper_clear(TP_EPD_WHITE);
    tp_epaper_fill_rect(0, 0, TP_EPD_WIDTH, 22, TP_EPD_BLACK);
    char hdr[32];
    snprintf(hdr, sizeof(hdr), "DODGE   %04lu HI:%04lu", (unsigned long)g_score, (unsigned long)best);
    tp_epaper_draw_text(6, 7, hdr, 1U, TP_EPD_WHITE);

    /* Track kerbs (left x=24..30, right x=170..176) */
    for (int16_t y = 24; y < 180; y += 16) {
        bool alt = (((y / 16) + (int16_t)g_tick_count) & 1) != 0;
        if (alt) {
            tp_epaper_fill_rect(24, y, 6, 12, TP_EPD_BLACK);
            tp_epaper_fill_rect(170, y, 6, 12, TP_EPD_BLACK);
        } else {
            tp_epaper_draw_rect(24, y, 6, 12, TP_EPD_BLACK);
            tp_epaper_draw_rect(170, y, 6, 12, TP_EPD_BLACK);
        }
    }

    /* Dashed lane dividers (x=77, x=123) */
    int16_t yoff = (int16_t)((g_tick_count * 10U) % 20U);
    for (int16_t y = (int16_t)(24 + yoff); y < 176; y += 20) {
        tp_epaper_draw_vline(77, y, 10, TP_EPD_BLACK);
        tp_epaper_draw_vline(123, y, 10, TP_EPD_BLACK);
    }

    /* Oncoming rival cars */
    for (uint8_t i = 0U; i < EE_MAX_OBSTACLES; i++) {
        if (!g_obs[i].active) continue;
        int16_t cx = k_lane_cx[g_obs[i].kind % 3U];
        tp_epaper_draw_rect((int16_t)(cx - 12), g_obs[i].y, 24, 22, TP_EPD_BLACK);
        tp_epaper_fill_rect((int16_t)(cx - 8), (int16_t)(g_obs[i].y + 4), 16, 14, TP_EPD_BLACK);
    }

    /* Player kart */
    int16_t pcx = k_lane_cx[g_dodge_lane % 3U];
    tp_epaper_fill_rect((int16_t)(pcx - 11), 144, 22, 26, TP_EPD_BLACK);
    tp_epaper_fill_rect((int16_t)(pcx - 5), 150, 10, 10, TP_EPD_WHITE);
    tp_epaper_fill_rect((int16_t)(pcx - 14), 147, 3, 7, TP_EPD_BLACK);
    tp_epaper_fill_rect((int16_t)(pcx + 11), 147, 3, 7, TP_EPD_BLACK);
    tp_epaper_fill_rect((int16_t)(pcx - 14), 161, 3, 7, TP_EPD_BLACK);
    tp_epaper_fill_rect((int16_t)(pcx + 11), 161, 3, 7, TP_EPD_BLACK);

    tp_epaper_draw_text_centered(184, "BP1:LEFT  BP2:RIGHT", 1U, TP_EPD_BLACK);
}

static void render_game_over_overlay(void)
{
    const tp_evt_easter_egg_score_t sc = tp_easter_egg_get_score(g_game_id);

    tp_epaper_fill_rect(18, 52, 164, 96, TP_EPD_WHITE);
    tp_epaper_draw_rect(18, 52, 164, 96, TP_EPD_BLACK);
    tp_epaper_draw_rect(20, 54, 160, 92, TP_EPD_BLACK);

    tp_epaper_draw_text_centered(62, g_new_record ? "NEW RECORD!" : "GAME OVER", 2U, TP_EPD_BLACK);

    char s1[28], s2[28];
    snprintf(s1, sizeof(s1), "SCORE: %lu", (unsigned long)g_score);
    snprintf(s2, sizeof(s2), "BEST : %lu", (unsigned long)sc.high_score);
    tp_epaper_draw_text_centered(90, s1, 1U, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(106, s2, 1U, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(128, "BP1:RETRY  BP2:EXIT", 1U, TP_EPD_BLACK);
}

/* ---- Public API ---- */

void tp_easter_egg_start(uint8_t game_id, bool sound_enabled, bool invert_bw)
{
    g_game_id = (game_id == TP_EE_GAME_TRACK_DODGE) ? TP_EE_GAME_TRACK_DODGE : TP_EE_GAME_PULSE_RUNNER;
    g_sound   = sound_enabled;
    g_invert  = invert_bw;
    g_running = true;
    reset_round();

    if (g_sound) {
        tp_buzzer_play(TP_MELODY_STARTUP_JINGLE, 1U, 15U);
    }

    if (g_game_id == TP_EE_GAME_TRACK_DODGE) {
        render_track_dodge();
    } else {
        render_pulse_runner();
    }
    if (g_invert) tp_epaper_invert_fb();
    tp_epaper_refresh(true);
    g_last_frame_ms = HAL_GetTick();
}

void tp_easter_egg_stop(void)
{
    g_running = false;
    tp_buzzer_stop();
}

bool tp_easter_egg_is_running(void)
{
    return g_running;
}

void tp_easter_egg_tick(uint32_t now_ms, bool *out_score_ready, bool *out_exit_req)
{
    if (out_score_ready != NULL) *out_score_ready = false;
    if (out_exit_req != NULL)    *out_exit_req    = false;

    if (!g_running) {
        return;
    }

    bool bp1 = tp_btn_bp1_is_pressed();
    bool bp2 = tp_btn_bp2_is_pressed();
    bool bp1_edge = (bp1 && !g_bp1_latch);
    bool bp2_edge = (bp2 && !g_bp2_latch);
    g_bp1_latch = bp1;
    g_bp2_latch = bp2;

    if (g_game_over) {
        if (bp1_edge) {
            reset_round();
            if (g_game_id == TP_EE_GAME_TRACK_DODGE) render_track_dodge();
            else render_pulse_runner();
            if (g_invert) tp_epaper_invert_fb();
            tp_epaper_refresh(false);
            g_last_frame_ms = now_ms;
        } else if (bp2_edge) {
            tp_easter_egg_stop();
            if (out_exit_req != NULL) *out_exit_req = true;
        }
        return;
    }

    /* Capture instantaneous button edges between frames for Track Dodge responsiveness */
    static bool queued_bp1 = false;
    static bool queued_bp2 = false;
    if (bp1_edge) queued_bp1 = true;
    if (bp2_edge) queued_bp2 = true;

    if ((now_ms - g_last_frame_ms) < EE_FRAME_MS) {
        return;
    }
    g_last_frame_ms = now_ms;
    g_tick_count++;

    bool crashed = false;
    if (g_game_id == TP_EE_GAME_TRACK_DODGE) {
        step_track_dodge(queued_bp1 || bp1, queued_bp2 || bp2, &crashed);
        render_track_dodge();
    } else {
        step_pulse_runner(queued_bp1 || bp1, queued_bp2 || bp2, &crashed);
        render_pulse_runner();
    }
    queued_bp1 = false;
    queued_bp2 = false;

    if (crashed) {
        g_game_over = true;
        commit_match_score();
        render_game_over_overlay();
        if (g_sound) {
            if (g_new_record) {
                tp_buzzer_play(TP_MELODY_VICTORY, 1U, 12U);
            } else {
                tp_buzzer_beep(320U, 260U);
            }
        }
        if (out_score_ready != NULL) *out_score_ready = true;
    }

    if (g_invert) tp_epaper_invert_fb();
    tp_epaper_refresh(false);
}

tp_evt_easter_egg_score_t tp_easter_egg_get_score(uint8_t game_id)
{
    tp_superblock_t *sb = tp_flash_get_superblock();
    uint8_t slot = score_slot(game_id);
    tp_evt_easter_egg_score_t sc = sb->ee_scores[slot];
    sc.game_id = (slot == 1U) ? TP_EE_GAME_TRACK_DODGE : TP_EE_GAME_PULSE_RUNNER;
    return sc;
}

void tp_easter_egg_reset_score(uint8_t game_id)
{
    tp_superblock_t *sb = tp_flash_get_superblock();
    uint8_t slot = score_slot(game_id);
    memset(&sb->ee_scores[slot], 0, sizeof(sb->ee_scores[slot]));
    sb->ee_scores[slot].game_id = (slot == 1U) ? TP_EE_GAME_TRACK_DODGE : TP_EE_GAME_PULSE_RUNNER;
    (void)tp_flash_save_superblock();
}
