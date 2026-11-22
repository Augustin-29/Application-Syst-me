/**
 * @file    tp_app.c
 * @brief   TrackPulse Hybrid Connectivity Manager (BLE priority + 4G LTE-M MQTT) & State Machine.
 */
#include "tp_app.h"
#include "tp_battery.h"
#include "tp_ble_rn4871.h"
#include "tp_board.h"
#include "tp_buzzer.h"
#include "tp_config.h"
#include "tp_easter_egg.h"
#include "tp_epaper.h"
#include "tp_lis3dh.h"
#include "tp_modem_sim7080g.h"
#include "tp_protocol.h"
#include "tp_qspi_flash.h"
#include "main.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

typedef enum {
    CH_BLE = 0,
    CH_4G_MQTT
} tp_tx_channel_t;

/* ---- Application Runtime Context ---- */
static tp_mode_t g_mode              = TP_MODE_IDLE_STANDBY;
static uint8_t   g_tx_seq            = 0U;
static uint8_t   g_alert_counter     = 0U;
static bool      g_alert_active      = false;

/* MQTT derived topics */
static char g_topic_uplink[24];
static char g_topic_alert[24];

/* Periodic timers */
static uint32_t g_last_batt_ms       = 0U;
static uint32_t g_last_heartbeat_ms  = 0U;
static uint32_t g_last_led_ms        = 0U;
static uint32_t g_ble_wake_until_ms  = 0U;

/* Track Recording runtime */
static uint8_t  g_track_rate_hz      = 10U;
static uint8_t  g_track_range_g      = 8U;
static uint8_t  g_track_vehicle      = 0U;
static bool     g_track_stream_ble   = true;
static uint32_t g_track_start_ms     = 0U;
static uint32_t g_last_sample_ms     = 0U;
static uint32_t g_last_gnss_poll_ms  = 0U;
static uint32_t g_last_live_tx_ms    = 0U;
static uint32_t g_last_hud_ms        = 0U;
static uint16_t g_track_max_spd_cms  = 0U;
static uint16_t g_track_peak_g_mg    = 0U;
static tp_accel_mg_t g_last_accel    = {0, 0, 1000};

/* ---- Helpers ---- */

static uint8_t next_seq(void)
{
    return g_tx_seq++;
}

static tp_evt_status_t build_status(void)
{
    tp_evt_status_t st;
    memset(&st, 0, sizeof(st));

    const tp_battery_status_t batt = tp_battery_get_status();
    const tp_gnss_fix_t *fix       = tp_modem_get_last_fix();
    const tp_superblock_t *sb      = tp_flash_get_superblock();

    uint8_t flags = 0U;
    if (batt.is_charging)            flags |= TP_SFLAG_CHARGING;
    if (batt.has_usb_fault)          flags |= TP_SFLAG_USB_FAULT;
    if (fix->has_fix)                flags |= TP_SFLAG_GPS_FIX;
    if (tp_flash_session_is_active())flags |= TP_SFLAG_RECORDING;
    if (tp_ble_is_connected())       flags |= TP_SFLAG_BLE_CONNECTED;
    if (tp_modem_is_lte_attached())  flags |= TP_SFLAG_LTE_ATTACHED;
    if (tp_modem_is_agps_valid())    flags |= TP_SFLAG_AGPS_VALID;

    st.mode              = (uint8_t)g_mode;
    st.status_flags      = flags;
    st.battery_mv        = batt.voltage_mv;
    st.battery_pct       = batt.percent;
    st.flash_used_bytes  = tp_flash_get_used_bytes();
    st.gps_sats          = fix->sats;
    st.lat_e7            = fix->lat_e7;
    st.lon_e7            = fix->lon_e7;
    st.speed_cms         = fix->speed_cms;
    st.uptime_sec        = tp_board_get_uptime_sec();
    st.active_epaper_tpl = sb->epaper_cfg.template_id;
    return st;
}

static bool send_on_channel(tp_tx_channel_t ch, uint8_t opcode,
                            const void *payload, uint16_t len, bool is_alert)
{
    if (ch == CH_BLE) {
        return tp_ble_send_frame(opcode, next_seq(), 0U, payload, len);
    }
    const char *topic = is_alert ? g_topic_alert : g_topic_uplink;
    uint8_t qos    = is_alert ? 1U : 0U;
    uint8_t retain = is_alert ? 1U : 0U;
    return tp_modem_mqtt_publish_frame(topic, qos, retain, opcode, next_seq(), payload, len);
}

static void send_ack(tp_tx_channel_t ch, uint8_t orig_op, uint8_t orig_seq, uint8_t status)
{
    tp_rsp_ack_t ack = {
        .orig_opcode = orig_op,
        .orig_seq    = orig_seq,
        .status      = status
    };
    (void)send_on_channel(ch, TP_RSP_ACK, &ack, sizeof(ack), false);
}

static void send_status(tp_tx_channel_t ch)
{
    tp_evt_status_t st = build_status();
    (void)send_on_channel(ch, TP_EVT_STATUS, &st, sizeof(st), false);
}

static void send_all_easter_egg_scores(tp_tx_channel_t ch)
{
    tp_evt_easter_egg_score_t s1 = tp_easter_egg_get_score(TP_EE_GAME_PULSE_RUNNER);
    tp_evt_easter_egg_score_t s2 = tp_easter_egg_get_score(TP_EE_GAME_TRACK_DODGE);
    (void)send_on_channel(ch, TP_EVT_EASTER_EGG_SCORE, &s1, sizeof(s1), false);
    (void)send_on_channel(ch, TP_EVT_EASTER_EGG_SCORE, &s2, sizeof(s2), false);
}

static void send_flash_session_list(tp_tx_channel_t ch)
{
    uint8_t count = tp_flash_get_session_count();
    for (uint8_t i = 0U; i < count; i++) {
        tp_rsp_flash_session_t entry;
        if (tp_flash_get_session_info(i, &entry)) {
            (void)send_on_channel(ch, TP_RSP_FLASH_SESSION, &entry, sizeof(entry), false);
            HAL_Delay(15U); /* Give BLE/RN4871 breathing room */
        }
    }
}

static void refresh_epaper_for_current_template(bool force_full)
{
    tp_superblock_t *sb = tp_flash_get_superblock();
    const tp_device_identity_t *id = tp_board_get_identity();
    const tp_evt_status_t st = build_status();
    bool invert = (sb->epaper_cfg.flags & TP_EPD_FLAG_INVERT_BW) != 0U;
    bool full   = force_full || (sb->epaper_cfg.refresh_mode != 0U);

    switch (sb->epaper_cfg.template_id) {
        case TP_EPD_TPL_ANTITHEFT:
            tp_epaper_render_antitheft(&st, g_alert_active, invert, full);
            break;

        case TP_EPD_TPL_TRACK_HUD: {
            uint32_t dur = tp_flash_session_is_active()
                               ? ((HAL_GetTick() - g_track_start_ms) / 1000UL)
                               : 0UL;
            tp_epaper_render_track_hud(&st, g_last_accel.ax_mg, g_last_accel.ay_mg,
                                       g_track_max_spd_cms, g_track_peak_g_mg, dur,
                                       invert, full);
            break;
        }

        case TP_EPD_TPL_CUSTOM_CARD:
            tp_epaper_render_custom_card(&sb->epaper_cfg, &st, full);
            break;

        case TP_EPD_TPL_MINIMAL_QR:
            tp_epaper_render_pairing_qr(id->device_id_upper, sb->pairing_key,
                                        st.battery_pct, invert, full);
            break;

        case TP_EPD_TPL_EASTER_EGG:
            /* Rendered directly by tp_easter_egg */
            break;

        default:
            tp_epaper_render_pairing_qr(id->device_id_upper, sb->pairing_key,
                                        st.battery_pct, invert, full);
            break;
    }
}

/* ---- Mode Transitions ---- */

static void stop_track_recording_if_active(void)
{
    if (tp_flash_session_is_active()) {
        (void)tp_flash_session_end();
    }
    if (tp_modem_is_powered() && !tp_modem_is_mqtt_connected()) {
        (void)tp_modem_gnss_enable(false);
    }
}

static void enter_mode(tp_mode_t new_mode)
{
    tp_superblock_t *sb = tp_flash_get_superblock();

    if (g_mode == TP_MODE_EASTER_EGG && new_mode != TP_MODE_EASTER_EGG) {
        tp_easter_egg_stop();
    }
    if (g_mode == TP_MODE_TRACK_RECORDING && new_mode != TP_MODE_TRACK_RECORDING) {
        stop_track_recording_if_active();
    }

    g_mode = new_mode;

    switch (new_mode) {
        case TP_MODE_ANTITHEFT_SLEEP:
            tp_buzzer_stop();
            (void)tp_lis3dh_config_antitheft(sb->motion_thresh_steps);
            if ((sb->epaper_cfg.flags & TP_EPD_FLAG_AUTO_MODE) != 0U) {
                sb->epaper_cfg.template_id = TP_EPD_TPL_ANTITHEFT;
                refresh_epaper_for_current_template(true);
            }
            tp_epaper_sleep();
            break;

        case TP_MODE_TRACK_RECORDING:
            (void)tp_lis3dh_config_track(g_track_rate_hz, g_track_range_g);
            (void)tp_modem_gnss_enable(true);
            g_track_start_ms    = HAL_GetTick();
            g_last_sample_ms    = g_track_start_ms;
            g_last_gnss_poll_ms = g_track_start_ms;
            g_last_live_tx_ms   = g_track_start_ms;
            g_last_hud_ms       = g_track_start_ms;
            g_track_max_spd_cms = 0U;
            g_track_peak_g_mg   = 0U;
            if ((sb->epaper_cfg.flags & TP_EPD_FLAG_AUTO_MODE) != 0U) {
                sb->epaper_cfg.template_id = TP_EPD_TPL_TRACK_HUD;
                refresh_epaper_for_current_template(true);
            }
            break;

        case TP_MODE_PROXIMITY_FIND:
            tp_led_set(true);
            break;

        case TP_MODE_EASTER_EGG:
            sb->epaper_cfg.template_id = TP_EPD_TPL_EASTER_EGG;
            break;

        case TP_MODE_IDLE_STANDBY:
        default:
            tp_buzzer_stop();
            /* Keep BLE awake; turn off SIM7080G to preserve battery & 500 MB LTE quota */
            if (tp_ble_is_connected() && tp_modem_is_powered()) {
                tp_modem_power_off();
            }
            (void)tp_lis3dh_init();
            break;
    }
}

/* ---- Protocol Command Dispatcher (Unified BLE + 4G MQTT) ---- */

static void handle_frame(const tp_frame_t *f, tp_tx_channel_t ch)
{
    bool is_4g = (ch == CH_4G_MQTT) || ((f->flags & TP_FLAG_IS_4G_CHANNEL) != 0U);
    tp_superblock_t *sb = tp_flash_get_superblock();

    switch (f->opcode) {
        case TP_CMD_PING:
            if (f->flags & TP_FLAG_ACK_REQ) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            }
            send_status(ch);
            break;

        case TP_CMD_GET_STATUS:
            tp_battery_sample();
            send_status(ch);
            break;

        case TP_CMD_SET_MODE: {
            if (f->len < sizeof(tp_cmd_set_mode_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_set_mode_t cmd;
            memcpy(&cmd, f->payload, sizeof(cmd));
            if (cmd.mode > TP_MODE_IDLE_STANDBY) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            if (cmd.rtc_wake_sec >= 10U) {
                sb->rtc_wake_sec = cmd.rtc_wake_sec;
            }
            if (cmd.motion_thresh_mg >= 1U) {
                sb->motion_thresh_steps = cmd.motion_thresh_mg;
            }
            (void)tp_flash_save_superblock();

            if ((tp_mode_t)cmd.mode == TP_MODE_TRACK_RECORDING && !tp_flash_session_is_active()) {
                uint16_t sid = 0U;
                (void)tp_flash_session_begin(tp_rtc_get_epoch(), g_track_rate_hz, g_track_vehicle, &sid);
            }
            enter_mode((tp_mode_t)cmd.mode);
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;
        }

        case TP_CMD_INJECT_AGPS: {
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            if (f->len < sizeof(tp_cmd_agps_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_agps_t agps;
            memcpy(&agps, f->payload, sizeof(agps));
            (void)tp_modem_gnss_inject_agps(&agps);
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;
        }

        case TP_CMD_BUZZER_PLAY: {
            if (f->len < sizeof(tp_cmd_buzzer_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_buzzer_t bz;
            memcpy(&bz, f->payload, sizeof(bz));
            tp_buzzer_play(bz.melody_id, bz.repeat_count, bz.tempo_scale);
            if (bz.melody_id != TP_MELODY_STOP && g_mode != TP_MODE_PROXIMITY_FIND) {
                enter_mode(TP_MODE_PROXIMITY_FIND);
            }
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;
        }

        case TP_CMD_REQUEST_FIX: {
            uint16_t timeout_sec = 30U;
            if (f->len >= 2U) {
                timeout_sec = (uint16_t)f->payload[0] | ((uint16_t)f->payload[1] << 8);
                if (timeout_sec < 5U)   timeout_sec = 5U;
                if (timeout_sec > 120U) timeout_sec = 120U;
            }
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            tp_gnss_fix_t fix;
            (void)tp_modem_acquire_fix(timeout_sec, &fix);
            send_status(ch);
            break;
        }

        case TP_CMD_CLEAR_ALERT:
            g_alert_active = false;
            (void)tp_lis3dh_rearm_int1();
            if (g_mode == TP_MODE_ANTITHEFT_SLEEP) {
                refresh_epaper_for_current_template(false);
            }
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;

        case TP_CMD_PAIR_HANDSHAKE: {
            if (f->len < 4U) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            char rx_key[11];
            uint16_t klen = (f->len > 10U) ? 10U : f->len;
            memcpy(rx_key, f->payload, klen);
            rx_key[klen] = '\0';

            if (strncmp(rx_key, sb->pairing_key, sizeof(sb->pairing_key)) == 0) {
                sb->paired = 1U;
                (void)tp_flash_save_superblock();
                tp_buzzer_play(TP_MELODY_VICTORY, 1U, 14U);
                send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            } else {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
            }
            break;
        }

        case TP_CMD_TRACK_START: {
            if (f->len < sizeof(tp_cmd_track_start_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            if (tp_battery_is_low_for_flash()) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BUSY);
                break;
            }
            tp_cmd_track_start_t ts;
            memcpy(&ts, f->payload, sizeof(ts));
            g_track_rate_hz    = (ts.rate_hz == 0U) ? 10U : ((ts.rate_hz > 50U) ? 50U : ts.rate_hz);
            g_track_range_g    = (ts.accel_range_g < 2U) ? 8U : ts.accel_range_g;
            g_track_vehicle    = ts.vehicle_type;
            g_track_stream_ble = (ts.flags & 0x01U) != 0U;

            uint16_t sid = 0U;
            if (!tp_flash_session_begin(tp_rtc_get_epoch(), g_track_rate_hz, g_track_vehicle, &sid)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_FLASH_FULL);
                break;
            }
            enter_mode(TP_MODE_TRACK_RECORDING);
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;
        }

        case TP_CMD_TRACK_STOP:
            stop_track_recording_if_active();
            enter_mode(TP_MODE_IDLE_STANDBY);
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            if (!is_4g) {
                send_flash_session_list(ch);
            }
            break;

        case TP_CMD_FLASH_LIST:
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            send_flash_session_list(ch);
            if (tp_flash_get_session_count() == 0U) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            }
            break;

        case TP_CMD_FLASH_READ: {
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            if (f->len < sizeof(tp_cmd_flash_read_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_flash_read_t req;
            memcpy(&req, f->payload, sizeof(req));

            uint8_t chunk_buf[sizeof(tp_rsp_flash_chunk_hdr_t) + 14U * sizeof(tp_telemetry_sample_t)];
            tp_rsp_flash_chunk_hdr_t *hdr = (tp_rsp_flash_chunk_hdr_t *)chunk_buf;
            tp_telemetry_sample_t *samples = (tp_telemetry_sample_t *)(chunk_buf + sizeof(*hdr));

            uint8_t count = 0U;
            bool is_last  = true;
            (void)tp_flash_read_session_chunk(req.session_id, req.offset_bytes, req.max_len,
                                              samples, &count, &is_last);

            hdr->session_id   = req.session_id;
            hdr->offset_bytes = req.offset_bytes;
            hdr->flags        = is_last ? 0x01U : 0x00U;
            hdr->sample_count = count;

            uint16_t pay_len = (uint16_t)(sizeof(*hdr) + (uint16_t)count * sizeof(tp_telemetry_sample_t));
            (void)send_on_channel(ch, TP_RSP_FLASH_CHUNK, chunk_buf, pay_len, false);
            break;
        }

        case TP_CMD_FLASH_ERASE_SESSION: {
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            if (f->len < 2U) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            uint16_t sid = (uint16_t)f->payload[0] | ((uint16_t)f->payload[1] << 8);
            bool ok = tp_flash_erase_session(sid);
            send_ack(ch, f->opcode, f->seq, ok ? TP_STATUS_OK : TP_STATUS_ERR_INVALID_PARAM);
            send_status(ch);
            send_flash_session_list(ch);
            break;
        }

        case TP_CMD_FLASH_FORMAT: {
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            if (f->len < 4U) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            uint32_t magic;
            memcpy(&magic, f->payload, 4U);
            if (magic != TP_FLASH_FORMAT_MAGIC) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            bool ok = tp_flash_format_sessions();
            send_ack(ch, f->opcode, f->seq, ok ? TP_STATUS_OK : TP_STATUS_ERR_BUSY);
            send_status(ch);
            break;
        }

        case TP_CMD_EPAPER_CONFIG: {
            if (f->len < sizeof(tp_cmd_epaper_config_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            memcpy(&sb->epaper_cfg, f->payload, sizeof(tp_cmd_epaper_config_t));
            sb->epaper_cfg.title[23]    = '\0';
            sb->epaper_cfg.subtitle[31] = '\0';
            (void)tp_flash_save_superblock();
            refresh_epaper_for_current_template(sb->epaper_cfg.refresh_mode != 0U);
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            break;
        }

        case TP_CMD_EPAPER_BITMAP: {
            if (is_4g) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_BLE_ONLY);
                break;
            }
            if (f->len < sizeof(tp_cmd_epaper_bitmap_hdr_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_epaper_bitmap_hdr_t hdr;
            memcpy(&hdr, f->payload, sizeof(hdr));
            bool ok = tp_epaper_handle_bitmap_chunk(&hdr,
                                                    &f->payload[sizeof(hdr)],
                                                    (uint16_t)(f->len - sizeof(hdr)));
            send_ack(ch, f->opcode, f->seq, ok ? TP_STATUS_OK : TP_STATUS_ERR_INVALID_PARAM);
            break;
        }

        case TP_CMD_EASTER_EGG_CTRL: {
            if (f->len < sizeof(tp_cmd_easter_egg_t)) {
                send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                break;
            }
            tp_cmd_easter_egg_t ctrl;
            memcpy(&ctrl, f->payload, sizeof(ctrl));
            bool invert = (sb->epaper_cfg.flags & TP_EPD_FLAG_INVERT_BW) != 0U;

            switch (ctrl.action) {
                case TP_EE_ACTION_EXIT:
                    enter_mode(TP_MODE_IDLE_STANDBY);
                    break;
                case TP_EE_ACTION_START:
                    enter_mode(TP_MODE_EASTER_EGG);
                    tp_easter_egg_start(ctrl.game_id, ctrl.sound_enabled != 0U, invert);
                    break;
                case TP_EE_ACTION_SYNC:
                    break;
                case TP_EE_ACTION_RESET:
                    tp_easter_egg_reset_score(ctrl.game_id);
                    break;
                default:
                    send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_INVALID_PARAM);
                    return;
            }
            send_ack(ch, f->opcode, f->seq, TP_STATUS_OK);
            send_status(ch);
            send_all_easter_egg_scores(ch);
            break;
        }

        default:
            send_ack(ch, f->opcode, f->seq, TP_STATUS_ERR_UNKNOWN_OP);
            break;
    }
}

/* ---- Anti-Theft Wakeup & 4G MQTT Report Cycle ---- */

static void antitheft_report_via_4g(bool is_motion_alert, const tp_motion_event_t *mot)
{
    const tp_device_identity_t *id = tp_board_get_identity();
    tp_battery_sample();

    /* Acquire fresh GNSS fix (fast warm start when A-GPS injected) */
    tp_gnss_fix_t fix = *tp_modem_get_last_fix();
    (void)tp_modem_acquire_fix(is_motion_alert ? 30U : 20U, &fix);

    /* Persist offline point in Flash NOR Sector 0 ring */
    tp_battery_status_t batt = tp_battery_get_status();
    (void)tp_flash_log_offline_fix(tp_rtc_get_epoch(), fix.lat_e7, fix.lon_e7,
                                   fix.speed_cms, batt.percent,
                                   is_motion_alert ? 1U : 0U);

    if (tp_modem_mqtt_connect(id->device_id_lower)) {
        if (is_motion_alert && mot != NULL) {
            tp_evt_motion_alert_t alert = {
                .alert_id      = ++g_alert_counter,
                .utc_epoch_sec = tp_rtc_get_epoch(),
                .peak_accel_mg = mot->peak_mg,
                .axis_mask     = mot->axis_mask,
                .lat_e7        = fix.lat_e7,
                .lon_e7        = fix.lon_e7,
                .battery_pct   = batt.percent,
                .speed_cms     = fix.speed_cms
            };
            (void)send_on_channel(CH_4G_MQTT, TP_EVT_MOTION_ALERT, &alert, sizeof(alert), true);
        }

        send_status(CH_4G_MQTT);

        /* Listen briefly for queued remote downlink commands on tp/<id>/d */
        uint32_t listen_start = HAL_GetTick();
        while ((HAL_GetTick() - listen_start) < 3500U) {
            tp_frame_t rx;
            if (tp_modem_poll_mqtt_frame(&rx, HAL_GetTick())) {
                handle_frame(&rx, CH_4G_MQTT);
                listen_start = HAL_GetTick(); /* Extend window if active commands arrive */
            }
            tp_buzzer_tick(HAL_GetTick());
            HAL_Delay(20U);
        }

        tp_modem_mqtt_disconnect();
    }

    /* Power down cellular daughterboard unless user switched out of anti-theft mode */
    if (g_mode == TP_MODE_ANTITHEFT_SLEEP) {
        tp_modem_power_off();
    }
}

static void run_antitheft_cycle(uint32_t now_ms)
{
    /* Priority rule: never enter Stop 2 while BLE is connected or within BLE wakeup grace window */
    if (tp_ble_is_connected() || ((int32_t)(g_ble_wake_until_ms - now_ms) > 0)) {
        return;
    }

    tp_superblock_t *sb = tp_flash_get_superblock();

    /* Prepare ultra-low-power state (~16.5 uA total board) */
    tp_buzzer_stop();
    tp_epaper_sleep();
    (void)tp_qspi_enter_deep_power_down();
    tp_ble_enter_low_power();
    if (tp_modem_is_powered()) {
        tp_modem_power_off();
    }
    (void)tp_lis3dh_rearm_int1();

    /* Sleep in Stop 2 until ACC_INT1 (PA1), RTC WakeUp, or LPUART1 BLE byte */
    tp_power_enter_stop2(sb->rtc_wake_sec);

    /* Wakeup! Release Flash DPD and re-arm BLE UART */
    (void)tp_qspi_release_deep_power_down();
    tp_ble_ensure_rx_armed();

    bool woken_by_acc = tp_board_consume_acc_int1();
    bool woken_by_rtc = tp_board_consume_rtc_wakeup();

    if (woken_by_acc) {
        tp_motion_event_t mot = {0};
        if (tp_lis3dh_read_motion_event(&mot)) {
            g_alert_active = true;
            refresh_epaper_for_current_template(false);
            antitheft_report_via_4g(true, &mot);
        }
    } else if (woken_by_rtc) {
        antitheft_report_via_4g(false, NULL);
    } else {
        /* Woken by LPUART1 BLE activity -> grant 15 s active window for phone commands */
        g_ble_wake_until_ms = HAL_GetTick() + 15000U;
    }
}

/* ---- Track Recording Acquisition Loop ---- */

static void run_track_recording(uint32_t now_ms)
{
    uint32_t period_ms = 1000UL / (uint32_t)g_track_rate_hz;
    if (period_ms < 20UL) period_ms = 20UL;

    /* Poll GNSS every 200 ms (up to 5 Hz) */
    if ((now_ms - g_last_gnss_poll_ms) >= 200U) {
        g_last_gnss_poll_ms = now_ms;
        tp_gnss_fix_t fix;
        (void)tp_modem_gnss_poll(&fix);
    }

    /* High-frequency sampling step */
    if ((now_ms - g_last_sample_ms) >= period_ms) {
        uint32_t dt = now_ms - g_last_sample_ms;
        g_last_sample_ms = now_ms;

        (void)tp_lis3dh_read_mg(&g_last_accel);
        const tp_gnss_fix_t *fix = tp_modem_get_last_fix();

        tp_telemetry_sample_t sample = {
            .dt_ms     = (dt > 65535UL) ? 65535U : (uint16_t)dt,
            .lat_e7    = fix->lat_e7,
            .lon_e7    = fix->lon_e7,
            .speed_cms = fix->speed_cms,
            .ax_mg     = g_last_accel.ax_mg,
            .ay_mg     = g_last_accel.ay_mg
        };

        if (sample.speed_cms > g_track_max_spd_cms) {
            g_track_max_spd_cms = sample.speed_cms;
        }
        float comb_g = sqrtf((float)sample.ax_mg * (float)sample.ax_mg +
                             (float)sample.ay_mg * (float)sample.ay_mg);
        if ((uint32_t)comb_g > g_track_peak_g_mg) {
            g_track_peak_g_mg = ((uint32_t)comb_g > 65535UL) ? 65535U : (uint16_t)comb_g;
        }

        if (!tp_flash_session_append(&sample)) {
            /* Stopped gracefully (e.g. battery < 3350 mV protection) */
            enter_mode(TP_MODE_IDLE_STANDBY);
            if (tp_ble_is_connected()) {
                send_status(CH_BLE);
            }
            return;
        }

        /* Optional live BLE telemetry stream (~5 Hz to avoid saturating BLE) */
        if (g_track_stream_ble && tp_ble_is_connected() && (now_ms - g_last_live_tx_ms) >= 200U) {
            g_last_live_tx_ms = now_ms;
            (void)send_on_channel(CH_BLE, TP_EVT_LIVE_TELEMETRY, &sample, sizeof(sample), false);
        }
    }

    /* Update Track HUD on E-Paper every 1.5 s (fast partial refresh + anti-ghosting every 10) */
    if ((now_ms - g_last_hud_ms) >= 1500U) {
        g_last_hud_ms = now_ms;
        refresh_epaper_for_current_template(false);
    }
}

/* ---- Public Entry Points ---- */

void tp_app_init(void)
{
    tp_board_init();
    const tp_device_identity_t *id = tp_board_get_identity();

    snprintf(g_topic_uplink, sizeof(g_topic_uplink), "tp/%s/u", id->device_id_lower);
    snprintf(g_topic_alert,  sizeof(g_topic_alert),  "tp/%s/a", id->device_id_lower);

    tp_battery_init();
    tp_buzzer_init();
    (void)tp_lis3dh_init();
    (void)tp_flash_fs_init();
    tp_modem_init();
    (void)tp_ble_init(id->ble_name);
    tp_epaper_init();

    tp_superblock_t *sb = tp_flash_get_superblock();
    if (!sb->paired) {
        /* First boot: display compact pairing QR Code TP1:<ID>:<KEY> on E-Paper */
        sb->epaper_cfg.template_id = TP_EPD_TPL_MINIMAL_QR;
    }
    refresh_epaper_for_current_template(true);

    tp_buzzer_play(TP_MELODY_STARTUP_JINGLE, 1U, 10U);
    g_mode              = TP_MODE_IDLE_STANDBY;
    g_ble_wake_until_ms = HAL_GetTick() + 15000U;
    tp_log("[TrackPulse] Boot OK ID=%s BLE=%s\r\n", id->device_id_upper, id->ble_name);
}

void tp_app_loop(void)
{
    uint32_t now_ms = HAL_GetTick();

    /* 1. Buzzer non-blocking sequencer (enforces 30 s hard safety cutoff) */
    tp_buzzer_tick(now_ms);

    /* 2. Periodic battery sampling every 5 s */
    if ((now_ms - g_last_batt_ms) >= 5000U) {
        g_last_batt_ms = now_ms;
        tp_battery_sample();
    }

    /* 3. Process incoming BLE frames with absolute priority (0 bytes 4G) */
    tp_frame_t rx_frame;
    while (tp_ble_poll_frame(&rx_frame, now_ms)) {
        g_ble_wake_until_ms = now_ms + 15000U;
        handle_frame(&rx_frame, CH_BLE);
    }
    uint8_t err_op = 0U, err_seq = 0U;
    if (tp_ble_consume_crc_error(&err_op, &err_seq)) {
        send_ack(CH_BLE, err_op, err_seq, TP_STATUS_ERR_CRC);
    }

    /* 4. Physical buttons (BP1 PB2 & BP2 PC13) when not captured by Easter Egg */
    if (g_mode != TP_MODE_EASTER_EGG) {
        tp_btn_event_t bev = tp_btn_poll(now_ms);
        tp_superblock_t *sb = tp_flash_get_superblock();
        if (bev == TP_BTN_EVENT_BOTH_LONG) {
            /* PROTOCOL.md §4.5: Long press BP1+BP2 displays compact pairing QR Code */
            sb->epaper_cfg.template_id = TP_EPD_TPL_MINIMAL_QR;
            refresh_epaper_for_current_template(true);
            tp_buzzer_beep(2730U, 120U);
        } else if (bev == TP_BTN_EVENT_BP2_LONG && g_mode == TP_MODE_IDLE_STANDBY) {
            /* Local Easter Egg launch shortcut */
            enter_mode(TP_MODE_EASTER_EGG);
            tp_easter_egg_start(TP_EE_GAME_PULSE_RUNNER, true,
                                (sb->epaper_cfg.flags & TP_EPD_FLAG_INVERT_BW) != 0U);
        } else if (bev == TP_BTN_EVENT_BP1_LONG) {
            /* Toggle Track Recording locally */
            if (g_mode == TP_MODE_TRACK_RECORDING) {
                stop_track_recording_if_active();
                enter_mode(TP_MODE_IDLE_STANDBY);
                tp_buzzer_beep(1047U, 150U);
            } else if (!tp_battery_is_low_for_flash()) {
                uint16_t sid = 0U;
                if (tp_flash_session_begin(tp_rtc_get_epoch(), g_track_rate_hz, g_track_vehicle, &sid)) {
                    enter_mode(TP_MODE_TRACK_RECORDING);
                    tp_buzzer_beep(2093U, 150U);
                }
            }
        }
    }

    /* 5. Mode-specific state machine execution */
    switch (g_mode) {
        case TP_MODE_ANTITHEFT_SLEEP:
            run_antitheft_cycle(now_ms);
            break;

        case TP_MODE_TRACK_RECORDING:
            run_track_recording(now_ms);
            break;

        case TP_MODE_PROXIMITY_FIND:
            if ((now_ms - g_last_led_ms) >= 250U) {
                g_last_led_ms = now_ms;
                tp_led_toggle();
            }
            break;

        case TP_MODE_EASTER_EGG: {
            bool score_ready = false;
            bool exit_req    = false;
            tp_easter_egg_tick(now_ms, &score_ready, &exit_req);
            if (score_ready && tp_ble_is_connected()) {
                send_all_easter_egg_scores(CH_BLE);
            }
            if (exit_req) {
                enter_mode(TP_MODE_IDLE_STANDBY);
                refresh_epaper_for_current_template(true);
                if (tp_ble_is_connected()) {
                    send_status(CH_BLE);
                }
            }
            break;
        }

        case TP_MODE_IDLE_STANDBY:
        default:
            /* Gentle LED heartbeat blink when awake */
            if ((now_ms - g_last_led_ms) >= 1500U) {
                g_last_led_ms = now_ms;
                tp_led_toggle();
            }
            break;
    }

    /* 6. Periodic BLE status telemetry (every 2 s while BLE connected, 0 bytes 4G) */
    if (tp_ble_is_connected() && g_mode != TP_MODE_EASTER_EGG &&
        (now_ms - g_last_heartbeat_ms) >= 2000U) {
        g_last_heartbeat_ms = now_ms;
        send_status(CH_BLE);
    }
}
