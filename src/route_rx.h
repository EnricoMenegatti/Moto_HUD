// =====================================================================
//  route_rx.h  -  Ricezione percorso via BLE (formato proprio, NON Chronos)
//
//  Il parser e' testato sul PC. Il servizio GATT si accende con
//  ROUTE_BLE_ENABLE. Il mittente e' tools/send_route.py (GPX + posizione):
//  Chronos non ha la geometria della strada.
//
//  Servizio GATT  : 8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00
//  Caratteristica : 8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00 (WRITE / WRITE_NR)
//  Ogni scrittura = 1 pacchetto; interi little-endian; lat/lon in gradi*1e7
//
//   0x01 BEGIN  : u8 0x01, u16 nPunti, i32 lat0, i32 lon0      (11 byte)
//                 inizia un percorso nuovo; lat0/lon0 = origine del piano locale.
//                 Quello gia' a schermo resta finche' END non chiude il conteggio.
//   0x02 POINTS : u8 0x02, u16 indicePrimo, u8 n, n x (i32 lat, i32 lon)
//                 i punti devono arrivare in ordine (indicePrimo = ricevuti)
//                 con MTU 247 stanno fino a 30 punti per pacchetto
//   0x03 END    : u8 0x03, u16 nPunti   -> sostituisce il percorso se il conteggio torna
//   0x04 POS    : u8 0x04, i32 lat, i32 lon, u16 vel (0,1 km/h), u16 rotta (0,1 gradi)
//                 posizione GPS del pilota (es. dal telefono), ~1-5 Hz
//   0x05 CLEAR  : u8 0x05   -> cancella il percorso
//   0x06 GUIDE  : u8 0x06, u8 manovra, u16 distM, u32 restM, u16 durS,
//                 u16 etaMin (minuti da mezzanotte, 0xFFFF = assente),
//                 u8 n, n byte UTF-8 della via (max 72)
//                 manovra: 0 dritto, 1-3 sinistra, 4-6 destra, 7 inversione,
//                 8 rotonda senza uscita, 9 arrivo,
//                 0x11..0x18 rotonda con uscita 1..8. Aggiorna icona e mappa, non la curva.
//   0x07 CTRL   : u8 0x07, u8 cmd, u8 arg
//                 cmd 1 = layout (0 icona, 1 mappa, 2 curva)
//                 cmd 2 = specchio orizzontale (0/1)
//                 cmd 3 = luminosita' 0..255
//                 cmd 4 = display acceso (0/1)
//   0x08 SIDES  : u8 0x08, u8 flags, u8 nTratti, poi per ogni tratto
//                 u8 nPunti (2..8) e nPunti x (i32 lat, i32 lon)
//                 flags bit0 = sostituisci (cancella le laterali gia' tenute).
//                 nTratti 0 con quel bit le toglie. Sono le diramazioni della mini-mappa.
// =====================================================================
#pragma once
#include <stdint.h>
#include <stddef.h>
#include "curve.h"

namespace curve {

enum class RxResult : uint8_t {
    Ok, RouteReady, Position, Cleared, Guide, Ctrl,
    ErrShort, ErrType, ErrSequence, ErrCount, ErrNoBegin
};

class RouteReceiver {
public:
    explicit RouteReceiver(Route &r) : route_(r) {}
    RxResult feed(const uint8_t *data, size_t len);

    bool loading() const { return loading_; }
    uint16_t received() const { return received_; }
    // Ultima posizione ricevuta
    bool hasPosition() const { return hasPos_; }
    double lat() const { return lat_; }
    double lon() const { return lon_; }
    float speedKmh() const { return speedKmh_; }
    float courseDeg() const { return courseDeg_; }

    // Ultima istruzione per i layout ICONA e MAPPA (pacchetto 0x06)
    bool hasGuide() const { return guideOk_; }
    uint8_t guideTurn() const { return guideTurn_; }
    int guideDistM() const { return guideDist_; }
    int guideRemainM() const { return guideRemain_; }
    int guideDurS() const { return guideDur_; }
    int guideEtaMin() const { return guideEta_; }
    const char *guideStreet() const { return guideStreet_; }

    uint8_t ctrlCmd() const { return ctrlCmd_; }
    uint8_t ctrlArg() const { return ctrlArg_; }

    // Diramazioni per la mini-mappa (pacchetto 0x08). Coordinate assolute.
    uint8_t sideCount() const { return sideCount_; }
    uint8_t sideLen(uint8_t i) const { return i < sideCount_ ? sideN_[i] : 0; }
    double sideLat(uint8_t i, uint8_t j) const { return sideLat_[i][j] / 1e7; }
    double sideLon(uint8_t i, uint8_t j) const { return sideLon_[i][j] / 1e7; }

    // Codifica (per test e per un futuro mittente): ritornano i byte scritti
    static size_t encodeBegin(uint8_t *out, uint16_t n, double lat0, double lon0);
    static size_t encodePoints(uint8_t *out, uint16_t first, const double *lat, const double *lon, uint8_t n);
    static size_t encodeEnd(uint8_t *out, uint16_t n);
    static size_t encodePos(uint8_t *out, double lat, double lon, float kmh, float course);

private:
    static constexpr int RX_MAX_PTS = 1000;   // la finestra del telefono non ne manda di piu'

    Route &route_;
    bool loading_ = false;
    uint16_t received_ = 0;
    int32_t originLat_ = 0, originLon_ = 0;
    int32_t rawLat_[RX_MAX_PTS] = {};
    int32_t rawLon_[RX_MAX_PTS] = {};
    bool hasPos_ = false;
    double lat_ = 0, lon_ = 0;
    float speedKmh_ = 0, courseDeg_ = 0;
    bool guideOk_ = false;
    uint8_t guideTurn_ = 0;
    int guideDist_ = -1;
    int guideRemain_ = 0;
    int guideDur_ = 0;
    int guideEta_ = -1;
    char guideStreet_[73] = {};
    uint8_t ctrlCmd_ = 0;
    uint8_t ctrlArg_ = 0;

    static constexpr int SIDE_SEGS = 14;
    static constexpr int SIDE_PTS = 8;
    uint8_t sideCount_ = 0;
    uint8_t sideN_[SIDE_SEGS] = {};
    int32_t sideLat_[SIDE_SEGS][SIDE_PTS] = {};
    int32_t sideLon_[SIDE_SEGS][SIDE_PTS] = {};
};

} // namespace curve
