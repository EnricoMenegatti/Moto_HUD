// =====================================================================
//  HUD-Moto firmware v1
//  ESP32 + OLED SSD1306 128x64 + app Android "Chronos" (libreria
//  ChronosESP32). Mostra frecce/distanza/via di Google Maps sull'HUD.
//
//  Flusso:  SPLASH -> ATTESA TELEFONO -> (connesso) OROLOGIO
//                                      -> (navigazione attiva) NAV
//  Il tasto BOOT: pressione breve = cambia layout (icona -> mappa -> curva)
//                 pressione lunga (>1.5 s) = attiva/disattiva MIRROR_X
//  Le scelte del tasto vengono salvate in NVS (Preferences).
// =====================================================================
#include <Arduino.h>
#include <math.h>
#include <ChronosESP32.h>
#include <Preferences.h>

#include "config.h"
#include "display.h"
#include "hud_ui.h"
#include "demo_data.h"
#include "curve.h"
#include "curve_view.h"
#include "route_rx.h"
#if ROUTE_BLE_ENABLE
#include <NimBLEDevice.h>
#endif

// ---------------------------------------------------------------------
//  Stato globale
// ---------------------------------------------------------------------
static ChronosESP32 watch(HUD_BLE_NAME);
static Preferences prefs;

enum class Screen : uint8_t { Splash, Waiting, Idle, Nav };
enum NavLayout : uint8_t { LAYOUT_ICON = 0, LAYOUT_MAP = 1, LAYOUT_CURVE = 2, LAYOUT_COUNT = 3 };

static uint8_t navLayout = NAV_LAYOUT_DEFAULT;
static uint32_t layoutBannerMs = 0; // millis() dell'ultimo cambio layout (0 = nessuno)
static NavView nav;                 // dati di navigazione pronti per la UI
static bool everConnected = false;  // per distinguere "attesa" da "persa"
static uint32_t lastNavMs = 0;      // ultimo aggiornamento navigazione
static uint32_t bootMs = 0;

// Flag impostati dai callback BLE (task NimBLE) e letti nel loop().
// Nei callback NON si disegna e non si copiano String: solo flag.
static volatile bool navDirty = false;
static volatile bool iconDirty = false;
static volatile uint32_t iconCrcDone = 0xFFFFFFFF;
static volatile bool connChanged = false;

// Modalita' CURVA: percorso (demo o ricevuto via BLE) + pilota
static curve::Route curveRoute;           // ~36 KB statici
static curve::DemoRider demoRider;
static bool curveRouteIsDemo = false;
static float curveZoom = 0;               // zoom ammorbidito tra i frame
static int curveHint = 0;                 // indice per la proiezione GPS
static curve::RouteReceiver routeRx(curveRoute);
static bool routeNavCache = false;        // mini-mappa gia' disegnata, anche durante un rinvio
static MapPoint routeMapPts[40];
static MapPoint branchPts[14][8];
static MapSegment branchSeg[14];

#if ROUTE_BLE_ENABLE
// I pacchetti BLE arrivano nel task NimBLE: li accodiamo e li elaboriamo
// nel loop() (il Route non va toccato da due task insieme).
struct RoutePacket { uint16_t len; uint8_t data[244]; };
static QueueHandle_t routeQueue = nullptr;
static const char *ROUTE_SVC_UUID = "8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00";
static const char *ROUTE_CHR_UUID = "8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00";

class RouteRxCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &info) override
    {
        (void)info;
        NimBLEAttValue v = c->getValue();
        RoutePacket pk;
        pk.len = v.length() > sizeof(pk.data) ? sizeof(pk.data) : v.length();
        memcpy(pk.data, v.data(), pk.len);
        if (routeQueue) xQueueSend(routeQueue, &pk, 0);   // se piena: scarta
    }
};
static RouteRxCallbacks routeRxCallbacks;

// Aggiunge il servizio percorso al server GATT creato da ChronosESP32.
// L'annuncio si spegne anche all'inizio di un collegamento, prima che il
// client sia contato. Riaccenderlo in quel momento chiude il collegamento
// (Chrome e Windows: GATT aperto e subito chiuso). Si riaccende solo quando
// l'annuncio e' davvero terminato, oppure dopo un client gia' stabilito.
static void onAdvFinished(NimBLEAdvertising *adv)
{
    NimBLEServer *rs = NimBLEDevice::getServer();
    uint8_t n = rs ? rs->getConnectedCount() : 0;
    Serial.printf("[%lu] BLE annuncio terminato peers=%u\n", millis(), n);
    if (n == 0) {
        bool ok = adv->start();
        Serial.printf("[%lu] BLE riavvio annuncio=%d\n", millis(), ok);
    }
}

// NimBLE 2.x permette di aggiungere servizi dopo l'avvio: start() rigenera
// la tabella GATT (solo senza connessioni attive, cioe' subito dopo begin).
static void setupRouteBle()
{
    routeQueue = xQueueCreate(24, sizeof(RoutePacket));
    NimBLEServer *srv = NimBLEDevice::getServer();
    if (!srv || !routeQueue) return;
    NimBLEService *svc = srv->createService(ROUTE_SVC_UUID);
    NimBLECharacteristic *chr = svc->createCharacteristic(
        ROUTE_CHR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    chr->setCallbacks(&routeRxCallbacks);
    // Chronos annuncia un intervallo di connessione da 7,5 ms (per iPhone).
    // Windows, con Chrome, rifiuta quel valore: "Connection attempt failed".
    // Si ricostruisce l'annuncio con 100-160 ms: il telefono continua a collegarsi.
    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->stop();
    adv->clearData();
    adv->enableScanResponse(true);
    adv->addServiceUUID(CS_SERVICE_UUID);
    adv->setPreferredParams(0x50, 0x80);
    adv->setName(HUD_BLE_NAME);
    adv->setAdvertisingCompleteCallback(onAdvFinished);
    srv->advertiseOnDisconnect(true);
    bool gattOk = srv->start();
    bool advOk = adv->start();
    Serial.printf("[%lu] ROUTE gatt=%d annuncio=%d\n", millis(), gattOk, advOk);
}
#endif

// Stato della navigazione Chronos (copiato nel loop)
static bool navActive = false;      // Chronos sta inviando navigazione
static bool navDisabled = false;    // Chronos risponde "Disabled"

#if WAZE_NOTIFICATION_FALLBACK
// Fallback sperimentale: notifiche Waze trasformate in testo
static volatile bool wazeDirty = false;
static String wazeTitle, wazeMsg;   // scritti dal callback, letti nel loop
static portMUX_TYPE wazeMux = portMUX_INITIALIZER_UNLOCKED;
static uint32_t wazeLastMs = 0;
static bool wazeActive = false;
static const uint32_t WAZE_TIMEOUT_MS = 45000;
#endif

// ---------------------------------------------------------------------
//  Callback ChronosESP32 (girano nel task BLE)
// ---------------------------------------------------------------------
static void onConnection(bool state)
{
    Serial.printf("[%lu] BLE %s\n", millis(), state ? "connesso" : "disconnesso");
    if (state) {
        everConnected = true;
#if ROUTE_BLE_ENABLE
        // Un secondo programma (telefono o PC) puo' entrare solo se l'annuncio
        // torna attivo. Ormai il client e' contato, riaccenderlo non lo chiude.
        NimBLEServer *rs = NimBLEDevice::getServer();
        NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
        if (rs && adv && rs->getConnectedCount() < 3 && !adv->isAdvertising()) {
            bool ok = adv->start();
            Serial.printf("[%lu] BLE annuncio per altro client=%d\n", millis(), ok);
        }
#endif
    }
    connChanged = true;
    navDirty = true;    // alla disconnessione la lib azzera la navigazione
}

static void onConfig(Config config, uint32_t a, uint32_t b)
{
    switch (config) {
    case CF_NAV_DATA:            // a = 1 attiva / 0 inattiva
        navDirty = true;
        break;
    case CF_NAV_ICON:            // a = indice blocco (0..2), b = CRC icona
        if (a == 2) {            // ultimo dei 3 blocchi da 96 byte
            iconCrcDone = b;
            iconDirty = true;
        }
        break;
    default:
        break;
    }
}

#if WAZE_NOTIFICATION_FALLBACK
static void onNotification(Notification n)
{
    String app = n.app;
    app.toLowerCase();
    if (app.indexOf("waze") < 0) return;
    portENTER_CRITICAL(&wazeMux);
    wazeTitle = n.title;   // nota: assegnazione String (alloca) in sezione
    wazeMsg = n.message;   // critica breve; accettabile per testi corti
    portEXIT_CRITICAL(&wazeMux);
    wazeDirty = true;
}
#endif

// ---------------------------------------------------------------------
//  Utilita'
// ---------------------------------------------------------------------
static void toAscii(const String &s, char *out, size_t n)
{
    ui::utf8ToAscii(s.c_str(), out, n);
}

static void currentClock(char *out, size_t n)
{
    snprintf(out, n, "%s:%s", watch.getHourZ().c_str(), watch.getTime("%M").c_str());
}

static bool timeValid() { return watch.getYear() >= 2024; }

static void currentDate(char *out, size_t n)
{
    static const char *days[] = {"Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"};
    static const char *months[] = {"gen", "feb", "mar", "apr", "mag", "giu",
                                   "lug", "ago", "set", "ott", "nov", "dic"};
    int dow = watch.getDayofWeek();
    int mon = watch.getMonth();
    snprintf(out, n, "%s %d %s", days[dow % 7], watch.getDay(), months[mon % 12]);
}

// Copia lo stato di navigazione della libreria nella NavView della UI
static void pullNavigation()
{
    const Navigation &n = watch.getNavigation();
    navActive = n.active && n.isNavigation;
    navDisabled = n.active && !n.isNavigation && n.duration == "Disabled";
    if (!navActive) return;

    // Campi Chronos (vedi ChronosESP32.h):
    //   title      = distanza alla prossima manovra (es. "350 m")
    //   directions = via / istruzione
    //   distance   = distanza alla destinazione, duration = tempo residuo
    //   eta        = orario di arrivo, speed = velocita' (solo OsmAnd)
    toAscii(n.title, nav.distance, sizeof(nav.distance));
    toAscii(n.directions, nav.street, sizeof(nav.street));
    toAscii(n.distance, nav.remaining, sizeof(nav.remaining));
    toAscii(n.duration, nav.duration, sizeof(nav.duration));
    toAscii(n.eta, nav.eta, sizeof(nav.eta));
    toAscii(n.speed, nav.speed, sizeof(nav.speed));
    if (!n.hasIcon) nav.hasIcon = false;   // Chronos dice: nessuna icona
    nav.maneuver = 0;
    lastNavMs = millis();
}

// L'icona arriva in 3 blocchi: la copiamo solo quando e' completa e il CRC
// corrisponde a quello annunciato nei dati di navigazione. Finche' la nuova
// non e' completa resta visibile la precedente (niente sfarfallio).
static void pullIcon()
{
    const Navigation &n = watch.getNavigation();
    if (n.hasIcon && iconCrcDone == n.iconCRC) {
        memcpy(nav.icon, n.icon, HUD_ICON_BYTES);
        nav.hasIcon = true;
    }
}

#if WAZE_NOTIFICATION_FALLBACK
// Estrae "350 m" / "1,2 km" da un testo libero, se presente
static bool findDistance(const char *s, char *out, size_t n)
{
    for (const char *p = s; *p; p++) {
        if (*p < '0' || *p > '9') continue;
        if (p != s && ((p[-1] >= '0' && p[-1] <= '9') || p[-1] == '.' || p[-1] == ',')) continue;
        const char *q = p;
        while ((*q >= '0' && *q <= '9') || *q == '.' || *q == ',') q++;
        const char *u = q;
        while (*u == ' ') u++;
        int ulen = 0;
        if (u[0] == 'k' && u[1] == 'm') ulen = 2;
        else if (u[0] == 'm' && !(u[1] >= 'a' && u[1] <= 'z')) ulen = 1;
        if (!ulen) continue;
        snprintf(out, n, "%.*s %.*s", (int)(q - p), p, ulen, u);
        return true;
    }
    return false;
}

static void pullWaze()
{
    String t, m;
    portENTER_CRITICAL(&wazeMux);
    t = wazeTitle;
    m = wazeMsg;
    portEXIT_CRITICAL(&wazeMux);
    char title[112], msg[112];
    toAscii(t, title, sizeof(title));
    toAscii(m, msg, sizeof(msg));

    ui::clearNav(nav);
    if (!findDistance(title, nav.distance, sizeof(nav.distance)))
        findDistance(msg, nav.distance, sizeof(nav.distance));
    snprintf(nav.street, sizeof(nav.street), "%s%s%s", title, (title[0] && msg[0]) ? " - " : "", msg);
    nav.hasIcon = false;          // Waze via notifica: nessuna freccia
    wazeLastMs = lastNavMs = millis();
    wazeActive = true;
}
#endif

static const char *layoutName(uint8_t layout)
{
    static const char *names[] = {"ICONA", "MAPPA", "CURVA"};
    return names[layout < LAYOUT_COUNT ? layout : 0];
}

// Comandi dall'app (pacchetto 0x07). Stesso effetto del tasto BOOT, piu' la luce.
static void applyHudControl(uint8_t cmd, uint8_t arg)
{
    switch (cmd) {
    case 1:
        if (arg >= LAYOUT_COUNT) return;
        navLayout = arg;
        prefs.putUChar("layout", navLayout);
        layoutBannerMs = millis();
        Serial.printf("[HUD] layout = %s\n", layoutName(navLayout));
        break;
    case 2: {
        bool mx = arg != 0;
        hud::setMirror(mx, MIRROR_Y);
        prefs.putBool("mirrorX", mx);
        Serial.printf("[HUD] MIRROR_X = %d\n", mx);
        break;
    }
    case 3:
        hud::setContrast(arg);
        prefs.putUChar("bright", arg);
        break;
    case 4:
        hud::displayPower(arg != 0);
        Serial.printf("[HUD] display %s\n", arg ? "on" : "off");
        break;
    default:
        break;
    }
}

// ---------------------------------------------------------------------
//  Tasto BOOT
// ---------------------------------------------------------------------
static void handleButton()
{
#if PIN_BUTTON >= 0
    static bool wasDown = false;
    static uint32_t downMs = 0;
    static bool longDone = false;
    bool down = digitalRead(PIN_BUTTON) == LOW;
    uint32_t now = millis();
    if (down && !wasDown) { downMs = now; longDone = false; }
    if (down && !longDone && now - downMs > 1500) {
        longDone = true;
        bool mx = !hud::mirrorXEnabled();
        hud::setMirror(mx, MIRROR_Y);
        prefs.putBool("mirrorX", mx);
        Serial.printf("[BTN] MIRROR_X = %d\n", mx);
    }
    if (!down && wasDown && !longDone && now - downMs > 40) {
        navLayout = (navLayout + 1) % LAYOUT_COUNT;   // icona -> mappa -> curva
        prefs.putUChar("layout", navLayout);
        layoutBannerMs = now;
        Serial.printf("[BTN] layout = %s\n", layoutName(navLayout));
    }
    wasDown = down;
#endif
}

// ---------------------------------------------------------------------
//  Rendering
// ---------------------------------------------------------------------
static void drawNav(GFXcanvas1 &c, uint32_t now)
{
    currentClock(nav.clock, sizeof(nav.clock));
    nav.stale = NAV_STALE_MS > 0 && (now - lastNavMs) > NAV_STALE_MS;
    // Fase 1: Chronos/Google Maps forniscono solo l'icona, nessuna
    // geometria stradale => la mappa usa il fallback icona al centro.
    nav.route = nullptr;
    nav.routeLen = 0;
    nav.branches = nullptr;
    nav.branchCount = 0;
    if (navLayout == LAYOUT_MAP) ui::drawNavMap(c, nav, now);
    else                         ui::drawNavIcon(c, nav, now);
}

// Distanza in metri -> "350 m" / "12.4 km"
static void fmtMeters(char *out, size_t n, int meters)
{
    if (meters < 0) { if (n) out[0] = 0; return; }
    if (meters < 1000) snprintf(out, n, "%d m", meters);
    else snprintf(out, n, "%d.%d km", meters / 1000, (meters % 1000) / 100);
}

static void fmtMinutes(char *out, size_t n, int sec)
{
    if (sec <= 0) { if (n) out[0] = 0; return; }
    int min = (sec + 30) / 60;
    if (min < 1) min = 1;
    if (min < 60) snprintf(out, n, "%d min", min);
    else snprintf(out, n, "%dh%02d", min / 60, min % 60);
}

// ICONA e MAPPA dalla strada dell'app: freccia della manovra, via,
// distanza, e mini-mappa heading-up. Non dipende da Chronos.
static float riderS()
{
    if (!curveRoute.ready() || !routeRx.hasPosition()) return -1.0f;
    curve::Vec2 p = curveRoute.origin().toLocal(routeRx.lat(), routeRx.lon());
    return curveRoute.project(p, curveHint);
}

static void fillRouteMap(float s)
{
    using namespace curve;
    Vec2 p = curveRoute.at(s);
    float ahead = s + 12.0f <= curveRoute.length() ? s + 12.0f : s;
    float back = s > 12.0f ? s - 12.0f : s;
    Vec2 q = curveRoute.at(ahead);
    Vec2 b = curveRoute.at(back);
    float fx = q.x - b.x, fy = q.y - b.y;
    float fl = sqrtf(fx * fx + fy * fy);
    if (fl < 1e-3f) { fx = 0; fy = 1; }
    else { fx /= fl; fy /= fl; }
    const float rx = fy, ry = -fx;
    uint8_t n = 0;
    for (float t = s - 20.0f; t <= s + 170.0f && n < 40; t += 8.0f) {
        if (t < 0) continue;
        if (t > curveRoute.length()) break;
        Vec2 a = curveRoute.at(t);
        float dx = a.x - p.x, dy = a.y - p.y;
        routeMapPts[n].x = (int16_t)(dx * rx + dy * ry);
        routeMapPts[n].y = (int16_t)(dx * fx + dy * fy);
        n++;
    }
    nav.route = n >= 2 ? routeMapPts : nullptr;
    nav.routeLen = n >= 2 ? n : 0;
    uint8_t nb = 0;
    uint8_t sc = routeRx.sideCount();
    for (uint8_t si = 0; si < sc && nb < 14; si++) {
        uint8_t np = routeRx.sideLen(si);
        uint8_t m = 0;
        for (uint8_t j = 0; j < np && m < 8; j++) {
            Vec2 a = curveRoute.origin().toLocal(routeRx.sideLat(si, j), routeRx.sideLon(si, j));
            float dx = a.x - p.x, dy = a.y - p.y;
            float mx = dx * rx + dy * ry;
            float my = dx * fx + dy * fy;
            if (mx > 2000.f) mx = 2000.f;
            if (mx < -2000.f) mx = -2000.f;
            if (my > 2000.f) my = 2000.f;
            if (my < -2000.f) my = -2000.f;
            branchPts[nb][m].x = (int16_t)lroundf(mx);
            branchPts[nb][m].y = (int16_t)lroundf(my);
            m++;
        }
        if (m < 2) continue;
        branchSeg[nb].pts = branchPts[nb];
        branchSeg[nb].n = m;
        nb++;
    }
    nav.branches = nb ? branchSeg : nullptr;
    nav.branchCount = nb;
    nav.metersPerPx = MAP_METERS_PER_PX;
}

static void renderRouteNav(GFXcanvas1 &c, uint32_t now)
{
    nav.hasIcon = false;
    nav.stale = false;
    if (timeValid()) currentClock(nav.clock, sizeof(nav.clock));
    else nav.clock[0] = 0;

    float s = riderS();
    if (s >= 0) fillRouteMap(s);
    else if (!routeNavCache) {
        nav.route = nullptr;
        nav.routeLen = 0;
        nav.branches = nullptr;
        nav.branchCount = 0;
    }

    if (routeRx.hasGuide()) {
        fmtMeters(nav.distance, sizeof(nav.distance), routeRx.guideDistM());
        ui::utf8ToAscii(routeRx.guideStreet(), nav.street, sizeof(nav.street));
        nav.maneuver = routeRx.guideTurn();
        fmtMeters(nav.remaining, sizeof(nav.remaining), routeRx.guideRemainM());
        fmtMinutes(nav.duration, sizeof(nav.duration), routeRx.guideDurS());
        int eta = routeRx.guideEtaMin();
        if (eta >= 0 && eta < 24 * 60) snprintf(nav.eta, sizeof(nav.eta), "%02d:%02d", eta / 60, eta % 60);
        else nav.eta[0] = 0;
        lastNavMs = now;
    } else if (s >= 0) {
        int ci = curveRoute.nextCurve(s);
        nav.maneuver = 0;
        nav.eta[0] = 0;
        nav.duration[0] = 0;
        if (ci >= 0) {
            const curve::Curve &cv = curveRoute.curveAt(ci);
            fmtMeters(nav.distance, sizeof(nav.distance), (int)(cv.startS - s));
            nav.maneuver = cv.dir < 0 ? 2 : 5;
            snprintf(nav.street, sizeof(nav.street), "Curva a %s", cv.dir < 0 ? "sinistra" : "destra");
        } else {
            nav.distance[0] = 0;
            snprintf(nav.street, sizeof(nav.street), "Dritto");
        }
        fmtMeters(nav.remaining, sizeof(nav.remaining), (int)(curveRoute.length() - s));
    } else {
        nav.distance[0] = 0;
        nav.remaining[0] = 0;
        nav.duration[0] = 0;
        nav.eta[0] = 0;
        nav.maneuver = 0;
        snprintf(nav.street, sizeof(nav.street), "%s", routeRx.loading() ? "CARICO PERCORSO" : "ATTESA GPS");
    }

    int kmh = (int)(routeRx.speedKmh() + 0.5f);
    if (routeRx.hasPosition()) snprintf(nav.speed, sizeof(nav.speed), "%d", kmh);
    else nav.speed[0] = 0;

    if (navLayout == LAYOUT_MAP) ui::drawNavMap(c, nav, now);
    else                          ui::drawNavIcon(c, nav, now);
}

// Modalita' CURVA: avanza il pilota (demo) o proietta la posizione GPS
// ricevuta sul percorso, poi disegna. Indipendente da Chronos.
static void renderCurve(GFXcanvas1 &c, uint32_t now)
{
    static uint32_t lastMs = 0;
    float dt = lastMs ? (now - lastMs) / 1000.0f : 0.0f;
    if (dt > 0.5f) dt = 0.5f;               // dopo pause lunghe non "saltare"
    lastMs = now;

    static CurveView v;
    float s = 0;
    if (curveRouteIsDemo) {
        demoRider.step(curveRoute, dt);
        s = demoRider.s;
    } else if (curveRoute.ready() && routeRx.hasPosition()) {
        curve::Vec2 p = curveRoute.origin().toLocal(routeRx.lat(), routeRx.lon());
        s = curveRoute.project(p, curveHint);
    }
    curve::buildView(curveRoute, s, v, &curveZoom);
    v.demo = curveRouteIsDemo;
    // La finestra nuova arriva in piu' pacchetti. Finche' non e' chiusa
    // si tiene la strada gia' pronta, senza coprirla con «CARICO PERCORSO».
    if (routeRx.loading() && !curveRoute.ready()) v.message = "CARICO PERCORSO";
    else if (!curveRouteIsDemo && curveRoute.ready() && !routeRx.hasPosition()) v.message = "ATTESA GPS";
    ui::drawCurveScreen(c, v, now);
}

#if DEMO_MODE
static void renderDemo(GFXcanvas1 &c, uint32_t now)
{
    static NavView dv;
    uint32_t phase = (now / 6000) % 5;
    if (phase == 4) { renderCurve(c, now); return; }
    int dist = 350 - (int)((now % 6000) / 20);       // conto alla rovescia
    char d[16];
    snprintf(d, sizeof(d), "%d m", dist);
    switch (phase) {
    case 0: ui::drawWaiting(c, HUD_BLE_NAME, false, now); break;
    case 1: ui::drawIdle(c, "10:24", "Dom 4 ott", "DEMO", "Avvia Google Maps", 80); break;
    case 2:
        demo::fillNav(dv, d, "Via Roma - poi SS12 direzione Verona", false);
        ui::drawNavIcon(c, dv, now);
        break;
    default:
        demo::fillNav(dv, "75 m", "Via Roma", true);
        ui::drawNavMap(c, dv, now);
        break;
    }
}
#endif

static void render(uint32_t now)
{
    GFXcanvas1 &c = hud::canvas();

    if (layoutBannerMs && now - layoutBannerMs < LAYOUT_BANNER_MS) {
        ui::drawLayoutBanner(c, layoutName(navLayout));
        return;
    }

    if (now - bootMs < SPLASH_MS) {
        ui::drawSplash(c, HUD_BLE_NAME, HUD_FW_VERSION);
        return;
    }
#if DEMO_MODE
    renderDemo(c, now);
    return;
#endif
    bool showNav = false;
    if (watch.isConnected()) {
        showNav = navActive;
#if WAZE_NOTIFICATION_FALLBACK
        if (wazeActive && now - wazeLastMs > WAZE_TIMEOUT_MS) wazeActive = false;
        if (!navActive && wazeActive) showNav = true;
#endif
    }

    // CURVA non usa Chronos: senza una traccia vera resta la strada demo.
    // ICONA e MAPPA usano la stessa strada dell'app (freccia, via, mini-mappa).
    // Senza strada vera restano su Chronos.
    if (navLayout == LAYOUT_CURVE) {
        renderCurve(c, now);
        return;
    }
    if (!curveRouteIsDemo && (curveRoute.ready() || routeNavCache || routeRx.loading())) {
        renderRouteNav(c, now);
        return;
    }

    if (!watch.isConnected()) {
        ui::drawWaiting(c, HUD_BLE_NAME, everConnected, now);
        return;
    }

    if (showNav) {
        drawNav(c, now);
        return;
    }

    char clk[8] = "", date[20] = "";
    if (timeValid()) {
        currentClock(clk, sizeof(clk));
        currentDate(date, sizeof(date));
    }
    const char *hint = navDisabled ? "Nav OFF: vedi Chronos" : "Avvia navigazione";
    ui::drawIdle(c, clk, date, "Chronos OK", hint, watch.getPhoneBattery());
}

// ---------------------------------------------------------------------
void setup()
{
    Serial.begin(115200);
    delay(50);
    Serial.printf("\nHUD-Moto fw %s  (SDA %d, SCL %d, I2C %lu Hz)\n",
                  HUD_FW_VERSION, PIN_SDA, PIN_SCL, (unsigned long)I2C_HZ);

#if PIN_BUTTON >= 0
    pinMode(PIN_BUTTON, INPUT_PULLUP);
#endif
    prefs.begin("hud", false);
    navLayout = prefs.getUChar("layout", NAV_LAYOUT_DEFAULT);
    if (navLayout >= LAYOUT_COUNT) navLayout = LAYOUT_ICON;
    bool mx = prefs.getBool("mirrorX", MIRROR_X);

    if (!hud::displayBegin()) {
        Serial.println("[OLED] SSD1306 non trovato! Controlla cablaggio/indirizzo.");
    }
    hud::setMirror(mx, MIRROR_Y);
    if (prefs.isKey("bright")) hud::setContrast(prefs.getUChar("bright", OLED_CONTRAST));
    ui::clearNav(nav);

#if CURVE_DEMO_IF_NO_ROUTE || DEMO_MODE
    curve::buildMountainRoad(curveRoute);
    curveRouteIsDemo = true;
    Serial.printf("[CURVA] strada demo %.0f m, %d curve\n", curveRoute.length(), curveRoute.curveCount());
#endif

    bootMs = millis();
    ui::drawSplash(hud::canvas(), HUD_BLE_NAME, HUD_FW_VERSION);
    hud::present(true);

#if !DEMO_MODE
    watch.set24Hour(CLOCK_24H);
    watch.setConnectionCallback(onConnection);
    watch.setConfigurationCallback(onConfig);
#if WAZE_NOTIFICATION_FALLBACK
    watch.setNotificationCallback(onNotification);
#endif
    watch.begin();          // avvia BLE (advertising come HUD_BLE_NAME)
    // Windows accoppia il dispositivo. Senza chiavi salvate sulla scheda
    // il collegamento dopo si chiude da solo (motivo 0x13).
    NimBLEDevice::setSecurityAuth(true, false, true);
    Serial.printf("[BLE] nome %s  MAC %s\n", HUD_BLE_NAME, watch.getAddress().c_str());
#if ROUTE_BLE_ENABLE
    setupRouteBle();
#endif
#endif
}

void loop()
{
#if !DEMO_MODE
    watch.loop();           // routine interne della libreria
#endif
    handleButton();

    if (connChanged) {
        connChanged = false;
        if (!watch.isConnected()) {
            // Disconnessione: niente dati vecchi a schermo
            navActive = false;
            ui::clearNav(nav);
#if WAZE_NOTIFICATION_FALLBACK
            wazeActive = false;
#endif
        }
    }
    if (navDirty) {
        navDirty = false;
        pullNavigation();
#if WAZE_NOTIFICATION_FALLBACK
        if (navActive) wazeActive = false;     // la nav nativa ha priorita'
#endif
    }
    if (iconDirty) {
        iconDirty = false;
        pullIcon();
    }
#if ROUTE_BLE_ENABLE
    // pacchetti percorso/posizione ricevuti via BLE
    RoutePacket pk;
    while (routeQueue && xQueueReceive(routeQueue, &pk, 0) == pdTRUE) {
        curve::RxResult r = routeRx.feed(pk.data, pk.len);
        if (r == curve::RxResult::RouteReady) {
            curveRouteIsDemo = false;
            routeNavCache = true;
            curveHint = 0;
            curveZoom = 0;
            Serial.printf("[%lu] ROUTE percorso %.0f m, %d curve\n", millis(), curveRoute.length(), curveRoute.curveCount());
        } else if (r == curve::RxResult::Cleared) {
            routeNavCache = false;
            nav.route = nullptr;
            nav.routeLen = 0;
            nav.maneuver = 0;
        } else if (r == curve::RxResult::Ctrl) {
            applyHudControl(routeRx.ctrlCmd(), routeRx.ctrlArg());
        } else if (r == curve::RxResult::Position) {
            static bool loggedPos = false;
            if (!loggedPos) {
                loggedPos = true;
                Serial.printf("[%lu] ROUTE posizione %.5f %.5f\n", millis(), routeRx.lat(), routeRx.lon());
            }
        } else if ((uint8_t)r >= (uint8_t)curve::RxResult::ErrShort) {
            Serial.printf("[%lu] ROUTE errore pacchetto %d\n", millis(), (int)r);
        }
    }
    // Solo osservazione. L'annuncio si riaccende in onAdvFinished (si e'
    // spento da solo) e in onConnection (c'e' gia' un client). Riaccenderlo
    // qui, appena isAdvertising() diventa falso, chiude il collegamento
    // Windows che sta ancora nascendo.
    {
        static uint8_t lastPeers = 255;
        static int lastAdv = -1;
        static uint32_t darkSince = 0;
        uint32_t t = millis();
        NimBLEServer *rs = NimBLEDevice::getServer();
        NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
        if (rs && adv) {
            uint8_t n = rs->getConnectedCount();
            int on = adv->isAdvertising() ? 1 : 0;
            if (n != lastPeers || on != lastAdv) {
                Serial.printf("[%lu] BLE peers=%u adv=%d\n", t, n, on);
                lastPeers = n;
                lastAdv = on;
            }
            if (on || n > 0) {
                darkSince = 0;
            } else {
                if (!darkSince) darkSince = t;
                else if (t - darkSince > 8000) {
                    bool ok = adv->start();
                    Serial.printf("[%lu] BLE annuncio assente da 8s, riavvio=%d\n", t, ok);
                    darkSince = t;
                }
            }
        }
    }
#endif
#if WAZE_NOTIFICATION_FALLBACK
    if (wazeDirty) {
        wazeDirty = false;
        if (!navActive) pullWaze();
    }
#endif

    static uint32_t lastFrame = 0;
    uint32_t now = millis();
    if (now - lastFrame >= FRAME_MS) {
        lastFrame = now;
        render(now);
        hud::present();     // invia all'OLED solo se il frame e' cambiato
    }
    delay(5);
}
