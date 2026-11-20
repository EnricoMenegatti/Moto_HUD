// =====================================================================
//  display.cpp  -  Driver OLED SSD1306 con blit specchiato
// =====================================================================
#include "display.h"
#include "config.h"

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_SSD1306.h>
#include <string.h>

namespace {

// clkDuring = clkAfter = I2C_HZ: la libreria non alza la frequenza durante
// il trasferimento (default Adafruit 400 kHz), resta sempre a 100 kHz.
Adafruit_SSD1306 oled(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET_PIN, I2C_HZ, I2C_HZ);
GFXcanvas1 frame(OLED_WIDTH, OLED_HEIGHT);

bool mirrorX = MIRROR_X;
bool mirrorY = MIRROR_Y;
bool ready = false;
bool panelOn = true;

// Comando e argomento nello stesso pacchetto I2C. Due ssd1306_command() di
// seguito passano da un cambio di clock e diversi moduli buttano via l'argomento:
// il contrasto restava quello messo da begin() e cambiare config non faceva nulla.
void sendPair(uint8_t cmd, uint8_t arg)
{
    Wire.beginTransmission(OLED_ADDR);
    Wire.write((uint8_t)0x00);
    Wire.write(cmd);
    Wire.write(arg);
    Wire.endTransmission();
}

} // namespace

namespace hud {

bool displayBegin()
{
    Wire.begin(PIN_SDA, PIN_SCL);
    Wire.setClock(I2C_HZ);
    // periphBegin=false: Wire e' gia' inizializzato con i nostri pin
    if (!oled.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR, true, false)) {
        ready = false;
        return false;
    }
    ready = true;
    oled.invertDisplay(INVERT);
    oled.clearDisplay();
    oled.display();
    setContrast(OLED_CONTRAST);
    return true;
}

GFXcanvas1 &canvas() { return frame; }

void setContrast(uint8_t value)
{
    if (!ready) return;
    uint8_t contrast = value;
    uint8_t pre = OLED_PRECHARGE;
    uint8_t vcom = OLED_VCOMH;
    if (pre == 0xF1 && vcom == 0x40) {
        // 0x10 .. 0xCF. 0xFF su questo modulo a 3.3 V spegne di fatto il pannello.
        contrast = (uint8_t)(0x10 + ((uint16_t)value * (0xCF - 0x10)) / 255);
        if (value >= 48) {
            pre = 0xF1;
            vcom = 0x40;
        } else {
            uint8_t phase2 = 4 + ((uint16_t)value * 11) / 48;
            pre = (uint8_t)((phase2 << 4) | 0x01);
            vcom = 0x20;
        }
    }
    sendPair(SSD1306_CHARGEPUMP, 0x14);
    sendPair(SSD1306_SETCONTRAST, contrast);
    sendPair(SSD1306_SETPRECHARGE, pre);
    sendPair(SSD1306_SETVCOMDETECT, vcom);
    oled.ssd1306_command(SSD1306_DISPLAYON);
    Serial.printf("OLED livello=%u contrasto=%02X pre=%02X vcom=%02X\n",
                  value, contrast, pre, vcom);
}

void setMirror(bool mx, bool my)
{
    mirrorX = mx;
    mirrorY = my;
}

bool mirrorXEnabled() { return mirrorX; }

void displayPower(bool on)
{
    if (!ready) return;
    panelOn = on;
    oled.ssd1306_command(on ? SSD1306_DISPLAYON : SSD1306_DISPLAYOFF);
    if (on) present(true);
}

// Il canvas e' "row-major" (16 byte per riga, MSB = pixel a sinistra);
// il buffer SSD1306 e' a pagine (1 byte = 8 pixel in verticale,
// indice = x + (y/8)*128). Convertiamo pixel per pixel applicando il
// mirror: 8192 pixel, ~1 ms su ESP32 => trascurabile rispetto all'I2C.
void present(bool force)
{
    if (!ready || !panelOn) return;
    const uint8_t *src = frame.getBuffer();
    // Se il frame e' identico al precedente non occupiamo il bus I2C
    static uint8_t last[OLED_WIDTH * OLED_HEIGHT / 8];
    static bool haveLast = false;
    if (!force && haveLast && memcmp(last, src, sizeof(last)) == 0) return;
    memcpy(last, src, sizeof(last));
    haveLast = true;

    uint8_t *dst = oled.getBuffer();
    memset(dst, 0, OLED_WIDTH * OLED_HEIGHT / 8);
    const int bpr = (OLED_WIDTH + 7) / 8;
    for (int y = 0; y < OLED_HEIGHT; y++) {
        const int dy = mirrorY ? (OLED_HEIGHT - 1 - y) : y;
        const uint8_t *row = src + y * bpr;
        uint8_t *page = dst + (dy >> 3) * OLED_WIDTH;
        const uint8_t bit = 1 << (dy & 7);
        for (int xb = 0; xb < bpr; xb++) {
            uint8_t b = row[xb];
            if (!b) continue;                       // salta byte vuoti
            for (int k = 0; k < 8; k++) {
                if (b & (0x80 >> k)) {
                    int x = xb * 8 + k;
                    int dx = mirrorX ? (OLED_WIDTH - 1 - x) : x;
                    page[dx] |= bit;
                }
            }
        }
    }
    oled.display();
}

} // namespace hud
