/**
 * @file    tp_qspi_flash.h
 * @brief   8 MB QSPI NOR Flash low-level driver (Renesas AT25QF641B-SHB-T / Winbond W25Q64JV)
 *          & TrackPulse circular Flash FS.
 *
 * Flash Map (PROTOCOL.md §4.3):
 *   0x000000 - 0x000FFF (Sector 0,      4 KB) : Superblock (config, pairing, high scores, offline fixes)
 *   0x001000 - 0x00FFFF (Sectors 1..15, 60 KB): Session Table (up to 128 entries of 64 bytes)
 *   0x010000 - 0x7FFFFF (Sectors 16..2047, 8128 KB): Raw Telemetry Ring (16 B/sample, 16 samples/page)
 */
#ifndef TP_QSPI_FLASH_H
#define TP_QSPI_FLASH_H

#include "tp_protocol.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TP_FLASH_TOTAL_BYTES        (8UL * 1024UL * 1024UL) /* 8 388 608 bytes */
#define TP_FLASH_PAGE_SIZE          256U
#define TP_FLASH_SECTOR_SIZE        4096U
#define TP_FLASH_SUPERBLOCK_ADDR    0x000000UL
#define TP_FLASH_TABLE_ADDR         0x001000UL
#define TP_FLASH_DATA_START_ADDR    0x010000UL
#define TP_FLASH_DATA_END_ADDR      0x800000UL
#define TP_FLASH_MAX_SESSIONS       128U
#define TP_FLASH_OFFLINE_LOG_MAX    24U

typedef struct __attribute__((packed)) {
    uint32_t utc_epoch_sec;
    int32_t  lat_e7;
    int32_t  lon_e7;
    uint16_t speed_cms;
    uint8_t  battery_pct;
    uint8_t  reason; /* 0=RTC, 1=MOTION */
} tp_offline_fix_t;

typedef struct __attribute__((packed)) {
    uint32_t                 magic;           /* 0x54504653 ("TPFS") */
    uint16_t                 version;         /* 1 */
    uint16_t                 next_session_id;
    uint32_t                 data_write_addr; /* Sector-aligned ring cursor in [0x010000..0x7FFFFF] */
    uint8_t                  paired;
    char                     pairing_key[10];
    uint16_t                 rtc_wake_sec;
    uint8_t                  motion_thresh_steps;
    tp_cmd_epaper_config_t   epaper_cfg;
    tp_evt_easter_egg_score_t ee_scores[2];   /* [0]=PULSE_RUNNER, [1]=TRACK_DODGE */
    uint8_t                  offline_head;
    uint8_t                  offline_count;
    tp_offline_fix_t         offline_fixes[TP_FLASH_OFFLINE_LOG_MAX];
    uint16_t                 crc16;
} tp_superblock_t;

typedef struct __attribute__((packed)) {
    uint32_t magic;          /* 0x54505345 ("TPSE") */
    uint8_t  flags;          /* b0: valid, b1: deleted */
    uint8_t  sample_rate_hz;
    uint8_t  vehicle_type;
    uint8_t  reserved0;
    uint16_t session_id;
    uint16_t max_speed_cms;
    uint16_t peak_g_mg;
    uint16_t reserved1;
    uint32_t start_utc_sec;
    uint32_t duration_ms;
    uint32_t sample_count;
    uint32_t data_start_addr;
    uint32_t data_alloc_bytes;
    uint8_t  pad[26];
    uint16_t crc16;
} tp_session_entry_t;

_Static_assert(sizeof(tp_session_entry_t) == 64U, "Session table entry must be 64 bytes");

/* ---- Low-Level W25Q64JV QSPI Driver ---- */
bool tp_qspi_init(void);
bool tp_qspi_is_ready(void);
bool tp_qspi_read_jedec_id(uint8_t id[3]);
bool tp_qspi_enter_deep_power_down(void);
bool tp_qspi_release_deep_power_down(void);
bool tp_qspi_erase_sector_4k(uint32_t addr);
bool tp_qspi_erase_block_64k(uint32_t addr);
bool tp_qspi_read(uint32_t addr, uint8_t *buf, uint32_t len);
bool tp_qspi_write(uint32_t addr, const uint8_t *buf, uint32_t len);

/* ---- High-Level TrackPulse Storage Manager ---- */
bool tp_flash_fs_init(void);
tp_superblock_t *tp_flash_get_superblock(void);
bool tp_flash_save_superblock(void);

/** Log an offline anti-theft GPS fix into the persistent ring buffer. */
bool tp_flash_log_offline_fix(uint32_t utc_sec, int32_t lat_e7, int32_t lon_e7,
                              uint16_t speed_cms, uint8_t batt_pct, uint8_t reason);

/** Track Recording Session Lifecycle */
bool tp_flash_session_begin(uint32_t start_utc_sec, uint8_t rate_hz, uint8_t vehicle_type,
                            uint16_t *out_session_id);
bool tp_flash_session_append(const tp_telemetry_sample_t *sample);
bool tp_flash_session_end(void);
bool tp_flash_session_is_active(void);

/** Session Inspection, BLE Dump & Management */
uint8_t  tp_flash_get_session_count(void);
bool     tp_flash_get_session_info(uint8_t list_index, tp_rsp_flash_session_t *out);
bool     tp_flash_read_session_chunk(uint16_t session_id, uint32_t offset_bytes,
                                     uint16_t max_len, tp_telemetry_sample_t *out_samples,
                                     uint8_t *out_count, bool *out_is_last);
bool     tp_flash_erase_session(uint16_t session_id);
bool     tp_flash_format_sessions(void);
uint32_t tp_flash_get_used_bytes(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_QSPI_FLASH_H */
