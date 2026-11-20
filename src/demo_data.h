// =====================================================================
//  demo_data.h  -  Dati finti per DEMO_MODE e per le anteprime PNG
//  (stesso codice usato dal firmware e da tools/preview)
// =====================================================================
#pragma once
#include "hud_ui.h"

namespace demo {

// Strada tortuosa con incrocio: percorso (metri, heading-up)
extern const MapPoint ROUTE[];
extern const uint8_t ROUTE_LEN;
// Strade laterali (sottili)
extern const MapSegment BRANCHES[];
extern const uint8_t BRANCH_COUNT;

// Genera un'icona 48x48 1bpp "svolta a destra" nello stesso formato
// inviato da Chronos (6 byte/riga, MSB a sinistra).
void makeTurnRightIcon(uint8_t *icon288);

// Riempie una NavView di esempio. withMap = aggiunge la geometria demo.
void fillNav(NavView &v, const char *distance, const char *street, bool withMap);

} // namespace demo
