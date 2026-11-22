/**
 * @file    tp_modem_sim7080g.h
 * @brief   SIMCom SIM7080G 4G LTE-M/NB-IoT + GNSS + A-GPS + Binary MQTT driver on USART1.
 *
 * Pinout: TX=PA9, RX=PA10, PWRKEY=PB14, DTR=PB3.
 */
#ifndef TP_MODEM_SIM7080G_H
#define TP_MODEM_SIM7080G_H

#include "tp_protocol.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool     has_fix;
    uint8_t  sats;
    int32_t  lat_e7;
    int32_t  lon_e7;
    int16_t  alt_dm;
    uint16_t speed_cms;
    uint32_t utc_epoch_sec;
} tp_gnss_fix_t;

/** Initialize driver state and arm USART1 RX interrupt (modem kept OFF to save battery). */
void tp_modem_init(void);

/** Power on SIM7080G via PWRKEY (PB14) and wake UART via DTR (PB3). */
bool tp_modem_power_on(void);

/** Cleanly disconnect MQTT/GNSS and power off SIM7080G (AT+CPOWD=1). */
void tp_modem_power_off(void);

/** Put SIM7080G UART to sleep via DTR HIGH (AT+CSCLK=1). */
void tp_modem_uart_sleep(bool sleep);

bool tp_modem_is_powered(void);
bool tp_modem_is_lte_attached(void);
bool tp_modem_is_mqtt_connected(void);

/* ---- GNSS & A-GPS ---- */
bool tp_modem_gnss_enable(bool enable);
bool tp_modem_gnss_inject_agps(const tp_cmd_agps_t *agps);
bool tp_modem_gnss_poll(tp_gnss_fix_t *out_fix);
const tp_gnss_fix_t *tp_modem_get_last_fix(void);
bool tp_modem_is_agps_valid(void);

/** Acquire a fresh GNSS fix synchronously up to timeout_sec (returns true on valid fix). */
bool tp_modem_acquire_fix(uint16_t timeout_sec, tp_gnss_fix_t *out_fix);

/* ---- 4G LTE-M & Binary MQTT v3.1.1 ---- */
bool tp_modem_mqtt_connect(const char *dev_id_lower);
void tp_modem_mqtt_disconnect(void);

/** Publish a TrackPulse binary frame to MQTT topic (tp/<id>/u or tp/<id>/a). */
bool tp_modem_mqtt_publish_frame(const char *topic, uint8_t qos, uint8_t retain,
                                 uint8_t opcode, uint8_t seq,
                                 const void *payload, uint16_t payload_len);

/** Drain USART1 URC stream (+SMSUB:) and extract any binary downlink frame. */
bool tp_modem_poll_mqtt_frame(tp_frame_t *out_frame, uint32_t now_ms);

/** ISR hooks called from HAL_UART_RxCpltCallback / HAL_UART_ErrorCallback. */
void tp_modem_uart_rx_isr(void);
void tp_modem_uart_err_isr(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_MODEM_SIM7080G_H */
