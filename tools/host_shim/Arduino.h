// Shim minimale di Arduino.h per compilare Adafruit GFX + hud_ui sul PC
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <algorithm>
#ifndef ARDUINO
#define ARDUINO 100
#endif
#define PROGMEM
#define pgm_read_byte(a) (*(const uint8_t *)(a))
#define pgm_read_word(a) (*(const uint16_t *)(a))
#define pgm_read_dword(a) (*(const uint32_t *)(a))
#define pgm_read_pointer(a) (*(void *const *)(a))
typedef bool boolean;
typedef uint8_t byte;
class __FlashStringHelper;
#define radians(d) ((d) * M_PI / 180.0)
#define degrees(r) ((r) * 180.0 / M_PI)
using std::min;
using std::max;
#include <string>
// String minimale (Adafruit GFX dichiara overload con String)
class String {
public:
    String(const char *s = "") : s_(s ? s : "") {}
    const char *c_str() const { return s_.c_str(); }
    unsigned int length() const { return (unsigned int)s_.size(); }
private:
    std::string s_;
};
#include "Print.h"
