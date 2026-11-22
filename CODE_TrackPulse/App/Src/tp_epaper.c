/**
 * @file    tp_epaper.c
 * @brief   Waveshare 1.54" E-Paper V2 (200x200, SSD1681) SPI1 driver, 5x7 font & UI screens.
 */
#include "tp_epaper.h"
#include "tp_config.h"
#include "main.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- Internal State ---- */
static uint8_t  g_fb[TP_EPD_FB_SIZE];
static bool     g_is_sleeping    = true;
static bool     g_has_base_frame = false;
static uint8_t  g_partial_count  = 0U;

/* PackBits RLE streaming decode state for CMD_EPAPER_BITMAP */
static uint16_t g_rle_out_pos    = 0U;
static int16_t  g_rle_pending    = 0; /* >0: literal bytes remaining, <0: repeat next byte (-pending) times */

/* ---- Low-Level SSD1681 SPI1 Helpers ---- */

static inline void epd_cs(bool low)
{
    HAL_GPIO_WritePin(EPD_CS_GPIO_Port, EPD_CS_Pin, low ? GPIO_PIN_RESET : GPIO_PIN_SET);
}

static inline void epd_dc(bool data)
{
    HAL_GPIO_WritePin(EPD_DC_GPIO_Port, EPD_DC_Pin, data ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

static inline void epd_rst(bool low)
{
    HAL_GPIO_WritePin(EPD_RST_GPIO_Port, EPD_RST_Pin, low ? GPIO_PIN_RESET : GPIO_PIN_SET);
}

static void epd_wait_busy(uint32_t timeout_ms)
{
    uint32_t start = HAL_GetTick();
    while (HAL_GPIO_ReadPin(EPD_BUSY_GPIO_Port, EPD_BUSY_Pin) == GPIO_PIN_SET) {
        if ((HAL_GetTick() - start) >= timeout_ms) {
            break; /* Do not lock up if panel is unplugged on bench */
        }
        HAL_Delay(2U);
    }
}

static void epd_send_cmd(uint8_t cmd)
{
    epd_dc(false);
    epd_cs(true);
    (void)HAL_SPI_Transmit(&hspi1, &cmd, 1U, 50U);
    epd_cs(false);
}

static void epd_send_data(uint8_t val)
{
    epd_dc(true);
    epd_cs(true);
    (void)HAL_SPI_Transmit(&hspi1, &val, 1U, 50U);
    epd_cs(false);
}

static void epd_send_buf(const uint8_t *buf, uint16_t len)
{
    epd_dc(true);
    epd_cs(true);
    (void)HAL_SPI_Transmit(&hspi1, (uint8_t *)buf, len, 500U);
    epd_cs(false);
}

static void epd_hw_reset(void)
{
    epd_rst(false);
    HAL_Delay(10U);
    epd_rst(true);
    HAL_Delay(5U);
    epd_rst(false);
    HAL_Delay(10U);
}

static void epd_set_window(uint8_t x_start_byte, uint8_t x_end_byte,
                           uint16_t y_start, uint16_t y_end)
{
    epd_send_cmd(0x44U); /* Set RAM X address start/end */
    epd_send_data(x_start_byte);
    epd_send_data(x_end_byte);

    epd_send_cmd(0x45U); /* Set RAM Y address start/end */
    epd_send_data((uint8_t)(y_start & 0xFFU));
    epd_send_data((uint8_t)((y_start >> 8) & 0xFFU));
    epd_send_data((uint8_t)(y_end & 0xFFU));
    epd_send_data((uint8_t)((y_end >> 8) & 0xFFU));
}

static void epd_set_cursor(uint8_t x_byte, uint16_t y)
{
    epd_send_cmd(0x4EU);
    epd_send_data(x_byte);
    epd_send_cmd(0x4FU);
    epd_send_data((uint8_t)(y & 0xFFU));
    epd_send_data((uint8_t)((y >> 8) & 0xFFU));
}

static void epd_wake_and_init_full(void)
{
    epd_hw_reset();
    epd_wait_busy(200U);

    epd_send_cmd(0x12U); /* SWRESET */
    epd_wait_busy(200U);

    epd_send_cmd(0x01U); /* Driver output control: 200 lines (0..199) */
    epd_send_data(0xC7U);
    epd_send_data(0x00U);
    epd_send_data(0x00U);

    epd_send_cmd(0x11U); /* Data entry mode: X inc, Y inc */
    epd_send_data(0x03U);

    epd_set_window(0U, (uint8_t)(TP_EPD_ROW_BYTES - 1U), 0U, TP_EPD_HEIGHT - 1U);

    epd_send_cmd(0x3CU); /* Border waveform */
    epd_send_data(0x05U);

    epd_send_cmd(0x18U); /* Internal temperature sensor */
    epd_send_data(0x80U);

    epd_set_cursor(0U, 0U);
    epd_wait_busy(200U);
    g_is_sleeping = false;
}

void tp_epaper_init(void)
{
    memset(g_fb, 0xFF, sizeof(g_fb));
    g_partial_count  = 0U;
    g_has_base_frame = false;
    epd_wake_and_init_full();
}

void tp_epaper_sleep(void)
{
    if (g_is_sleeping) {
        return;
    }
    epd_send_cmd(0x10U); /* Deep sleep mode 1 */
    epd_send_data(0x01U);
    HAL_Delay(5U);
    g_is_sleeping = true;
}

void tp_epaper_refresh(bool full_refresh)
{
    /* PROTOCOL.md §5.4: enforce full refresh every 10 consecutive partials */
    if (!g_has_base_frame || g_partial_count >= TP_EPAPER_MAX_PARTIALS) {
        full_refresh = true;
    }

    if (g_is_sleeping || full_refresh) {
        epd_wake_and_init_full();
    }

    if (full_refresh) {
        epd_set_window(0U, (uint8_t)(TP_EPD_ROW_BYTES - 1U), 0U, TP_EPD_HEIGHT - 1U);
        epd_set_cursor(0U, 0U);
        epd_send_cmd(0x24U); /* Write BW RAM */
        epd_send_buf(g_fb, TP_EPD_FB_SIZE);

        /* Mirror into RED/previous RAM (0x26) so subsequent partial refreshes have clean base */
        epd_set_cursor(0U, 0U);
        epd_send_cmd(0x26U);
        epd_send_buf(g_fb, TP_EPD_FB_SIZE);

        epd_send_cmd(0x22U); /* Display Update Control 2: Full */
        epd_send_data(0xF7U);
        epd_send_cmd(0x20U); /* Master Activation */
        epd_wait_busy(2500U);

        g_partial_count  = 0U;
        g_has_base_frame = true;
    } else {
        /* SSD1681 fast partial update (~0.3 s) */
        epd_send_cmd(0x3CU); /* Border waveform for partial */
        epd_send_data(0x80U);

        epd_set_window(0U, (uint8_t)(TP_EPD_ROW_BYTES - 1U), 0U, TP_EPD_HEIGHT - 1U);
        epd_set_cursor(0U, 0U);
        epd_send_cmd(0x24U);
        epd_send_buf(g_fb, TP_EPD_FB_SIZE);

        epd_send_cmd(0x22U); /* Display Update Control 2: Partial */
        epd_send_data(0xFFU);
        epd_send_cmd(0x20U);
        epd_wait_busy(600U);

        g_partial_count++;
    }
}

uint8_t *tp_epaper_get_framebuffer(void)
{
    return g_fb;
}

bool tp_epaper_handle_bitmap_chunk(const tp_cmd_epaper_bitmap_hdr_t *hdr,
                                   const uint8_t *data, uint16_t data_len)
{
    if (hdr == NULL || (data_len > 0U && data == NULL)) {
        return false;
    }

    if (hdr->encoding == 0U) {
        /* RAW 1-bit MSB first */
        if (hdr->offset_bytes >= TP_EPD_FB_SIZE) {
            return false;
        }
        uint16_t copy_len = data_len;
        if ((uint32_t)hdr->offset_bytes + copy_len > TP_EPD_FB_SIZE) {
            copy_len = (uint16_t)(TP_EPD_FB_SIZE - hdr->offset_bytes);
        }
        if (copy_len > 0U) {
            memcpy(&g_fb[hdr->offset_bytes], data, copy_len);
        }
    } else if (hdr->encoding == 1U) {
        /* PackBits RLE streaming decode (matches TrackPulseProtocol.encodePackBitsRle) */
        if (hdr->offset_bytes == 0U) {
            g_rle_out_pos = 0U;
            g_rle_pending = 0;
        }
        for (uint16_t i = 0U; i < data_len && g_rle_out_pos < TP_EPD_FB_SIZE; i++) {
            uint8_t b = data[i];
            if (g_rle_pending > 0) {
                g_fb[g_rle_out_pos++] = b;
                g_rle_pending--;
            } else if (g_rle_pending < 0) {
                uint16_t count = (uint16_t)(-g_rle_pending);
                while (count > 0U && g_rle_out_pos < TP_EPD_FB_SIZE) {
                    g_fb[g_rle_out_pos++] = b;
                    count--;
                }
                g_rle_pending = 0;
            } else {
                if (b <= 127U) {
                    g_rle_pending = (int16_t)(b + 1U);
                } else if (b >= 129U) {
                    g_rle_pending = -(int16_t)(257U - b);
                }
                /* b == 128 is PackBits NOP */
            }
        }
    } else {
        return false;
    }

    if ((hdr->flags & 0x01U) != 0U) {
        bool full = ((hdr->flags & 0x02U) != 0U);
        tp_epaper_refresh(full);
    }
    return true;
}

/* ---- 2D Graphics Primitives ---- */

void tp_epaper_clear(uint8_t color)
{
    memset(g_fb, (color == TP_EPD_WHITE) ? 0xFF : 0x00, sizeof(g_fb));
}

void tp_epaper_invert_fb(void)
{
    for (uint16_t i = 0U; i < TP_EPD_FB_SIZE; i++) {
        g_fb[i] ^= 0xFFU;
    }
}

void tp_epaper_set_pixel(int16_t x, int16_t y, uint8_t color)
{
    if (x < 0 || y < 0 || x >= (int16_t)TP_EPD_WIDTH || y >= (int16_t)TP_EPD_HEIGHT) {
        return;
    }
    uint16_t idx = (uint16_t)y * TP_EPD_ROW_BYTES + (uint16_t)(x >> 3);
    uint8_t mask = (uint8_t)(0x80U >> (x & 7));
    if (color == TP_EPD_WHITE) {
        g_fb[idx] |= mask;
    } else {
        g_fb[idx] &= (uint8_t)(~mask);
    }
}

void tp_epaper_draw_hline(int16_t x, int16_t y, int16_t w, uint8_t color)
{
    for (int16_t i = 0; i < w; i++) {
        tp_epaper_set_pixel((int16_t)(x + i), y, color);
    }
}

void tp_epaper_draw_vline(int16_t x, int16_t y, int16_t h, uint8_t color)
{
    for (int16_t i = 0; i < h; i++) {
        tp_epaper_set_pixel(x, (int16_t)(y + i), color);
    }
}

void tp_epaper_draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint8_t color)
{
    if (w <= 0 || h <= 0) return;
    tp_epaper_draw_hline(x, y, w, color);
    tp_epaper_draw_hline(x, (int16_t)(y + h - 1), w, color);
    tp_epaper_draw_vline(x, y, h, color);
    tp_epaper_draw_vline((int16_t)(x + w - 1), y, h, color);
}

void tp_epaper_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint8_t color)
{
    for (int16_t dy = 0; dy < h; dy++) {
        tp_epaper_draw_hline(x, (int16_t)(y + dy), w, color);
    }
}

void tp_epaper_draw_circle(int16_t cx, int16_t cy, int16_t r, uint8_t color)
{
    int16_t x = r, y = 0, err = 1 - r;
    while (x >= y) {
        tp_epaper_set_pixel((int16_t)(cx + x), (int16_t)(cy + y), color);
        tp_epaper_set_pixel((int16_t)(cx + y), (int16_t)(cy + x), color);
        tp_epaper_set_pixel((int16_t)(cx - y), (int16_t)(cy + x), color);
        tp_epaper_set_pixel((int16_t)(cx - x), (int16_t)(cy + y), color);
        tp_epaper_set_pixel((int16_t)(cx - x), (int16_t)(cy - y), color);
        tp_epaper_set_pixel((int16_t)(cx - y), (int16_t)(cy - x), color);
        tp_epaper_set_pixel((int16_t)(cx + y), (int16_t)(cy - x), color);
        tp_epaper_set_pixel((int16_t)(cx + x), (int16_t)(cy - y), color);
        y++;
        if (err < 0) {
            err += (int16_t)(2 * y + 1);
        } else {
            x--;
            err += (int16_t)(2 * (y - x + 1));
        }
    }
}

/* ---- Compact 5x7 ASCII Font (0x20..0x5F, lowercase auto-mapped to uppercase) ---- */

static const uint8_t k_font5x7[64][5] = {
    {0x00,0x00,0x00,0x00,0x00}, /* ' ' */
    {0x00,0x00,0x5F,0x00,0x00}, /* '!' */
    {0x00,0x07,0x00,0x07,0x00}, /* '"' */
    {0x14,0x7F,0x14,0x7F,0x14}, /* '#' */
    {0x24,0x2A,0x7F,0x2A,0x12}, /* '$' */
    {0x23,0x13,0x08,0x64,0x62}, /* '%' */
    {0x36,0x49,0x55,0x22,0x50}, /* '&' */
    {0x00,0x05,0x03,0x00,0x00}, /* '\'' */
    {0x00,0x1C,0x22,0x41,0x00}, /* '(' */
    {0x00,0x41,0x22,0x1C,0x00}, /* ')' */
    {0x14,0x08,0x3E,0x08,0x14}, /* '*' */
    {0x08,0x08,0x3E,0x08,0x08}, /* '+' */
    {0x00,0x50,0x30,0x00,0x00}, /* ',' */
    {0x08,0x08,0x08,0x08,0x08}, /* '-' */
    {0x00,0x60,0x60,0x00,0x00}, /* '.' */
    {0x20,0x10,0x08,0x04,0x02}, /* '/' */
    {0x3E,0x51,0x49,0x45,0x3E}, /* '0' */
    {0x00,0x42,0x7F,0x40,0x00}, /* '1' */
    {0x42,0x61,0x51,0x49,0x46}, /* '2' */
    {0x21,0x41,0x45,0x4B,0x31}, /* '3' */
    {0x18,0x14,0x12,0x7F,0x10}, /* '4' */
    {0x27,0x45,0x45,0x45,0x39}, /* '5' */
    {0x3C,0x4A,0x49,0x49,0x30}, /* '6' */
    {0x01,0x71,0x09,0x05,0x03}, /* '7' */
    {0x36,0x49,0x49,0x49,0x36}, /* '8' */
    {0x06,0x49,0x49,0x29,0x1E}, /* '9' */
    {0x00,0x36,0x36,0x00,0x00}, /* ':' */
    {0x00,0x56,0x36,0x00,0x00}, /* ';' */
    {0x08,0x14,0x22,0x41,0x00}, /* '<' */
    {0x14,0x14,0x14,0x14,0x14}, /* '=' */
    {0x00,0x41,0x22,0x14,0x08}, /* '>' */
    {0x02,0x01,0x51,0x09,0x06}, /* '?' */
    {0x32,0x49,0x79,0x41,0x3E}, /* '@' */
    {0x7E,0x11,0x11,0x11,0x7E}, /* 'A' */
    {0x7F,0x49,0x49,0x49,0x36}, /* 'B' */
    {0x3E,0x41,0x41,0x41,0x22}, /* 'C' */
    {0x7F,0x41,0x41,0x22,0x1C}, /* 'D' */
    {0x7F,0x49,0x49,0x49,0x41}, /* 'E' */
    {0x7F,0x09,0x09,0x09,0x01}, /* 'F' */
    {0x3E,0x41,0x49,0x49,0x7A}, /* 'G' */
    {0x7F,0x08,0x08,0x08,0x7F}, /* 'H' */
    {0x00,0x41,0x7F,0x41,0x00}, /* 'I' */
    {0x20,0x40,0x41,0x3F,0x01}, /* 'J' */
    {0x7F,0x08,0x14,0x22,0x41}, /* 'K' */
    {0x7F,0x40,0x40,0x40,0x40}, /* 'L' */
    {0x7F,0x02,0x0C,0x02,0x7F}, /* 'M' */
    {0x7F,0x04,0x08,0x10,0x7F}, /* 'N' */
    {0x3E,0x41,0x41,0x41,0x3E}, /* 'O' */
    {0x7F,0x09,0x09,0x09,0x06}, /* 'P' */
    {0x3E,0x41,0x51,0x21,0x5E}, /* 'Q' */
    {0x7F,0x09,0x19,0x29,0x46}, /* 'R' */
    {0x46,0x49,0x49,0x49,0x31}, /* 'S' */
    {0x01,0x01,0x7F,0x01,0x01}, /* 'T' */
    {0x3F,0x40,0x40,0x40,0x3F}, /* 'U' */
    {0x1F,0x20,0x40,0x20,0x1F}, /* 'V' */
    {0x3F,0x40,0x38,0x40,0x3F}, /* 'W' */
    {0x63,0x14,0x08,0x14,0x63}, /* 'X' */
    {0x07,0x08,0x70,0x08,0x07}, /* 'Y' */
    {0x61,0x51,0x49,0x45,0x43}, /* 'Z' */
    {0x00,0x7F,0x41,0x41,0x00}, /* '[' */
    {0x02,0x04,0x08,0x10,0x20}, /* '\\' */
    {0x00,0x41,0x41,0x7F,0x00}, /* ']' */
    {0x04,0x02,0x01,0x02,0x04}, /* '^' */
    {0x40,0x40,0x40,0x40,0x40}  /* '_' */
};

static void draw_char(int16_t x, int16_t y, char c, uint8_t scale, uint8_t color)
{
    if (c >= 'a' && c <= 'z') {
        c = (char)(c - 'a' + 'A');
    }
    if (c < 0x20 || c > 0x5F) {
        c = ' ';
    }
    const uint8_t *glyph = k_font5x7[(uint8_t)(c - 0x20)];
    for (uint8_t col = 0U; col < 5U; col++) {
        uint8_t bits = glyph[col];
        for (uint8_t row = 0U; row < 7U; row++) {
            if (bits & (1U << row)) {
                tp_epaper_fill_rect((int16_t)(x + col * scale),
                                    (int16_t)(y + row * scale),
                                    scale, scale, color);
            }
        }
    }
}

void tp_epaper_draw_text(int16_t x, int16_t y, const char *str, uint8_t scale, uint8_t color)
{
    if (str == NULL || scale == 0U) return;
    int16_t cx = x;
    while (*str != '\0') {
        draw_char(cx, y, *str, scale, color);
        cx = (int16_t)(cx + 6 * scale);
        str++;
    }
}

void tp_epaper_draw_text_centered(int16_t y, const char *str, uint8_t scale, uint8_t color)
{
    if (str == NULL || scale == 0U) return;
    int16_t w = (int16_t)(strlen(str) * 6U * scale);
    int16_t x = (int16_t)((TP_EPD_WIDTH - w) / 2);
    if (x < 0) x = 0;
    tp_epaper_draw_text(x, y, str, scale, color);
}

void tp_epaper_draw_qrcode(const tp_qrcode_t *qr, int16_t x0, int16_t y0, uint8_t module_px)
{
    if (qr == NULL || module_px == 0U) return;
    for (uint8_t my = 0U; my < qr->size; my++) {
        for (uint8_t mx = 0U; mx < qr->size; mx++) {
            if (tp_qrcode_get_module(qr, mx, my)) {
                tp_epaper_fill_rect((int16_t)(x0 + mx * module_px),
                                    (int16_t)(y0 + my * module_px),
                                    module_px, module_px, TP_EPD_BLACK);
            }
        }
    }
}

/* ---- Shared Top Status Bar ---- */

static void draw_status_bar(uint8_t batt_pct, bool charging, uint8_t sats, bool ble_conn)
{
    tp_epaper_fill_rect(0, 0, TP_EPD_WIDTH, 22, TP_EPD_BLACK);
    char left[24];
    snprintf(left, sizeof(left), "%s SAT:%u", ble_conn ? "BLE" : "4G ", (unsigned)sats);
    tp_epaper_draw_text(6, 7, left, 1U, TP_EPD_WHITE);

    char right[12];
    snprintf(right, sizeof(right), "%u%%%s", (unsigned)batt_pct, charging ? "+" : "");
    tp_epaper_draw_text(136, 7, right, 1U, TP_EPD_WHITE);

    /* Battery outline icon */
    tp_epaper_draw_rect(172, 5, 20, 11, TP_EPD_WHITE);
    tp_epaper_fill_rect(192, 8, 3, 5, TP_EPD_WHITE);
    int16_t fill_w = (int16_t)((batt_pct * 16U) / 100U);
    if (fill_w > 0) {
        tp_epaper_fill_rect(174, 7, fill_w, 7, TP_EPD_WHITE);
    }
}

/* ---- High-Level UI Screens ---- */

void tp_epaper_render_pairing_qr(const char *device_id, const char *pairing_key,
                                 uint8_t batt_pct, bool invert, bool full_refresh)
{
    char qr_str[32];
    snprintf(qr_str, sizeof(qr_str), "TP1:%s:%s", device_id, pairing_key);

    tp_qrcode_t qr;
    tp_epaper_clear(TP_EPD_WHITE);

    if (tp_qrcode_encode(qr_str, &qr)) {
        /* Preserve >= 2.5 modules of pure white quiet zone on all 4 sides for instant ML Kit scan:
           V1 (21x21): 7 px/module = 147x147 px (quiet zone 26 px H, 18 px top, 19 px bottom)
           V2 (25x25): 6 px/module = 150x150 px (quiet zone 25 px H, 16 px top, 18 px bottom) */
        uint8_t mpx   = (qr.version == 1U) ? 7U : 6U;
        int16_t qr_px = (int16_t)(qr.size * mpx);
        int16_t x0    = (int16_t)((TP_EPD_WIDTH - qr_px) / 2);
        int16_t y0    = (qr.version == 1U) ? 18 : 16;
        tp_epaper_draw_qrcode(&qr, x0, y0, mpx);
    } else {
        tp_epaper_draw_text_centered(90, "QR ERROR", 2U, TP_EPD_BLACK);
    }

    /* Compact footer below quiet zone with human-readable ID, key & battery */
    char footer[40];
    snprintf(footer, sizeof(footer), "%s %u%%", qr_str, (unsigned)batt_pct);
    tp_epaper_draw_text_centered(186, footer, 1U, TP_EPD_BLACK);

    if (invert) {
        tp_epaper_invert_fb();
    }
    tp_epaper_refresh(full_refresh);
}

void tp_epaper_render_antitheft(const tp_evt_status_t *status, bool alert_active,
                                bool invert, bool full_refresh)
{
    tp_epaper_clear(TP_EPD_WHITE);
    draw_status_bar(status->battery_pct,
                    (status->status_flags & TP_SFLAG_CHARGING) != 0U,
                    status->gps_sats,
                    (status->status_flags & TP_SFLAG_BLE_CONNECTED) != 0U);

    /* Padlock icon centered */
    tp_epaper_draw_circle(100, 54, 16, TP_EPD_BLACK);
    tp_epaper_draw_circle(100, 54, 15, TP_EPD_BLACK);
    tp_epaper_fill_rect(80, 54, 41, 20, TP_EPD_WHITE);
    tp_epaper_fill_rect(76, 56, 48, 36, TP_EPD_BLACK);
    tp_epaper_fill_rect(97, 68, 6, 12, TP_EPD_WHITE);

    if (alert_active) {
        tp_epaper_fill_rect(14, 102, 172, 26, TP_EPD_BLACK);
        tp_epaper_draw_text_centered(108, "ALERT MOTION!", 2U, TP_EPD_WHITE);
    } else {
        tp_epaper_draw_text_centered(106, "ANTIVOL ARME", 2U, TP_EPD_BLACK);
    }

    char line1[32], line2[32];
    if ((status->status_flags & TP_SFLAG_GPS_FIX) != 0U) {
        int32_t lat_i = status->lat_e7 / 10000000L;
        int32_t lat_f = labs(status->lat_e7 % 10000000L) / 1000L;
        int32_t lon_i = status->lon_e7 / 10000000L;
        int32_t lon_f = labs(status->lon_e7 % 10000000L) / 1000L;
        snprintf(line1, sizeof(line1), "GPS %ld.%04ld %ld.%04ld",
                 (long)lat_i, (long)lat_f, (long)lon_i, (long)lon_f);
    } else {
        snprintf(line1, sizeof(line1), "GPS: WAITING FIX");
    }
    snprintf(line2, sizeof(line2), "BATT %umV  UP %lus",
             (unsigned)status->battery_mv, (unsigned long)status->uptime_sec);

    tp_epaper_draw_hline(10, 140, 180, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(150, line1, 1U, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(168, line2, 1U, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(184, "BP1+BP2: QR CODE", 1U, TP_EPD_BLACK);

    if (invert) {
        tp_epaper_invert_fb();
    }
    tp_epaper_refresh(full_refresh);
}

void tp_epaper_render_track_hud(const tp_evt_status_t *status,
                                int16_t ax_mg, int16_t ay_mg, uint16_t max_speed_cms,
                                uint16_t peak_g_mg, uint32_t session_sec,
                                bool invert, bool full_refresh)
{
    tp_epaper_clear(TP_EPD_WHITE);
    draw_status_bar(status->battery_pct,
                    (status->status_flags & TP_SFLAG_CHARGING) != 0U,
                    status->gps_sats,
                    (status->status_flags & TP_SFLAG_BLE_CONNECTED) != 0U);

    /* Big speed readout in km/h: speed_kmh = speed_cms * 36 / 1000 */
    uint32_t kmh = ((uint32_t)status->speed_cms * 36UL) / 1000UL;
    uint32_t max_kmh = ((uint32_t)max_speed_cms * 36UL) / 1000UL;
    char spd_str[12];
    snprintf(spd_str, sizeof(spd_str), "%3lu", (unsigned long)kmh);
    tp_epaper_draw_text(14, 34, spd_str, 4U, TP_EPD_BLACK);
    tp_epaper_draw_text(94, 52, "KM/H", 1U, TP_EPD_BLACK);

    /* G-force circular target on the right */
    const int16_t gcx = 160, gcy = 60, gr = 28;
    tp_epaper_draw_circle(gcx, gcy, gr, TP_EPD_BLACK);
    tp_epaper_draw_hline((int16_t)(gcx - gr), gcy, (int16_t)(2 * gr + 1), TP_EPD_BLACK);
    tp_epaper_draw_vline(gcx, (int16_t)(gcy - gr), (int16_t)(2 * gr + 1), TP_EPD_BLACK);

    /* Clamp G dot within +/-2.0 G */
    int16_t gx = (int16_t)((ay_mg * (int32_t)(gr - 4)) / 2000L);
    int16_t gy = (int16_t)((-ax_mg * (int32_t)(gr - 4)) / 2000L);
    if (gx > (gr - 4))  gx = (int16_t)(gr - 4);
    if (gx < -(gr - 4)) gx = (int16_t)(-(gr - 4));
    if (gy > (gr - 4))  gy = (int16_t)(gr - 4);
    if (gy < -(gr - 4)) gy = (int16_t)(-(gr - 4));
    tp_epaper_fill_rect((int16_t)(gcx + gx - 3), (int16_t)(gcy + gy - 3), 7, 7, TP_EPD_BLACK);

    tp_epaper_draw_hline(8, 100, 184, TP_EPD_BLACK);

    char l1[32], l2[32], l3[32];
    snprintf(l1, sizeof(l1), "MAX: %lu KM/H", (unsigned long)max_kmh);
    snprintf(l2, sizeof(l2), "PEAK G: %u.%02u G",
             (unsigned)(peak_g_mg / 1000U), (unsigned)((peak_g_mg % 1000U) / 10U));
    snprintf(l3, sizeof(l3), "TIME: %02lu:%02lu  %s",
             (unsigned long)(session_sec / 60UL), (unsigned long)(session_sec % 60UL),
             (status->status_flags & TP_SFLAG_RECORDING) ? "REC" : "RDY");

    tp_epaper_draw_text(12, 112, l1, 2U, TP_EPD_BLACK);
    tp_epaper_draw_text(12, 138, l2, 2U, TP_EPD_BLACK);
    tp_epaper_draw_text(12, 166, l3, 2U, TP_EPD_BLACK);

    if (invert) {
        tp_epaper_invert_fb();
    }
    tp_epaper_refresh(full_refresh);
}

void tp_epaper_render_custom_card(const tp_cmd_epaper_config_t *cfg,
                                  const tp_evt_status_t *status, bool full_refresh)
{
    tp_epaper_clear(TP_EPD_WHITE);
    if ((cfg->flags & TP_EPD_FLAG_SHOW_BATT) || (cfg->flags & TP_EPD_FLAG_SHOW_SATS)) {
        draw_status_bar(status->battery_pct,
                        (status->status_flags & TP_SFLAG_CHARGING) != 0U,
                        status->gps_sats,
                        (status->status_flags & TP_SFLAG_BLE_CONNECTED) != 0U);
    }

    tp_epaper_draw_rect(8, 30, 184, 158, TP_EPD_BLACK);
    tp_epaper_draw_rect(10, 32, 180, 154, TP_EPD_BLACK);

    char title[25];
    char sub[33];
    memcpy(title, cfg->title, 24U);
    title[24] = '\0';
    memcpy(sub, cfg->subtitle, 32U);
    sub[32] = '\0';

    uint8_t tscale = (strlen(title) <= 14U) ? 2U : 1U;
    tp_epaper_draw_text_centered(66, title, tscale, TP_EPD_BLACK);
    tp_epaper_draw_hline(24, 98, 152, TP_EPD_BLACK);
    tp_epaper_draw_text_centered(114, sub, 1U, TP_EPD_BLACK);

    char info[32];
    snprintf(info, sizeof(info), "FLASH: %lu KB",
             (unsigned long)(status->flash_used_bytes / 1024UL));
    tp_epaper_draw_text_centered(154, info, 1U, TP_EPD_BLACK);

    if ((cfg->flags & TP_EPD_FLAG_INVERT_BW) != 0U) {
        tp_epaper_invert_fb();
    }
    tp_epaper_refresh(full_refresh);
}
