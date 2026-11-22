/**
 * @file    tp_epaper.h
 * @brief   Waveshare 1.54" E-Paper V2 (200x200, SSD1681) driver on SPI1 TX-Only.
 *
 * Pinout: SCK=PA5, MOSI=PB5, CS=PH1, DC=PA15, RST=PA8, BUSY=PB15.
 */
#ifndef TP_EPAPER_H
#define TP_EPAPER_H

#include "tp_protocol.h"
#include "tp_qrcode.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TP_EPD_WIDTH        200U
#define TP_EPD_HEIGHT       200U
#define TP_EPD_ROW_BYTES    (TP_EPD_WIDTH / 8U)           /* 25 bytes */
#define TP_EPD_FB_SIZE      (TP_EPD_ROW_BYTES * TP_EPD_HEIGHT) /* 5000 bytes */

#define TP_EPD_WHITE        1U
#define TP_EPD_BLACK        0U

/** Initialize E-Paper panel (full mode) and clear framebuffer. */
void tp_epaper_init(void);

/** Put SSD1681 panel into Deep Sleep (< 5 uA). Automatically wakes on next refresh. */
void tp_epaper_sleep(void);

/** Refresh display from internal 5000-byte framebuffer.
 *  Enforces automatic full refresh after TP_EPAPER_MAX_PARTIALS (10) consecutive partials. */
void tp_epaper_refresh(bool full_refresh);

/** Direct framebuffer access (5000 bytes, 1-bit MSB-first, 1=white, 0=black). */
uint8_t *tp_epaper_get_framebuffer(void);

/** Apply a CMD_EPAPER_BITMAP chunk (RAW or PackBits RLE); refreshes when commit flag set. */
bool tp_epaper_handle_bitmap_chunk(const tp_cmd_epaper_bitmap_hdr_t *hdr,
                                   const uint8_t *data, uint16_t data_len);

/* ---- 2D Graphics & Text Primitives ---- */
void tp_epaper_clear(uint8_t color);
void tp_epaper_invert_fb(void);
void tp_epaper_set_pixel(int16_t x, int16_t y, uint8_t color);
void tp_epaper_draw_hline(int16_t x, int16_t y, int16_t w, uint8_t color);
void tp_epaper_draw_vline(int16_t x, int16_t y, int16_t h, uint8_t color);
void tp_epaper_draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint8_t color);
void tp_epaper_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint8_t color);
void tp_epaper_draw_circle(int16_t cx, int16_t cy, int16_t r, uint8_t color);
void tp_epaper_draw_text(int16_t x, int16_t y, const char *str, uint8_t scale, uint8_t color);
void tp_epaper_draw_text_centered(int16_t y, const char *str, uint8_t scale, uint8_t color);
void tp_epaper_draw_qrcode(const tp_qrcode_t *qr, int16_t x0, int16_t y0, uint8_t module_px);

/* ---- High-Level TrackPulse Screen Renderers ---- */
void tp_epaper_render_pairing_qr(const char *device_id, const char *pairing_key,
                                 uint8_t batt_pct, bool invert, bool full_refresh);

void tp_epaper_render_antitheft(const tp_evt_status_t *status, bool alert_active,
                                bool invert, bool full_refresh);

void tp_epaper_render_track_hud(const tp_evt_status_t *status,
                                int16_t ax_mg, int16_t ay_mg, uint16_t max_speed_cms,
                                uint16_t peak_g_mg, uint32_t session_sec,
                                bool invert, bool full_refresh);

void tp_epaper_render_custom_card(const tp_cmd_epaper_config_t *cfg,
                                  const tp_evt_status_t *status, bool full_refresh);

#ifdef __cplusplus
}
#endif

#endif /* TP_EPAPER_H */
