// =====================================================================
//  display.h  -  Driver OLED SSD1306 + framebuffer GFXcanvas1
//
//  Tutte le schermate vengono disegnate su un canvas 128x64 in RAM; poi
//  present() lo copia nel buffer dell'SSD1306 applicando, se richiesto,
//  lo specchio orizzontale (MIRROR_X, per beam splitter) e/o verticale.
// =====================================================================
#pragma once
#include <Adafruit_GFX.h>

namespace hud {

bool displayBegin();                 // init I2C + SSD1306, false se non trovato
GFXcanvas1 &canvas();                // canvas su cui disegnare
void present(bool force = false);   // blit canvas -> OLED (con mirror), salta se invariato
void setContrast(uint8_t value);     // 0 spento .. 255 sole
void setMirror(bool mx, bool my);    // cambia mirror a runtime
bool mirrorXEnabled();
void displayPower(bool on);

} // namespace hud
