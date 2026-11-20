// =====================================================================
//  preview_main.cpp  -  Genera le anteprime delle schermate sul PC.
//  Compila gli STESSI sorgenti del firmware (src/hud_ui.cpp,
//  src/demo_data.cpp) con Adafruit GFX reale e scrive file PBM 128x64
//  (con mirror opzionale, stessa logica di display.cpp). render_previews.py
//  li converte in PNG ingranditi 4x.
// =====================================================================
#include <stdio.h>
#include "hud_ui.h"
#include "demo_data.h"
#include "config.h"
#include "curve_view.h"
#include <string.h>

static void savePbm(GFXcanvas1 &c, const char *path, bool mirrorX)
{
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); return; }
    fprintf(f, "P1\n128 64\n");
    for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 128; x++) {
            int sx = mirrorX ? 127 - x : x;
            fputc(c.getPixel(sx, y) ? '1' : '0', f);
        }
        fputc('\n', f);
    }
    fclose(f);
    printf("wrote %s\n", path);
}

int main(int argc, char **argv)
{
    const char *out = argc > 1 ? argv[1] : ".";
    char path[512];
    GFXcanvas1 c(128, 64);
    NavView v;

    ui::drawSplash(c, HUD_BLE_NAME, HUD_FW_VERSION);
    snprintf(path, sizeof(path), "%s/splash.pbm", out); savePbm(c, path, false);

    ui::drawWaiting(c, HUD_BLE_NAME, false, 1300);   // 3 puntini + onde
    snprintf(path, sizeof(path), "%s/waiting.pbm", out); savePbm(c, path, false);

    ui::drawIdle(c, "10:24", "Dom 4 ott", "Chronos OK", "Avvia navigazione", 80);
    snprintf(path, sizeof(path), "%s/idle.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "350 m", "Via Roma", false);
    ui::drawNavIcon(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_icon.pbm", out); savePbm(c, path, false);
    snprintf(path, sizeof(path), "%s/nav_icon_mirror.pbm", out); savePbm(c, path, true);

    // via lunga: frame a meta' scorrimento
    demo::fillNav(v, "80 m", "Alla rotonda prendi la seconda uscita", false);
    v.hasIcon = false;
    v.maneuver = 0x12;
    ui::drawNavIcon(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_roundabout_2.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "40 m", "Alla rotonda prendi la prima uscita", false);
    v.hasIcon = false;
    v.maneuver = 0x11;
    ui::drawNavIcon(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_roundabout_1.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "120 m", "Alla rotonda prendi la terza uscita", false);
    v.hasIcon = false;
    v.maneuver = 0x13;
    ui::drawNavIcon(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_roundabout_3.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "1,2 km", "Via Nazionale - poi SS12 direzione Verona", false);
    ui::drawNavIcon(c, v, 3000);
    snprintf(path, sizeof(path), "%s/nav_icon_long.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "75 m", "Via Roma", true);
    ui::drawNavMap(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_map.pbm", out); savePbm(c, path, false);

    demo::fillNav(v, "350 m", "Via Roma", false);   // nessuna geometria
    ui::drawNavMap(c, v, 0);
    snprintf(path, sizeof(path), "%s/nav_map_fallback.pbm", out); savePbm(c, path, false);
    // ---- Modalita' CURVA (strada demo, stesso codice del firmware) ----
    static curve::Route road;
    curve::buildMountainRoad(road);
    static CurveView cv;
    struct Shot { int curveIdx; float before; const char *name; };
    const Shot shots[] = {
        {0, 150, "curve_d3_150m"},        // destra 3 a 150 m
        {1, 60, "curve_hairpin_s1_60m"},  // tornante sinistro 1 a 60 m
        {2, 100, "curve_tightening"},     // destra che stringe a 100 m
    };
    for (const Shot &sh : shots) {
        float s = road.curveAt(sh.curveIdx).startS - sh.before;
        curve::buildView(road, s, cv);
        cv.demo = true;
        ui::drawCurveScreen(c, cv, 0);
        snprintf(path, sizeof(path), "%s/%s.pbm", out, sh.name); savePbm(c, path, false);
    }

    // ---- GIF: un giro completo del pilota simulato ----
    // Il firmware ridisegna ogni FRAME_MS (120 ms); la GIF e' a velocita' 2x:
    // 0,24 s simulati per fotogramma, mostrati per 120 ms.
    if (argc > 2 && strcmp(argv[2], "gif") == 0) {
        curve::DemoRider rider;
        float zoom = 0;
        int frame = 0;
        float lastS = -1;
        while (frame < 2000) {
            curve::buildView(road, rider.s, cv, &zoom);
            cv.demo = true;
            ui::drawCurveScreen(c, cv, (uint32_t)(frame * 240));
            snprintf(path, sizeof(path), "%s/gif_%04d.pbm", out, frame);
            savePbm(c, path, false);
            frame++;
            lastS = rider.s;
            rider.step(road, 0.24f);
            if (rider.s < lastS) break;          // giro completato
        }
    }
    return 0;
}
