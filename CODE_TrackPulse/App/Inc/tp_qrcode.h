/**
 * @file    tp_qrcode.h
 * @brief   ISO/IEC 18004 zero-malloc QR Code encoder (Version 1: 21x21 & Version 2: 25x25, ECC-M).
 *          Designed for compact TrackPulse pairing codes ("TP1:<ID>:<KEY>") on 200x200 E-Paper.
 */
#ifndef TP_QRCODE_H
#define TP_QRCODE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TP_QR_MAX_SIZE      25U /* Version 2 = 25x25 modules */

typedef struct {
    uint8_t size;                        /* 21 (V1) or 25 (V2) */
    uint8_t version;                     /* 1 or 2 */
    uint8_t modules[TP_QR_MAX_SIZE * 4]; /* Packed row bits (up to 32 cols/row -> 4 bytes/row) */
} tp_qrcode_t;

/**
 * Encode ASCII string into QR Code Version 1-M (<=20 alphanumeric chars)
 * or Version 2-M (<=38 alphanumeric chars / <=26 arbitrary bytes).
 */
bool tp_qrcode_encode(const char *text, tp_qrcode_t *qr);

/** Query dark module state at (x, y) in [0..qr->size-1]. True = black module. */
bool tp_qrcode_get_module(const tp_qrcode_t *qr, uint8_t x, uint8_t y);

#ifdef __cplusplus
}
#endif

#endif /* TP_QRCODE_H */
