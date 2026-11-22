/**
 * @file    tp_protocol.c
 * @brief   TrackPulse binary protocol CRC-16, encoder, and 250 ms timeout FSM parser.
 */
#include "tp_protocol.h"
#include "tp_config.h"

#include <string.h>

uint16_t tp_crc16_ccitt(const uint8_t *data, uint16_t len)
{
    uint16_t crc = 0xFFFFU;
    for (uint16_t i = 0U; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t bit = 0U; bit < 8U; bit++) {
            if (crc & 0x8000U) {
                crc = (uint16_t)((crc << 1) ^ 0x1021U);
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

uint16_t tp_frame_encode(uint8_t *out_buf, uint16_t out_cap,
                         uint8_t opcode, uint8_t seq, uint8_t flags,
                         const void *payload, uint16_t payload_len)
{
    if (out_buf == NULL || payload_len > TP_MAX_PAYLOAD_LEN) {
        return 0U;
    }
    uint16_t total = (uint16_t)(TP_OVERHEAD_SIZE + payload_len);
    if (out_cap < total) {
        return 0U;
    }

    out_buf[0] = TP_SOF_0;
    out_buf[1] = TP_SOF_1;
    out_buf[2] = (uint8_t)(((TP_PROTOCOL_VERSION & 0x0FU) << 4) | (flags & 0x0FU));
    out_buf[3] = opcode;
    out_buf[4] = seq;
    out_buf[5] = (uint8_t)(payload_len & 0xFFU);
    out_buf[6] = (uint8_t)((payload_len >> 8) & 0xFFU);

    if (payload_len > 0U && payload != NULL) {
        memcpy(&out_buf[7], payload, payload_len);
    }

    uint16_t crc = tp_crc16_ccitt(&out_buf[2], (uint16_t)(5U + payload_len));
    out_buf[7U + payload_len] = (uint8_t)(crc & 0xFFU);
    out_buf[8U + payload_len] = (uint8_t)((crc >> 8) & 0xFFU);
    return total;
}

void tp_parser_reset(tp_parser_t *p)
{
    if (p == NULL) {
        return;
    }
    p->state          = TP_PARSE_SOF0;
    p->frame_start_ms = 0U;
    p->payload_idx    = 0U;
    p->crc_l          = 0U;
    p->last_crc_error = false;
}

void tp_parser_check_timeout(tp_parser_t *p, uint32_t now_ms)
{
    if (p == NULL || p->state == TP_PARSE_SOF0) {
        return;
    }
    if ((now_ms - p->frame_start_ms) >= TP_FRAME_RX_TIMEOUT_MS) {
        tp_parser_reset(p);
    }
}

bool tp_parser_feed(tp_parser_t *p, uint8_t byte, uint32_t now_ms)
{
    if (p == NULL) {
        return false;
    }

    p->last_crc_error = false;
    tp_parser_check_timeout(p, now_ms);

    switch (p->state) {
        case TP_PARSE_SOF0:
            if (byte == TP_SOF_0) {
                p->state          = TP_PARSE_SOF1;
                p->frame_start_ms = now_ms;
            }
            break;

        case TP_PARSE_SOF1:
            if (byte == TP_SOF_1) {
                p->state = TP_PARSE_VER_FLAGS;
            } else if (byte == TP_SOF_0) {
                p->frame_start_ms = now_ms;
            } else {
                p->state = TP_PARSE_SOF0;
            }
            break;

        case TP_PARSE_VER_FLAGS:
            p->raw_crc_buf[0] = byte;
            p->frame.version  = (uint8_t)((byte >> 4) & 0x0FU);
            p->frame.flags    = (uint8_t)(byte & 0x0FU);
            p->state          = TP_PARSE_OPCODE;
            break;

        case TP_PARSE_OPCODE:
            p->raw_crc_buf[1] = byte;
            p->frame.opcode   = byte;
            p->state          = TP_PARSE_SEQ;
            break;

        case TP_PARSE_SEQ:
            p->raw_crc_buf[2] = byte;
            p->frame.seq      = byte;
            p->state          = TP_PARSE_LEN_L;
            break;

        case TP_PARSE_LEN_L:
            p->raw_crc_buf[3] = byte;
            p->frame.len      = byte;
            p->state          = TP_PARSE_LEN_H;
            break;

        case TP_PARSE_LEN_H:
            p->raw_crc_buf[4] = byte;
            p->frame.len     |= (uint16_t)((uint16_t)byte << 8);
            if (p->frame.len > TP_MAX_PAYLOAD_LEN) {
                p->state = TP_PARSE_SOF0;
            } else if (p->frame.len == 0U) {
                p->payload_idx = 0U;
                p->state       = TP_PARSE_CRC_L;
            } else {
                p->payload_idx = 0U;
                p->state       = TP_PARSE_PAYLOAD;
            }
            break;

        case TP_PARSE_PAYLOAD:
            p->frame.payload[p->payload_idx]     = byte;
            p->raw_crc_buf[5U + p->payload_idx]  = byte;
            p->payload_idx++;
            if (p->payload_idx >= p->frame.len) {
                p->state = TP_PARSE_CRC_L;
            }
            break;

        case TP_PARSE_CRC_L:
            p->crc_l = byte;
            p->state = TP_PARSE_CRC_H;
            break;

        case TP_PARSE_CRC_H: {
            uint16_t rx_crc   = (uint16_t)p->crc_l | ((uint16_t)byte << 8);
            uint16_t calc_crc = tp_crc16_ccitt(p->raw_crc_buf, (uint16_t)(5U + p->frame.len));
            p->state          = TP_PARSE_SOF0;
            if (rx_crc == calc_crc) {
                return true;
            }
            p->last_crc_error = true;
            break;
        }

        default:
            p->state = TP_PARSE_SOF0;
            break;
    }

    return false;
}

bool tp_frame_decode_buf(const uint8_t *buf, uint16_t len, tp_frame_t *out)
{
    if (buf == NULL || out == NULL || len < TP_OVERHEAD_SIZE) {
        return false;
    }

    /* Scan for SOF inside buffer */
    for (uint16_t i = 0U; (uint16_t)(i + TP_OVERHEAD_SIZE) <= len; i++) {
        if (buf[i] != TP_SOF_0 || buf[i + 1U] != TP_SOF_1) {
            continue;
        }
        uint16_t plen = (uint16_t)buf[i + 5U] | ((uint16_t)buf[i + 6U] << 8);
        if (plen > TP_MAX_PAYLOAD_LEN) {
            continue;
        }
        uint16_t frame_len = (uint16_t)(TP_OVERHEAD_SIZE + plen);
        if ((uint16_t)(i + frame_len) > len) {
            return false;
        }
        uint16_t calc = tp_crc16_ccitt(&buf[i + 2U], (uint16_t)(5U + plen));
        uint16_t rxc  = (uint16_t)buf[i + 7U + plen] | ((uint16_t)buf[i + 8U + plen] << 8);
        if (calc != rxc) {
            continue;
        }
        out->version = (uint8_t)((buf[i + 2U] >> 4) & 0x0FU);
        out->flags   = (uint8_t)(buf[i + 2U] & 0x0FU);
        out->opcode  = buf[i + 3U];
        out->seq     = buf[i + 4U];
        out->len     = plen;
        if (plen > 0U) {
            memcpy(out->payload, &buf[i + 7U], plen);
        }
        return true;
    }
    return false;
}
