# Invio di un percorso reale all'HUD (protocollo in src/route_rx.h).
#
# Chronos non manda la forma della strada. Questo script si collega in BLE
# a "HUD-Moto", invia i punti di un GPX e poi sposta il pilota lungo la
# traccia, cosi' il layout CURVA calcola curve e gradi veri.
# Chronos puo' restare connesso: icona e mappa continuano da li'.
#
#   pip install bleak
#   python tools/send_route.py percorso.gpx --simulate 70
#   python tools/send_route.py --osrm 45.87,11.10 45.80,11.20 --simulate 70
#
# --simulate e' la prova al banco: non serve muoversi. Senza, lo script
# invia solo il percorso e si ferma (manca la posizione: a schermo "ATTESA GPS").

import argparse
import asyncio
import math
import struct
import sys
import time
import xml.etree.ElementTree as ET

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit("Manca bleak. Installa con: pip install bleak")

CHAR_UUID = "8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00"
NAME = "HUD-Moto"


def e7(deg):
    return int(round(deg * 1e7))


def hav(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = math.radians(b[0] - a[0]), math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def parse_gpx(path):
    pts = []
    for el in ET.parse(path).iter():
        if el.tag.endswith("trkpt") or el.tag.endswith("rtept"):
            pts.append((float(el.attrib["lat"]), float(el.attrib["lon"])))
    if len(pts) < 2:
        sys.exit(f"Nessun percorso in {path} (servono trkpt o rtept).")
    return pts


def fetch_osrm(a, b):
    import json
    import urllib.request
    url = (
        "https://router.project-osrm.org/route/v1/driving/"
        f"{a[1]},{a[0]};{b[1]},{b[0]}?overview=full&geometries=geojson"
    )
    with urllib.request.urlopen(url, timeout=20) as resp:
        data = json.load(resp)
    coords = data["routes"][0]["geometry"]["coordinates"]
    pts = [(lat, lon) for lon, lat in coords]
    if len(pts) < 2:
        sys.exit("OSRM non ha restituito un percorso.")
    return pts


def thin(pts, step):
    kept = [pts[0]]
    for p in pts[1:]:
        if hav(kept[-1], p) >= step:
            kept.append(p)
    if hav(kept[-1], pts[-1]) > 1.0:
        kept.append(pts[-1])
    return kept


def fit_hud(pts):
    # Il firmware tiene 12 km ricampionati ogni 5 m (2400 campioni).
    step = 8.0
    kept = thin(pts, step)
    while len(kept) > 2300 and step < 40:
        step += 4
        kept = thin(pts, step)
    if len(kept) > 2300:
        kept = kept[:2300]
        print("Percorso troncato a circa 12 km: oltre il firmware non va.")
    return kept


def length_m(pts):
    return sum(hav(a, b) for a, b in zip(pts, pts[1:]))


def pack_begin(n, lat0, lon0):
    return struct.pack("<BHii", 0x01, n & 0xFFFF, e7(lat0), e7(lon0))


def pack_points(first, chunk):
    buf = struct.pack("<BHB", 0x02, first & 0xFFFF, len(chunk))
    for lat, lon in chunk:
        buf += struct.pack("<ii", e7(lat), e7(lon))
    return buf


def pack_end(n):
    return struct.pack("<BH", 0x03, n & 0xFFFF)


def pack_pos(lat, lon, kmh, course):
    spd = max(0, min(65535, int(kmh * 10 + 0.5)))
    crs = int(course * 10 + 0.5) % 3600
    return struct.pack("<BiiHH", 0x04, e7(lat), e7(lon), spd, crs)


def at_distance(pts, cum, dist):
    dist %= cum[-1]
    lo, hi = 0, len(cum) - 1
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if cum[mid] <= dist:
            lo = mid
        else:
            hi = mid
    span = cum[hi] - cum[lo]
    t = 0.0 if span < 1e-3 else (dist - cum[lo]) / span
    lat = pts[lo][0] + (pts[hi][0] - pts[lo][0]) * t
    lon = pts[lo][1] + (pts[hi][1] - pts[lo][1]) * t
    return lat, lon, bearing(pts[lo], pts[hi])


async def write(client, data):
    await client.write_gatt_char(CHAR_UUID, data, response=True)


async def send_route(client, pts):
    mtu = getattr(client, "mtu_size", 23) or 23
    per = max(1, min(30, (mtu - 3 - 4) // 8))
    print(f"MTU {mtu}, {per} punti per pacchetto, {len(pts)} punti")
    await write(client, pack_begin(len(pts), pts[0][0], pts[0][1]))
    sent = 0
    while sent < len(pts):
        chunk = pts[sent:sent + per]
        await write(client, pack_points(sent, chunk))
        sent += len(chunk)
    await write(client, pack_end(len(pts)))
    print("Percorso inviato. Sul monitor seriale: [ROUTE] percorso ...")


async def simulate(client, pts, kmh):
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + hav(a, b))
    if cum[-1] < 20:
        sys.exit("Percorso troppo corto.")
    print(f"Simulazione a {kmh:.0f} km/h su {cum[-1]/1000:.2f} km. Ctrl+C per fermare.")
    v = kmh / 3.6
    s = 0.0
    last = time.monotonic()
    laps = 0
    while True:
        now = time.monotonic()
        s += v * (now - last)
        last = now
        if s >= cum[-1]:
            s -= cum[-1]
            laps += 1
            print(f"giro {laps}")
        lat, lon, crs = at_distance(pts, cum, s)
        await write(client, pack_pos(lat, lon, kmh, crs))
        await asyncio.sleep(0.25)


async def main():
    ap = argparse.ArgumentParser(description="Invia un GPX all'HUD e simula il movimento.")
    ap.add_argument("gpx", nargs="?", help="file GPX (trkpt o rtept)")
    ap.add_argument("--osrm", nargs=2, metavar=("LAT,LON", "LAT,LON"),
                    help="chiede il percorso stradale a OSRM fra due punti")
    ap.add_argument("--simulate", type=float, metavar="KMH",
                    help="fai scorrere il pilota a questa velocita', senza muoverti")
    ap.add_argument("--name", default=NAME, help="nome BLE della scheda")
    args = ap.parse_args()

    if args.osrm:
        def ll(s):
            lat, lon = s.split(",")
            return float(lat), float(lon)
        pts = fetch_osrm(ll(args.osrm[0]), ll(args.osrm[1]))
    elif args.gpx:
        pts = parse_gpx(args.gpx)
    else:
        ap.error("serve un file GPX oppure --osrm LAT,LON LAT,LON")

    pts = fit_hud(pts)
    print(f"{len(pts)} punti, {length_m(pts)/1000:.2f} km")

    print(f"Cerco {args.name}...")
    dev = await BleakScanner.find_device_by_name(args.name, timeout=12)
    if dev is None:
        sys.exit(f"{args.name} non trovato. Scheda accesa? Bluetooth del PC acceso?")
    async with BleakClient(dev) as client:
        await send_route(client, pts)
        if args.simulate:
            await simulate(client, pts, args.simulate)
        else:
            print("Posizione non inviata: layout CURVA in ATTESA GPS. Aggiungi --simulate 70.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nstop")
