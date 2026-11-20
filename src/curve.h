// =====================================================================
//  curve.h  -  Rilevamento curve da polilinea del percorso (PORTABILE)
//
//  Pipeline:
//   1. polilinea (metri locali o lat/lon) -> ricampionata ogni STEP_M (5 m)
//   2. curvatura con raggio del cerchio per 3 punti (i-K, i, i+K) su
//      finestra scorrevole (K=3 => corda +/-15 m), poi media su 3 campioni
//   3. segmentazione: curva dove R < 400 m (isteresi: esce sopra 500 m o
//      al cambio di direzione), raffinamento di inizio/fine a meta' rampa
//   4. per ogni curva: inizio/fine/lunghezza, raggio minimo, angolo totale,
//      direzione, "stringe" (raggio che cala verso l'uscita), "lunga",
//      grado stile note da rally 1 (tornante) .. 6 (velocissima)
//
//  Nessuna dipendenza da Arduino: compila anche sul PC (tools/test_curves).
// =====================================================================
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace curve {

constexpr float STEP_M = 5.0f;          // passo di ricampionamento
constexpr int   K = 3;                  // semi-finestra (campioni) del cerchio 3 punti
constexpr int   MAX_PTS = 2400;         // 2400 x 5 m = 12 km di percorso
constexpr int   MAX_CURVES = 192;
constexpr float R_ENTER = 400.0f;       // sotto questo raggio inizia una curva
constexpr float R_EXIT = 500.0f;        // sopra questo raggio la curva finisce
constexpr float MIN_ANGLE_DEG = 12.0f;  // curve piu' "dolci" vengono ignorate
constexpr float MERGE_GAP_M = 10.0f;    // unisce curve stessa direzione separate da < 10 m
constexpr float LONG_CURVE_M = 80.0f;   // "lunga" se piu' lunga di 80 m

struct Vec2 {
    float x, y;   // metri: x = est, y = nord (o qualsiasi piano locale)
};

enum Dir : int8_t { LEFT = -1, RIGHT = 1 };

struct Curve {
    float startS;     // distanza lungo il percorso di inizio curva (m)
    float endS;       // fine curva (m)
    float apexS;      // punto di raggio minimo (m)
    float minRadius;  // raggio minimo stimato (m)
    float angleDeg;   // angolo totale di svolta (sempre positivo)
    int8_t dir;       // LEFT / RIGHT
    uint8_t grade;    // 1 (tornante) .. 6 (velocissima)
    bool tightening;  // "stringe": raggio che diminuisce verso l'uscita
    bool longCurve;   // "lunga"
    float length() const { return endS - startS; }
};

// Tabella raggio minimo -> grado (vedi README per la giustificazione)
//   1: R < 20 m   2: 20-40   3: 40-70   4: 70-120   5: 120-200   6: 200-400
uint8_t gradeForRadius(float r);
// Velocita' indicativa (km/h) per un raggio con accelerazione laterale a (m/s^2)
float cornerSpeedKmh(float r, float aLat = 3.5f);

// Conversione lat/lon -> metri locali (equirettangolare attorno a un'origine:
// errore trascurabile entro qualche decina di km)
struct GeoOrigin {
    double lat0, lon0, cosLat0;
    void set(double lat, double lon);
    Vec2 toLocal(double lat, double lon) const;
};

class Route {
public:
    // --- costruzione in streaming (non serve tenere la polilinea originale)
    void begin();
    void addPoint(Vec2 p);                 // punto della polilinea originale
    void addLatLon(double lat, double lon);// idem in gradi (origine = 1o punto)
    void finish();                         // calcola curvatura e curve
    bool setPolyline(const Vec2 *pts, int n);  // scorciatoia: begin+add+finish

    bool ready() const { return ready_; }
    int size() const { return n_; }
    float length() const { return n_ > 1 ? (n_ - 1) * STEP_M : 0.0f; }
    const GeoOrigin &origin() const { return geo_; }

    // --- interrogazioni
    Vec2 at(float s) const;                // punto interpolato a distanza s
    float radiusAt(float s) const;         // raggio stimato a distanza s (m)
    float signedCurvature(int i) const { return kappa_[i]; } // >0 sinistra
    Vec2 sample(int i) const { return pts_[i]; }
    // Proiezione di un punto sul percorso -> distanza s. hint = indice del
    // segmento della chiamata precedente (velocizza e evita salti).
    float project(Vec2 p, int &hint, float *distOut = nullptr) const;

    int curveCount() const { return nc_; }
    const Curve &curveAt(int i) const { return curves_[i]; }
    // Indice della prima curva che INIZIA dopo s (-1 se nessuna)
    int nextCurve(float s) const;

private:
    void computeCurvature();
    void detectCurves();
    float turnAngleDeg(int s0, int e0) const;
    void pushSample(Vec2 p);

    Vec2 pts_[MAX_PTS];
    float kappa_[MAX_PTS];
    int n_ = 0;
    Curve curves_[MAX_CURVES];
    int nc_ = 0;
    bool ready_ = false;
    bool overflow_ = false;
    // stato del ricampionamento
    bool havePrev_ = false;
    Vec2 prev_{0, 0};
    float carry_ = 0;      // metri percorsi dall'ultimo campione emesso
    bool haveGeo_ = false;
    GeoOrigin geo_{0, 0, 1};
};

} // namespace curve
