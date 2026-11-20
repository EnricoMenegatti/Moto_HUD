#!/usr/bin/env python3
"""Compila il codice UI del firmware per PC e genera le anteprime PNG 4x.

Uso:  python3 tools/render_previews.py
Richiede: g++, Pillow, e le librerie scaricate da PlatformIO (pio pkg install)
oppure GFX_DIR=<cartella Adafruit GFX Library>.
I PNG simulano un OLED bianco su nero (pixel leggermente separati).
"""
import glob, os, subprocess, sys, tempfile
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCALE = 4

def find_gfx():
    if os.environ.get("GFX_DIR"):
        return os.environ["GFX_DIR"]
    hits = glob.glob(os.path.join(ROOT, ".pio", "libdeps", "*", "Adafruit GFX Library"))
    if not hits:
        sys.exit("Adafruit GFX non trovata: esegui prima 'pio pkg install' o imposta GFX_DIR")
    return hits[0]

def main():
    gfx = find_gfx()
    tmp = tempfile.mkdtemp(prefix="hudprev_")
    exe = os.path.join(tmp, "preview")
    cmd = ["g++", "-std=c++17", "-O1", "-w", "-DARDUINO=100",
           "-I", os.path.join(ROOT, "tools", "host_shim"),
           "-I", gfx, "-I", os.path.join(ROOT, "include"), "-I", os.path.join(ROOT, "src"),
           os.path.join(ROOT, "tools", "preview_main.cpp"),
           os.path.join(ROOT, "src", "hud_ui.cpp"),
           os.path.join(ROOT, "src", "demo_data.cpp"),
           os.path.join(ROOT, "src", "curve.cpp"),
           os.path.join(ROOT, "src", "curve_view.cpp"),
           os.path.join(gfx, "Adafruit_GFX.cpp"),
           "-o", exe]
    subprocess.check_call(cmd)
    subprocess.check_call([exe, tmp, "gif"], stdout=subprocess.DEVNULL)
    for pbm in sorted(glob.glob(os.path.join(tmp, "*.pbm"))):
        if os.path.basename(pbm).startswith("gif_"):
            continue
        name = os.path.splitext(os.path.basename(pbm))[0]
        rows = open(pbm).read().split("\n")[2:2 + 64]
        img = Image.new("RGB", (128 * SCALE, 64 * SCALE), (8, 8, 12))
        px = img.load()
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch == "1":
                    for dy in range(SCALE - 1):       # 1 px di "griglia"
                        for dx in range(SCALE - 1):
                            px[x * SCALE + dx, y * SCALE + dy] = (235, 245, 255)
        out = os.path.join(ROOT, f"preview_{name}.png")
        img.save(out)
        print("PNG:", out)

    # GIF animata della demo CURVA (2 colori, scala 4x senza griglia)
    frames = []
    for pbm in sorted(glob.glob(os.path.join(tmp, "gif_*.pbm"))):
        rows = open(pbm).read().split("\n")[2:2 + 64]
        im = Image.new("P", (128, 64), 0)
        im.putpalette([8, 8, 12, 235, 245, 255] + [0] * 762)
        im.putdata([1 if ch == "1" else 0 for row in rows for ch in row[:128]])
        frames.append(im.resize((128 * SCALE, 64 * SCALE), Image.NEAREST))
    if frames:
        out = os.path.join(ROOT, "preview_curve_demo.gif")
        frames[0].save(out, save_all=True, append_images=frames[1:], duration=120, loop=0, optimize=False)
        print("GIF:", out, len(frames), "fotogrammi")

    # test del rilevatore di curve
    texe = os.path.join(tmp, "test_curves")
    subprocess.check_call(["g++", "-std=c++17", "-O1", "-w", "-DARDUINO=100",
                           "-I", os.path.join(ROOT, "tools", "host_shim"), "-I", gfx,
                           "-I", os.path.join(ROOT, "include"), "-I", os.path.join(ROOT, "src"),
                           os.path.join(ROOT, "tools", "test_curves.cpp"),
                           os.path.join(ROOT, "src", "curve.cpp"),
                           os.path.join(ROOT, "src", "curve_view.cpp"),
                           os.path.join(ROOT, "src", "route_rx.cpp"),
                           os.path.join(ROOT, "src", "hud_ui.cpp"),
                           os.path.join(gfx, "Adafruit_GFX.cpp"), "-o", texe])
    if "--no-test" not in sys.argv:
        subprocess.call([texe])

if __name__ == "__main__":
    main()
