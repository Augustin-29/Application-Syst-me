/**
 * @file    tp_qspi_flash.c
 * @brief   8 MB QSPI NOR Flash driver (Renesas AT25QF641B-SHB-T & Winbond W25Q64JV) & TrackPulse FS.
 */
#include "tp_qspi_flash.h"
#include "tp_battery.h"
#include "tp_board.h"
#include "tp_config.h"
#include "main.h"

#include <math.h>
#include <string.h>

/* Standard JEDEC / AT25QF641B / W25Q64JV Commands */
#define NOR_CMD_WRITE_SR12          0x01U
#define NOR_CMD_WRITE_ENABLE        0x06U
#define NOR_CMD_READ_SR1            0x05U
#define NOR_CMD_READ_SR2            0x35U
#define NOR_CMD_WRITE_SR2           0x31U
#define NOR_CMD_QUAD_PAGE_PROG      0x32U
#define NOR_CMD_FAST_READ_QUAD      0x6BU
#define NOR_CMD_SECTOR_ERASE_4K     0x20U
#define NOR_CMD_BLOCK_ERASE_64K     0xD8U
#define NOR_CMD_JEDEC_ID            0x9FU
#define NOR_CMD_DEEP_POWER_DOWN     0xB9U
#define NOR_CMD_RELEASE_POWER_DOWN  0xABU
#define NOR_CMD_ENABLE_RESET        0x66U
#define NOR_CMD_RESET_DEVICE        0x99U

/* Supported JEDEC Manufacturer IDs (AT25QF641B: 0x1F 0x32 0x17 | W25Q64JV: 0xEF 0x40 0x17) */
#define NOR_MFR_RENESAS_ADESTO      0x1FU
#define NOR_MFR_WINBOND             0xEFU

#define NOR_SR1_WIP_MASK            0x01U
#define NOR_SR1_WEL_MASK            0x02U
#define NOR_SR2_QE_MASK             0x02U

#define TPFS_MAGIC                  0x54504653UL /* "TPFS" */
#define TPSE_MAGIC                  0x54505345UL /* "TPSE" */
#define TPSE_FLAG_VALID             0x01U
#define TPSE_FLAG_DELETED           0x02U

#define TABLE_SECTOR_0_ADDR         0x001000UL /* Entries 0..63  (4 KB) */
#define TABLE_SECTOR_1_ADDR         0x002000UL /* Entries 64..127 (4 KB) */

static bool            g_qspi_ready   = false;
static bool            g_in_dpd       = false;
static tp_superblock_t g_sb;
static tp_session_entry_t g_table[TP_FLASH_MAX_SESSIONS];

/* Active track recording write-behind page buffer (16 samples = 256 bytes) */
typedef struct {
    bool                  active;
    uint16_t              session_id;
    uint32_t              start_utc_sec;
    uint8_t               rate_hz;
    uint8_t               vehicle_type;
    uint32_t              start_addr;
    uint32_t              write_addr;
    uint32_t              erased_until;
    uint32_t              sample_count;
    uint32_t              duration_ms;
    uint16_t              max_speed_cms;
    uint16_t              peak_g_mg;
    tp_telemetry_sample_t page_buf[16];
    uint8_t               page_count;
} active_session_t;

static active_session_t g_rec = {0};

/* ---- Low-Level QSPI NOR Helpers (AT25QF641B / W25Q64JV) ---- */

static bool qspi_wait_ready(uint32_t timeout_ms)
{
    QSPI_CommandTypeDef cmd = {0};
    QSPI_AutoPollingTypeDef cfg = {0};

    cmd.InstructionMode   = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction       = NOR_CMD_READ_SR1;
    cmd.DataMode          = QSPI_DATA_1_LINE;
    cmd.NbData            = 1U;

    cfg.Match             = 0x00U;
    cfg.Mask              = NOR_SR1_WIP_MASK;
    cfg.MatchMode         = QSPI_MATCH_MODE_AND;
    cfg.StatusBytesSize   = 1U;
    cfg.Interval          = 0x10U;
    cfg.AutomaticStop     = QSPI_AUTOMATIC_STOP_ENABLE;

    return (HAL_QSPI_AutoPolling(&hqspi, &cmd, &cfg, timeout_ms) == HAL_OK);
}

static bool qspi_write_enable(void)
{
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_WRITE_ENABLE;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }

    QSPI_AutoPollingTypeDef cfg = {0};
    cmd.Instruction     = NOR_CMD_READ_SR1;
    cmd.DataMode        = QSPI_DATA_1_LINE;
    cmd.NbData          = 1U;
    cfg.Match           = NOR_SR1_WEL_MASK;
    cfg.Mask            = NOR_SR1_WEL_MASK;
    cfg.MatchMode       = QSPI_MATCH_MODE_AND;
    cfg.StatusBytesSize = 1U;
    cfg.Interval        = 0x10U;
    cfg.AutomaticStop   = QSPI_AUTOMATIC_STOP_ENABLE;
    return (HAL_QSPI_AutoPolling(&hqspi, &cmd, &cfg, 100U) == HAL_OK);
}

static bool qspi_read_sr(uint8_t opcode, uint8_t *val)
{
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = opcode;
    cmd.DataMode        = QSPI_DATA_1_LINE;
    cmd.NbData          = 1U;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    return (HAL_QSPI_Receive(&hqspi, val, 100U) == HAL_OK);
}

static bool qspi_enable_quad_mode(void)
{
    uint8_t sr2 = 0U;
    if (!qspi_read_sr(NOR_CMD_READ_SR2, &sr2)) {
        return false;
    }
    /* On AT25QF641B, QE (SR2 bit 1) is factory-enabled (1) by default -> fast exit without NV write */
    if ((sr2 & NOR_SR2_QE_MASK) != 0U) {
        return true;
    }

    /* Try dedicated Write Status Register 2 (0x31) first */
    if (!qspi_write_enable()) {
        return false;
    }
    sr2 |= NOR_SR2_QE_MASK;
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_WRITE_SR2;
    cmd.DataMode        = QSPI_DATA_1_LINE;
    cmd.NbData          = 1U;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) == HAL_OK &&
        HAL_QSPI_Transmit(&hqspi, &sr2, 100U) == HAL_OK &&
        qspi_wait_ready(150U)) {
        uint8_t verify = 0U;
        if (qspi_read_sr(NOR_CMD_READ_SR2, &verify) && ((verify & NOR_SR2_QE_MASK) != 0U)) {
            return true;
        }
    }

    /* Fallback: 2-byte Write Status Register 1+2 (0x01) for strict JEDEC compatibility */
    uint8_t sr1 = 0U;
    if (!qspi_read_sr(NOR_CMD_READ_SR1, &sr1) || !qspi_write_enable()) {
        return false;
    }
    uint8_t sr12[2] = { sr1, sr2 };
    cmd.Instruction = NOR_CMD_WRITE_SR12;
    cmd.NbData      = 2U;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    if (HAL_QSPI_Transmit(&hqspi, sr12, 100U) != HAL_OK) {
        return false;
    }
    return qspi_wait_ready(150U);
}

static void qspi_ensure_awake(void)
{
    if (g_in_dpd) {
        (void)tp_qspi_release_deep_power_down();
    }
}

bool tp_qspi_release_deep_power_down(void)
{
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_RELEASE_POWER_DOWN;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    HAL_Delay(1U); /* tRDPD / tRES1 <= 35 us (1 ms gives >25x margin) */
    g_in_dpd = false;
    return true;
}

bool tp_qspi_enter_deep_power_down(void)
{
    if (!g_qspi_ready || g_in_dpd) {
        return true;
    }
    (void)qspi_wait_ready(200U);
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_DEEP_POWER_DOWN;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    g_in_dpd = true;
    return true;
}

bool tp_qspi_read_jedec_id(uint8_t id[3])
{
    qspi_ensure_awake();
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_JEDEC_ID;
    cmd.DataMode        = QSPI_DATA_1_LINE;
    cmd.NbData          = 3U;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    return (HAL_QSPI_Receive(&hqspi, id, 100U) == HAL_OK);
}

bool tp_qspi_init(void)
{
    g_qspi_ready = false;
    g_in_dpd     = false;

    (void)tp_qspi_release_deep_power_down();

    /* Software reset sequence 0x66 + 0x99 (tRST ~30 us on AT25QF641B & W25Q64JV) */
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_ENABLE_RESET;
    (void)HAL_QSPI_Command(&hqspi, &cmd, 50U);
    cmd.Instruction     = NOR_CMD_RESET_DEVICE;
    (void)HAL_QSPI_Command(&hqspi, &cmd, 50U);
    HAL_Delay(2U);

    uint8_t id[3] = {0};
    if (!tp_qspi_read_jedec_id(id)) {
        return false;
    }
    /* Accept Renesas/Adesto AT25QF641B (0x1F 0x32 0x17) & Winbond W25Q64JV (0xEF 0x40 0x17) */
    bool mfr_ok = (id[0] == NOR_MFR_RENESAS_ADESTO) || (id[0] == NOR_MFR_WINBOND);
    bool bus_ok = (id[1] != 0x00U && id[1] != 0xFFU) && (id[2] != 0x00U && id[2] != 0xFFU);
    if (!mfr_ok || !bus_ok) {
        return false;
    }

    if (!qspi_enable_quad_mode()) {
        return false;
    }

    g_qspi_ready = true;
    return true;
}

bool tp_qspi_is_ready(void)
{
    return g_qspi_ready;
}

bool tp_qspi_erase_sector_4k(uint32_t addr)
{
    if (!g_qspi_ready || addr >= TP_FLASH_TOTAL_BYTES) {
        return false;
    }
    qspi_ensure_awake();
    if (!qspi_write_enable()) {
        return false;
    }
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_SECTOR_ERASE_4K;
    cmd.AddressMode     = QSPI_ADDRESS_1_LINE;
    cmd.AddressSize     = QSPI_ADDRESS_24_BITS;
    cmd.Address         = addr & ~(uint32_t)(TP_FLASH_SECTOR_SIZE - 1U);
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    /* AT25QF641B: 65 ms typ, up to 3 s worst-case (AutoPolling returns immediately when WIP=0) */
    return qspi_wait_ready(3000U);
}

bool tp_qspi_erase_block_64k(uint32_t addr)
{
    if (!g_qspi_ready || addr >= TP_FLASH_TOTAL_BYTES) {
        return false;
    }
    qspi_ensure_awake();
    if (!qspi_write_enable()) {
        return false;
    }
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_BLOCK_ERASE_64K;
    cmd.AddressMode     = QSPI_ADDRESS_1_LINE;
    cmd.AddressSize     = QSPI_ADDRESS_24_BITS;
    cmd.Address         = addr & ~0xFFFFUL;
    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    /* AT25QF641B: 240 ms typ, generous 4 s worst-case timeout */
    return qspi_wait_ready(4000U);
}

bool tp_qspi_read(uint32_t addr, uint8_t *buf, uint32_t len)
{
    if (!g_qspi_ready || buf == NULL || len == 0U || (addr + len) > TP_FLASH_TOTAL_BYTES) {
        return false;
    }
    qspi_ensure_awake();

    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_FAST_READ_QUAD;
    cmd.AddressMode     = QSPI_ADDRESS_1_LINE;
    cmd.AddressSize     = QSPI_ADDRESS_24_BITS;
    cmd.Address         = addr;
    cmd.DummyCycles     = 8U;
    cmd.DataMode        = QSPI_DATA_4_LINES;
    cmd.NbData          = len;

    if (HAL_QSPI_Command(&hqspi, &cmd, 200U) != HAL_OK) {
        return false;
    }
    return (HAL_QSPI_Receive(&hqspi, buf, 2000U) == HAL_OK);
}

static bool qspi_program_page(uint32_t addr, const uint8_t *buf, uint32_t len)
{
    if (!qspi_write_enable()) {
        return false;
    }
    QSPI_CommandTypeDef cmd = {0};
    cmd.InstructionMode = QSPI_INSTRUCTION_1_LINE;
    cmd.Instruction     = NOR_CMD_QUAD_PAGE_PROG;
    cmd.AddressMode     = QSPI_ADDRESS_1_LINE;
    cmd.AddressSize     = QSPI_ADDRESS_24_BITS;
    cmd.Address         = addr;
    cmd.DataMode        = QSPI_DATA_4_LINES;
    cmd.NbData          = len;

    if (HAL_QSPI_Command(&hqspi, &cmd, 100U) != HAL_OK) {
        return false;
    }
    if (HAL_QSPI_Transmit(&hqspi, (uint8_t *)buf, 500U) != HAL_OK) {
        return false;
    }
    return qspi_wait_ready(100U);
}

bool tp_qspi_write(uint32_t addr, const uint8_t *buf, uint32_t len)
{
    if (!g_qspi_ready || buf == NULL || len == 0U || (addr + len) > TP_FLASH_TOTAL_BYTES) {
        return false;
    }
    qspi_ensure_awake();

    uint32_t offset = 0U;
    while (offset < len) {
        uint32_t cur_addr    = addr + offset;
        uint32_t page_rem    = TP_FLASH_PAGE_SIZE - (cur_addr % TP_FLASH_PAGE_SIZE);
        uint32_t chunk       = len - offset;
        if (chunk > page_rem) {
            chunk = page_rem;
        }
        if (!qspi_program_page(cur_addr, &buf[offset], chunk)) {
            return false;
        }
        offset += chunk;
    }
    return true;
}

/* ---- High-Level TrackPulse Flash FS ---- */

static void sb_init_defaults(void)
{
    const tp_device_identity_t *id = tp_board_get_identity();
    memset(&g_sb, 0, sizeof(g_sb));
    g_sb.magic               = TPFS_MAGIC;
    g_sb.version             = 1U;
    g_sb.next_session_id     = 1U;
    g_sb.data_write_addr     = TP_FLASH_DATA_START_ADDR;
    g_sb.paired              = 0U;
    strncpy(g_sb.pairing_key, id->default_key, sizeof(g_sb.pairing_key) - 1U);
    g_sb.rtc_wake_sec        = TP_DEFAULT_RTC_WAKE_SEC;
    g_sb.motion_thresh_steps = TP_DEFAULT_MOTION_STEPS;

    g_sb.epaper_cfg.template_id   = TP_EPD_TPL_MINIMAL_QR;
    g_sb.epaper_cfg.refresh_mode  = 0U; /* Partial */
    g_sb.epaper_cfg.flags         = TP_EPD_FLAG_SHOW_BATT | TP_EPD_FLAG_SHOW_SATS | TP_EPD_FLAG_AUTO_MODE;
    strncpy(g_sb.epaper_cfg.title, "TRACKPULSE", sizeof(g_sb.epaper_cfg.title) - 1U);
    strncpy(g_sb.epaper_cfg.subtitle, id->ble_name, sizeof(g_sb.epaper_cfg.subtitle) - 1U);

    g_sb.ee_scores[0].game_id = TP_EE_GAME_PULSE_RUNNER;
    g_sb.ee_scores[1].game_id = TP_EE_GAME_TRACK_DODGE;
}

static bool session_entry_valid(const tp_session_entry_t *e)
{
    if (e->magic != TPSE_MAGIC) {
        return false;
    }
    if ((e->flags & TPSE_FLAG_VALID) == 0U || (e->flags & TPSE_FLAG_DELETED) != 0U) {
        return false;
    }
    uint16_t crc = tp_crc16_ccitt((const uint8_t *)e, (uint16_t)(sizeof(tp_session_entry_t) - 2U));
    return (crc == e->crc16);
}

static bool save_session_table(void)
{
    if (!g_qspi_ready) {
        return true; /* RAM-only fallback when flash absent on bench */
    }
    if (tp_battery_is_low_for_flash()) {
        return false;
    }
    if (!tp_qspi_erase_sector_4k(TABLE_SECTOR_0_ADDR)) return false;
    if (!tp_qspi_erase_sector_4k(TABLE_SECTOR_1_ADDR)) return false;
    if (!tp_qspi_write(TABLE_SECTOR_0_ADDR, (const uint8_t *)&g_table[0],  4096U)) return false;
    if (!tp_qspi_write(TABLE_SECTOR_1_ADDR, (const uint8_t *)&g_table[64], 4096U)) return false;
    return true;
}

bool tp_flash_save_superblock(void)
{
    g_sb.magic   = TPFS_MAGIC;
    g_sb.version = 1U;
    g_sb.crc16   = tp_crc16_ccitt((const uint8_t *)&g_sb, (uint16_t)(sizeof(tp_superblock_t) - 2U));

    if (!g_qspi_ready) {
        return true;
    }
    if (tp_battery_is_low_for_flash()) {
        return false;
    }
    if (!tp_qspi_erase_sector_4k(TP_FLASH_SUPERBLOCK_ADDR)) {
        return false;
    }
    return tp_qspi_write(TP_FLASH_SUPERBLOCK_ADDR, (const uint8_t *)&g_sb, sizeof(tp_superblock_t));
}

bool tp_flash_fs_init(void)
{
    memset(g_table, 0xFF, sizeof(g_table));
    memset(&g_rec, 0, sizeof(g_rec));
    sb_init_defaults();

    if (!tp_qspi_init()) {
        return false;
    }

    tp_superblock_t loaded;
    if (tp_qspi_read(TP_FLASH_SUPERBLOCK_ADDR, (uint8_t *)&loaded, sizeof(loaded))) {
        uint16_t calc = tp_crc16_ccitt((const uint8_t *)&loaded, (uint16_t)(sizeof(loaded) - 2U));
        if (loaded.magic == TPFS_MAGIC && loaded.version == 1U && loaded.crc16 == calc) {
            if (loaded.data_write_addr >= TP_FLASH_DATA_START_ADDR &&
                loaded.data_write_addr < TP_FLASH_DATA_END_ADDR) {
                g_sb = loaded;
            }
        } else {
            (void)tp_flash_save_superblock();
        }
    }

    (void)tp_qspi_read(TABLE_SECTOR_0_ADDR, (uint8_t *)&g_table[0],  4096U);
    (void)tp_qspi_read(TABLE_SECTOR_1_ADDR, (uint8_t *)&g_table[64], 4096U);
    return true;
}

tp_superblock_t *tp_flash_get_superblock(void)
{
    return &g_sb;
}

bool tp_flash_log_offline_fix(uint32_t utc_sec, int32_t lat_e7, int32_t lon_e7,
                              uint16_t speed_cms, uint8_t batt_pct, uint8_t reason)
{
    uint8_t idx = g_sb.offline_head % TP_FLASH_OFFLINE_LOG_MAX;
    g_sb.offline_fixes[idx].utc_epoch_sec = utc_sec;
    g_sb.offline_fixes[idx].lat_e7        = lat_e7;
    g_sb.offline_fixes[idx].lon_e7        = lon_e7;
    g_sb.offline_fixes[idx].speed_cms     = speed_cms;
    g_sb.offline_fixes[idx].battery_pct   = batt_pct;
    g_sb.offline_fixes[idx].reason        = reason;

    g_sb.offline_head = (uint8_t)((idx + 1U) % TP_FLASH_OFFLINE_LOG_MAX);
    if (g_sb.offline_count < TP_FLASH_OFFLINE_LOG_MAX) {
        g_sb.offline_count++;
    }
    return tp_flash_save_superblock();
}

static uint32_t ring_advance(uint32_t addr, uint32_t delta)
{
    uint32_t rel = (addr - TP_FLASH_DATA_START_ADDR) + delta;
    uint32_t cap = TP_FLASH_DATA_END_ADDR - TP_FLASH_DATA_START_ADDR;
    return TP_FLASH_DATA_START_ADDR + (rel % cap);
}

static bool addr_in_ring_span(uint32_t target, uint32_t start, uint32_t len)
{
    if (len == 0U) return false;
    uint32_t cap = TP_FLASH_DATA_END_ADDR - TP_FLASH_DATA_START_ADDR;
    uint32_t off = (target >= start) ? (target - start) : (cap - (start - target));
    return (off < len);
}

static void invalidate_overlapping_sessions(uint32_t sector_addr)
{
    bool changed = false;
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (!session_entry_valid(&g_table[i])) {
            continue;
        }
        if (addr_in_ring_span(sector_addr, g_table[i].data_start_addr, g_table[i].data_alloc_bytes)) {
            g_table[i].flags |= TPSE_FLAG_DELETED;
            changed = true;
        }
    }
    if (changed) {
        (void)save_session_table();
    }
}

static bool prepare_sector_for_write(uint32_t addr)
{
    uint32_t sector = addr & ~(uint32_t)(TP_FLASH_SECTOR_SIZE - 1U);
    if (sector != g_rec.erased_until) {
        invalidate_overlapping_sessions(sector);
        if (g_qspi_ready && !tp_qspi_erase_sector_4k(sector)) {
            return false;
        }
        g_rec.erased_until = sector;
    }
    return true;
}

static bool flush_page_buffer(void)
{
    if (g_rec.page_count == 0U) {
        return true;
    }
    if (tp_battery_is_low_for_flash()) {
        return false;
    }
    if (!prepare_sector_for_write(g_rec.write_addr)) {
        return false;
    }
    uint32_t bytes = (uint32_t)g_rec.page_count * sizeof(tp_telemetry_sample_t);
    if (g_qspi_ready) {
        if (!tp_qspi_write(g_rec.write_addr, (const uint8_t *)g_rec.page_buf, bytes)) {
            return false;
        }
    }
    g_rec.write_addr = ring_advance(g_rec.write_addr, bytes);
    g_rec.page_count = 0U;
    return true;
}

bool tp_flash_session_begin(uint32_t start_utc_sec, uint8_t rate_hz, uint8_t vehicle_type,
                            uint16_t *out_session_id)
{
    if (g_rec.active) {
        (void)tp_flash_session_end();
    }
    if (tp_battery_is_low_for_flash()) {
        return false;
    }

    /* Align session start on a 4 KB sector boundary inside data ring */
    uint32_t aligned = g_sb.data_write_addr;
    if ((aligned % TP_FLASH_SECTOR_SIZE) != 0U) {
        aligned = ring_advance(aligned & ~(uint32_t)(TP_FLASH_SECTOR_SIZE - 1U), TP_FLASH_SECTOR_SIZE);
    }
    if (aligned < TP_FLASH_DATA_START_ADDR || aligned >= TP_FLASH_DATA_END_ADDR) {
        aligned = TP_FLASH_DATA_START_ADDR;
    }

    memset(&g_rec, 0, sizeof(g_rec));
    g_rec.active        = true;
    g_rec.session_id    = (g_sb.next_session_id == 0U) ? 1U : g_sb.next_session_id;
    g_sb.next_session_id = (uint16_t)(g_rec.session_id + 1U);
    if (g_sb.next_session_id == 0U) g_sb.next_session_id = 1U;

    g_rec.start_utc_sec = start_utc_sec;
    g_rec.rate_hz       = (rate_hz == 0U) ? 10U : rate_hz;
    g_rec.vehicle_type  = vehicle_type;
    g_rec.start_addr    = aligned;
    g_rec.write_addr    = aligned;
    g_rec.erased_until  = 0xFFFFFFFFUL;

    if (out_session_id != NULL) {
        *out_session_id = g_rec.session_id;
    }
    return true;
}

bool tp_flash_session_append(const tp_telemetry_sample_t *sample)
{
    if (!g_rec.active || sample == NULL) {
        return false;
    }
    /* PROTOCOL.md §5.3: stop Flash writes gracefully if battery < 3350 mV */
    if (tp_battery_is_low_for_flash()) {
        (void)tp_flash_session_end();
        return false;
    }

    g_rec.page_buf[g_rec.page_count++] = *sample;
    g_rec.sample_count++;
    g_rec.duration_ms += sample->dt_ms;

    if (sample->speed_cms > g_rec.max_speed_cms) {
        g_rec.max_speed_cms = sample->speed_cms;
    }
    float g_comb = sqrtf((float)sample->ax_mg * (float)sample->ax_mg +
                         (float)sample->ay_mg * (float)sample->ay_mg);
    if ((uint32_t)g_comb > g_rec.peak_g_mg) {
        g_rec.peak_g_mg = ((uint32_t)g_comb > 65535UL) ? 65535U : (uint16_t)g_comb;
    }

    if (g_rec.page_count >= 16U) {
        return flush_page_buffer();
    }
    return true;
}

bool tp_flash_session_end(void)
{
    if (!g_rec.active) {
        return true;
    }

    (void)flush_page_buffer();
    g_rec.active = false;

    if (g_rec.sample_count == 0U) {
        return true;
    }

    /* Find free slot (or evict oldest session if table full) */
    int slot = -1;
    uint32_t oldest_utc = 0xFFFFFFFFUL;
    int oldest_idx = 0;

    for (int i = 0; i < (int)TP_FLASH_MAX_SESSIONS; i++) {
        if (!session_entry_valid(&g_table[i])) {
            slot = i;
            break;
        }
        if (g_table[i].start_utc_sec < oldest_utc) {
            oldest_utc = g_table[i].start_utc_sec;
            oldest_idx = i;
        }
    }
    if (slot < 0) {
        slot = oldest_idx;
    }

    uint32_t used_bytes  = g_rec.sample_count * sizeof(tp_telemetry_sample_t);
    uint32_t alloc_bytes = ((used_bytes + TP_FLASH_SECTOR_SIZE - 1U) / TP_FLASH_SECTOR_SIZE) * TP_FLASH_SECTOR_SIZE;

    tp_session_entry_t *e = &g_table[slot];
    memset(e, 0, sizeof(*e));
    e->magic            = TPSE_MAGIC;
    e->flags            = TPSE_FLAG_VALID;
    e->sample_rate_hz   = g_rec.rate_hz;
    e->vehicle_type     = g_rec.vehicle_type;
    e->session_id       = g_rec.session_id;
    e->max_speed_cms    = g_rec.max_speed_cms;
    e->peak_g_mg        = g_rec.peak_g_mg;
    e->start_utc_sec    = g_rec.start_utc_sec;
    e->duration_ms      = g_rec.duration_ms;
    e->sample_count     = g_rec.sample_count;
    e->data_start_addr  = g_rec.start_addr;
    e->data_alloc_bytes = alloc_bytes;
    e->crc16            = tp_crc16_ccitt((const uint8_t *)e, (uint16_t)(sizeof(*e) - 2U));

    g_sb.data_write_addr = ring_advance(g_rec.start_addr, alloc_bytes);
    bool ok = save_session_table();
    ok &= tp_flash_save_superblock();
    return ok;
}

bool tp_flash_session_is_active(void)
{
    return g_rec.active;
}

uint8_t tp_flash_get_session_count(void)
{
    uint8_t count = 0U;
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (session_entry_valid(&g_table[i])) {
            count++;
        }
    }
    return count;
}

bool tp_flash_get_session_info(uint8_t list_index, tp_rsp_flash_session_t *out)
{
    if (out == NULL) {
        return false;
    }
    uint8_t total = tp_flash_get_session_count();
    uint8_t seen  = 0U;
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (!session_entry_valid(&g_table[i])) {
            continue;
        }
        if (seen == list_index) {
            out->entry_index    = list_index;
            out->entry_count    = total;
            out->session_id     = g_table[i].session_id;
            out->start_utc_sec  = g_table[i].start_utc_sec;
            out->duration_ms    = g_table[i].duration_ms;
            out->sample_count   = g_table[i].sample_count;
            out->max_speed_cms  = g_table[i].max_speed_cms;
            out->peak_g_mg      = g_table[i].peak_g_mg;
            out->sample_rate_hz = g_table[i].sample_rate_hz;
            out->vehicle_type   = g_table[i].vehicle_type;
            out->reserved       = 0U;
            return true;
        }
        seen++;
    }
    return false;
}

bool tp_flash_read_session_chunk(uint16_t session_id, uint32_t offset_bytes,
                                 uint16_t max_len, tp_telemetry_sample_t *out_samples,
                                 uint8_t *out_count, bool *out_is_last)
{
    if (out_samples == NULL || out_count == NULL || out_is_last == NULL) {
        return false;
    }
    *out_count   = 0U;
    *out_is_last = true;

    const tp_session_entry_t *found = NULL;
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (session_entry_valid(&g_table[i]) && g_table[i].session_id == session_id) {
            found = &g_table[i];
            break;
        }
    }
    if (found == NULL) {
        return false;
    }

    uint32_t total_bytes = found->sample_count * sizeof(tp_telemetry_sample_t);
    if (offset_bytes >= total_bytes) {
        *out_count   = 0U;
        *out_is_last = true;
        return true;
    }

    uint32_t rem_samples = (total_bytes - offset_bytes) / sizeof(tp_telemetry_sample_t);
    uint32_t req_samples = max_len / sizeof(tp_telemetry_sample_t);
    if (req_samples > 14U) req_samples = 14U;
    if (req_samples > rem_samples) req_samples = rem_samples;

    uint32_t bytes_to_read = req_samples * sizeof(tp_telemetry_sample_t);
    uint32_t read_addr     = ring_advance(found->data_start_addr, offset_bytes);

    /* Handle wrap-around at 0x800000 cleanly */
    uint32_t until_end = TP_FLASH_DATA_END_ADDR - read_addr;
    if (g_qspi_ready) {
        if (bytes_to_read <= until_end) {
            if (!tp_qspi_read(read_addr, (uint8_t *)out_samples, bytes_to_read)) {
                return false;
            }
        } else {
            if (!tp_qspi_read(read_addr, (uint8_t *)out_samples, until_end)) {
                return false;
            }
            if (!tp_qspi_read(TP_FLASH_DATA_START_ADDR,
                              ((uint8_t *)out_samples) + until_end,
                              bytes_to_read - until_end)) {
                return false;
            }
        }
    } else {
        memset(out_samples, 0, bytes_to_read);
    }

    *out_count   = (uint8_t)req_samples;
    *out_is_last = ((offset_bytes + bytes_to_read) >= total_bytes);
    return true;
}

bool tp_flash_erase_session(uint16_t session_id)
{
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (session_entry_valid(&g_table[i]) && g_table[i].session_id == session_id) {
            g_table[i].flags |= TPSE_FLAG_DELETED;
            return save_session_table();
        }
    }
    return false;
}

bool tp_flash_format_sessions(void)
{
    if (g_rec.active) {
        g_rec.active = false;
    }
    memset(g_table, 0xFF, sizeof(g_table));
    g_sb.data_write_addr = TP_FLASH_DATA_START_ADDR;
    g_sb.offline_head    = 0U;
    g_sb.offline_count   = 0U;
    bool ok = save_session_table();
    ok &= tp_flash_save_superblock();
    return ok;
}

uint32_t tp_flash_get_used_bytes(void)
{
    /* Sectors 0..15 reserved (64 KB) + valid sessions + active recording */
    uint32_t used = TP_FLASH_DATA_START_ADDR;
    for (uint8_t i = 0U; i < TP_FLASH_MAX_SESSIONS; i++) {
        if (session_entry_valid(&g_table[i])) {
            used += g_table[i].sample_count * sizeof(tp_telemetry_sample_t);
        }
    }
    if (g_rec.active) {
        used += g_rec.sample_count * sizeof(tp_telemetry_sample_t);
    }
    if (used > TP_FLASH_TOTAL_BYTES) {
        used = TP_FLASH_TOTAL_BYTES;
    }
    return used;
}
