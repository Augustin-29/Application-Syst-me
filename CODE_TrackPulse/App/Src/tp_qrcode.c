/**
 * @file    tp_qrcode.c
 * @brief   ISO/IEC 18004 QR Code Generator (V1-M 21x21 & V2-M 25x25, Alphanumeric & Byte).
 */
#include "tp_qrcode.h"

#include <stdlib.h>
#include <string.h>

/* ---- Bit & Module Helpers ---- */

static inline void grid_set(uint8_t *grid, uint8_t x, uint8_t y, bool dark)
{
    uint16_t idx = (uint16_t)y * 4U + (uint16_t)(x >> 3);
    uint8_t mask = (uint8_t)(1U << (x & 7U));
    if (dark) {
        grid[idx] |= mask;
    } else {
        grid[idx] &= (uint8_t)(~mask);
    }
}

static inline bool grid_get(const uint8_t *grid, uint8_t x, uint8_t y)
{
    uint16_t idx = (uint16_t)y * 4U + (uint16_t)(x >> 3);
    return (grid[idx] & (uint8_t)(1U << (x & 7U))) != 0U;
}

bool tp_qrcode_get_module(const tp_qrcode_t *qr, uint8_t x, uint8_t y)
{
    if (qr == NULL || x >= qr->size || y >= qr->size) {
        return false;
    }
    return grid_get(qr->modules, x, y);
}

/* ---- Alphanumeric Encoding Table (ISO/IEC 18004 Table 5) ---- */

static int8_t alnum_value(char c)
{
    if (c >= '0' && c <= '9') return (int8_t)(c - '0');
    if (c >= 'A' && c <= 'Z') return (int8_t)(10 + (c - 'A'));
    switch (c) {
        case ' ': return 36;
        case '$': return 37;
        case '%': return 38;
        case '*': return 39;
        case '+': return 40;
        case '-': return 41;
        case '.': return 42;
        case '/': return 43;
        case ':': return 44;
        default:  return -1;
    }
}

/* ---- Bitstream Writer ---- */

typedef struct {
    uint8_t *buf;
    uint16_t bit_len;
    uint16_t max_bits;
} bitbuf_t;

static void bitbuf_append(bitbuf_t *bb, uint32_t val, uint8_t nbits)
{
    for (int8_t i = (int8_t)(nbits - 1U); i >= 0; i--) {
        if (bb->bit_len < bb->max_bits) {
            if ((val >> i) & 1UL) {
                bb->buf[bb->bit_len >> 3] |= (uint8_t)(0x80U >> (bb->bit_len & 7U));
            }
            bb->bit_len++;
        }
    }
}

/* ---- Reed-Solomon over GF(256) with primitive polynomial 0x11D ---- */

static uint8_t gf_mul(uint8_t a, uint8_t b)
{
    uint8_t res = 0U;
    for (uint8_t i = 0U; i < 8U; i++) {
        if (b & 1U) {
            res ^= a;
        }
        bool hi = (a & 0x80U) != 0U;
        a <<= 1;
        if (hi) {
            a ^= 0x1DU; /* x^8 + x^4 + x^3 + x^2 + 1 */
        }
        b >>= 1;
    }
    return res;
}

static void rs_compute_ec(const uint8_t *data, uint8_t data_len,
                          uint8_t *ec, uint8_t ec_len)
{
    uint8_t gen[20];
    memset(gen, 0, sizeof(gen));
    gen[ec_len - 1U] = 1U;

    uint8_t root = 1U;
    for (uint8_t i = 0U; i < ec_len; i++) {
        for (uint8_t j = 0U; j < ec_len; j++) {
            gen[j] = gf_mul(gen[j], root);
            if ((j + 1U) < ec_len) {
                gen[j] ^= gen[j + 1U];
            }
        }
        root = gf_mul(root, 0x02U);
    }

    memset(ec, 0, ec_len);
    for (uint8_t i = 0U; i < data_len; i++) {
        uint8_t factor = (uint8_t)(data[i] ^ ec[0]);
        memmove(&ec[0], &ec[1], (size_t)(ec_len - 1U));
        ec[ec_len - 1U] = 0U;
        for (uint8_t j = 0U; j < ec_len; j++) {
            ec[j] ^= gf_mul(gen[j], factor);
        }
    }
}

/* ---- Function Pattern Placement ---- */

static void set_func(uint8_t *mod, uint8_t *is_fn, uint8_t size,
                     int8_t x, int8_t y, bool dark)
{
    if (x < 0 || y < 0 || x >= (int8_t)size || y >= (int8_t)size) {
        return;
    }
    grid_set(mod,   (uint8_t)x, (uint8_t)y, dark);
    grid_set(is_fn, (uint8_t)x, (uint8_t)y, true);
}

static void draw_finder(uint8_t *mod, uint8_t *is_fn, uint8_t size,
                        int8_t cx, int8_t cy)
{
    for (int8_t dy = -4; dy <= 4; dy++) {
        for (int8_t dx = -4; dx <= 4; dx++) {
            int8_t dist = (abs(dx) > abs(dy)) ? (int8_t)abs(dx) : (int8_t)abs(dy);
            bool dark = (dist != 2 && dist != 4);
            set_func(mod, is_fn, size, (int8_t)(cx + dx), (int8_t)(cy + dy), dark);
        }
    }
}

static void draw_alignment(uint8_t *mod, uint8_t *is_fn, uint8_t size,
                           int8_t cx, int8_t cy)
{
    for (int8_t dy = -2; dy <= 2; dy++) {
        for (int8_t dx = -2; dx <= 2; dx++) {
            int8_t dist = (abs(dx) > abs(dy)) ? (int8_t)abs(dx) : (int8_t)abs(dy);
            set_func(mod, is_fn, size, (int8_t)(cx + dx), (int8_t)(cy + dy), dist != 1);
        }
    }
}

static void draw_format_bits(uint8_t *mod, uint8_t *is_fn, uint8_t size, uint8_t mask_id)
{
    /* ECC Level M format indicator = 0b00 (ISO/IEC 18004 Table 25) */
    uint16_t data = (uint16_t)((0U << 3) | (mask_id & 7U));
    uint16_t rem  = (uint16_t)(data << 10);
    for (int8_t i = 14; i >= 10; i--) {
        if (rem & (1U << i)) {
            rem ^= (uint16_t)(0x537U << (i - 10));
        }
    }
    uint16_t bits = (uint16_t)(((data << 10) | rem) ^ 0x5412U);

    /* Copy 1 around top-left finder (MSB = bit 14 at (0,8) .. LSB = bit 0 at (8,0)) */
    static const uint8_t k_x1[15] = { 8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0 };
    static const uint8_t k_y1[15] = { 0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8 };
    for (uint8_t i = 0U; i < 15U; i++) {
        bool dark = ((bits >> i) & 1U) != 0U;
        set_func(mod, is_fn, size, (int8_t)k_x1[i], (int8_t)k_y1[i], dark);
    }

    /* Copy 2: bits 14..8 at (8, size-1..size-7), bits 7..0 at (size-8..size-1, 8) */
    for (uint8_t i = 0U; i < 8U; i++) {
        bool dark = ((bits >> i) & 1U) != 0U;
        set_func(mod, is_fn, size, (int8_t)(size - 1U - i), 8, dark);
    }
    for (uint8_t i = 8U; i < 15U; i++) {
        bool dark = ((bits >> i) & 1U) != 0U;
        set_func(mod, is_fn, size, 8, (int8_t)(size - 15U + i), dark);
    }
    /* Always-dark module at (8, size - 8) */
    set_func(mod, is_fn, size, 8, (int8_t)(size - 8U), true);
}

static bool mask_bit(uint8_t mask_id, uint8_t x, uint8_t y)
{
    switch (mask_id) {
        case 0U: return ((y + x) % 2U) == 0U;
        case 1U: return (y % 2U) == 0U;
        case 2U: return (x % 3U) == 0U;
        case 3U: return ((y + x) % 3U) == 0U;
        case 4U: return (((y / 2U) + (x / 3U)) % 2U) == 0U;
        case 5U: return (((y * x) % 2U) + ((y * x) % 3U)) == 0U;
        case 6U: return ((((y * x) % 2U) + ((y * x) % 3U)) % 2U) == 0U;
        case 7U: return ((((y + x) % 2U) + ((y * x) % 3U)) % 2U) == 0U;
        default: return false;
    }
}

static void apply_mask(uint8_t *mod, const uint8_t *is_fn, uint8_t size, uint8_t mask_id)
{
    for (uint8_t y = 0U; y < size; y++) {
        for (uint8_t x = 0U; x < size; x++) {
            if (!grid_get(is_fn, x, y) && mask_bit(mask_id, x, y)) {
                grid_set(mod, x, y, !grid_get(mod, x, y));
            }
        }
    }
}

static uint32_t compute_penalty(const uint8_t *mod, uint8_t size)
{
    uint32_t penalty = 0U;

    /* Rule 1: runs of >= 5 same-color modules in rows & cols */
    for (uint8_t y = 0U; y < size; y++) {
        bool prev = grid_get(mod, 0U, y);
        uint8_t run = 1U;
        for (uint8_t x = 1U; x < size; x++) {
            bool cur = grid_get(mod, x, y);
            if (cur == prev) {
                run++;
                if (run == 5U) penalty += 3U;
                else if (run > 5U) penalty += 1U;
            } else {
                prev = cur;
                run  = 1U;
            }
        }
    }
    for (uint8_t x = 0U; x < size; x++) {
        bool prev = grid_get(mod, x, 0U);
        uint8_t run = 1U;
        for (uint8_t y = 1U; y < size; y++) {
            bool cur = grid_get(mod, x, y);
            if (cur == prev) {
                run++;
                if (run == 5U) penalty += 3U;
                else if (run > 5U) penalty += 1U;
            } else {
                prev = cur;
                run  = 1U;
            }
        }
    }

    /* Rule 2: 2x2 same-color blocks */
    for (uint8_t y = 0U; y < (size - 1U); y++) {
        for (uint8_t x = 0U; x < (size - 1U); x++) {
            bool c = grid_get(mod, x, y);
            if (c == grid_get(mod, (uint8_t)(x + 1U), y) &&
                c == grid_get(mod, x, (uint8_t)(y + 1U)) &&
                c == grid_get(mod, (uint8_t)(x + 1U), (uint8_t)(y + 1U))) {
                penalty += 3U;
            }
        }
    }

    /* Rule 4: dark/light balance */
    uint32_t dark = 0U;
    uint32_t total = (uint32_t)size * (uint32_t)size;
    for (uint8_t y = 0U; y < size; y++) {
        for (uint8_t x = 0U; x < size; x++) {
            if (grid_get(mod, x, y)) dark++;
        }
    }
    int32_t pct_dev = (int32_t)((dark * 100UL) / total) - 50L;
    if (pct_dev < 0) pct_dev = -pct_dev;
    penalty += (uint32_t)((pct_dev / 5L) * 10L);

    return penalty;
}

bool tp_qrcode_encode(const char *text, tp_qrcode_t *qr)
{
    if (text == NULL || qr == NULL) {
        return false;
    }

    size_t len = strlen(text);
    if (len == 0U || len > 38U) {
        return false;
    }

    /* Check if string fits Alphanumeric mode */
    bool is_alnum = true;
    for (size_t i = 0U; i < len; i++) {
        if (alnum_value(text[i]) < 0) {
            is_alnum = false;
            break;
        }
    }

    /* Select smallest version (V1-M or V2-M) */
    uint8_t version = 0U;
    uint8_t data_cw = 0U;
    uint8_t ec_cw   = 0U;

    if (is_alnum) {
        if (len <= 20U) {
            version = 1U; data_cw = 16U; ec_cw = 10U;
        } else if (len <= 38U) {
            version = 2U; data_cw = 28U; ec_cw = 16U;
        }
    } else {
        if (len <= 14U) {
            version = 1U; data_cw = 16U; ec_cw = 10U;
        } else if (len <= 26U) {
            version = 2U; data_cw = 28U; ec_cw = 16U;
        }
    }
    if (version == 0U) {
        return false;
    }

    uint8_t codewords[44];
    memset(codewords, 0, sizeof(codewords));

    bitbuf_t bb = {
        .buf      = codewords,
        .bit_len  = 0U,
        .max_bits = (uint16_t)data_cw * 8U
    };

    if (is_alnum) {
        bitbuf_append(&bb, 0x02U, 4U); /* Alphanumeric mode */
        bitbuf_append(&bb, (uint32_t)len, 9U);
        size_t i = 0U;
        while ((i + 1U) < len) {
            uint32_t v = (uint32_t)alnum_value(text[i]) * 45U + (uint32_t)alnum_value(text[i + 1U]);
            bitbuf_append(&bb, v, 11U);
            i += 2U;
        }
        if (i < len) {
            bitbuf_append(&bb, (uint32_t)alnum_value(text[i]), 6U);
        }
    } else {
        bitbuf_append(&bb, 0x04U, 4U); /* Byte mode */
        bitbuf_append(&bb, (uint32_t)len, 8U);
        for (size_t i = 0U; i < len; i++) {
            bitbuf_append(&bb, (uint8_t)text[i], 8U);
        }
    }

    /* Terminator (up to 4 zero bits) + zero-pad to byte boundary */
    uint16_t rem_bits = (uint16_t)(bb.max_bits - bb.bit_len);
    bitbuf_append(&bb, 0U, (rem_bits >= 4U) ? 4U : (uint8_t)rem_bits);
    if ((bb.bit_len & 7U) != 0U) {
        bitbuf_append(&bb, 0U, (uint8_t)(8U - (bb.bit_len & 7U)));
    }

    /* Pad remaining data codewords with 0xEC, 0x11 */
    uint8_t pad_idx = 0U;
    while (bb.bit_len < bb.max_bits) {
        bitbuf_append(&bb, (pad_idx & 1U) ? 0x11U : 0xECU, 8U);
        pad_idx++;
    }

    /* Append Reed-Solomon EC codewords */
    rs_compute_ec(codewords, data_cw, &codewords[data_cw], ec_cw);
    uint8_t total_cw = (uint8_t)(data_cw + ec_cw);

    /* Build base module grid with function patterns */
    uint8_t size = (uint8_t)(17U + 4U * version);
    uint8_t is_fn[TP_QR_MAX_SIZE * 4];
    memset(qr->modules, 0, sizeof(qr->modules));
    memset(is_fn, 0, sizeof(is_fn));
    qr->version = version;
    qr->size    = size;

    /* Timing patterns */
    for (uint8_t i = 0U; i < size; i++) {
        set_func(qr->modules, is_fn, size, 6, (int8_t)i, (i & 1U) == 0U);
        set_func(qr->modules, is_fn, size, (int8_t)i, 6, (i & 1U) == 0U);
    }

    /* Three 7x7 finder patterns + white separators */
    draw_finder(qr->modules, is_fn, size, 3, 3);
    draw_finder(qr->modules, is_fn, size, (int8_t)(size - 4U), 3);
    draw_finder(qr->modules, is_fn, size, 3, (int8_t)(size - 4U));

    /* Alignment pattern at (18, 18) for Version 2 */
    if (version == 2U) {
        draw_alignment(qr->modules, is_fn, size, 18, 18);
    }

    /* Reserve format info modules (placeholder mask 0) */
    draw_format_bits(qr->modules, is_fn, size, 0U);

    /* Place codewords in standard zigzag scan */
    uint16_t bit_idx = 0U;
    uint16_t total_bits = (uint16_t)total_cw * 8U;
    bool upward = true;

    for (int8_t right = (int8_t)(size - 1U); right >= 1; right -= 2) {
        if (right == 6) {
            right = 5; /* Skip vertical timing column */
        }
        for (uint8_t step = 0U; step < size; step++) {
            uint8_t y = upward ? (uint8_t)(size - 1U - step) : step;
            for (uint8_t dx = 0U; dx < 2U; dx++) {
                uint8_t x = (uint8_t)(right - (int8_t)dx);
                if (!grid_get(is_fn, x, y)) {
                    bool dark = false;
                    if (bit_idx < total_bits) {
                        dark = ((codewords[bit_idx >> 3] >> (7U - (bit_idx & 7U))) & 1U) != 0U;
                        bit_idx++;
                    }
                    grid_set(qr->modules, x, y, dark);
                }
            }
        }
        upward = !upward;
    }

    /* Evaluate all 8 masks and keep lowest penalty */
    uint32_t best_pen  = 0xFFFFFFFFUL;
    uint8_t  best_mask = 0U;
    for (uint8_t m = 0U; m < 8U; m++) {
        apply_mask(qr->modules, is_fn, size, m);
        draw_format_bits(qr->modules, is_fn, size, m);
        uint32_t pen = compute_penalty(qr->modules, size);
        if (pen < best_pen) {
            best_pen  = pen;
            best_mask = m;
        }
        apply_mask(qr->modules, is_fn, size, m); /* XOR undo */
    }

    apply_mask(qr->modules, is_fn, size, best_mask);
    draw_format_bits(qr->modules, is_fn, size, best_mask);
    return true;
}
