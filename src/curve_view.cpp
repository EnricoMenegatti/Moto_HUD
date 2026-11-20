// =====================================================================
//  curve_view.cpp
// =====================================================================
#include "curve_view.h"
#include <math.h>
#include <string.h>

namespace curve {

namespace {
const float LOOK_MIN = 150.0f, LOOK_MAX = 300.0f, LOOK_DEFAULT = 200.0f;
const float BEHIND = 15.0f;               // metri di strada dietro il pilota
const float MPP_MIN = 1.5f, MPP_MAX = 6.0f;
} // namespace

void buildView(const Route &r, float s, CurveView &v, float *zoomState)
{
    memset(&v, 0, sizeof(v));
    v.distance = -1;
    if (!r.ready()) { v.message = "NESSUN PERCORSO"; return; }

    // --- prossima curva e quanta strada mostrare
    int ci = r.nextCurve(s);
    float look = LOOK_DEFAULT;
    if (ci >= 0) {
        const Curve &c = r.curveAt(ci);
        float d = c.startS - s;
        look = c.endS - s + 25.0f;           // tutta la curva + un po' di uscita
        if (look < LOOK_MIN) look = LOOK_MIN;
        if (look > LOOK_MAX) look = LOOK_MAX;
        // arrotonda: 10 m sopra i 100 m, 5 m sotto (stile note)
        int di = (int)(d + 0.5f);
        v.distance = di >= 100 ? (di + 5) / 10 * 10 : (di + 2) / 5 * 5;
        v.grade = c.grade;
        v.dir = c.dir;
        v.tightening = c.tightening;
        v.longCurve = c.longCurve;
    }

    // --- sistema heading-up: avanti = direzione della strada nei prossimi 10 m
    Vec2 p = r.at(s);
    Vec2 q = (s + 10.0f <= r.length()) ? r.at(s + 10.0f) : p;
    Vec2 b = (s + 10.0f <= r.length()) ? p : r.at(s - 10.0f);
    float fx = q.x - b.x, fy = q.y - b.y;
    float fl = sqrtf(fx * fx + fy * fy);
    if (fl < 1e-3f) { fx = 0; fy = 1; } else { fx /= fl; fy /= fl; }
    const float rx = fy, ry = -fx;           // destra rispetto alla marcia

    float maxUp = 1, maxSide = 1;
    for (float t = s - BEHIND; t <= s + look && v.n < CURVE_VIEW_MAX; t += STEP_M) {
        if (t < 0) continue;
        if (t > r.length()) break;
        Vec2 a = r.at(t);
        float dx = a.x - p.x, dy = a.y - p.y;
        float lx = dx * rx + dy * ry;        // destra
        float ly = dx * fx + dy * fy;        // avanti
        v.x[v.n] = lx;
        v.y[v.n] = ly;
        v.n++;
        if (ly > maxUp) maxUp = ly;
        if (fabsf(lx) > maxSide) maxSide = fabsf(lx);
    }

    // --- auto-zoom: la strada visibile deve stare sullo schermo
    float mpp = maxUp / CURVE_AVAIL_UP;
    if (maxSide / CURVE_AVAIL_SIDE > mpp) mpp = maxSide / CURVE_AVAIL_SIDE;
    if (mpp < MPP_MIN) mpp = MPP_MIN;
    if (mpp > MPP_MAX) mpp = MPP_MAX;
    if (zoomState) {
        if (*zoomState <= 0) *zoomState = mpp;
        else *zoomState += (mpp - *zoomState) * 0.15f;   // zoom morbido
        mpp = *zoomState;
    }
    v.metersPerPx = mpp;
}

// ---------------------------------------------------------------------
//  Strada demo: primitive (rettilineo / arco) integrate ogni metro e
//  passate al Route come una traccia GPX con un vertice ogni ~6 m e un
//  piccolo rumore deterministico (+/-0,3 m) per realismo.
// ---------------------------------------------------------------------
namespace {
struct Prim { float len; float radius; int8_t dir; };   // radius 0 = dritto
// len per gli archi = angolo in gradi
const Prim ROAD[] = {
    {250, 0, 0},
    {75, 50, 1},              // destra 3 (R 50)
    {150, 0, 0},
    {170, 9, -1},             // tornante sinistro 1 (R 9)
    {120, 0, 0},
    {30, 90, 1}, {30, 55, 1}, {40, 32, 1},   // destra che STRINGE (90->32)
    {100, 0, 0},
    {25, 250, -1},            // sinistra 6 (R 250)
    {60, 0, 0},
    {50, 60, 1}, {15, 0, 0}, {50, 60, -1},   // S destra-sinistra 3
    {200, 0, 0},
    {160, 85, 1},             // lunga destra 4 (R 85, 160 gradi)
    {120, 0, 0},
    {165, 12, 1},             // tornante destro 1 (R 12)
    {80, 0, 0},
    {40, 150, -1},            // sinistra 5 (R 150)
    {200, 0, 0},
};
} // namespace

void buildMountainRoad(Route &r)
{
    r.begin();
    float x = 0, y = 0, h = 0;               // h = rotta (rad), 0 = nord
    uint32_t lcg = 12345;
    auto noise = [&]() {
        lcg = lcg * 1103515245u + 12345u;
        return ((lcg >> 16) & 0x7FFF) / 32767.0f * 0.6f - 0.3f;
    };
    float sinceEmit = 0;
    r.addPoint(Vec2{x, y});
    for (const Prim &p : ROAD) {
        float L = p.radius > 0 ? p.radius * p.len * (float)M_PI / 180.0f : p.len;
        int steps = (int)(L + 0.5f);
        for (int i = 0; i < steps; i++) {
            if (p.radius > 0) h += p.dir * (1.0f / p.radius);   // dx = rotta +
            x += sinf(h);
            y += cosf(h);
            if (++sinceEmit >= 6) {
                sinceEmit = 0;
                r.addPoint(Vec2{x + noise(), y + noise()});
            }
        }
    }
    r.addPoint(Vec2{x, y});
    r.finish();
}

// ---------------------------------------------------------------------
void DemoRider::step(const Route &r, float dt)
{
    const float VMAX = 70.0f / 3.6f, A_LAT = 3.5f, BRAKE_MS2 = 4.0f, ACCEL_MS2 = 2.0f;
    // velocita' massima ammessa ora per poter frenare in tempo
    float target = VMAX;
    float look = v * v / (2 * BRAKE_MS2) + 20.0f;
    for (float t = 0; t <= look; t += STEP_M) {
        float vc = sqrtf(A_LAT * r.radiusAt(s + t));
        float allowed = sqrtf(vc * vc + 2 * BRAKE_MS2 * t);
        if (allowed < target) target = allowed;
    }
    if (v < target) { v += ACCEL_MS2 * dt; if (v > target) v = target; }
    else            { v -= 6.0f * dt; if (v < target) v = target; }
    if (v < 3) v = 3;
    s += v * dt;
    if (s > r.length() - 20.0f) { s = 0; v = 15.0f; }   // ricomincia
}

} // namespace curve
