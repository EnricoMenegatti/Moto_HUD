# Note di progetto – HUD moto DIY (contesto per Cursor/AI)

Aggiornato il **20 novembre 2026**, prima di pubblicare il progetto su GitHub e riprendere da lì. Questo file è lo stato corrente. I diari `TEST_TELEFONO_ESP.md` e `TEST_APP_EMULATORE.md` arrivano al 7 ottobre e non vanno letti come il piano di oggi.

## Cartelle

Un solo albero: `Navigatore-moto` (su questo PC: `c:\Users\LENOVO\Nextcloud\DIY\Navigatore-moto`).

| Cartella | Contenuto |
|---|---|
| `HUD-firmware` | Firmware ESP32 e app Android `it.diy.hudnav` (`android/`) |
| `HUD-scatola-banco` | Scatola 3D da banco, ESP32 DevKit + OLED 0,96". Coperchio v2 del 18/10/2026 |
| `HUD-banco-ottico` | Banco ottico e modulo casco. La versione 0,96"/Ø25 è in `old/` |

Il workspace Cursor apre due volte `HUD-firmware` (cartella padre + radice `HUD-firmware` in `Navigatore-moto.code-workspace`). È lo stesso disco. Su GitHub va **un** repository della cartella padre, non una seconda copia del firmware.

## Obiettivo

Navigatore moto minimal per Enrico. Due forme:

1. **HUD monoculare nel casco Shark Spartan** (integrale) – forma preferita.
2. Display sul manubrio – piano B / banco attuale.

Uso principale: aiuto alla **guida sportiva** (forma della curva e distanza), più turn-by-turn quotidiano.

## Hardware

- Banco: **ESP32 classico (`esp32dev`)** + **OLED 0,96" SSD1306 128×64 I2C** (SDA 21, SCL 22, 0x3C, I2C 100 kHz). Scheda di test: COM7, MAC BLE `c8:2e:18:8e:b2:fa`, nome `HUD-Moto`. Telefono: Pixel 4a.
- Target casco: Seeed **XIAO ESP32-C3** (SDA GPIO6 / SCL GPIO7) + micro OLED **0,49" 64×32 SSD1306**, lente Ø14 f20 (opz. Ø15 f25), beam splitter **25×25×1 mm 50/50**.
- Elettronica (ESP32 + LiPo 500 mAh + USB-C) nel vano interfono posteriore dello Spartan; modulo ottico sulla mentoniera; cavo 4 fili ~45 cm (3V3, GND, SDA, SCL).
- Ottica: OLED alla focale della lente, immagine collimata, vetrino a 45°. Immagine **specchiata** (`MIRROR_X`).
- Meccanica: `HUD-banco-ottico/` e `HUD-scatola-banco/`.

## Software in uso

Due pezzi, entrambi in `HUD-firmware`, e parlano già tra loro.

- **App** `it.diy.hudnav` (`android/`). Percorso con Valhalla (profilo moto; senza autostrada se richiesto), limiti prima da Valhalla e poi da Overpass a pezzi, manovre e voce in italiano, simulazione a 40/70/110 km/h. Cliente GATT in `HudBle.kt`.
- **Firmware** ancora etichettato **1.1.0** (`include/config.h`). `ROUTE_BLE_ENABLE` è **1**. Il parser è `src/route_rx.cpp` (pacchetti `0x01`–`0x05`, servizio `8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00`, caratteristica `…0002`). Provato sul banco il 5–7 ottobre: finestre di percorso, posizione, layout dal pannello Display.
- ChronosESP32 è **ancora compilato** (`platformio.ini`, `src/main.cpp`): icona 48×48, orologio, testo «Nav OFF: vedi Chronos». Non è il canale su cui si continua. La geometria della strada arriva dall'app.
- Layout: tasto BOOT breve = ICONA → MAPPA → CURVA; lungo (>1,5 s) = toggle `MIRROR_X`. Salvati in NVS.
- `DEMO_MODE 0`. In CURVA senza rotta (`CURVE_DEMO_IF_NO_ROUTE 1`) gira la strada demo.

`docs/SPEC_PLUGIN_OSMAND_HUD.md` (11 ottobre 2026) descrive un firmware senza Chronos e un plugin OsmAnd. **Non è stato fatto** e non è il passo successivo. Si continua su questa app e su questo firmware.

## Cosa reggeva all'ultimo giro (7 ottobre 2026)

- Collegamento BLE, un percorso a finestre senza rinvii doppi, posizione, cambio layout da BOOT e dal telefono.
- Sull'app: frasi intere, icona rotonda (non una freccia), disco limite «50» sul viaggio lungo verso Via Enrico Bernardi (Valhalla; Overpass quel giorno non rispondeva).
- Seconda e terza finestra di percorso: sul seriale arrivano intere, a trasferimento chiuso. «CARICO PERCORSO» non deve coprire una strada già pronta. L'OLED in quel passaggio **non è stato guardato**.

## Da riprendere dopo GitHub

Tre difetti visti in guida la sera del 7 ottobre. Nel codice sono stati ritoccati; sull'OLED non sono stati ricontrollati.

1. **MAPPA.** Le strade vicine compaiono e spariscono. Vanno tenute sottili, almeno vicino alle svolte, finché l'incrocio non è passato. Non si fermano più dopo un salto di 90 m.
2. **Cavalcavia.** La posizione si agganciava alla strada sotto. Nel codice resta sul tratto in corso; se ricalcola, manda anche la direzione.
3. **Rotonda.** All'ingresso l'icona sull'HUD era una svolta. Nel codice una svolta entro 80 m prima della rotonda diventa la rotonda.

Non bloccanti, lasciati lì:

- Luminosità OLED già al massimo (contrasto 255, precharge `0xF1`, VCOMH `0x40`). Charge pump `0x8D`/`0x95` non provato.
- La stringa di versione è ancora `1.1.0`.
- Aprire la seriale a 115200 sul CP210x **resetta** la scheda e fa cadere il BLE.

## GitHub

Intenzione del 20 novembre 2026: un repository della cartella `Navigatore-moto`, poi si riprende dai tre punti sopra. Il repository non è ancora stato creato.

Fuori dal repository (i `.gitignore` di `HUD-firmware` e di `android/` già li escludono): `.pio/`, `.vscode/`, `android/.gradle/`, `android/app/build/`, `local.properties`. STL e STEP delle cartelle meccaniche si pubblicano.

## Note sicurezza

Ausilio indicativo: GPS ~1 s di ritardo (~20 m a 70 km/h), nessuna info su fondo o bagnato. Non usarlo per decidere quando frenare.
