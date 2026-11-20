// =====================================================================
//  demo_data.cpp  -  Percorso e icona di esempio
// =====================================================================
#include "demo_data.h"
#include <string.h>

namespace demo {

// Il pilota e' in (0,0) e guarda verso +y. A 3 m/px la mappa mostra
// ~110 m davanti (36 px sopra il triangolo) e +/-96 m ai lati.
const MapPoint ROUTE[] = {
    {0, -30}, {0, 0}, {-3, 12}, {-12, 24}, {-21, 34}, {-23, 44},  // curva a sx
    {-15, 54}, {-2, 61}, {6, 68},                                  // controcurva
    {8, 76},                                                       // incrocio
    {28, 80}, {55, 82}, {100, 83},                                 // svolta a dx
};
const uint8_t ROUTE_LEN = sizeof(ROUTE) / sizeof(ROUTE[0]);

// Prosecuzione dritta oltre l'incrocio + strada a sinistra all'incrocio
static const MapPoint BRANCH_1[] = {{8, 76}, {6, 100}, {4, 130}};
static const MapPoint BRANCH_2[] = {{8, 76}, {-20, 73}, {-60, 68}, {-100, 66}};
// Stradina laterale a destra lungo la curva
static const MapPoint BRANCH_3[] = {{-12, 24}, {15, 30}, {60, 28}, {100, 30}};

const MapSegment BRANCHES[] = {
    {BRANCH_1, sizeof(BRANCH_1) / sizeof(BRANCH_1[0])},
    {BRANCH_2, sizeof(BRANCH_2) / sizeof(BRANCH_2[0])},
    {BRANCH_3, sizeof(BRANCH_3) / sizeof(BRANCH_3[0])},
};
const uint8_t BRANCH_COUNT = sizeof(BRANCHES) / sizeof(BRANCHES[0]);

void makeTurnRightIcon(uint8_t *icon)
{
    GFXcanvas1 c(48, 48);   // 48 px = 6 byte per riga: formato Chronos
    c.fillScreen(0);
    c.fillRect(10, 20, 10, 28, 1);              // asta verticale dal basso
    c.fillRoundRect(10, 14, 26, 10, 4, 1);      // braccio verso destra
    c.fillTriangle(34, 5, 34, 33, 47, 19, 1);   // punta della freccia
    memcpy(icon, c.getBuffer(), 288);
}

void fillNav(NavView &v, const char *distance, const char *street, bool withMap)
{
    ui::clearNav(v);
    strncpy(v.distance, distance, sizeof(v.distance) - 1);
    strncpy(v.street, street, sizeof(v.street) - 1);
    strcpy(v.eta, "10:42");
    strcpy(v.remaining, "12 km");
    strcpy(v.duration, "18 min");
    strcpy(v.speed, "52");
    strcpy(v.clock, "10:24");
    makeTurnRightIcon(v.icon);
    v.hasIcon = true;
    if (withMap) {
        v.route = ROUTE;
        v.routeLen = ROUTE_LEN;
        v.branches = BRANCHES;
        v.branchCount = BRANCH_COUNT;
        v.metersPerPx = 3.0f;
    }
}

} // namespace demo
