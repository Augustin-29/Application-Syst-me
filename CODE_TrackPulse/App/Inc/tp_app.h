/**
 * @file    tp_app.h
 * @brief   TrackPulse STM32L433CCT6 top-level application state machine & connectivity manager.
 */
#ifndef TP_APP_H
#define TP_APP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Initialize all TrackPulse drivers, Flash FS, BLE, E-Paper QR/HUD and state machine. */
void tp_app_init(void);

/** Cooperative main loop iteration (called from main.c while(1)). */
void tp_app_loop(void);

#ifdef __cplusplus
}
#endif

#endif /* TP_APP_H */
