// =====================================================================
//  curve_view.h  -  Dal percorso (Route) alla schermata CURVA + demo
//  (PORTABILE: usato dal firmware, dalle anteprime e dai test sul PC)
// =====================================================================
#pragma once
#include "curve.h"
#include "hud_ui.h"

namespace curve {

// Riempie la CurveView per un pilota alla distanza s lungo il percorso:
// strada heading-up davanti (150-300 m, quanto basta per far stare la
// prossima curva), auto-zoom, distanza all'inizio curva e grado.
// zoomState (opzionale): memoria per ammorbidire lo zoom tra i frame.
void buildView(const Route &r, float s, CurveView &v, float *zoomState = nullptr);

// Strada di montagna dimostrativa (~2,1 km): rettilinei, destra 3,
// tornante sinistro 1, destra che stringe, S, lunga destra, tornante destro...
void buildMountainRoad(Route &r);

// Pilota simulato: ~70 km/h sul dritto, rallenta prima delle curve
// (accelerazione laterale 3,5 m/s^2, frenata 4 m/s^2) e riparte da capo.
struct DemoRider {
    float s = 0;        // metri lungo il percorso
    float v = 15.0f;    // m/s
    void reset() { s = 0; v = 15.0f; }
    void step(const Route &r, float dt);
    float speedKmh() const { return v * 3.6f; }
};

} // namespace curve
