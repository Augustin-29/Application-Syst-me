/**
 * @file    tp_protocol.h
 * @brief   TrackPulse binary telemetry protocol v1.0 (100% aligned with PROTOCOL.md).
 */
#ifndef TP_PROTOCOL_H
#define TP_PROTOCOL_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Framing Constants ---- */
#define TP_SOF_0                0xAAU
#define TP_SOF_1                0x55U
#define TP_PROTOCOL_VERSION     0x01U
#define TP_HEADER_SIZE          7U    /* SOF(2) + VER_FLG(1) + OPCODE(1) + SEQ(1) + LEN(2) */
#define TP_CRC_SIZE             2U
#define TP_OVERHEAD_SIZE        (TP_HEADER_SIZE + TP_CRC_SIZE)
#define TP_MAX_PAYLOAD_LEN      240U
#define TP_MAX_FRAME_LEN        (TP_OVERHEAD_SIZE + TP_MAX_PAYLOAD_LEN)

/* VER_FLAGS low nibble */
#define TP_FLAG_ACK_REQ         0x01U
#define TP_FLAG_IS_4G_CHANNEL   0x02U

/* ---- Opcodes: App -> Tracker (0x01..0x7F) ---- */
#define TP_CMD_PING                 0x01U
#define TP_CMD_GET_STATUS           0x02U
#define TP_CMD_SET_MODE             0x03U
#define TP_CMD_INJECT_AGPS          0x04U
#define TP_CMD_BUZZER_PLAY          0x05U
#define TP_CMD_REQUEST_FIX          0x06U
#define TP_CMD_CLEAR_ALERT          0x07U
#define TP_CMD_PAIR_HANDSHAKE       0x08U

#define TP_CMD_TRACK_START          0x10U
#define TP_CMD_TRACK_STOP           0x11U
#define TP_CMD_FLASH_LIST           0x12U
#define TP_CMD_FLASH_READ           0x13U
#define TP_CMD_FLASH_ERASE_SESSION  0x14U
#define TP_CMD_FLASH_FORMAT         0x15U

#define TP_CMD_EPAPER_CONFIG        0x20U
#define TP_CMD_EPAPER_BITMAP        0x21U
#define TP_CMD_EASTER_EGG_CTRL      0x30U

/* ---- Opcodes: Tracker -> App (0x80..0xFF) ---- */
#define TP_RSP_ACK                  0x81U
#define TP_EVT_STATUS               0x82U
#define TP_RSP_FLASH_SESSION        0x92U
#define TP_RSP_FLASH_CHUNK          0x93U
#define TP_EVT_MOTION_ALERT         0xA0U
#define TP_EVT_LIVE_TELEMETRY       0xA1U
#define TP_EVT_EASTER_EGG_SCORE     0xB0U

/* ---- ACK Status Codes ---- */
#define TP_STATUS_OK                0x00U
#define TP_STATUS_ERR_CRC           0x01U
#define TP_STATUS_ERR_UNKNOWN_OP    0x02U
#define TP_STATUS_ERR_INVALID_PARAM 0x03U
#define TP_STATUS_ERR_BUSY          0x04U
#define TP_STATUS_ERR_FLASH_FULL    0x05U
#define TP_STATUS_ERR_BLE_ONLY      0x06U

/* ---- Tracker Operating Modes ---- */
typedef enum {
    TP_MODE_ANTITHEFT_SLEEP = 0x00,
    TP_MODE_TRACK_RECORDING = 0x01,
    TP_MODE_PROXIMITY_FIND  = 0x02,
    TP_MODE_EASTER_EGG      = 0x03,
    TP_MODE_IDLE_STANDBY    = 0x04
} tp_mode_t;

/* ---- Status Flags (EVT_STATUS.status_flags) ---- */
#define TP_SFLAG_CHARGING       (1U << 0)
#define TP_SFLAG_USB_FAULT      (1U << 1)
#define TP_SFLAG_GPS_FIX        (1U << 2)
#define TP_SFLAG_RECORDING      (1U << 3)
#define TP_SFLAG_BLE_CONNECTED  (1U << 4)
#define TP_SFLAG_LTE_ATTACHED   (1U << 5)
#define TP_SFLAG_AGPS_VALID     (1U << 6)

/* ---- E-Paper Templates & Flags ---- */
#define TP_EPD_TPL_ANTITHEFT    0x00U
#define TP_EPD_TPL_TRACK_HUD    0x01U
#define TP_EPD_TPL_CUSTOM_CARD  0x02U
#define TP_EPD_TPL_MINIMAL_QR   0x03U
#define TP_EPD_TPL_EASTER_EGG   0x04U

#define TP_EPD_FLAG_INVERT_BW   (1U << 0)
#define TP_EPD_FLAG_SHOW_BATT   (1U << 1)
#define TP_EPD_FLAG_SHOW_SATS   (1U << 2)
#define TP_EPD_FLAG_AUTO_MODE   (1U << 3)

/* ---- Easter Egg Actions & Game IDs ---- */
#define TP_EE_ACTION_EXIT       0x00U
#define TP_EE_ACTION_START      0x01U
#define TP_EE_ACTION_SYNC       0x02U
#define TP_EE_ACTION_RESET      0x03U

#define TP_EE_GAME_PULSE_RUNNER 0x01U
#define TP_EE_GAME_TRACK_DODGE  0x02U

/* ---- Flash Format Magic ---- */
#define TP_FLASH_FORMAT_MAGIC   0xDEADBEEFUL

/* ---- Packed Wire Structures (Little-Endian Cortex-M4F) ---- */

typedef struct __attribute__((packed)) {
    uint8_t orig_opcode;
    uint8_t orig_seq;
    uint8_t status;
} tp_rsp_ack_t;

typedef struct __attribute__((packed)) {
    uint8_t  mode;
    uint8_t  status_flags;
    uint16_t battery_mv;
    uint8_t  battery_pct;
    uint32_t flash_used_bytes;
    uint8_t  gps_sats;
    int32_t  lat_e7;
    int32_t  lon_e7;
    uint16_t speed_cms;
    uint32_t uptime_sec;
    uint8_t  active_epaper_tpl;
} tp_evt_status_t;

typedef struct __attribute__((packed)) {
    uint8_t  mode;
    uint16_t rtc_wake_sec;
    uint8_t  motion_thresh_mg; /* 16 mg steps */
} tp_cmd_set_mode_t;

typedef struct __attribute__((packed)) {
    uint32_t utc_epoch_sec;
    uint16_t utc_millis;
    int32_t  lat_e7;
    int32_t  lon_e7;
    int16_t  alt_dm;
    uint16_t accuracy_m;
    uint8_t  flags; /* b0:valid_pos, b1:valid_time */
} tp_cmd_agps_t;

typedef struct __attribute__((packed)) {
    uint8_t melody_id;
    uint8_t repeat_count;
    uint8_t tempo_scale;
} tp_cmd_buzzer_t;

typedef struct __attribute__((packed)) {
    uint8_t  alert_id;
    uint32_t utc_epoch_sec;
    uint16_t peak_accel_mg;
    uint8_t  axis_mask; /* b0:X, b1:Y, b2:Z, b3:tamper_tilt */
    int32_t  lat_e7;
    int32_t  lon_e7;
    uint8_t  battery_pct;
    uint16_t speed_cms;
} tp_evt_motion_alert_t;

typedef struct __attribute__((packed)) {
    uint8_t rate_hz;
    uint8_t accel_range_g;
    uint8_t vehicle_type; /* 0=KART, 1=MOTO, 2=CAR */
    uint8_t flags;        /* b0: stream live over BLE */
} tp_cmd_track_start_t;

typedef struct __attribute__((packed)) {
    uint16_t dt_ms;
    int32_t  lat_e7;
    int32_t  lon_e7;
    uint16_t speed_cms;
    int16_t  ax_mg;
    int16_t  ay_mg;
} tp_telemetry_sample_t;

typedef struct __attribute__((packed)) {
    uint8_t  entry_index;
    uint8_t  entry_count;
    uint16_t session_id;
    uint32_t start_utc_sec;
    uint32_t duration_ms;
    uint32_t sample_count;
    uint16_t max_speed_cms;
    uint16_t peak_g_mg;
    uint8_t  sample_rate_hz;
    uint8_t  vehicle_type;
    uint16_t reserved;
} tp_rsp_flash_session_t;

typedef struct __attribute__((packed)) {
    uint16_t session_id;
    uint32_t offset_bytes;
    uint16_t max_len;
} tp_cmd_flash_read_t;

typedef struct __attribute__((packed)) {
    uint16_t session_id;
    uint32_t offset_bytes;
    uint8_t  flags;        /* b0: is_last_chunk */
    uint8_t  sample_count; /* 0..14 */
} tp_rsp_flash_chunk_hdr_t;

typedef struct __attribute__((packed)) {
    uint8_t template_id;
    uint8_t refresh_mode;
    uint8_t flags;
    uint8_t contrast_level;
    char    title[24];
    char    subtitle[32];
} tp_cmd_epaper_config_t;

typedef struct __attribute__((packed)) {
    uint16_t offset_bytes;
    uint16_t total_bytes;
    uint8_t  encoding; /* 0=RAW_1BIT_MSB_FIRST, 1=PACKBITS_RLE */
    uint8_t  flags;    /* b0: commit_and_refresh, b1: full_refresh */
} tp_cmd_epaper_bitmap_hdr_t;

typedef struct __attribute__((packed)) {
    uint8_t action;
    uint8_t game_id;
    uint8_t sound_enabled;
} tp_cmd_easter_egg_t;

typedef struct __attribute__((packed)) {
    uint8_t  game_id;
    uint32_t last_score;
    uint32_t high_score;
    uint16_t games_played;
    uint16_t play_time_sec;
} tp_evt_easter_egg_score_t;

/* Compile-time size verification against PROTOCOL.md */
_Static_assert(sizeof(tp_rsp_ack_t)               == 3U,  "RSP_ACK must be 3 bytes");
_Static_assert(sizeof(tp_evt_status_t)            == 25U, "EVT_STATUS must be 25 bytes");
_Static_assert(sizeof(tp_cmd_set_mode_t)          == 4U,  "CMD_SET_MODE must be 4 bytes");
_Static_assert(sizeof(tp_cmd_agps_t)              == 19U, "CMD_INJECT_AGPS must be 19 bytes");
_Static_assert(sizeof(tp_cmd_buzzer_t)            == 3U,  "CMD_BUZZER_PLAY must be 3 bytes");
_Static_assert(sizeof(tp_evt_motion_alert_t)      == 19U, "EVT_MOTION_ALERT must be 19 bytes");
_Static_assert(sizeof(tp_cmd_track_start_t)       == 4U,  "CMD_TRACK_START must be 4 bytes");
_Static_assert(sizeof(tp_telemetry_sample_t)      == 16U, "telemetry sample must be 16 bytes");
_Static_assert(sizeof(tp_rsp_flash_session_t)     == 24U, "RSP_FLASH_SESSION must be 24 bytes");
_Static_assert(sizeof(tp_cmd_flash_read_t)        == 8U,  "CMD_FLASH_READ must be 8 bytes");
_Static_assert(sizeof(tp_rsp_flash_chunk_hdr_t)   == 8U,  "RSP_FLASH_CHUNK hdr must be 8 bytes");
_Static_assert(sizeof(tp_cmd_epaper_config_t)     == 60U, "CMD_EPAPER_CONFIG must be 60 bytes");
_Static_assert(sizeof(tp_cmd_epaper_bitmap_hdr_t) == 6U,  "CMD_EPAPER_BITMAP hdr must be 6 bytes");
_Static_assert(sizeof(tp_cmd_easter_egg_t)        == 3U,  "CMD_EASTER_EGG_CTRL must be 3 bytes");
_Static_assert(sizeof(tp_evt_easter_egg_score_t)  == 13U, "EVT_EASTER_EGG_SCORE must be 13 bytes");

/* ---- Decoded Frame & Streaming Parser ---- */

typedef struct {
    uint8_t  version;
    uint8_t  flags;
    uint8_t  opcode;
    uint8_t  seq;
    uint16_t len;
    uint8_t  payload[TP_MAX_PAYLOAD_LEN];
} tp_frame_t;

typedef enum {
    TP_PARSE_SOF0 = 0,
    TP_PARSE_SOF1,
    TP_PARSE_VER_FLAGS,
    TP_PARSE_OPCODE,
    TP_PARSE_SEQ,
    TP_PARSE_LEN_L,
    TP_PARSE_LEN_H,
    TP_PARSE_PAYLOAD,
    TP_PARSE_CRC_L,
    TP_PARSE_CRC_H
} tp_parse_state_t;

typedef struct {
    tp_parse_state_t state;
    uint32_t         frame_start_ms;
    uint16_t         payload_idx;
    uint8_t          crc_l;
    uint8_t          raw_crc_buf[5U + TP_MAX_PAYLOAD_LEN];
    tp_frame_t       frame;
    bool             last_crc_error;
} tp_parser_t;

/** Reference CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
uint16_t tp_crc16_ccitt(const uint8_t *data, uint16_t len);

/** Encode a TrackPulse wire frame into out_buf (size >= TP_MAX_FRAME_LEN). Returns total length or 0. */
uint16_t tp_frame_encode(uint8_t *out_buf, uint16_t out_cap,
                         uint8_t opcode, uint8_t seq, uint8_t flags,
                         const void *payload, uint16_t payload_len);

/** Reset streaming parser state machine. */
void tp_parser_reset(tp_parser_t *p);

/** Check 250 ms assembly timeout (PROTOCOL.md §5.1). */
void tp_parser_check_timeout(tp_parser_t *p, uint32_t now_ms);

/**
 * Feed one byte into the streaming parser.
 * Returns true when a complete, CRC-valid frame is available in p->frame.
 */
bool tp_parser_feed(tp_parser_t *p, uint8_t byte, uint32_t now_ms);

/** Decode a complete buffer (e.g. MQTT message) directly. Returns true if valid. */
bool tp_frame_decode_buf(const uint8_t *buf, uint16_t len, tp_frame_t *out);

#ifdef __cplusplus
}
#endif

#endif /* TP_PROTOCOL_H */
