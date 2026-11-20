// =====================================================================
//  config.h  -  Configurazione HUD-Moto firmware v1
//  Tutte le opzioni "da smanettare" stanno qui.
// =====================================================================
#pragma once

// ---------------------------------------------------------------------
//  Versione
// ---------------------------------------------------------------------
#define HUD_FW_VERSION      "1.1.0"

// ---------------------------------------------------------------------
//  Bluetooth
// ---------------------------------------------------------------------
// Nome BLE con cui la scheda appare nell'app Chronos
#define HUD_BLE_NAME        "HUD-Moto"

// ---------------------------------------------------------------------
//  Pin I2C (scelti automaticamente in base all'ambiente PlatformIO,
//  ma sovrascrivibili con -DPIN_SDA=.. -DPIN_SCL=..)
// ---------------------------------------------------------------------
#ifndef PIN_SDA
  #if defined(HUD_BOARD_XIAO_C3)
    #define PIN_SDA         6       // XIAO ESP32-C3: pin D4 = GPIO6
    #define PIN_SCL         7       // XIAO ESP32-C3: pin D5 = GPIO7
  #else
    #define PIN_SDA         21      // ESP32 DevKit classico
    #define PIN_SCL         22
  #endif
#endif

// Pulsante opzionale per cambiare layout (icona <-> mini-mappa).
// Di default il tasto BOOT gia' presente sulla scheda (attivo basso).
// Metti -1 per disabilitarlo.
#ifndef PIN_BUTTON
  #if defined(HUD_BOARD_XIAO_C3)
    #define PIN_BUTTON      9       // tasto BOOT della XIAO C3
  #else
    #define PIN_BUTTON      0       // tasto BOOT della DevKit
  #endif
#endif

// Frequenza bus I2C. 100 kHz = piu' robusto con cavi lunghi / disturbi
// (un frame completo ~95 ms). Con cavi corti si puo' provare 400000.
#define I2C_HZ              100000UL

// ---------------------------------------------------------------------
//  Display OLED SSD1306 128x64
// ---------------------------------------------------------------------
#define OLED_ADDR           0x3C    // alcuni moduli usano 0x3D
#define OLED_WIDTH          128
#define OLED_HEIGHT         64
#define OLED_RESET_PIN      -1      // -1 = nessun pin di reset (moduli 4 pin)

// Specchiatura orizzontale dell'immagine:
//   0 = visione diretta del display (default)
//   1 = visione tramite beam splitter / vetrino riflettente (immagine
//       ribaltata destra-sinistra, cosi' riflessa si legge dritta)
#define MIRROR_X            0

// Ribaltamento verticale (utile se il display e' montato capovolto o se la
// geometria del combinatore ribalta alto/basso). MIRROR_X + MIRROR_Y = 180°
#define MIRROR_Y            0

// Inversione colori hardware (0 = testo bianco su nero, consigliato per HUD:
// il nero e' "trasparente" nel riflesso; 1 = nero su bianco)
#define INVERT              0

// Luminosita' 0..255. 255 e' il massimo che questo OLED a 3.3 V regge.
// Il registro del chip a 0xFF fa cedere il charge pump e il pannello sparisce:
// qui 255 corrisponde al contrasto 0xCF consigliato dal datasheet, con
// precharge 0xF1 e VCOMH 0x40. Piu' in basso si abbassa solo la corrente
// dei pixel; VCOMH non va mai a 0, senno' il tratto acceso non si vede.
#define OLED_CONTRAST       255
// 0xF1 = calcolalo da OLED_CONTRAST. Per forzarlo: nibble alto 1..F.
#define OLED_PRECHARGE      0xF1
// 0x40 = calcolalo da OLED_CONTRAST. Valori del chip: 0x20, 0x30, 0x40.
#define OLED_VCOMH          0x40

// ---------------------------------------------------------------------
//  Interfaccia
// ---------------------------------------------------------------------
// Layout all'avvio (il tasto BOOT li cicla: ICONA -> MAPPA -> CURVA):
//   0 = ICONA  (freccia 48x48 grande a sinistra + distanza + via)
//   1 = MAPPA  (stile AR-Track: mini-mappa centrale + colonne laterali)
//   2 = CURVA  (guida sportiva: solo forma strada + distanza + grado curva)
#define NAV_LAYOUT_DEFAULT  0

// Formato orologio: 1 = 24h (consigliato). Chronos puo' sovrascriverlo.
#define CLOCK_24H           1

// Durata splash all'avvio (ms)
#define SPLASH_MS           1800

// Nome del layout a tutto schermo dopo una pressione breve di BOOT (ms)
#define LAYOUT_BANNER_MS    1000

// Intervallo minimo tra due refresh del display (ms). Con I2C a 100 kHz
// un frame impiega ~95 ms, quindi sotto i ~100 ms non ha senso scendere.
#define FRAME_MS            120

// Velocita' di scorrimento del testo della via (pixel al secondo)
#define SCROLL_PX_PER_S     30
// Pausa a inizio/fine scorrimento (ms)
#define SCROLL_PAUSE_MS     1500

// Se non arrivano aggiornamenti di navigazione per questo tempo (ms) viene
// mostrato un piccolo indicatore "dato vecchio". 0 = disabilitato.
#define NAV_STALE_MS        30000UL

// Mini-mappa: metri per pixel (zoom). 3 m/px => ~110 m visibili davanti.
#define MAP_METERS_PER_PX   3.0f

// Modalita' demo: 1 = non serve il telefono, cicla schermate finte
// (utile per provare ottica/luminosita' al banco). 0 = normale.
#define DEMO_MODE           0

// Modalita' CURVA senza percorso: strada di montagna dimostrativa
// (scritta "DEMO"). Appena arriva un GPX via BLE (tools/send_route.py)
// la demo si spegne e si usa quel percorso. 0 = "NESSUN PERCORSO".
#define CURVE_DEMO_IF_NO_ROUTE 1

// Servizio BLE del percorso (formato in src/route_rx.h). Chronos non
// invia la geometria: la manda tools/send_route.py, anche mentre Chronos
// resta connesso per icona e mappa. 0 = disattivato.
#define ROUTE_BLE_ENABLE    1

// Fallback sperimentale per Waze: se Waze non viene letto come navigazione
// nativa da Chronos, prova a usare le sue NOTIFICHE (solo testo, niente
// freccia). Richiede Waze abilitato nella lista notifiche di Chronos.
#define WAZE_NOTIFICATION_FALLBACK 1
