// =====================================================================
//  curve.cpp  -  Rilevamento curve (vedi curve.h)
// =====================================================================
#include "curve.h"
#include <math.h>
#include <string.h>

namespace curve {

namespace {
inline float len2(Vec2 a, Vec2 b) { float dx = b.x - a.x, dy = b.y - a.y; return sqrtf(dx * dx + dy * dy); }

// Curvatura con segno del cerchio per 3 punti: k = 2*cross / (|ab||bc||ca|)
// cross > 0 = svolta antioraria = SINISTRA (con x = destra/est, y = avanti/nord)
float circumCurvature(Vec2 a, Vec2 b, Vec2 c)
{
    float cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    float d = len2(a, b) * len2(b, c) * len2(c, a);
    if (d < 1e-6f) return 0.0f;
    return 2.0f * cross / d;
}
} // namespace

uint8_t gradeForRadius(float r)
{
    if (r < 20.0f) return 1;
    if (r < 40.0f) return 2;
    if (r < 70.0f) return 3;
    if (r < 120.0f) return 4;
    if (r < 200.0f) return 5;
    return 6;
}

float cornerSpeedKmh(float r, float aLat) { return sqrtf(aLat * r) * 3.6f; }

void GeoOrigin::set(double lat, double lon)
{
    lat0 = lat;
    lon0 = lon;
    cosLat0 = cos(lat * M_PI / 180.0);
}

Vec2 GeoOrigin::toLocal(double lat, double lon) const
{
    const double R = 6371000.0;
    double x = (lon - lon0) * M_PI / 180.0 * R * cosLat0;
    double y = (lat - lat0) * M_PI / 180.0 * R;
    return Vec2{(float)x, (float)y};
}

// ---------------------------------------------------------------------
void Route::begin()
{
    n_ = 0;
    nc_ = 0;
    ready_ = false;
    overflow_ = false;
    havePrev_ = false;
    carry_ = 0;
    haveGeo_ = false;
}

void Route::pushSample(Vec2 p)
{
    if (n_ < MAX_PTS) pts_[n_++] = p;
    else overflow_ = true;   // percorso troppo lungo: tronca
}

// Ricampionamento a passo costante lungo la polilinea
void Route::addPoint(Vec2 p)
{
    if (!havePrev_) {
        havePrev_ = true;
        prev_ = p;
        carry_ = 0;
        pushSample(p);
        return;
    }
    float seg = len2(prev_, p);
    if (seg < 1e-3f) return;
    float pos = 0;                         // metri gia' consumati sul segmento
    while (carry_ + (seg - pos) >= STEP_M) {
        pos += STEP_M - carry_;
        carry_ = 0;
        float t = pos / seg;
        pushSample(Vec2{prev_.x + (p.x - prev_.x) * t, prev_.y + (p.y - prev_.y) * t});
    }
    carry_ += seg - pos;
    prev_ = p;
}

void Route::addLatLon(double lat, double lon)
{
    if (!haveGeo_) { geo_.set(lat, lon); haveGeo_ = true; }
    addPoint(geo_.toLocal(lat, lon));
}

void Route::finish()
{
    computeCurvature();
    detectCurves();
    ready_ = n_ >= 2 * K + 2;
}

bool Route::setPolyline(const Vec2 *pts, int n)
{
    begin();
    for (int i = 0; i < n; i++) addPoint(pts[i]);
    finish();
    return ready_ && !overflow_;
}

// ---------------------------------------------------------------------
// Curvatura a finestra adattiva: si parte dalla finestra larga (K=6,
// corda +/-30 m: robusta al rumore, giusta per curve veloci) e si passa a
// finestre piu' corte solo se la curva e' abbastanza stretta da superare il
// rumore di quella finestra. Cosi' i tornanti (archi di 15-30 m, piu' corti
// della finestra larga) non vengono "ammorbiditi", e le curve ampie non
// vengono rese piu' strette dal rumore dei vertici GPX.
//   K=6 (+/-30 m) base | K=3 (+/-15 m) se R<100 | K=2 (+/-10 m) se R<45 |
//   K=1 (+/-5 m) se R<15
void Route::computeCurvature()
{
    static float raw[MAX_PTS];
    struct Win { int k; float kMin; };
    static const Win wins[] = {{6, 0.0f}, {3, 1.0f / 100}, {2, 1.0f / 45}, {1, 1.0f / 15}};
    for (int i = 0; i < n_; i++) {
        float best = 0;
        for (const Win &w : wins) {
            int a = i - w.k, c = i + w.k;
            if (a < 0 || c >= n_) continue;
            float k = circumCurvature(pts_[a], pts_[i], pts_[c]);
            if (fabsf(k) >= w.kMin && (w.kMin == 0.0f || fabsf(k) > fabsf(best))) best = k;
        }
        raw[i] = best;
    }
    // media mobile su 3 campioni: riduce il rumore dei vertici GPX
    for (int i = 0; i < n_; i++) {
        float s = raw[i];
        int cnt = 1;
        if (i > 0) { s += raw[i - 1]; cnt++; }
        if (i + 1 < n_) { s += raw[i + 1]; cnt++; }
        kappa_[i] = s / cnt;
    }
}

// Angolo di svolta dalla geometria: somma delle variazioni di direzione
// tra segmenti consecutivi da 2 campioni prima dell'inizio a 2 dopo la fine
// (gestisce anche i tornanti > 180 gradi).
float Route::turnAngleDeg(int s0, int e0) const
{
    int a = s0 - 2 < 0 ? 0 : s0 - 2;
    int b = e0 + 2 > n_ - 1 ? n_ - 1 : e0 + 2;
    float sum = 0;
    float hPrev = 0;
    bool have = false;
    for (int i = a; i < b; i++) {
        float h = atan2f(pts_[i + 1].x - pts_[i].x, pts_[i + 1].y - pts_[i].y);
        if (have) {
            float d = h - hPrev;
            while (d > (float)M_PI) d -= 2 * (float)M_PI;
            while (d < -(float)M_PI) d += 2 * (float)M_PI;
            sum += d;
        }
        hPrev = h;
        have = true;
    }
    return fabsf(sum) * 180.0f / (float)M_PI;
}

void Route::detectCurves()
{
    nc_ = 0;
    const float kEnter = 1.0f / R_ENTER, kExit = 1.0f / R_EXIT;
    int i = 0;
    while (i < n_ && nc_ < MAX_CURVES) {
        float k = kappa_[i];
        if (fabsf(k) < kEnter) { i++; continue; }
        // --- segmento grezzo: stessa direzione e |k| sopra la soglia di uscita
        const int sign = k > 0 ? 1 : -1;
        int a = i, b = i;
        while (b + 1 < n_ && kappa_[b + 1] * sign > kExit) b++;
        i = b + 1;

        // angolo totale = integrale della curvatura (anche le rampe)
        float angle = 0, peak = 0;
        int apex = a;
        for (int j = a; j <= b; j++) {
            angle += kappa_[j] * STEP_M;
            if (fabsf(kappa_[j]) > peak) { peak = fabsf(kappa_[j]); apex = j; }
        }
        float angleDeg = fabsf(angle) * 180.0f / (float)M_PI;
        // raggio "robusto": media dei campioni vicini al picco (>= 85%),
        // invece del solo picco che con il rumore sottostima il raggio
        float coreSum = 0;
        int coreN = 0;
        for (int j = a; j <= b; j++)
            if (fabsf(kappa_[j]) >= 0.85f * peak) { coreSum += fabsf(kappa_[j]); coreN++; }
        float kCore = coreN ? coreSum / coreN : peak;

        // --- raffinamento inizio/fine: il cerchio a 3 punti "vede" la curva
        // con K campioni di anticipo; il vero inizio e' dove |k| raggiunge
        // meta' del valore che avra' K campioni dopo (meta' rampa).
        int s0 = a;
        for (int j = a; j <= apex; j++) {
            int f = j + K < b ? j + K : b;
            if (fabsf(kappa_[j]) >= 0.5f * fabsf(kappa_[f])) { s0 = j; break; }
        }
        int e0 = b;
        for (int j = b; j >= apex; j--) {
            int f = j - K > a ? j - K : a;
            if (fabsf(kappa_[j]) >= 0.5f * fabsf(kappa_[f])) { e0 = j; break; }
        }
        if (e0 < s0) e0 = s0;

        Curve cv;
        cv.startS = s0 * STEP_M;
        cv.endS = e0 * STEP_M;
        cv.apexS = apex * STEP_M;
        cv.minRadius = kCore > 1e-6f ? 1.0f / kCore : 1e6f;
        cv.angleDeg = turnAngleDeg(s0, e0);
        (void)angleDeg;
        cv.dir = sign > 0 ? LEFT : RIGHT;
        cv.grade = gradeForRadius(cv.minRadius);
        cv.longCurve = false;
        cv.tightening = false;

        // --- unione con la curva precedente (stessa direzione, gap corto)
        if (nc_ > 0) {
            Curve &p = curves_[nc_ - 1];
            if (p.dir == cv.dir && cv.startS - p.endS < MERGE_GAP_M) {
                if (cv.minRadius < p.minRadius) { p.minRadius = cv.minRadius; p.apexS = cv.apexS; }
                p.endS = cv.endS;
                p.angleDeg = turnAngleDeg((int)(p.startS / STEP_M), (int)(p.endS / STEP_M));
                p.grade = gradeForRadius(p.minRadius);
                continue;
            }
        }
        curves_[nc_++] = cv;
    }

    // --- filtro curve trascurabili + attributi finali
    int w = 0;
    for (int c = 0; c < nc_; c++) {
        Curve cv = curves_[c];
        if (cv.angleDeg < MIN_ANGLE_DEG) continue;
        if (cv.endS - cv.startS < STEP_M) cv.endS = cv.startS + STEP_M;
        // "lunga": curva che gira tanto E dura tanto, oppure lunghissima
        cv.longCurve = (cv.angleDeg >= 120.0f && cv.length() >= LONG_CURVE_M) ||
                       cv.length() >= 2 * LONG_CURVE_M;
        // "stringe": curvatura media dell'ultimo 40% della curva > 1,35x
        // quella del primo 40% (le rampe d'ingresso/uscita si compensano)
        int s0 = (int)(cv.startS / STEP_M), e0 = (int)(cv.endS / STEP_M);
        int part = (e0 - s0 + 1) * 2 / 5;
        if (part >= 2) {
            float in = 0, out = 0;
            for (int j = 0; j < part; j++) {
                in += fabsf(kappa_[s0 + j]);
                out += fabsf(kappa_[e0 - j]);
            }
            cv.tightening = out > 1.35f * in;
        }
        curves_[w++] = cv;
    }
    nc_ = w;
}

// ---------------------------------------------------------------------
Vec2 Route::at(float s) const
{
    if (n_ == 0) return Vec2{0, 0};
    if (s <= 0) return pts_[0];
    float f = s / STEP_M;
    int i = (int)f;
    if (i >= n_ - 1) return pts_[n_ - 1];
    float t = f - i;
    return Vec2{pts_[i].x + (pts_[i + 1].x - pts_[i].x) * t,
                pts_[i].y + (pts_[i + 1].y - pts_[i].y) * t};
}

float Route::radiusAt(float s) const
{
    if (n_ == 0) return 1e6f;
    int i = (int)(s / STEP_M + 0.5f);
    if (i < 0) i = 0;
    if (i >= n_) i = n_ - 1;
    float k = fabsf(kappa_[i]);
    return k > 1e-6f ? 1.0f / k : 1e6f;
}

float Route::project(Vec2 p, int &hint, float *distOut) const
{
    if (n_ < 2) { if (distOut) *distOut = 0; return 0; }
    auto search = [&](int lo, int hi, float &bestD, float &bestS, int &bestI) {
        if (lo < 0) lo = 0;
        if (hi > n_ - 2) hi = n_ - 2;
        for (int i = lo; i <= hi; i++) {
            Vec2 a = pts_[i], b = pts_[i + 1];
            float vx = b.x - a.x, vy = b.y - a.y;
            float l2 = vx * vx + vy * vy;
            float t = l2 > 0 ? ((p.x - a.x) * vx + (p.y - a.y) * vy) / l2 : 0;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            float qx = a.x + vx * t - p.x, qy = a.y + vy * t - p.y;
            float d = qx * qx + qy * qy;
            if (d < bestD) { bestD = d; bestS = (i + t) * STEP_M; bestI = i; }
        }
    };
    float bestD = 1e30f, bestS = 0;
    int bestI = hint;
    search(hint - 10, hint + 40, bestD, bestS, bestI);   // vicino al precedente
    if (bestD > 30.0f * 30.0f) {                          // perso: ricerca globale
        bestD = 1e30f;
        search(0, n_ - 2, bestD, bestS, bestI);
    }
    hint = bestI;
    if (distOut) *distOut = sqrtf(bestD);
    return bestS;
}

int Route::nextCurve(float s) const
{
    for (int i = 0; i < nc_; i++)
        if (curves_[i].startS > s) return i;
    return -1;
}

} // namespace curve
