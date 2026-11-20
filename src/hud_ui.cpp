// =====================================================================
//  hud_ui.cpp  -  Disegno schermate HUD (portabile: firmware + preview PC)
// =====================================================================
#include "hud_ui.h"
#include "config.h"

#include <string.h>
#include <stdio.h>
#include <math.h>

#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>
#include <Fonts/FreeSansBold24pt7b.h>

namespace {

const uint16_t ON = 1;   // pixel acceso (bianco)
const uint16_t OFF = 0;  // pixel spento (nero = trasparente nel riflesso)

// --- Geometria del layout MAPPA (128x64) -------------------------------
//  | 0..30 colonna SX | 32..95 mappa 64 px | 97..127 colonna DX |
const int16_t MAP_VX = 32;
const int16_t MAP_VY = 0;
const int16_t MAP_VW = 64;
const int16_t MAP_VH = 45;       // sotto: riga distanza (y 46..63)
const int16_t MAP_OX = 64;       // posizione pilota (centro triangolo)
const int16_t MAP_OY = 37;
const int16_t COL_L_X = 0, COL_W = 31;
const int16_t COL_R_X = 97;

// Raggio della linea del percorso (2 => ~5 px di spessore)
const int16_t ROUTE_R = 2;
const int16_t BRANCH_R = 0;

// ---------------------------------------------------------------------
//  Helper testo
// ---------------------------------------------------------------------
struct Box { int16_t x1, y1; uint16_t w, h; };

Box bounds(GFXcanvas1 &c, const char *s, const GFXfont *f, int16_t x = 0, int16_t y = 0)
{
    Box b{0, 0, 0, 0};
    c.setFont(f);
    c.setTextSize(1);
    c.setTextWrap(false);
    c.getTextBounds(s, x, y, &b.x1, &b.y1, &b.w, &b.h);
    return b;
}

// Larghezza "pixel effettiva" del testo con un font
int16_t textW(GFXcanvas1 &c, const char *s, const GFXfont *f)
{
    if (!s || !*s) return 0;
    return (int16_t)bounds(c, s, f).w;
}

// Scrive il testo con il bordo sinistro dei pixel in x (compensa x1 dei font)
void drawAt(GFXcanvas1 &c, const char *s, const GFXfont *f, int16_t x, int16_t baseline)
{
    Box b = bounds(c, s, f);
    c.setFont(f);
    c.setTextColor(ON);
    c.setCursor(x - b.x1, f ? baseline : baseline - 7);
    c.print(s);
}

// Testo centrato orizzontalmente su cx. Per il font 6x8 (f=nullptr)
// "baseline" e' la riga inferiore della cella (cella = baseline-7..baseline).
void drawCentered(GFXcanvas1 &c, const char *s, const GFXfont *f, int16_t cx, int16_t baseline)
{
    int16_t w = textW(c, s, f);
    drawAt(c, s, f, cx - w / 2, baseline);
}

// Sceglie il font piu' grande (dalla lista) in cui il testo sta in maxW
const GFXfont *fitFont(GFXcanvas1 &c, const char *s, const GFXfont *const *fonts, int n, int16_t maxW)
{
    for (int i = 0; i < n; i++)
        if (textW(c, s, fonts[i]) <= maxW) return fonts[i];
    return fonts[n - 1];
}

// Tronca la stringa (in place) finche' non sta in maxW, aggiungendo "."
void truncateToWidth(GFXcanvas1 &c, char *s, const GFXfont *f, int16_t maxW)
{
    size_t len = strlen(s);
    if (textW(c, s, f) <= maxW) return;
    while (len > 1) {
        s[--len] = '\0';
        s[len - 1] = '.';
        if (textW(c, s, f) <= maxW) return;
    }
}

// Copia sicura
void copyStr(char *dst, size_t n, const char *src)
{
    if (!n) return;
    strncpy(dst, src ? src : "", n - 1);
    dst[n - 1] = '\0';
}

// Rimuove gli spazi ("18 min" -> "18min") per le colonne strette
void compact(char *s)
{
    char *w = s;
    for (char *r = s; *r; r++)
        if (*r != ' ') *w++ = *r;
    *w = '\0';
}

// Divide "350 m" / "1,2 km" / "0.3mi" in numero + unita'
// Ritorna false se la stringa non inizia con una cifra.
bool splitDistance(const char *in, char *num, size_t nn, char *unit, size_t un)
{
    while (*in == ' ') in++;
    if (*in < '0' || *in > '9') return false;
    size_t i = 0;
    while (in[i] && ((in[i] >= '0' && in[i] <= '9') || in[i] == '.' || in[i] == ',')) i++;
    size_t k = i < nn - 1 ? i : nn - 1;
    memcpy(num, in, k);
    num[k] = '\0';
    const char *u = in + i;
    while (*u == ' ') u++;
    copyStr(unit, un, u);
    return true;
}

// Offset di scorrimento "marquee" calcolato solo dal tempo (senza stato):
// pausa -> scorre fino a mostrare la fine -> pausa -> ricomincia.
int16_t marqueeOffset(int16_t textWidth, int16_t avail, uint32_t nowMs)
{
    if (textWidth <= avail) return 0;
    const int32_t travel = textWidth - avail + 4;
    const uint32_t scrollMs = (uint32_t)travel * 1000UL / SCROLL_PX_PER_S;
    const uint32_t period = SCROLL_PAUSE_MS + scrollMs + SCROLL_PAUSE_MS;
    uint32_t t = nowMs % period;
    if (t < SCROLL_PAUSE_MS) return 0;
    t -= SCROLL_PAUSE_MS;
    if (t >= scrollMs) return (int16_t)travel;
    return (int16_t)((uint32_t)travel * t / scrollMs);
}

// Testo su una riga: centrato se ci sta, altrimenti scorrevole.
void drawLineScrolling(GFXcanvas1 &c, const char *s, const GFXfont *f,
                       int16_t x, int16_t w, int16_t baseline, uint32_t nowMs)
{
    if (!s || !*s) return;
    int16_t tw = textW(c, s, f);
    if (tw <= w) {
        drawAt(c, s, f, x + (w - tw) / 2, baseline);
    } else {
        drawAt(c, s, f, x - marqueeOffset(tw, w, nowMs), baseline);
    }
}

// Distanza grande: numero in font grande + unita' piccola accanto.
// Allineata a destra/centrata nel riquadro (x, w), baseline data.
void drawBigDistance(GFXcanvas1 &c, const char *dist, int16_t x, int16_t w, int16_t baseline,
                     const GFXfont *const *fonts, int nFonts, const GFXfont *unitFont)
{
    char num[12], unit[12];
    if (!dist || !*dist) {
        drawCentered(c, "--", fonts[nFonts - 1], x + w / 2, baseline);
        return;
    }
    if (!splitDistance(dist, num, sizeof(num), unit, sizeof(unit))) {
        // Non e' una distanza (es. "Procedi verso nord"): testo troncato
        char tmp[40];
        copyStr(tmp, sizeof(tmp), dist);
        const GFXfont *f = fonts[nFonts - 1];
        truncateToWidth(c, tmp, f, w);
        drawCentered(c, tmp, f, x + w / 2, baseline);
        return;
    }
    const int16_t gap = 2;
    int16_t uw = textW(c, unit, unitFont);
    const GFXfont *nf = fonts[nFonts - 1];
    for (int i = 0; i < nFonts; i++) {
        if (textW(c, num, fonts[i]) + (uw ? gap + uw : 0) <= w) { nf = fonts[i]; break; }
    }
    int16_t nw = textW(c, num, nf);
    int16_t total = nw + (uw ? gap + uw : 0);
    int16_t sx = x + (w - total) / 2;
    if (sx < x) sx = x;
    drawAt(c, num, nf, sx, baseline);
    if (uw) drawAt(c, unit, unitFont, sx + nw + gap, baseline);
}

// Piccola icona batteria (14x7) con livello
void drawBattery(GFXcanvas1 &c, int16_t x, int16_t y, int level)
{
    c.drawRect(x, y, 13, 7, ON);
    c.fillRect(x + 13, y + 2, 2, 3, ON);
    int fill = (level * 9 + 50) / 100;
    if (fill < 0) fill = 0;
    if (fill > 9) fill = 9;
    c.fillRect(x + 2, y + 2, fill, 3, ON);
}

// Logo Bluetooth stilizzato, alto h pixel, centrato su cx
void drawBtLogo(GFXcanvas1 &c, int16_t cx, int16_t y, int16_t h)
{
    int16_t q = h / 4;
    for (int16_t dx = 0; dx <= 1; dx++) {        // spessore 2 px
        int16_t x = cx + dx;
        c.drawLine(x, y, x, y + h, ON);                       // asta
        c.drawLine(x, y, x + q, y + q, ON);                   // punta sup
        c.drawLine(x + q, y + q, x - q, y + 3 * q, ON);       // diagonale
        c.drawLine(x, y + h, x + q, y + 3 * q, ON);           // punta inf
        c.drawLine(x + q, y + 3 * q, x - q, y + q, ON);       // diagonale
    }
}

// --- Geometria mini-mappa ---------------------------------------------

// Clipping di Cohen-Sutherland su rettangolo [xmin,xmax]x[ymin,ymax]
int outCode(float x, float y, float xmin, float ymin, float xmax, float ymax)
{
    int c = 0;
    if (x < xmin) c |= 1; else if (x > xmax) c |= 2;
    if (y < ymin) c |= 4; else if (y > ymax) c |= 8;
    return c;
}

bool clipLine(float &x0, float &y0, float &x1, float &y1,
              float xmin, float ymin, float xmax, float ymax)
{
    int c0 = outCode(x0, y0, xmin, ymin, xmax, ymax);
    int c1 = outCode(x1, y1, xmin, ymin, xmax, ymax);
    for (int iter = 0; iter < 8; iter++) {
        if (!(c0 | c1)) return true;      // tutto dentro
        if (c0 & c1) return false;        // tutto fuori dallo stesso lato
        int co = c0 ? c0 : c1;
        float x = 0, y = 0;
        if (co & 8)      { x = x0 + (x1 - x0) * (ymax - y0) / (y1 - y0); y = ymax; }
        else if (co & 4) { x = x0 + (x1 - x0) * (ymin - y0) / (y1 - y0); y = ymin; }
        else if (co & 2) { y = y0 + (y1 - y0) * (xmax - x0) / (x1 - x0); x = xmax; }
        else             { y = y0 + (y1 - y0) * (xmin - x0) / (x1 - x0); x = xmin; }
        if (co == c0) { x0 = x; y0 = y; c0 = outCode(x0, y0, xmin, ymin, xmax, ymax); }
        else          { x1 = x; y1 = y; c1 = outCode(x1, y1, xmin, ymin, xmax, ymax); }
    }
    return false;
}

inline int16_t rnd(float v)
{
    if (v > 4000.f) v = 4000.f;
    if (v < -4000.f) v = -4000.f;
    return (int16_t)lroundf(v);
}

// Linea spessa: quadrilatero (2 triangoli) + tappi tondi alle estremita'
void thickLine(GFXcanvas1 &c, float x0, float y0, float x1, float y1, int16_t r)
{
    float dx = x1 - x0, dy = y1 - y0;
    float len = sqrtf(dx * dx + dy * dy);
    if (len >= 0.5f) {
        float nx = -dy / len * r, ny = dx / len * r;
        c.fillTriangle(rnd(x0 + nx), rnd(y0 + ny), rnd(x1 + nx), rnd(y1 + ny),
                       rnd(x1 - nx), rnd(y1 - ny), ON);
        c.fillTriangle(rnd(x0 + nx), rnd(y0 + ny), rnd(x1 - nx), rnd(y1 - ny),
                       rnd(x0 - nx), rnd(y0 - ny), ON);
    }
    c.fillCircle(rnd(x0), rnd(y0), r, ON);
    c.fillCircle(rnd(x1), rnd(y1), r, ON);
}

// Polilinea in metri -> pixel, clippata al viewport ridotto di "margin"
void drawPolyline(GFXcanvas1 &c, const MapPoint *p, uint8_t n,
                  int16_t vx, int16_t vy, int16_t vw, int16_t vh,
                  int16_t ox, int16_t oy, float mpp, int16_t r)
{
    if (!p || n < 2) return;
    const float m = (float)r + 1.0f;   // +1: stacco dai separatori
    const float xmin = vx + m, ymin = vy + m;
    const float xmax = vx + vw - 1 - m, ymax = vy + vh - 1 - m;
    for (uint8_t i = 0; i + 1 < n; i++) {
        float x0 = ox + p[i].x / mpp,     y0 = oy - p[i].y / mpp;
        float x1 = ox + p[i + 1].x / mpp, y1 = oy - p[i + 1].y / mpp;
        if (!clipLine(x0, y0, x1, y1, xmin, ymin, xmax, ymax)) continue;
        if (r <= 0) c.drawLine(rnd(x0), rnd(y0), rnd(x1), rnd(y1), ON);
        else        thickLine(c, x0, y0, x1, y1, r);
    }
}

// Triangolo posizione (punta in alto) con alone nero per staccarlo
// dalla linea del percorso.
void drawPositionMarker(GFXcanvas1 &c, int16_t x, int16_t y)
{
    c.fillTriangle(x, y - 10, x - 8, y + 6, x + 8, y + 6, OFF);   // alone
    c.fillTriangle(x, y - 7, x - 5, y + 4, x + 5, y + 4, ON);     // marker
}

// Gradi in senso orario, 0 = avanti. In Italia si gira in senso antiorario:
// l'uscita 1 è a destra, la 2 dritto, la 3 a sinistra.
float roundaboutDeg(uint8_t exit)
{
    switch (exit) {
        case 1: return 90.f;
        case 2: return 0.f;
        case 3: return 270.f;
        case 4: return 225.f;
        case 5: return 200.f;
        case 6: return 185.f;
        default: return 175.f;
    }
}

void polar(int16_t cx, int16_t cy, float rad, float deg, int16_t &px, int16_t &py)
{
    float a = deg * 0.0174532925f;
    px = rnd(cx + sinf(a) * rad);
    py = rnd(cy - cosf(a) * rad);
}

void drawExitDigit(GFXcanvas1 &c, int16_t cx, int16_t cy, uint8_t exit)
{
    // Cifre 3x5, bit 0x4 a sinistra. Spesse, così si leggono in corsa.
    static const uint8_t g[9][5] = {
        {0, 0, 0, 0, 0},
        {0x2, 0x6, 0x2, 0x2, 0x7},
        {0x7, 0x1, 0x7, 0x4, 0x7},
        {0x7, 0x1, 0x7, 0x1, 0x7},
        {0x5, 0x5, 0x7, 0x1, 0x1},
        {0x7, 0x4, 0x7, 0x1, 0x7},
        {0x7, 0x4, 0x7, 0x5, 0x7},
        {0x7, 0x1, 0x2, 0x4, 0x4},
        {0x7, 0x5, 0x7, 0x5, 0x7},
    };
    if (exit < 1 || exit > 8) return;
    const int16_t sc = 3;
    int16_t x0 = cx - (3 * sc) / 2;
    int16_t y0 = cy - (5 * sc) / 2;
    for (int row = 0; row < 5; row++) {
        uint8_t bits = g[exit][row];
        for (int col = 0; col < 3; col++) {
            if (bits & (uint8_t)(0x4 >> col))
                c.fillRect(x0 + col * sc, y0 + row * sc, sc, sc, ON);
        }
    }
}

// Anello, ingresso dal basso, uscite saltate come tacche, quella da prendere
// con la freccia e il numero in mezzo.
void drawRoundabout(GFXcanvas1 &c, int16_t x, int16_t y, int16_t s, uint8_t exit)
{
    const int16_t cx = x + s / 2;
    const int16_t cy = y + s / 2 - 1;
    const int16_t r = s / 3 - 1;
    if (r < 6) { ui::drawFallbackArrow(c, x, y, s); return; }

    c.drawCircle(cx, cy, r, ON);
    int16_t stubTop = cy + r - 1;
    int16_t stubBot = y + s - 2;
    if (stubBot > stubTop) c.fillRect(cx - 2, stubTop, 4, stubBot - stubTop, ON);

    if (exit < 1 || exit > 8) {
        c.drawCircle(cx, cy, r - 1, ON);
        return;
    }

    for (uint8_t i = 1; i < exit; i++) {
        int16_t x0, y0, x1, y1;
        polar(cx, cy, (float)r + 1, roundaboutDeg(i), x0, y0);
        polar(cx, cy, (float)r + 5, roundaboutDeg(i), x1, y1);
        c.drawLine(x0, y0, x1, y1, ON);
    }

    const float end = roundaboutDeg(exit);
    float a = 180.f;
    float stop = end;
    if (stop > a) stop -= 360.f;
    int16_t x0, y0, x1, y1;
    polar(cx, cy, (float)r, a, x0, y0);
    for (float step = a - 14.f; step > stop; step -= 14.f) {
        polar(cx, cy, (float)r, step, x1, y1);
        thickLine(c, x0, y0, x1, y1, 1);
        x0 = x1;
        y0 = y1;
    }
    polar(cx, cy, (float)r, stop, x1, y1);
    thickLine(c, x0, y0, x1, y1, 1);

    int16_t tipX, tipY, baseX, baseY;
    polar(cx, cy, (float)r + 7, end, tipX, tipY);
    polar(cx, cy, (float)r - 1, end, baseX, baseY);
    float ar = end * 0.0174532925f;
    float px = cosf(ar) * 5.f, py = sinf(ar) * 5.f;
    c.fillTriangle(tipX, tipY,
                   rnd(baseX + px), rnd(baseY + py),
                   rnd(baseX - px), rnd(baseY - py), ON);
    drawExitDigit(c, cx, cy, exit);
}

// Freccia di manovra al posto dell'icona Chronos (layout ICONA e MAPPA).
void drawManeuverArrow(GFXcanvas1 &c, int16_t x, int16_t y, int16_t s, uint8_t m)
{
    if (m == 0) { ui::drawFallbackArrow(c, x, y, s); return; }
    const int16_t t = s / 7;
    if (t < 3) { ui::drawFallbackArrow(c, x, y, s); return; }

    if (m == 8 || (m >= 0x11 && m <= 0x18)) {
        drawRoundabout(c, x, y, s, m == 8 ? 0 : (uint8_t)(m & 0x0F));
        return;
    }
    if (m == 9) {
        int16_t px = x + s / 3;
        c.fillRect(px, y + 4, t, s - 8, ON);
        c.fillTriangle(px + t, y + 4, px + t, y + s / 2, x + s - 6, y + s / 4 + 2, ON);
        return;
    }
    if (m == 7) {
        int16_t left = x + s / 5;
        int16_t mid = x + s / 2;
        c.fillRect(mid, y + s / 2, t, s / 2 - 2, ON);
        c.fillRect(left, y + s / 2, mid - left, t, ON);
        c.fillRect(left, y + s / 4, t, s / 2 - s / 4 + t, ON);
        c.fillTriangle(left - t, y + s / 4 + t / 2, left + t * 2, y + s / 4 + t / 2, left + t / 2, y + 2, ON);
        return;
    }

    const bool right = m >= 4 && m <= 6;
    const uint8_t mag = right ? (uint8_t)(m - 3) : m;
    if (mag < 1 || mag > 3) { ui::drawFallbackArrow(c, x, y, s); return; }
    int16_t bend = mag == 1 ? y + s / 3 : mag == 3 ? y + (s * 2) / 3 : y + s / 2;
    int16_t shaftX = x + s / 2 - t / 2;
    int16_t bottom = y + s - 4;
    if (bend > bottom - t) bend = bottom - t;
    c.fillRect(shaftX, bend, t, bottom - bend, ON);
    int16_t arm = mag == 3 ? s / 2 : s * 2 / 5;
    if (right) {
        c.fillRect(shaftX, bend, arm, t, ON);
        int16_t ax = shaftX + arm;
        c.fillTriangle(ax - 1, bend - t, ax - 1, bend + t * 2, ax + t * 2, bend + t / 2, ON);
    } else {
        int16_t ax = shaftX + t - arm;
        if (ax < x + 2) ax = x + 2;
        c.fillRect(ax, bend, shaftX + t - ax, t, ON);
        c.fillTriangle(ax + 1, bend - t, ax + 1, bend + t * 2, ax - t * 2, bend + t / 2, ON);
    }
}

void drawIconOrFallback(GFXcanvas1 &c, const NavView &v, int16_t x, int16_t y)
{
    if (v.hasIcon) c.drawBitmap(x, y, v.icon, HUD_ICON_SIZE, HUD_ICON_SIZE, ON);
    else           drawManeuverArrow(c, x, y, HUD_ICON_SIZE, v.maneuver);
}

// Indicatore "dato vecchio": piccolo quadratino lampeggiante in alto a dx
void drawStale(GFXcanvas1 &c, bool stale, uint32_t nowMs)
{
    if (stale && ((nowMs / 500) & 1)) c.fillRect(124, 0, 4, 4, ON);
}

const GFXfont *const BIG_FONTS[] = {&FreeSansBold18pt7b, &FreeSansBold12pt7b, &FreeSansBold9pt7b};
const GFXfont *const MID_FONTS[] = {&FreeSansBold12pt7b, &FreeSansBold9pt7b};
const GFXfont *const CLOCK_FONTS[] = {&FreeSansBold24pt7b, &FreeSansBold18pt7b};

} // namespace

// =====================================================================
namespace ui {

void utf8ToAscii(const char *in, char *out, size_t outSize)
{
    // Tabella Latin-1 0xC0..0xFF -> ASCII
    static const char latin1[] =
        "AAAAAAACEEEEIIII" "DNOOOOOxOUUUUYTs"
        "aaaaaaaceeeeiiii" "dnooooo/ouuuuyty";
    if (!outSize) return;
    size_t o = 0;
    const unsigned char *p = (const unsigned char *)(in ? in : "");
    auto put = [&](char ch) { if (o + 1 < outSize) out[o++] = ch; };
    while (*p) {
        unsigned char b = *p;
        if (b < 0x80) {
            put((b < 0x20) ? ' ' : (char)b);   // \n, \t -> spazio
            p++;
        } else if ((b & 0xE0) == 0xC0 && p[1]) {
            unsigned cp = ((b & 0x1F) << 6) | (p[1] & 0x3F);
            if (cp >= 0xC0 && cp <= 0xFF) put(latin1[cp - 0xC0]);
            else if (cp == 0xA0) put(' ');            // NBSP
            else if (cp == 0xB0) put('o');            // grado
            else if (cp == 0xB7) put('-');            // punto medio
            p += 2;
        } else if ((b & 0xF0) == 0xE0 && p[1] && p[2]) {
            unsigned cp = ((b & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
            if (cp == 0x2018 || cp == 0x2019) put('\'');
            else if (cp == 0x201C || cp == 0x201D) put('"');
            else if (cp == 0x2013 || cp == 0x2014 || cp == 0x2022) put('-');
            else if (cp == 0x2026) { put('.'); put('.'); put('.'); }
            else if (cp == 0x202F || cp == 0x2009 || cp == 0x2007) put(' ');
            else if (cp == 0x2192) put('>');          // freccia destra
            p += 3;
        } else if ((b & 0xF8) == 0xF0) {
            p += (p[1] && p[2] && p[3]) ? 4 : 1;      // emoji ecc.: scarta
        } else {
            p++;                                      // byte non valido
        }
    }
    out[o] = '\0';
}

void clearNav(NavView &v)
{
    memset(&v, 0, sizeof(v));
}

void drawFallbackArrow(GFXcanvas1 &c, int16_t x, int16_t y, int16_t s)
{
    // Freccia "dritto": punta triangolare + asta
    int16_t cx = x + s / 2;
    c.fillTriangle(cx, y + 2, x + 6, y + s / 2, x + s - 7, y + s / 2, ON);
    c.fillRect(cx - s / 8, y + s / 2, s / 4, s / 2 - 2, ON);
}

// ---------------------------------------------------------------------
void drawSplash(GFXcanvas1 &c, const char *bleName, const char *version)
{
    char buf[40];
    c.fillScreen(OFF);
    drawCentered(c, "HUD-Moto", &FreeSansBold12pt7b, 64, 22);
    c.drawFastHLine(14, 28, 100, ON);
    snprintf(buf, sizeof(buf), "BLE: %s", bleName);
    drawCentered(c, buf, nullptr, 64, 40);
    snprintf(buf, sizeof(buf), "fw %s  Chronos", version);
    drawCentered(c, buf, nullptr, 64, 50);
    snprintf(buf, sizeof(buf), "mirror %s%s", MIRROR_X ? "X:ON" : "X:off", MIRROR_Y ? " Y:ON" : "");
    drawCentered(c, buf, nullptr, 64, 62);
}

void drawLayoutBanner(GFXcanvas1 &c, const char *name)
{
    c.fillScreen(OFF);
    const char *s = (name && *name) ? name : "?";
    const GFXfont *f = fitFont(c, s, BIG_FONTS, 3, 120);
    drawCentered(c, s, f, 64, 42);
}

// ---------------------------------------------------------------------
void drawWaiting(GFXcanvas1 &c, const char *bleName, bool lost, uint32_t nowMs)
{
    c.fillScreen(OFF);
    drawBtLogo(c, 63, 1, 20);
    // "onde" lampeggianti ai lati del logo = advertising in corso
    if ((nowMs / 600) & 1) {
        c.drawFastVLine(52, 7, 9, ON);
        c.drawFastVLine(75, 7, 9, ON);
        c.drawFastVLine(48, 5, 13, ON);
        c.drawFastVLine(79, 5, 13, ON);
    }
    drawCentered(c, bleName, &FreeSansBold9pt7b, 64, 39);

    char line[28];
    int dots = (nowMs / 400) % 4;
    snprintf(line, sizeof(line), "%s%.*s", lost ? "Connessione persa" : "Attendo telefono", dots, "...");
    // larghezza fissa: centra sul testo senza puntini per non "ballare"
    int16_t w = textW(c, lost ? "Connessione persa..." : "Attendo telefono...", nullptr);
    drawAt(c, line, nullptr, 64 - w / 2, 52);
    drawCentered(c, "Apri app Chronos", nullptr, 64, 63);
}

// ---------------------------------------------------------------------
void drawIdle(GFXcanvas1 &c, const char *clock, const char *date,
              const char *status, const char *hint, int phoneBatt)
{
    c.fillScreen(OFF);
    if (status && *status) drawAt(c, status, nullptr, 0, 7);
    if (phoneBatt >= 0) drawBattery(c, 112, 0, phoneBatt);

    const char *clk = (clock && *clock) ? clock : "--:--";
    const GFXfont *f = fitFont(c, clk, CLOCK_FONTS, 2, 126);
    drawCentered(c, clk, f, 64, 45);

    if (date && *date) drawCentered(c, date, nullptr, 64, 55);
    if (hint && *hint) {
        char tmp[24];
        copyStr(tmp, sizeof(tmp), hint);
        truncateToWidth(c, tmp, nullptr, 128);
        drawCentered(c, tmp, nullptr, 64, 63);
    }
}

// ---------------------------------------------------------------------
//  Layout ICONA
//   +--------+-------------------+
//   | icona  |   350 m (grande)  |   y 0..29
//   | 48x48  |   ETA 10:42       |   y 33..40
//   |        |   12km 18min      |   y 42..48 (se c'e' spazio)
//   +--------+-------------------+
//   |   Via Roma (scorrevole)    |   y 49..63
//   +----------------------------+
// ---------------------------------------------------------------------
void drawNavIcon(GFXcanvas1 &c, const NavView &v, uint32_t nowMs)
{
    c.fillScreen(OFF);
    drawIconOrFallback(c, v, 0, 0);

    const int16_t rx = 50, rw = 128 - rx;
    drawBigDistance(c, v.distance, rx, rw, 27, BIG_FONTS, 3, &FreeSansBold9pt7b);

    char buf[32];
    if (v.eta[0]) {
        snprintf(buf, sizeof(buf), "ETA %s", v.eta);
        truncateToWidth(c, buf, nullptr, rw);
        drawCentered(c, buf, nullptr, rx + rw / 2, 40);
    }
    if (v.remaining[0] || v.duration[0]) {
        char rem[20], dur[20];
        copyStr(rem, sizeof(rem), v.remaining); compact(rem);
        copyStr(dur, sizeof(dur), v.duration);  compact(dur);
        snprintf(buf, sizeof(buf), "%s%s%s", rem, (rem[0] && dur[0]) ? " " : "", dur);
        if (textW(c, buf, nullptr) > rw) copyStr(buf, sizeof(buf), rem[0] ? rem : dur);
        truncateToWidth(c, buf, nullptr, rw);
        drawCentered(c, buf, nullptr, rx + rw / 2, 48);
    }

    // Riga via/istruzione: font 9pt bold, scorrevole se lunga
    drawLineScrolling(c, v.street, &FreeSansBold9pt7b, 0, 128, 62, nowMs);
    drawStale(c, v.stale, nowMs);
}

// ---------------------------------------------------------------------
void drawMiniMap(GFXcanvas1 &c, int16_t vx, int16_t vy, int16_t vw, int16_t vh,
                 int16_t ox, int16_t oy,
                 const MapPoint *route, uint8_t routeLen,
                 const MapSegment *branches, uint8_t branchCount,
                 float mpp)
{
    if (mpp <= 0.01f) mpp = MAP_METERS_PER_PX;
    // 1) strade laterali sottili (sotto)
    for (uint8_t i = 0; i < branchCount; i++)
        drawPolyline(c, branches[i].pts, branches[i].n, vx, vy, vw, vh, ox, oy, mpp, BRANCH_R);
    // 2) percorso da seguire, spesso
    drawPolyline(c, route, routeLen, vx, vy, vw, vh, ox, oy, mpp, ROUTE_R);
    // 3) posizione pilota
    drawPositionMarker(c, ox, oy);
}

// ---------------------------------------------------------------------
//  Layout MAPPA (stile AR-Track)
//   +-------+----------------+-------+
//   |  52   |   mini-mappa   |  ETA  |
//   | km/h  |  (percorso     | 10:42 |
//   |       |   spesso)  ^   |       |
//   |       |            |   | 12km  |
//   | 10:24 |   150 m        | 18min |
//   +-------+----------------+-------+
// ---------------------------------------------------------------------
void drawNavMap(GFXcanvas1 &c, const NavView &v, uint32_t nowMs)
{
    c.fillScreen(OFF);

    // --- Centro: mappa o, in mancanza di geometria, icona Chronos ---
    if (v.route && v.routeLen >= 2) {
        drawMiniMap(c, MAP_VX, MAP_VY, MAP_VW, MAP_VH, MAP_OX, MAP_OY,
                    v.route, v.routeLen, v.branches, v.branchCount, v.metersPerPx);
        // distanza alla manovra sotto la mappa (font grande)
        drawBigDistance(c, v.distance, MAP_VX, MAP_VW, 63, MID_FONTS, 2, nullptr);
    } else {
        // Fallback fase 1: solo icona Chronos 48x48 al centro (y 0..47) e
        // distanza in 9pt sotto (y 50..63) per non sovrapporsi all'icona.
        drawIconOrFallback(c, v, MAP_VX + (MAP_VW - HUD_ICON_SIZE) / 2, 0);
        drawBigDistance(c, v.distance, MAP_VX, MAP_VW, 63, &MID_FONTS[1], 1, nullptr);
    }

    // separatori tratteggiati
    for (int16_t y = 0; y < 64; y += 3) {
        c.drawPixel(COL_L_X + COL_W, y, ON);
        c.drawPixel(COL_R_X - 1, y, ON);
    }

    char buf[20];
    // --- Colonna sinistra: velocita' + orario ---
    copyStr(buf, sizeof(buf), v.speed[0] ? v.speed : "--");
    compact(buf);
    // tieni solo la parte numerica ("52 km/h" -> "52")
    for (char *p = buf; *p; p++) if ((*p < '0' || *p > '9') && *p != '-') { *p = '\0'; break; }
    if (!buf[0]) copyStr(buf, sizeof(buf), "--");
    const GFXfont *sf = fitFont(c, buf, MID_FONTS, 2, COL_W);
    drawCentered(c, buf, sf, COL_L_X + COL_W / 2, 20);
    drawCentered(c, "km/h", nullptr, COL_L_X + COL_W / 2, 31);
    if (v.clock[0]) drawCentered(c, v.clock, nullptr, COL_L_X + COL_W / 2, 63);

    // --- Colonna destra: ETA, distanza e tempo residui ---
    const int16_t rcx = COL_R_X + COL_W / 2;
    drawCentered(c, "ETA", nullptr, rcx, 7);
    if (v.eta[0]) {
        copyStr(buf, sizeof(buf), v.eta); compact(buf);
        truncateToWidth(c, buf, nullptr, COL_W);
        drawCentered(c, buf, nullptr, rcx, 18);
    }
    if (v.remaining[0]) {
        copyStr(buf, sizeof(buf), v.remaining); compact(buf);
        truncateToWidth(c, buf, nullptr, COL_W);
        drawCentered(c, buf, nullptr, rcx, 52);
    }
    if (v.duration[0]) {
        copyStr(buf, sizeof(buf), v.duration); compact(buf);
        if (textW(c, buf, nullptr) > COL_W) {         // "1h20min" -> "1h20'"
            char *m = strstr(buf, "min");
            if (m) { m[0] = '\''; m[1] = '\0'; }
        }
        truncateToWidth(c, buf, nullptr, COL_W);
        drawCentered(c, buf, nullptr, rcx, 63);
    }
    drawStale(c, v.stale, nowMs);
}

// ---------------------------------------------------------------------
//  Modalita' CURVA
//   - strada spessa (7 px) heading-up, pilota in basso al centro
//   - nell'angolo alto OPPOSTO al lato della curva (cosi' non copre la
//     strada): distanza grande all'inizio curva + riquadro bianco con
//     freccia e grado 1..6; sotto, "STRINGE" / "LUNGA" se presenti
//   - "DEMO" piccolo in basso se i dati sono simulati
// ---------------------------------------------------------------------
namespace {
const int16_t CURVE_ROAD_R = 3;
const int16_t INFO_W = 58;

// Freccia di svolta 15x16 (stile pace-note) in (x,y), dir +1 dx / -1 sx
void drawTurnArrow(GFXcanvas1 &c, int16_t x, int16_t y, int8_t dir, uint16_t col)
{
    auto X = [&](int16_t dx) { return (int16_t)(dir > 0 ? x + dx : x + 14 - dx); };
    auto rect = [&](int16_t dx, int16_t dy, int16_t w, int16_t h) {
        int16_t x0 = dir > 0 ? x + dx : x + 14 - dx - w + 1;
        c.fillRect(x0, y + dy, w, h, col);
    };
    rect(1, 6, 4, 10);                 // asta verticale
    rect(1, 6, 8, 4);                  // braccio orizzontale
    c.fillTriangle(X(8), y + 2, X(8), y + 13, X(14), y + 7, col);   // punta
}
} // namespace

void drawCurveScreen(GFXcanvas1 &c, const CurveView &v, uint32_t nowMs)
{
    (void)nowMs;
    c.fillScreen(OFF);

    if (v.message) {
        drawCentered(c, v.message, nullptr, 64, 36);
        if (v.demo) drawCentered(c, "DEMO", nullptr, 64, 63);
        return;
    }

    // --- strada ---
    float mpp = v.metersPerPx > 0.1f ? v.metersPerPx : 3.0f;
    const float r = CURVE_ROAD_R;
    for (int i = 0; i + 1 < v.n; i++) {
        float x0 = CURVE_RIDER_X + v.x[i] / mpp, y0 = CURVE_RIDER_Y - v.y[i] / mpp;
        float x1 = CURVE_RIDER_X + v.x[i + 1] / mpp, y1 = CURVE_RIDER_Y - v.y[i + 1] / mpp;
        if (!clipLine(x0, y0, x1, y1, r, r, 127 - r, 63 - r)) continue;
        thickLine(c, x0, y0, x1, y1, CURVE_ROAD_R);
    }
    // pilota
    const int16_t px = CURVE_RIDER_X, py = CURVE_RIDER_Y;
    c.fillTriangle(px, py - 9, px - 7, py + 6, px + 7, py + 6, OFF);
    c.fillTriangle(px, py - 6, px - 4, py + 4, px + 4, py + 4, ON);

    // --- blocco informazioni nell'angolo opposto alla curva ---
    const bool left = !(v.grade && v.dir < 0);       // curva a sx => info a dx
    char dist[12];
    if (v.distance < 0) snprintf(dist, sizeof(dist), "--");
    else if (v.distance >= 1000) snprintf(dist, sizeof(dist), "%d.%dk",
                                          (int)(v.distance / 1000), (int)((v.distance % 1000) / 100));
    else snprintf(dist, sizeof(dist), "%d", (int)v.distance);
    const GFXfont *df = fitFont(c, dist, BIG_FONTS, 2, INFO_W);
    int16_t dw = textW(c, dist, df);
    int16_t dx = left ? 0 : 127 - dw;
    c.fillRect(left ? 0 : dx - 2, 0, dw + 2, 27, OFF);
    drawAt(c, dist, df, dx, 24);

    if (v.grade) {
        char g[2] = {(char)('0' + v.grade), 0};
        const GFXfont *gf = &FreeSansBold18pt7b;
        int16_t gw = textW(c, g, gf);
        const int16_t bw = 4 + 15 + 3 + gw + 4, bh = 29, by = 27;
        int16_t bx = left ? 0 : 128 - bw;
        c.fillRect(left ? 0 : bx - 2, by - 1, bw + 2, bh + 2, OFF);   // stacco
        c.fillRoundRect(bx, by, bw, bh, 4, ON);
        drawTurnArrow(c, bx + 4, by + 7, v.dir, OFF);
        // cifra nera dentro il riquadro bianco
        Box b = bounds(c, g, gf);
        c.setFont(gf);
        c.setTextColor(OFF);
        c.setCursor(bx + 4 + 15 + 3 - b.x1, by + 27);
        c.print(g);
        c.setTextColor(ON);

        const char *flag = v.tightening && v.longCurve ? "LUNGA+STR"
                         : v.tightening ? "STRINGE" : v.longCurve ? "LUNGA" : nullptr;
        if (flag) {
            int16_t fw = textW(c, flag, nullptr);
            int16_t fx = left ? 0 : 128 - fw;
            c.fillRect(left ? 0 : fx - 1, 56, fw + 1, 8, OFF);
            drawAt(c, flag, nullptr, fx, 63);
        }
    }
    if (v.demo) {
        int16_t w = textW(c, "DEMO", nullptr);
        int16_t x = left ? 128 - w : 0;
        c.fillRect(left ? x - 1 : 0, 56, w + 1, 8, OFF);
        drawAt(c, "DEMO", nullptr, x, 63);
    }
}

} // namespace ui
