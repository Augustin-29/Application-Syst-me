/**
 * @file    tp_ble_rn4871.h
 * @brief   Microchip RN4871 BLE Transparent UART driver on LPUART1 (115200 8N1, RTS/CTS).
 */
#ifndef TP_BLE_RN4871_H
#define TP_BLE_RN4871_H

#include "tp_protocol.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Reset RN4871, arm LPUART1 RX interrupt, and configure Transparent UART + device name. */
bool tp_ble_init(const char *ble_adv_name);

/** Poll RX ring buffer and extract next CRC-valid binary frame (250 ms timeout enforced). */
bool tp_ble_poll_frame(tp_frame_t *out_frame, uint32_t now_ms);

/** Encode and transmit a TrackPulse binary frame over BLE Transparent UART. */
bool tp_ble_send_frame(uint8_t opcode, uint8_t seq, uint8_t flags,
                       const void *payload, uint16_t payload_len);

/** True if BLE peer is currently connected (or active traffic received recently). */
bool tp_ble_is_connected(void);

/** True if last parser feed encountered a CRC error (for optional ERR_CRC ACK). */
bool tp_ble_consume_crc_error(uint8_t *out_opcode, uint8_t *out_seq);

/** Put RN4871 into low-power Dormant/sleep mode (keeps RST_BLE HIGH to avoid R12 leak). */
void tp_ble_enter_low_power(void);

/** Re-arm LPUART1 interrupt reception (e.g. after Stop 2 wakeup). */
void tp_ble_ensure_rx_armed(void);

/** ISR hooks called from HAL_UART_RxCpltCallback / HAL_UART_ErrorCallback. */
void tp_ble_uart_rx_isr(void);
void tp_ble_uart_err_isr(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_BLE_RN4871_H */
