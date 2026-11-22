/**
 * @file    tp_config.h
 * @brief   TrackPulse STM32L433CCT6 global firmware configuration constants.
 */
#ifndef TP_CONFIG_H
#define TP_CONFIG_H

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Default 4G LTE-M / MQTT Settings (matching APK MqttSettings) ---- */
#define TP_LTE_APN                  "iot.1nce.net"
#define TP_MQTT_BROKER_HOST         "broker.hivemq.com"
#define TP_MQTT_BROKER_PORT         1883U
#define TP_MQTT_KEEPALIVE_SEC       60U

/* ---- Safety & Power Thresholds (PROTOCOL.md §5) ---- */
#define TP_FRAME_RX_TIMEOUT_MS      250U   /* RX reassembly timeout (ms) */
#define TP_BUZZER_MAX_RUN_MS        30000U /* Max buzzer active duration (ms) */
#define TP_FLASH_MIN_BATT_MV        3350U  /* Stop Flash writes below 3.35 V */
#define TP_EPAPER_MAX_PARTIALS      10U    /* Full refresh after 10 partials */

/* ---- Default Anti-Theft Parameters ---- */
#define TP_DEFAULT_RTC_WAKE_SEC     3600U  /* 1 hour periodic wakeup */
#define TP_DEFAULT_MOTION_STEPS     10U    /* 10 * 16 mg = 160 mg */

/* ---- Button Timings ---- */
#define TP_BTN_DEBOUNCE_MS          30U
#define TP_BTN_LONG_PRESS_MS        2500U

/* ---- RTC Backup Register Magic ---- */
#define TP_RTC_BKP_MAGIC            0x5450U

/* ---- UART Ring Buffer Sizes ---- */
#define TP_BLE_RX_BUF_SIZE          512U
#define TP_MODEM_RX_BUF_SIZE        1024U

#ifdef __cplusplus
}
#endif

#endif /* TP_CONFIG_H */
