// =====================================================================
//  test_curves.cpp  -  Test sul PC del rilevatore di curve
//  Compilato/eseguito da tools/render_previews.py (o a mano, vedi README)
// =====================================================================
#include <stdio.h>
#include <math.h>
#include <vector>
#include "curve.h"
#include "curve_view.h"
#include "route_rx.h"

using namespace curve;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("  FAIL: " __VA_ARGS__); printf("\n"); } } while (0)

static Route route, route2;   // grandi: statici

static void printTable(const Route &r)
{
    printf("  #  inizio(m)  fine(m)  lungh  dir  Rmin(m)  angolo  grado  flag      v.ind.\n");
    for (int i = 0; i < r.curveCount(); i++) {
        const Curve &c = r.curveAt(i);
        printf("  %-2d %8.0f %8.0f %6.0f   %s  %7.1f  %5.0f°   %s%d   %-8s %4.0f km/h\n", i + 1, c.startS, c.endS,
               c.length(), c.dir > 0 ? "DX" : "SX", c.minRadius, c.angleDeg, c.dir > 0 ? "D" : "S", c.grade,
               c.tightening && c.longCurve ? "STR+LUN" : c.tightening ? "STRINGE" : c.longCurve ? "LUNGA" : "",
               cornerSpeedKmh(c.minRadius));
    }
}

// rettilineo 200 m + arco (R, angolo) + rettilineo 200 m, un punto al metro
static void testArc(float R, float angDeg, int dir, uint8_t expGrade)
{
    std::vector<Vec2> pts;
    float x = 0, y = 0, h = 0;
    pts.push_back({x, y});
    auto go = [&](float len, float rad) {
        for (int i = 0; i < (int)(len + 0.5f); i++) {
            if (rad > 0) h += dir / rad;
            x += sinf(h); y += cosf(h);
            pts.push_back({x, y});
        }
    };
    go(200, 0);
    float arcLen = R * angDeg * (float)M_PI / 180.0f;
    go(arcLen, R);
    go(200, 0);
    route.setPolyline(pts.data(), (int)pts.size());
    printf("arco R=%5.0f m %3.0f° %s -> curve %d", R, angDeg, dir > 0 ? "DX" : "SX", route.curveCount());
    CHECK(route.curveCount() == 1, "attesa 1 curva, trovate %d", route.curveCount());
    if (route.curveCount() < 1) { printf("\n"); return; }
    const Curve &c = route.curveAt(0);
    printf("  Rmin %.1f  inizio %.0f (vero 200)  fine %.0f (vero %.0f)  angolo %.0f  grado %d\n",
           c.minRadius, c.startS, c.endS, 200 + arcLen, c.angleDeg, c.grade);
    CHECK(fabsf(c.minRadius - R) / R < (R <= 15 ? 0.20f : 0.12f), "raggio %.1f vs %.1f", c.minRadius, R);
    CHECK(fabsf(c.startS - 200) <= 10.0f, "inizio %.1f vs 200", c.startS);
    CHECK(fabsf(c.angleDeg - angDeg) <= 10.0f, "angolo %.1f vs %.1f", c.angleDeg, angDeg);
    CHECK(c.dir == dir, "direzione");
    CHECK(c.grade == expGrade, "grado %d vs %d", c.grade, expGrade);
}

int main()
{
    printf("=== 1) Archi sintetici ===\n");
    testArc(10, 90, -1, 1);
    testArc(15, 170, 1, 1);
    testArc(30, 90, 1, 2);
    testArc(55, 60, -1, 3);
    testArc(95, 60, 1, 4);
    testArc(160, 45, -1, 5);
    testArc(300, 30, 1, 6);

    printf("\n=== 2) Strada di montagna demo ===\n");
    buildMountainRoad(route);
    printf("  lunghezza %.0f m, %d campioni, %d curve\n", route.length(), route.size(), route.curveCount());
    printTable(route);
    // attese (vedi ROAD[] in curve_view.cpp)
    struct Exp { int dir; int grade; bool tight; bool lng; const char *name; };
    const Exp exp[] = {
        {1, 3, false, false, "destra 3"},
        {-1, 1, false, false, "tornante sx"},
        {1, 2, true, false, "destra che stringe"},
        {-1, 6, false, false, "sinistra 6"},
        {1, 3, false, false, "S destra"},
        {-1, 3, false, false, "S sinistra"},
        {1, 4, false, true, "lunga destra"},
        {1, 1, false, false, "tornante dx"},
        {-1, 5, false, false, "sinistra 5"},
    };
    const int ne = sizeof(exp) / sizeof(exp[0]);
    CHECK(route.curveCount() == ne, "attese %d curve, trovate %d", ne, route.curveCount());
    for (int i = 0; i < ne && i < route.curveCount(); i++) {
        const Curve &c = route.curveAt(i);
        CHECK(c.dir == exp[i].dir && c.grade == exp[i].grade, "%s: dir %d grado %d", exp[i].name, c.dir, c.grade);
        CHECK(c.tightening == exp[i].tight, "%s: stringe=%d", exp[i].name, c.tightening);
        CHECK(c.longCurve == exp[i].lng, "%s: lunga=%d", exp[i].name, c.longCurve);
    }

    printf("\n=== 3) Proiezione posizione ===\n");
    {
        int hint = 0;
        float maxErr = 0;
        for (float s = 0; s < route.length() - 10; s += 7.3f) {
            Vec2 p = route.at(s);
            Vec2 q = route.at(s + 1.0f);
            // spostamento laterale di 3 m (errore GPS)
            float dx = q.x - p.x, dy = q.y - p.y, l = sqrtf(dx * dx + dy * dy);
            Vec2 g{p.x + dy / l * 3.0f, p.y - dx / l * 3.0f};
            float sp = route.project(g, hint);
            float e = fabsf(sp - s);
            if (e > maxErr) maxErr = e;
        }
        printf("  errore massimo di proiezione (GPS spostato 3 m): %.1f m\n", maxErr);
        CHECK(maxErr < 6.0f, "proiezione");
    }

    printf("\n=== 4) Formato BLE (lat/lon) ===\n");
    {
        GeoOrigin g;
        g.set(45.70, 11.30);   // Prealpi venete
        const double mLat = 1.0 / (6371000.0 * M_PI / 180.0);
        const double mLon = mLat / cos(45.70 * M_PI / 180.0);
        std::vector<double> lat, lon;
        for (int i = 0; i < route.size(); i += 2) {        // 1 punto ogni 10 m
            Vec2 p = route.sample(i);
            lat.push_back(45.70 + p.y * mLat);
            lon.push_back(11.30 + p.x * mLon);
        }
        RouteReceiver rx(route2);
        uint8_t buf[256];
        rx.feed(buf, RouteReceiver::encodeBegin(buf, (uint16_t)lat.size(), lat[0], lon[0]));
        uint16_t sent = 0;
        int packets = 1;
        while (sent < lat.size()) {
            uint8_t n = (uint8_t)std::min<size_t>(30, lat.size() - sent);
            RxResult r = rx.feed(buf, RouteReceiver::encodePoints(buf, sent, &lat[sent], &lon[sent], n));
            CHECK(r == RxResult::Ok, "POINTS");
            sent += n;
            packets++;
        }
        RxResult r = rx.feed(buf, RouteReceiver::encodeEnd(buf, sent));
        packets++;
        CHECK(r == RxResult::RouteReady, "END");
        printf("  %u punti in %d pacchetti -> %d curve (locale: %d)\n", sent, packets, route2.curveCount(), route.curveCount());
        CHECK(route2.curveCount() == route.curveCount(), "conteggio curve lat/lon");
        for (int i = 0; i < route2.curveCount() && i < route.curveCount(); i++)
            CHECK(route2.curveAt(i).grade == route.curveAt(i).grade && route2.curveAt(i).dir == route.curveAt(i).dir,
                  "curva %d diversa", i + 1);
        r = rx.feed(buf, RouteReceiver::encodePos(buf, lat[10], lon[10], 63.5f, 12.3f));
        CHECK(r == RxResult::Position && fabsf(rx.speedKmh() - 63.5f) < 0.1f, "POS");
        int keepCurves = route2.curveCount();
        float keepLen = route2.length();
        rx.feed(buf, RouteReceiver::encodeBegin(buf, (uint16_t)lat.size(), lat[0], lon[0]));
        CHECK(rx.loading() && route2.ready() && route2.curveCount() == keepCurves &&
              fabsf(route2.length() - keepLen) < 0.1f, "durante il carico resta il percorso");
        rx.feed(buf, RouteReceiver::encodeEnd(buf, 1));
        CHECK(!rx.loading() && route2.ready() && route2.curveCount() == keepCurves, "END corto non cancella");
        // pacchetto fuori sequenza
        rx.feed(buf, RouteReceiver::encodeBegin(buf, 2, lat[0], lon[0]));
        CHECK(rx.feed(buf, RouteReceiver::encodePoints(buf, 5, &lat[0], &lon[0], 1)) == RxResult::ErrSequence, "sequenza");
        CHECK(route2.ready() && route2.curveCount() == keepCurves, "errore non cancella");
    }

    printf("\n=== 5) Vista CURVA ===\n");
    {
        CurveView v;
        buildView(route, route.curveAt(0).startS - 150, v);
        printf("  150 m prima della 1a curva: distanza %d, grado %d, dir %d, zoom %.2f m/px, %d punti\n",
               (int)v.distance, v.grade, v.dir, v.metersPerPx, v.n);
        CHECK(v.distance == 150 && v.grade == 3 && v.dir == 1, "vista");
    }

    printf("\n%s (%d errori)\n", failures ? "TEST FALLITI" : "TUTTI I TEST OK", failures);
    return failures ? 1 : 0;
}
