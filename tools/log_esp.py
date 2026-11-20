# Copia il seriale della scheda in logs/esp.log (115200).
#   python tools/log_esp.py COM7
import sys
import time
from pathlib import Path

try:
    import serial
except ImportError:
    sys.exit("Manca pyserial.")

port = sys.argv[1] if len(sys.argv) > 1 else "COM7"
path = Path(__file__).resolve().parents[1] / "logs" / "esp.log"
path.parent.mkdir(exist_ok=True)
ser = serial.Serial(port, 115200, timeout=1)
print(f"seriale {port} -> {path}", flush=True)
with path.open("a", encoding="utf-8") as f:
    f.write(f"\n--- {time.strftime('%Y-%m-%d %H:%M:%S')} {port} ---\n")
    f.flush()
    while True:
        line = ser.readline().decode("utf-8", "replace")
        if not line:
            continue
        f.write(line)
        f.flush()
        print(line, end="", flush=True)
