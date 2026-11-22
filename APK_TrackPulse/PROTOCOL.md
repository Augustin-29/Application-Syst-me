# TrackPulse — Spécification du Protocole de Communication Binaire (v1.0)

Ce document définit le protocole binaire compact partagé entre l'application Android **TrackPulse** et le futur firmware C du boîtier embarqué **STM32L433CCT6** (`CODE_TrackPulse.ioc`).

L'objectif principal est double :
1. **Unifier les trames** entre la liaison locale **BLE (RN4871)** et la liaison distante **4G LTE-M / MQTT (SIM7080G)** afin de partager un seul parseur C côté STM32 et un seul codec Kotlin côté Android.
2. **Minimiser la consommation d'octets** pour respecter le forfait **500 Mo de data 4G**, accélérer les transferts de la **Flash NOR 8 Mo (W25Q64JV)** via BLE et réduire le temps d'éveil CPU (*Race-to-Sleep* à 80 MHz puis retour en mode `Stop 2` à ~16,5 µA).

---

## 1. Rappel de l'architecture matérielle (`CODE_TrackPulse.ioc`)

| Périphérique | Composant | Bus STM32L433 | Paramètres clés |
|---|---|---|---|
| **MCU** | STM32L433CCT6 | SYSCLK 80 MHz, LSE 32,768 kHz | Stop 2 ~1,4 µA, Little-Endian (ARM Cortex-M4F) |
| **BLE** | Microchip **RN4871** | `LPUART1` (PB10/PB11, RTS PB12, CTS PB13, RST PH0) | 115 200 bauds, contrôle de flux matériel RTS/CTS, réveil Stop 2 |
| **4G / GNSS** | SIMCom **SIM7080G** | `USART1` (PA9/PA10, PWRKEY PB14, DTR PB3) | 115 200 bauds, MQTT AT (`AT+SM*`), GNSS + A-GPS |
| **Flash NOR** | Winbond **W25Q64JVSSIQ** | `QUADSPI` Bank1 40 MHz | 8 Mo (64 Mbits), pages 256 o, secteurs 4 Ko, Deep Power-Down `0xB9` |
| **Accéléromètre** | ST **LIS3DH** | `I2C1` (PB8/PB9, addr 7-bit `0x18`) + `ACC_INT1` (PA1 EXTI1) | 10 Hz Low-Power (antivol) / 50–100 Hz ±8g (circuit) |
| **E-Paper** | Waveshare **1.54" V2** | `SPI1` TX-only 5 MHz (PA5/PB5, CS PH1, DC PA15, RST PA8, BUSY PB15) | 200 × 200 px, 1-bit monochrome (5 000 octets brut), partiel 0,3 s / complet 2 s |
| **Buzzer** | **CMT-8530S-SMT** | `TIM2_CH1` PWM (PA0) | Base 1 MHz (PSC=79), résonance 2 730 Hz (ARR=365, CCR1=183) |
| **Batterie** | Li-Ion 1S (3,3–4,2 V) | `ADC1_IN9` (PA4) + `BAT_STAT` (PB6) + `FAULT` (PB7) | Pont diviseur 1 MΩ / 1 MΩ (pleine échelle 6 600 mV) |
| **IHM Locale** | `BP1` (PB2), `BP2` (PC13), `LED1` (PB4) | GPIO Pull-up (actifs bas) | Contrôle local & mini-jeu Easter Egg |

---

## 2. Couches de Transport (Hybride BLE / 4G MQTT)

L'application Android implémente `HybridTransportManager` qui applique la règle suivante :
- **Priorité absolue au BLE** dès que le module RN4871 est détecté à proximité (RSSI > -95 dBm). Zéro octet 4G consommé, latence faible (< 30 ms), débit adapté au dump de la Flash NOR.
- **Bascule automatique sur 4G / MQTT** dès que le lien BLE est perdu ou hors de portée (veille antivol, interrogation position à distance, réception alertes vol `ACC_INT1`).
- **Injection A-GPS automatique** : à chaque établissement de connexion BLE, l'application envoie immédiatement une trame `CMD_INJECT_AGPS` (20 octets) contenant l'heure UTC précise et la position GPS du smartphone afin d'initialiser la RTC STM32 et d'accélérer le *Cold Start* du GNSS SIM7080G.

### 2.1 Transport BLE — Microchip RN4871 (Transparent UART)

Le RN4871 intègre nativement le service **Microchip Transparent UART** qui ponte directement les trames BLE sur `LPUART1` avec contrôle de flux RTS/CTS.

| Élément GATT | UUID 128 bits | Propriétés |
|---|---|---|
| **Service Transparent UART** | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` | Primary Service |
| **Caractéristique TX (Tracker → App)** | `49535343-1E4D-4BD9-BA61-23C647249616` | `NOTIFY` (CCCD `0x2902`) |
| **Caractéristique RX (App → Tracker)** | `49535343-8841-43F4-A8D4-ECBE34729BB3` | `WRITE` / `WRITE_NO_RESPONSE` |

Configuration AT minimale recommandée du RN4871 à la fabrication :
```text
$$$                // Entrée mode commande
SN,TrackPulse      // Nom publicitaire BLE
SS,C0              // Active Device Info + Transparent UART
SR,0000            // Mode périphérique standard
R,1                // Redémarrage
```
- **MTU négocié :** l'application demande `MTU = 247` (soit jusqu'à 244 octets utiles par paquet GATT) et retombe automatiquement sur une fragmentation par tranches de 20 octets si nécessaire.
- **Proximity Finder :** lecture périodique du RSSI (`BluetoothGatt.readRemoteRssi()`) toutes les 400 ms couplée à `CMD_BUZZER_PLAY`.

### 2.2 Transport 4G LTE-M / NB-IoT — SIM7080G & MQTT v3.1.1

Pour préserver le forfait **500 Mo**, les topics sont volontairement ultra-courts et les payloads sont **strictement binaires** (aucun JSON, aucune clé texte).

| Direction | Topic MQTT | QoS | Retain | Usage |
|---|---|---|---|---|
| **Tracker → App** | `tp/<devId>/u` | `0` (ou `1` ACK) | `false` | Télémétrie périodique, réponses statut, scores |
| **App → Tracker** | `tp/<devId>/d` | `1` | `false` | Commandes à distance (Refresh Fix, changement mode, buzzer) |
| **Tracker → App (Vol)** | `tp/<devId>/a` | `1` | `true` | Alertes mouvement critique (`EVT_MOTION_ALERT`) |

> **Règle d'économie de data :** Le téléchargement de sessions Circuit (Flash NOR 8 Mo) est **strictement verrouillé en BLE** afin d'empêcher tout dépassement du forfait 500 Mo.

#### Estimation de consommation data (forfait 500 Mo)
| Événement | Payload binaire | Enveloppe trame | Total MQTT + TCP/IP estimé | Coût sur 500 Mo |
|---|---|---|---|---|
| Heartbeat / Position (`EVT_STATUS`) | 25 octets | 34 octets | ~110 octets | **~4,5 millions de positions** |
| Alerte mouvement (`EVT_MOTION_ALERT`) | 19 octets | 28 octets | ~105 octets | Négligeable |
| Commande `CMD_REQUEST_FIX` | 2 octets | 11 octets | ~85 octets | Négligeable |
| **1 position / heure pendant 1 an** | — | — | **~0,96 Mo / an** | **0,19 % du forfait** |

---

## 3. Enveloppe de Trame Binaire Commune

Toutes les valeurs multi-octets sont codées en **Little-Endian** (natif ARM Cortex-M4F STM32 et `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)` sur Android).

```
+--------+--------+---------+--------+--------+--------+------------------+--------+--------+
|  0xAA  |  0x55  | VER|FLG | OPCODE |  SEQ   | LEN_L  | LEN_H | PAYLOAD  | CRC_L  | CRC_H  |
+--------+--------+---------+--------+--------+--------+------------------+--------+--------+
  Byte 0   Byte 1   Byte 2    Byte 3   Byte 4   Byte 5   Byte 6  7..7+L-1   7+L      8+L
```

| Champ | Taille | Description |
|---|---|---|
| `SOF` | 2 octets | Synchronisation fixe : `0xAA`, `0x55` |
| `VER_FLAGS` | 1 octet | Bits 7..4 = Version (`0x1`), Bit 0 = `ACK_REQ`, Bit 1 = `IS_4G_CHANNEL`, Bits 3..2 = réservés |
| `OPCODE` | 1 octet | Identifiant de commande (`0x01..0x7F` App→Tracker) ou réponse/événement (`0x80..0xFF` Tracker→App) |
| `SEQ` | 1 octet | Numéro de séquence roulant `0..255` (corrélation requête/réponse et déduplication MQTT) |
| `LEN` | 2 octets (`uint16`) | Longueur exacte du champ `PAYLOAD` en octets (`0..240`) |
| `PAYLOAD` | `LEN` octets | Données binaires spécifiques à l'`OPCODE` |
| `CRC16` | 2 octets (`uint16`) | **CRC-16/CCITT-FALSE** (polynôme `0x1021`, init `0xFFFF`, pas de réflexion, xorout `0x0000`) calculé sur `VER_FLAGS .. fin PAYLOAD` |

**Surcoût fixe : 9 octets par trame.**

### Implémentation de référence CRC-16/CCITT-FALSE (C STM32)
```c
uint16_t tp_crc16_ccitt(const uint8_t *data, uint16_t len) {
    uint16_t crc = 0xFFFFU;
    for (uint16_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t bit = 0; bit < 8; bit++) {
            if (crc & 0x8000U) {
                crc = (uint16_t)((crc << 1) ^ 0x1021U);
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}
```

---

## 4. Catalogue des Opcodes & Structures C (`__attribute__((packed))`)

### 4.1 Système, Statut & Connectivité

| Opcode | Nom | Sens | Payload | Description |
|---|---|---|---|---|
| `0x01` | `CMD_PING` | App → MCU | 0 o | Test de présence / latence |
| `0x81` | `RSP_ACK` | MCU → App | 3 o | Acquittement (`origOpcode:u8`, `origSeq:u8`, `status:u8`) |
| `0x02` | `CMD_GET_STATUS` | App → MCU | 0 o | Demande état complet immédiat |
| `0x82` | `EVT_STATUS` | MCU → App | **25 o** | État complet boîtier (batterie, flash, GPS, mode) |
| `0x03` | `CMD_SET_MODE` | App → MCU | **4 o** | Changement mode + paramètres veille/seuil |
| `0x04` | `CMD_INJECT_AGPS` | App → MCU | **19 o** | Injection position GPS + heure UTC (BLE uniquement) |
| `0x05` | `CMD_BUZZER_PLAY` | App → MCU | **3 o** | Déclenche une mélodie / bip sur `TIM2_CH1` |
| `0x06` | `CMD_REQUEST_FIX` | App → MCU | **2 o** | Demande fix GNSS immédiat via 4G (`timeoutSec:u16`) |

#### Codes de statut (`RSP_ACK.status`)
- `0x00` : `OK`
- `0x01` : `ERR_CRC`
- `0x02` : `ERR_UNKNOWN_OPCODE`
- `0x03` : `ERR_INVALID_PARAM`
- `0x04` : `ERR_BUSY`
- `0x05` : `ERR_FLASH_FULL`
- `0x06` : `ERR_BLE_ONLY` (ex: dump Flash refusé sur 4G)

#### Modes du boîtier (`mode`)
- `0x00` : `ANTITHEFT_SLEEP` — Veille Stop 2 + LIS3DH 10 Hz `ACC_INT1` + réveil RTC périodique
- `0x01` : `TRACK_RECORDING` — Acquisition circuit haute fréquence (GNSS + LIS3DH → Flash W25Q64JV)
- `0x02` : `PROXIMITY_FIND` — Mode localisation courte portée (BLE actif + buzzer)
- `0x03` : `EASTER_EGG` — Mode mini-jeu sur E-Paper + BP1/BP2 + buzzer
- `0x04` : `IDLE_STANDBY` — Veille connectée BLE

#### Structures C associées
```c
typedef struct __attribute__((packed)) {
    uint8_t  mode;             // 0..4
    uint8_t  status_flags;     // b0:charging(BAT_STAT==0), b1:usb_fault(FAULT==0), b2:gps_fix, b3:recording, b4:ble_connected, b5:lte_attached, b6:agps_valid
    uint16_t battery_mv;       // Tension Li-Ion en mV (ex: 4050)
    uint8_t  battery_pct;      // 0..100 %
    uint32_t flash_used_bytes; // Octets utilisés sur 8 388 608 (W25Q64JV)
    uint8_t  gps_sats;         // Nombre de satellites verrouillés
    int32_t  lat_e7;           // Latitude * 1e7 (0 si pas de fix)
    int32_t  lon_e7;           // Longitude * 1e7
    uint16_t speed_cms;        // Vitesse sol en cm/s
    uint32_t uptime_sec;       // Secondes depuis le boot
    uint8_t  active_epaper_tpl;// Modèle E-Paper actuellement affiché
} tp_evt_status_t; // 25 octets

typedef struct __attribute__((packed)) {
    uint8_t  mode;              // Mode cible (0..4)
    uint16_t rtc_wake_sec;      // Intervalle réveil RTC Stop 2 en mode Antivol (ex: 3600 s)
    uint8_t  motion_thresh_mg;  // Seuil LIS3DH INT1 (pas de 16 mg : valeur 10 = 160 mg)
} tp_cmd_set_mode_t; // 4 octets

typedef struct __attribute__((packed)) {
    uint32_t utc_epoch_sec;     // Secondes Unix UTC (synchro RTC STM32 + SIM7080G)
    uint16_t utc_millis;        // Millisecondes 0..999
    int32_t  lat_e7;            // Latitude smartphone * 1e7
    int32_t  lon_e7;            // Longitude smartphone * 1e7
    int16_t  alt_dm;            // Altitude en décimètres (-3276,8 m à +3276,7 m)
    uint16_t accuracy_m;        // Précision horizontale estimée (mètres)
    uint8_t  flags;             // b0:valid_pos, b1:valid_time
} tp_cmd_agps_t; // 19 octets

typedef struct __attribute__((packed)) {
    uint8_t melody_id;          // 0=STOP, 1=PING_2730HZ, 2=STARTUP_JINGLE, 3=SIREN_ALERT, 4=SOS_MORSE, 5=VICTORY
    uint8_t repeat_count;       // 1..10 (0 = continu jusqu'à STOP, max 30 s sécurité)
    uint8_t tempo_scale;        // 10 = vitesse x1.0 (5..20)
} tp_cmd_buzzer_t; // 3 octets
```

---

### 4.2 Alertes Antivol (LIS3DH `ACC_INT1`)

| Opcode | Nom | Sens | Payload | Description |
|---|---|---|---|---|
| `0xA0` | `EVT_MOTION_ALERT` | MCU → App | **19 o** | Interruption mouvement suspect (publié en priorité sur MQTT `tp/<id>/a` QoS 1 ou BLE) |
| `0x07` | `CMD_CLEAR_ALERT` | App → MCU | 1 o | Acquitte l'alerte antivol (`alertId:u8`) et réarme `ACC_INT1` |

```c
typedef struct __attribute__((packed)) {
    uint8_t  alert_id;          // Identifiant incrémental d'alerte
    uint32_t utc_epoch_sec;     // Horodatage RTC du déclenchement
    uint16_t peak_accel_mg;     // Pic d'accélération mesuré en milli-g (LIS3DH)
    uint8_t  axis_mask;         // b0:X, b1:Y, b2:Z, b3:tamper_tilt
    int32_t  lat_e7;            // Dernière latitude connue * 1e7
    int32_t  lon_e7;            // Dernière longitude connue * 1e7
    uint8_t  battery_pct;       // Niveau batterie au moment de l'alerte
    uint16_t speed_cms;         // Vitesse détectée si déplacement en cours
} tp_evt_motion_alert_t; // 19 octets
```

---

### 4.3 Acquisition Circuit & Transfert Flash NOR (W25Q64JV — 8 Mo)

| Opcode | Nom | Sens | Payload | Description |
|---|---|---|---|---|
| `0x10` | `CMD_TRACK_START` | App → MCU | **4 o** | Démarre une session circuit (`rateHz:u8`, `accelRangeG:u8`, `vehicleType:u8`, `flags:u8`) |
| `0x11` | `CMD_TRACK_STOP` | App → MCU | 0 o | Clôture la session active et écrit l'en-tête final en Flash |
| `0x12` | `CMD_FLASH_LIST` | App → MCU | 0 o | Demande la liste des sessions enregistrées (BLE uniquement) |
| `0x92` | `RSP_FLASH_SESSION` | MCU → App | **24 o** | Métadonnées d'une session (`index/count` inclus) |
| `0x13` | `CMD_FLASH_READ` | App → MCU | **8 o** | Lit un bloc (`sessionId:u16`, `offset:u32`, `maxLen:u16`) — BLE uniquement |
| `0x93` | `RSP_FLASH_CHUNK` | MCU → App | **8+N o** | Bloc binaire de points (`sessionId:u16`, `offset:u32`, `totalBytes:u32`? -> `flags:u8`, `count:u8`, `samples[]`) |
| `0x14` | `CMD_FLASH_ERASE_SESSION` | App → MCU | 2 o | Supprime une session (`sessionId:u16`) |
| `0x15` | `CMD_FLASH_FORMAT` | App → MCU | 4 o | Effacement complet Flash (clé magique `0xDEADBEEF`) |
| `0xA1` | `EVT_LIVE_TELEMETRY` | MCU → App | **16 o** | Échantillon temps réel pendant roulage (optionnel en BLE) |

#### Cartographie Mémoire Flash NOR W25Q64JV (8 388 608 octets)
```text
0x000000 - 0x000FFF (Secteur 0,     4 Ko) : Superblock (version FS, high scores Easter Egg, config E-Paper)
0x001000 - 0x00FFFF (Secteurs 1..15, 60 Ko) : Table des sessions (jusqu'à 128 entrées de 64 octets)
0x010000 - 0x7FFFFF (Secteurs 16..2047, 8 128 Ko) : Zone de données brutes de télémétrie
```

#### Échantillon de télémétrie compact (16 octets exacts = 16 échantillons par page Flash de 256 o)
```c
typedef struct __attribute__((packed)) {
    uint16_t dt_ms;      // Delta temps depuis le point précédent (ms)
    int32_t  lat_e7;     // Latitude * 1e7 (~1,1 cm de résolution)
    int32_t  lon_e7;     // Longitude * 1e7
    uint16_t speed_cms;  // Vitesse en cm/s (0..655,35 m/s)
    int16_t  ax_mg;      // Accélération longitudinale LIS3DH en milli-g (+ accélération, - freinage)
    int16_t  ay_mg;      // Accélération latérale LIS3DH en milli-g (+ droite, - gauche)
} tp_telemetry_sample_t; // 16 octets exacts
```
- **Capacité totale en Flash :** `8 128 Ko / 16 o = 520 192 points`
  - À **10 Hz** : **14 heures 26 minutes** de télémétrie continue !
  - À **20 Hz** : **7 heures 13 minutes** !
  - À **50 Hz** : **2 heures 53 minutes** !
- **Durée estimée d'un transfert BLE (10 min à 10 Hz = 6 000 pts = 96 Ko) :** ~10 à 15 secondes via RN4871 (115 200 bauds effectifs ≈ 8–10 Ko/s).

```c
typedef struct __attribute__((packed)) {
    uint8_t  entry_index;     // Index 0..entry_count-1
    uint8_t  entry_count;     // Nombre total de sessions en Flash
    uint16_t session_id;      // ID unique
    uint32_t start_utc_sec;   // Timestamp début
    uint32_t duration_ms;     // Durée totale (ms)
    uint32_t sample_count;    // Nombre de tp_telemetry_sample_t (taille = sample_count * 16)
    uint16_t max_speed_cms;   // Vitesse max atteinte (cm/s)
    uint16_t peak_g_mg;       // Pic G combiné en milli-g
    uint8_t  sample_rate_hz;  // 10, 20 ou 50 Hz
    uint8_t  vehicle_type;    // 0=KART, 1=MOTO, 2=VOITURE
    uint16_t reserved;
} tp_rsp_flash_session_t; // 24 octets

typedef struct __attribute__((packed)) {
    uint16_t session_id;
    uint32_t offset_bytes;    // Multiple de 16
    uint16_t max_len;         // Multiple de 16 (ex: 224 octets = 14 samples / trame)
} tp_cmd_flash_read_t; // 8 octets

typedef struct __attribute__((packed)) {
    uint16_t session_id;
    uint32_t offset_bytes;
    uint8_t  flags;           // b0: is_last_chunk
    uint8_t  sample_count;    // N = 0..14
    // suivi de N * tp_telemetry_sample_t (16 * N octets)
} tp_rsp_flash_chunk_hdr_t; // 8 octets + 16*N
```

---

### 4.4 Écran Waveshare 1.54" E-Paper V2 (200×200) & Mode Easter Egg

| Opcode | Nom | Sens | Payload | Description |
|---|---|---|---|---|
| `0x20` | `CMD_EPAPER_CONFIG` | App → MCU | **60 o** | Choisit le gabarit d'affichage selon le mode + textes personnalisés |
| `0x21` | `CMD_EPAPER_BITMAP` | App → MCU | **6+N o** | Envoi d'un framebuffer monochrome 200×200 (5 000 o brut ou RLE PackBits) par blocs |
| `0x30` | `CMD_EASTER_EGG_CTRL` | App → MCU | **3 o** | Lance/quitte un mini-jeu sur E-Paper + BP1/BP2 + Buzzer, ou demande les scores |
| `0xB0` | `EVT_EASTER_EGG_SCORE` | MCU → App | **13 o** | Synchronise le score de la dernière partie et le record stocké en Flash |

```c
typedef struct __attribute__((packed)) {
    uint8_t template_id;      // 0=ANTITHEFT_STATUS, 1=TRACK_HUD_SPEED_G, 2=CUSTOM_CARD, 3=MINIMAL_QR, 4=EASTER_EGG
    uint8_t refresh_mode;     // 0=PARTIAL (0.3s), 1=FULL (2.0s anti-ghosting)
    uint8_t flags;            // b0:invert_bw, b1:show_battery, b2:show_gps_sats, b3:auto_switch_with_mode
    uint8_t contrast_level;   // Réservé (0..3)
    char    title[24];        // ASCII/UTF-8 nul-terminé (ex: "TRACKPULSE #07")
    char    subtitle[32];     // ASCII/UTF-8 nul-terminé (ex: "KARTING MANS - PRO")
} tp_cmd_epaper_config_t; // 60 octets

typedef struct __attribute__((packed)) {
    uint16_t offset_bytes;    // Offset dans le framebuffer (0..4999)
    uint16_t total_bytes;     // 5000 (brut) ou taille compressée RLE
    uint8_t  encoding;        // 0=RAW_1BIT_MSB_FIRST, 1=PACKBITS_RLE
    uint8_t  flags;           // b0:commit_and_refresh, b1:full_refresh
    // suivi des octets image (jusqu'à 224 octets / trame)
} tp_cmd_epaper_bitmap_hdr_t; // 6 octets + N

typedef struct __attribute__((packed)) {
    uint8_t action;           // 0=EXIT_GAME, 1=START_GAME, 2=SYNC_SCORES, 3=RESET_HIGH_SCORE
    uint8_t game_id;          // 1=PULSE_RUNNER (BP1 saut, BP2 glissade), 2=TRACK_DODGE (BP1 gauche, BP2 droite)
    uint8_t sound_enabled;    // 0=MUET, 1=SONS BUZZER TIM2 ACTIFS
} tp_cmd_easter_egg_t; // 3 octets

typedef struct __attribute__((packed)) {
    uint8_t  game_id;         // 1=PULSE_RUNNER, 2=TRACK_DODGE
    uint32_t last_score;      // Score de la dernière partie
    uint32_t high_score;      // Meilleur score persisté en Flash (secteur 0)
    uint16_t games_played;    // Nombre de parties jouées sur le boîtier
    uint16_t play_time_sec;   // Temps de jeu cumulé
} tp_evt_easter_egg_score_t; // 13 octets
```

---

### 4.5 Appairage Initial par QR Code sur E-Paper (200×200 px)

Pour faciliter l'association initiale entre le boîtier STM32 et l'application Android (y compris sur Android 10–15), le firmware affiche au premier démarrage — ou sur appui long simultané `BP1 + BP2` — un **QR Code compact** sur la dalle **Waveshare 1,54" E-Paper V2 (200×200 px)**.

#### Contrainte de densité sur 200×200 px
Pour garantir une lecture instantanée même en faible luminosité par CameraX + ML Kit, la chaîne encodée doit rester **inférieure à 30 caractères alphanumériques** afin de tenir dans un **QR Code Version 1 (21×21 modules)** ou **Version 2 (25×25 modules)** :

| Version QR | Modules | Capacité Alphanumérique (ECC-M) | Taille d'un module sur 168 px utiles (zone calme incluse) | Lisibilité |
|---|---|---|---|---|
| **Version 1** | 21 × 21 | 20 caractères | **8 × 8 pixels** (168×168 px + 16 px marge) | Excellente (recommandé) |
| **Version 2** | 25 × 25 | 38 caractères | **6 × 6 pixels** (150×150 px + 25 px marge) | Très bonne |

#### Format de la chaîne QR Code (`TP1`)
```text
TP1:<ID_BOITIER>:<CLE_PAIRING>
```
- **Préfixe fixe :** `TP1` (TrackPulse Protocole v1).
- **`<ID_BOITIER>` :** 2 à 8 caractères hexadécimaux / alphanumériques majuscules issus des derniers octets de l'UID STM32 (`HAL_GetUIDw0()`), ex. `8F3A`.
- **`<CLE_PAIRING>` :** code alphanumérique de 4 à 10 caractères (ex. `492015`, 6 chiffres) généré en usine ou stocké en Flash secteur 0.
- **Exemple réel (15 caractères → QR Version 1) :** `TP1:8F3A:492015`
- **Exemple mode test simulateur (sans matériel) :** `TP1:SIM1:123456`

#### Dérivation automatique BLE & MQTT côté application Android et STM32
À partir de `TP1:8F3A:492015`, l'application déduit automatiquement sans aucune saisie manuelle :
- **Nom publicitaire BLE RN4871 :** `TrackPulse-8F3A` (le scanner accepte également le préfixe générique `TrackPulse` et l'UUID Transparent UART).
- **Topics MQTT 4G (SIM7080G) :**
  - Uplink : `tp/8f3a/u`
  - Downlink : `tp/8f3a/d`
  - Alertes vol : `tp/8f3a/a`

#### Handshake optionnel d'appairage (`0x08`)
| Opcode | Nom | Sens | Payload | Description |
|---|---|---|---|---|
| `0x08` | `CMD_PAIR_HANDSHAKE` | App → MCU | **10 o** | Valide la clé lue sur QR Code auprès du STM32 (`pairingKey[10]` ASCII nul-terminé) |

---

## 5. Règles de Sécurité & Robustesse Firmware

1. **Timeout d'assemblage RX :** Si une trame commencée par `0xAA 0x55` n'est pas complète sous 250 ms sur `LPUART1`, le parseur STM32 réinitialise son automate d'état.
2. **Protection Buzzer :** Même si `repeat_count = 0` est envoyé, le firmware doit couper automatiquement `TIM2_CH1` (`CCR1 = 0`) après 30 s pour préserver la batterie et la bobine du CMT-8530S.
3. **Protection Flash NOR :** L'écriture en Flash s'interrompt proprement si `battery_mv < 3350 mV` et ferme la session active avant passage en `Stop 2`.
4. **Anti-ghosting E-Paper :** Après 10 rafraîchissements partiels (`0,3 s`) consécutifs (notamment en mode Circuit ou Mini-Jeu), déclencher automatiquement un rafraîchissement complet (`2,0 s`) pour préserver la dalle Waveshare 1.54" V2.
