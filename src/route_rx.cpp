// =====================================================================
//  route_rx.cpp  -  Parser del formato BLE percorso (vedi route_rx.h)
// =====================================================================
#include "route_rx.h"
#include <string.h>

namespace curve {

namespace {
inline uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline int32_t rd32(const uint8_t *p)
{
    return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}
inline void wr16(uint8_t *p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
inline void wr32(uint8_t *p, int32_t v)
{
    uint32_t u = (uint32_t)v;
    p[0] = u & 0xFF; p[1] = (u >> 8) & 0xFF; p[2] = (u >> 16) & 0xFF; p[3] = u >> 24;
}
inline int32_t e7(double deg) { return (int32_t)(deg * 1e7 + (deg >= 0 ? 0.5 : -0.5)); }
} // namespace

RxResult RouteReceiver::feed(const uint8_t *d, size_t len)
{
    if (len < 1) return RxResult::ErrShort;
    switch (d[0]) {
    case 0x01: {                                   // BEGIN
        if (len < 11) return RxResult::ErrShort;
        uint16_t n = rd16(d + 1);
        // Il percorso a schermo non si tocca: si sostituisce solo a END.
        loading_ = false;
        received_ = 0;
        if (n < 2 || n > RX_MAX_PTS) return RxResult::ErrCount;
        originLat_ = rd32(d + 3);
        originLon_ = rd32(d + 7);
        loading_ = true;
        return RxResult::Ok;
    }
    case 0x02: {                                   // POINTS
        if (!loading_) return RxResult::ErrNoBegin;
        if (len < 4) return RxResult::ErrShort;
        uint16_t first = rd16(d + 1);
        uint8_t n = d[3];
        if (len < 4 + (size_t)n * 8) return RxResult::ErrShort;
        if (first != received_ || (int)received_ + n > RX_MAX_PTS) {
            loading_ = false;
            return RxResult::ErrSequence;
        }
        for (uint8_t i = 0; i < n; i++) {
            const uint8_t *p = d + 4 + i * 8;
            rawLat_[received_ + i] = rd32(p);
            rawLon_[received_ + i] = rd32(p + 4);
        }
        received_ += n;
        return RxResult::Ok;
    }
    case 0x03: {                                   // END
        if (!loading_) return RxResult::ErrNoBegin;
        if (len < 3) return RxResult::ErrShort;
        loading_ = false;
        if (rd16(d + 1) != received_) return RxResult::ErrCount;
        // L'origine e' anche il primo punto: il segmento nullo viene scartato.
        route_.begin();
        route_.addLatLon(originLat_ / 1e7, originLon_ / 1e7);
        for (uint16_t i = 0; i < received_; i++)
            route_.addLatLon(rawLat_[i] / 1e7, rawLon_[i] / 1e7);
        route_.finish();
        return RxResult::RouteReady;
    }
    case 0x04: {                                   // POS
        if (len < 13) return RxResult::ErrShort;
        lat_ = rd32(d + 1) / 1e7;
        lon_ = rd32(d + 5) / 1e7;
        speedKmh_ = rd16(d + 9) / 10.0f;
        courseDeg_ = rd16(d + 11) / 10.0f;
        hasPos_ = true;
        return RxResult::Position;
    }
    case 0x05:                                     // CLEAR
        route_.begin();
        loading_ = false;
        received_ = 0;
        guideOk_ = false;
        guideStreet_[0] = 0;
        sideCount_ = 0;
        return RxResult::Cleared;
    case 0x06: {                                   // GUIDE (icona / mappa)
        if (len < 13) return RxResult::ErrShort;
        uint8_t n = d[12];
        if (n > 72) return RxResult::ErrShort;
        if (len < (size_t)13 + n) return RxResult::ErrShort;
        guideTurn_ = d[1];
        uint16_t dist = rd16(d + 2);
        guideDist_ = dist == 0xFFFF ? -1 : (int)dist;
        guideRemain_ = (int)rd32(d + 4);
        if (guideRemain_ < 0) guideRemain_ = 0;
        guideDur_ = (int)rd16(d + 8);
        uint16_t eta = rd16(d + 10);
        guideEta_ = eta == 0xFFFF ? -1 : (int)eta;
        memcpy(guideStreet_, d + 13, n);
        guideStreet_[n] = 0;
        guideOk_ = true;
        return RxResult::Guide;
    }
    case 0x07:                                     // CTRL (layout, specchio, luce)
        if (len < 3) return RxResult::ErrShort;
        ctrlCmd_ = d[1];
        ctrlArg_ = d[2];
        return RxResult::Ctrl;
    case 0x08: {                                   // SIDES (diramazioni della mini-mappa)
        if (len < 3) return RxResult::ErrShort;
        if (d[1] & 1) sideCount_ = 0;
        uint8_t nSeg = d[2];
        size_t o = 3;
        for (uint8_t s = 0; s < nSeg; s++) {
            if (o >= len) return RxResult::ErrShort;
            uint8_t n = d[o++];
            if (n < 2 || n > SIDE_PTS) return RxResult::ErrShort;
            if (o + (size_t)n * 8 > len) return RxResult::ErrShort;
            if (sideCount_ < SIDE_SEGS) {
                sideN_[sideCount_] = n;
                for (uint8_t i = 0; i < n; i++) {
                    sideLat_[sideCount_][i] = rd32(d + o);
                    sideLon_[sideCount_][i] = rd32(d + o + 4);
                    o += 8;
                }
                sideCount_++;
            } else {
                o += (size_t)n * 8;
            }
        }
        return RxResult::Ok;
    }
    default:
        return RxResult::ErrType;
    }
}

size_t RouteReceiver::encodeBegin(uint8_t *o, uint16_t n, double lat0, double lon0)
{
    o[0] = 0x01; wr16(o + 1, n); wr32(o + 3, e7(lat0)); wr32(o + 7, e7(lon0));
    return 11;
}

size_t RouteReceiver::encodePoints(uint8_t *o, uint16_t first, const double *lat, const double *lon, uint8_t n)
{
    o[0] = 0x02; wr16(o + 1, first); o[3] = n;
    for (uint8_t i = 0; i < n; i++) { wr32(o + 4 + i * 8, e7(lat[i])); wr32(o + 8 + i * 8, e7(lon[i])); }
    return 4 + (size_t)n * 8;
}

size_t RouteReceiver::encodeEnd(uint8_t *o, uint16_t n)
{
    o[0] = 0x03; wr16(o + 1, n);
    return 3;
}

size_t RouteReceiver::encodePos(uint8_t *o, double lat, double lon, float kmh, float course)
{
    o[0] = 0x04; wr32(o + 1, e7(lat)); wr32(o + 5, e7(lon));
    wr16(o + 9, (uint16_t)(kmh * 10 + 0.5f)); wr16(o + 11, (uint16_t)(course * 10 + 0.5f));
    return 13;
}

} // namespace curve
