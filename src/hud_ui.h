// =====================================================================
//  hud_ui.h  -  Disegno delle schermate (codice PORTABILE)
//
//  Questo modulo disegna SOLO su un GFXcanvas1 128x64 e non dipende da
//  BLE, Wire o dal driver SSD1306. Per questo lo stesso identico codice
//  viene compilato anche sul PC (tools/preview) per generare le anteprime
//  PNG: quello che vedi nelle preview e' quello che vedrai sull'OLED.
// =====================================================================
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <Adafruit_GFX.h>

// Dimensione icona di navigazione Chronos: 48x48, 1 bit per pixel,
// MSB first, 6 byte per riga (stesso formato di drawBitmap di Adafruit GFX)
#define HUD_ICON_SIZE       48
#define HUD_ICON_BYTES      288

// Punto della mini-mappa in METRI, sistema locale "heading-up":
//   origine = posizione del pilota, x = destra, y = avanti (direzione marcia)
struct MapPoint {
    int16_t x;
    int16_t y;
};

// Strada laterale (polilinea sottile)
struct MapSegment {
    const MapPoint *pts;
    uint8_t n;
};

// Tutti i dati che servono alle schermate di navigazione (gia' ripuliti in
// ASCII, vedi ui::utf8ToAscii). Stringhe vuote = dato non disponibile.
struct NavView {
    char distance[20];   // distanza alla prossima manovra, es. "350 m"
    char street[112];    // via / istruzione, es. "Via Roma"
    char eta[20];        // orario di arrivo, es. "10:42"
    char remaining[20];  // distanza alla destinazione, es. "12 km"
    char duration[20];   // tempo alla destinazione, es. "18 min"
    char speed[12];      // velocita' (Chronos la fornisce solo con OsmAnd)
    char clock[8];       // ora corrente, es. "10:24"
    bool hasIcon;        // icon[] valida
    uint8_t icon[HUD_ICON_BYTES];
    // Freccia disegnata se hasIcon e' falso. 0 dritto, 1-3 sinistra
    // (leggera, normale, stretta), 4-6 destra, 7 inversione, 8 rotonda,
    // 9 arrivo, 0x11..0x18 rotonda con uscita 1..8.
    uint8_t maneuver;
    bool stale;          // nessun aggiornamento da troppo tempo

    // Geometria opzionale per la mini-mappa (fase 2: GPX / polilinea).
    // Se route == nullptr o routeLen < 2 la mappa usa il fallback icona.
    const MapPoint *route;
    uint8_t routeLen;
    const MapSegment *branches;
    uint8_t branchCount;
    float metersPerPx;   // zoom mappa (<= 0 => default di config.h)
};

// ---------------------------------------------------------------------
//  Modalita' CURVA: solo forma della strada + distanza + grado della curva
// ---------------------------------------------------------------------
constexpr int CURVE_VIEW_MAX = 96;      // punti strada (5 m l'uno => 480 m)
constexpr int16_t CURVE_RIDER_X = 64;   // posizione pilota sullo schermo
constexpr int16_t CURVE_RIDER_Y = 57;
constexpr int16_t CURVE_AVAIL_UP = 52;  // pixel disponibili sopra il pilota (margine linea)
constexpr int16_t CURVE_AVAIL_SIDE = 58;// pixel disponibili ai lati

struct CurveView {
    float x[CURVE_VIEW_MAX];  // strada in metri heading-up, pilota in (0,0)
    float y[CURVE_VIEW_MAX];
    uint8_t n;
    float metersPerPx;        // zoom (auto)
    int32_t distance;         // metri all'inizio della prossima curva (<0 = nessuna)
    uint8_t grade;            // 1..6 (0 = nessuna curva)
    int8_t dir;               // -1 sinistra, +1 destra
    bool tightening;          // "STRINGE"
    bool longCurve;           // "LUNGA"
    bool demo;                // dati simulati: mostra "DEMO"
    const char *message;      // se non nullo: messaggio al posto della strada
};

namespace ui {

// Converte testo UTF-8 (es. "Via Nazionale - Citta' di Castello", "Città")
// in ASCII stampabile per i font GFX a 7 bit (à->a, è->e, ß->ss, ecc.).
void utf8ToAscii(const char *in, char *out, size_t outSize);

// Azzera una NavView
void clearNav(NavView &v);

// --- Schermate --------------------------------------------------------
void drawSplash(GFXcanvas1 &c, const char *bleName, const char *version);

// Nome del layout a tutto schermo (ICONA / MAPPA / CURVA), un attimo dopo il cambio.
void drawLayoutBanner(GFXcanvas1 &c, const char *name);

// "In attesa del telefono". lost = connessione appena persa.
void drawWaiting(GFXcanvas1 &c, const char *bleName, bool lost, uint32_t nowMs);

// Connesso ma senza navigazione: orologio grande.
// status = riga piccola in alto (es. "Chronos OK"), hint = riga in basso.
// phoneBatt < 0 => non mostrare la batteria del telefono.
void drawIdle(GFXcanvas1 &c, const char *clock, const char *date,
              const char *status, const char *hint, int phoneBatt);

// Navigazione layout ICONA: freccia 48x48 a sinistra, distanza grande,
// ETA piccola, via in basso (scorrevole se lunga).
void drawNavIcon(GFXcanvas1 &c, const NavView &v, uint32_t nowMs);

// Navigazione layout MAPPA (stile AR-Track): mini-mappa centrale 64 px con
// percorso spesso + laterali sottili + triangolo posizione, distanza sotto,
// colonne laterali con velocita' / ETA / orario.
void drawNavMap(GFXcanvas1 &c, const NavView &v, uint32_t nowMs);

// Disegna solo la mini-mappa dentro il riquadro (vx,vy,vw,vh).
// (ox,oy) = posizione del pilota in pixel. Punti in metri heading-up.
void drawMiniMap(GFXcanvas1 &c, int16_t vx, int16_t vy, int16_t vw, int16_t vh,
                 int16_t ox, int16_t oy,
                 const MapPoint *route, uint8_t routeLen,
                 const MapSegment *branches, uint8_t branchCount,
                 float metersPerPx);

// Modalita' CURVA (minimale, per guida sportiva)
void drawCurveScreen(GFXcanvas1 &c, const CurveView &v, uint32_t nowMs);

// Freccia "dritto" di riserva quando Chronos non ha ancora inviato l'icona
void drawFallbackArrow(GFXcanvas1 &c, int16_t x, int16_t y, int16_t size);

} // namespace ui
