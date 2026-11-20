# HUD-Moto – firmware e app Android

Navigatore "head-up" fai-da-te per la moto: un **ESP32** con un **OLED SSD1306
0,96" 128×64 I2C** riceve via Bluetooth LE percorso e posizione dall'app
Android **HUD Nav** (`it.diy.hudnav`, cartella `android/`) e li mostra in modo
leggibile a colpo d'occhio, anche riflessi su un vetrino (beam splitter).

Lo stato da cui si riparte, e la pubblicazione su GitHub della cartella padre
`Navigatore-moto`, sono in `NOTE_PROGETTO.md` (aggiornato il 20 novembre 2026).
I diari di test arrivano al 7 ottobre.

Il firmware compila ancora **ChronosESP32** (icona, orologio, testi). Dal 4
ottobre 2026 la geometria della strada, le manovre e la simulazione arrivano
dall'app propria (`ROUTE_BLE_ENABLE 1`). Si continua su quell'app, non su Chronos
e non sul plugin OsmAnd descritto in `docs/SPEC_PLUGIN_OSMAND_HUD.md`.

---

## Anteprime (renderizzate con lo stesso codice del firmware, scala 4×)

| Schermata | File |
|---|---|
| Avvio (splash) con nome BLE | `preview_splash.png` |
| In attesa del telefono | `preview_waiting.png` |
| Connesso, nessuna navigazione: orologio | `preview_idle.png` |
| Navigazione – layout ICONA ("350 m", "Via Roma") | `preview_nav_icon.png` |
| Layout ICONA con via lunga (frame a metà scorrimento) | `preview_nav_icon_long.png` |
| Layout ICONA specchiato (MIRROR_X=1, come appare sul display) | `preview_nav_icon_mirror.png` |
| Navigazione – layout MAPPA stile AR-Track (strada tortuosa + incrocio) | `preview_nav_map.png` |
| Layout MAPPA senza geometria (fallback: icona Chronos al centro) | `preview_nav_map_fallback.png` |
| CURVA – destra grado 3 a 150 m | `preview_curve_d3_150m.png` |
| CURVA – tornante sinistro grado 1 a 60 m | `preview_curve_hairpin_s1_60m.png` |
| CURVA – destra che stringe (grado 2, "STRINGE") a 100 m | `preview_curve_tightening.png` |
| CURVA – animazione di un giro della strada demo (velocità 2×) | `preview_curve_demo.gif` |

Per rigenerarle: `python3 tools/render_previews.py` (serve `g++` e Pillow, e le
librerie scaricate da PlatformIO; lo stesso script esegue anche i test del
rilevatore di curve, `--no-test` per saltarli). Lo script compila sul PC **gli stessi file**
`src/hud_ui.cpp` e `src/demo_data.cpp` usati dal firmware con la vera Adafruit
GFX, quindi l'anteprima corrisponde pixel per pixel a ciò che disegna l'ESP32.

---

## Hardware e collegamenti

Display OLED SSD1306 128×64 I2C a 4 pin (GND, VCC, SCL, SDA), indirizzo 0x3C.

### ESP32 DevKit (WROOM-32) – ambiente `esp32dev`

| OLED | ESP32 DevKit |
|---|---|
| GND | GND |
| VCC | 3V3 |
| SDA | GPIO 21 |
| SCL | GPIO 22 |
| (pulsante opz.) | tasto **BOOT** già presente (GPIO 0) |

### Seeed XIAO ESP32-C3 – ambiente `xiao_esp32c3`

| OLED | XIAO ESP32-C3 |
|---|---|
| GND | GND |
| VCC | 3V3 |
| SDA | **D4 = GPIO 6** |
| SCL | **D5 = GPIO 7** |
| (pulsante opz.) | tasto **BOOT** già presente (GPIO 9) |

Note:
- Alimenta l'OLED a **3,3 V**. La maggior parte dei moduli ha già le
  resistenze di pull-up I2C; con cavi lunghi (>30 cm) aggiungine 4,7 kΩ verso 3V3.
- Il bus I2C è impostato a **100 kHz** (più robusto ai disturbi della moto):
  un frame completo richiede ~95 ms, il firmware invia un nuovo frame solo se
  l'immagine è cambiata.
- In moto alimenta da un convertitore USB 5 V stabilizzato.

### Tasto BOOT (opzionale, nessun cablaggio)
- **Pressione breve**: cambia layout ICONA → MAPPA → CURVA → ICONA…
- **Pressione lunga (>1,5 s)**: attiva/disattiva lo specchio orizzontale.
- Le due scelte vengono salvate in memoria (NVS) e restano dopo lo spegnimento.

---

## Struttura del progetto

```
hud-firmware/
├── android/                app it.diy.hudnav (il telefono che manda il percorso)
├── platformio.ini          ambienti esp32dev e xiao_esp32c3
├── include/config.h        TUTTE le impostazioni (pin, I2C, mirror, contrasto…)
├── src/main.cpp            stati, callback Chronos, tasto, ciclo di refresh
├── src/display.cpp/.h      SSD1306 + canvas 128x64 + blit specchiato
├── src/hud_ui.cpp/.h       disegno schermate (portabile, usato anche dalle preview)
├── src/demo_data.cpp/.h    percorso/icona di esempio (DEMO_MODE e anteprime)
├── src/curve.cpp/.h        rilevamento curve da polilinea (portabile, testato su PC)
├── src/curve_view.cpp/.h   vista CURVA (auto-zoom), strada demo, pilota simulato
├── src/route_rx.cpp/.h     formato BLE percorso/posizione (usato dall'app)
├── tools/test_curves.cpp   test del rilevatore (archi sintetici + strada demo)
├── tools/render_previews.py  genera preview_*.png
├── tools/preview_main.cpp    programma PC per le anteprime
├── tools/host_shim/          mini "Arduino.h" per compilare GFX sul PC
└── preview_*.png
```

### Impostazioni principali (`include/config.h`)

| Opzione | Default | Significato |
|---|---|---|
| `HUD_BLE_NAME` | `"HUD-Moto"` | nome Bluetooth visto dall'app (e da Chronos, se usato) |
| `PIN_SDA` / `PIN_SCL` | 21/22 (C3: 6/7) | pin I2C |
| `I2C_HZ` | 100000 | frequenza bus I2C |
| `OLED_ADDR` | 0x3C | indirizzo display (alcuni moduli 0x3D) |
| `MIRROR_X` | 0 | 1 = immagine specchiata per beam splitter |
| `MIRROR_Y` | 0 | 1 = ribalta alto/basso (display montato capovolto) |
| `INVERT` | 0 | 1 = colori invertiti (nero su bianco) |
| `OLED_CONTRAST` | 255 | luminosità 0–255 |
| `OLED_PRECHARGE` / `OLED_VCOMH` | 0xF1 / 0x40 | "boost" luminosità |
| `NAV_LAYOUT_DEFAULT` | 0 | 0 = ICONA, 1 = MAPPA, 2 = CURVA |
| `CURVE_DEMO_IF_NO_ROUTE` | 1 | CURVA senza percorso caricato: strada demo + pilota simulato ("DEMO") |
| `ROUTE_BLE_ENABLE` | 1 | servizio BLE del percorso: è il canale dell'app `it.diy.hudnav` |
| `MAP_METERS_PER_PX` | 3.0 | zoom della mini-mappa |
| `NAV_STALE_MS` | 30000 | dopo quanto mostrare l'indicatore "dato vecchio" |
| `DEMO_MODE` | 0 | 1 = schermate finte a ciclo, senza telefono (prove al banco) |
| `WAZE_NOTIFICATION_FALLBACK` | 1 | prova a usare le notifiche di Waze (sperimentale) |

---

## Schermate

1. **Splash** (1,8 s): nome BLE, versione, stato mirror.
2. **Attesa telefono**: logo Bluetooth con "onde" lampeggianti, nome BLE,
   "Attendo telefono…" oppure "Connessione persa…" dopo una disconnessione.
3. **Orologio** (connesso ma senza navigazione): ora grande sincronizzata dal
   telefono, data, batteria del telefono, suggerimento ("Avvia navigazione" o
   "Nav OFF: vedi Chronos" se la navigazione è disattivata nell'app).
4. **Navigazione – layout ICONA**: freccia di manovra 48×48 di Chronos a
   sinistra, distanza alla manovra in grande (es. **350 m**), ETA e
   km/minuti residui, via/istruzione in basso (scorre se è lunga).
5. **Navigazione – layout MAPPA** (stile AR-Track): mini-mappa centrale larga
   64 px "heading-up" con il **percorso spesso**, le **strade laterali
   sottili** e il **triangolo di posizione** in basso; sotto, la distanza alla
   manovra. Colonna sinistra: velocità + ora; colonna destra: ETA, km e tempo
   residui.

6. **CURVA** (solo guida sportiva): vedi sezione dedicata sotto.

Alla disconnessione i dati di navigazione vengono cancellati subito (niente
frecce vecchie a schermo) e si torna alla schermata di attesa; la riconnessione
è automatica (l'advertising BLE riparte da solo).

### Mini-mappa: cosa funziona oggi e cosa serve per la fase 2
La funzione `ui::drawMiniMap()` disegna qualsiasi percorso fornito come elenco
di punti in **metri locali heading-up** (pilota in 0,0, x = destra, y = avanti)
più eventuali rami laterali; l'anteprima `preview_nav_map.png` usa una strada
tortuosa di esempio.

L'app `it.diy.hudnav` invia la polilinea e la posizione sul servizio BLE
(`NavView.route` / `NavView.branches`). Senza geometria il layout MAPPA ricade
sull'icona al centro (`preview_nav_map_fallback.png`): è il caso Chronos, che
manda solo icona, distanze e testi.

---

## Modalità CURVA (guida sportiva)

Schermata volutamente minimale: **solo la forma della strada davanti** e
**quando/come arriva la prossima curva**. Niente via, ETA, orologio.

- Strada disegnata come **linea singola spessa** (7 px), heading-up, pilota
  (triangolo) in basso al centro; mostra i prossimi **150–300 m**, con
  **auto-zoom** (1,5–6 m/px) tale da far stare tutta la prossima curva.
- **Distanza grande** (metri all'inizio della prossima curva, arrotondata a
  10 m sopra i 100 m e a 5 m sotto) nell'angolo alto **opposto** al lato della
  curva, così non copre la strada.
- Sotto, riquadro bianco con **freccia ↱/↰ e grado 1–6** stile note da rally.
- Eventuale **STRINGE** (raggio che diminuisce verso l'uscita) e/o **LUNGA**.
- Con dati simulati compare sempre la scritta **DEMO**.
- Quando si entra in una curva, l'indicazione passa subito alla curva
  successiva (quella in cui si è già dentro non serve più).

### Tabella dei gradi

Il grado dipende dal **raggio minimo** della curva. La velocità indicativa è
`v = √(a·R)` con accelerazione laterale prudente **a = 3,5 m/s² (~0,36 g)**,
ben sotto il limite di aderenza su asciutto: serve solo a dare un'idea della
"cattiveria" della curva, **non è una velocità consigliata**.

| Grado | Raggio minimo | Tipo | Velocità indicativa |
|---|---|---|---|
| **1** | < 20 m | tornante / curva strettissima | < 30 km/h |
| **2** | 20–40 m | molto stretta | 30–42 km/h |
| **3** | 40–70 m | stretta | 42–56 km/h |
| **4** | 70–120 m | media | 56–74 km/h |
| **5** | 120–200 m | veloce | 74–95 km/h |
| **6** | 200–400 m | velocissima / leggera | 95–135 km/h |
| — | > 400 m | considerata rettilineo | — |

Perché questi valori: rispetto alla proposta iniziale (1 < 15, 2 15–30, …) le
soglie sono un po' più larghe in basso perché (a) i tornanti stradali reali
hanno R 8–20 m e devono finire tutti in grado 1, (b) il cerchio per 3 punti su
una traccia con vertici ogni 5–10 m tende a **sovrastimare** di 1–2 m i raggi
molto piccoli; le fasce crescono di ~1,7–2× per grado, così ogni grado
vale circa +12–20 km/h di velocità indicativa (di più solo nel grado 6), in
modo simile alle note da rally.

"**LUNGA**": angolo ≥ 120° e lunghezza ≥ 80 m, oppure lunghezza ≥ 160 m.
"**STRINGE**": curvatura media dell'ultimo 40% della curva > 1,35× quella del
primo 40%.

### Algoritmo (src/curve.cpp)

1. Polilinea (metri locali o lat/lon, convertiti con proiezione
   equirettangolare) **ricampionata ogni 5 m**.
2. **Curvatura** con il cerchio per 3 punti (i−K, i, i+K) su finestra
   scorrevole **adattiva**: K=6 (corda ±30 m, robusta al rumore, per le curve
   veloci), K=3 (±15 m) se R < 100 m, K=2 (±10 m) se R < 45 m, K=1 (±5 m) se
   R < 15 m (i tornanti sono più corti della finestra larga); poi media su 3
   campioni.
3. **Segmentazione**: inizia dove R < 400 m, finisce sopra 500 m (isteresi) o
   al cambio di direzione; inizio/fine raffinati a metà della "rampa" di
   curvatura (il cerchio a 3 punti vede la curva K campioni prima); curve
   stessa direzione separate da < 10 m vengono unite; scartate quelle < 12°.
4. Per ogni curva: inizio, fine, lunghezza, raggio minimo (media dei campioni
   ≥ 85% del picco, più robusta del solo picco), angolo totale (somma delle
   variazioni di direzione), direzione, STRINGE, LUNGA, grado.
5. **Posizione**: la posizione GPS viene proiettata sul percorso (ricerca
   locale attorno alla posizione precedente, globale se ci si è persi) →
   distanza lungo il percorso → distanza all'inizio della prossima curva.

### Test sul PC

`python3 tools/render_previews.py` compila ed esegue `tools/test_curves.cpp`:
archi sintetici di raggio noto (10–300 m: raggio entro 12%, 20% sotto i 15 m;
inizio entro 10 m; angolo entro 10°), la strada demo (9 curve attese con
direzione/grado/flag), la proiezione di posizioni spostate di 3 m e il giro
completo del formato BLE in lat/lon. Risultato attuale sulla strada demo:

```
 #  inizio(m)  fine(m)  lungh  dir  Rmin(m)  angolo  grado  flag
 1       255      310     55   DX     43.8     72°   D3
 2       465      490     25   SX      8.9    173°   S1          (tornante)
 3       615      710     95   DX     36.3     99°   D2   STRINGE
 4       815      910     95   SX    252.1     22°   S6
 5       980     1025     45   DX     60.6     51°   D3          (S)
 6      1050     1100     50   SX     61.3     49°   S3          (S)
 7      1295     1535    240   DX     74.1    163°   D4   LUNGA
 8      1655     1690     35   DX     11.4    165°   D1          (tornante)
 9      1765     1875    110   SX    150.9     41°   S5
```
(raggi reali della strada demo: 50, 9, 90→55→32, 250, 60, 60, 85, 12, 150 m;
la traccia ha un vertice ogni 6 m con ±0,3 m di rumore.)

### Da dove arrivano i dati

- **App Android** (`android/`, `it.diy.hudnav`): calcola il percorso e scrive
  i pacchetti sotto. Provata sul Pixel 4a collegato all'ESP32 il 5–7 ottobre
  2026 (finestre di percorso, posizione, layout). È il canale su cui si continua.
- **Senza percorso**: `CURVE_DEMO_IF_NO_ROUTE 1` mostra una strada di montagna
  (~2,1 km) e un pilota simulato a ~70 km/h (`preview_curve_demo.gif`). A
  schermo c'è "DEMO". Al primo percorso valido la demo si spegne.
- **Chronos** non invia la geometria: con solo Chronos la vista CURVA resta in demo.
- **Formato BLE** (`src/route_rx.h`, `ROUTE_BLE_ENABLE 1`):
  - Servizio `8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00`, caratteristica
    `8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00` (WRITE / WRITE_NR), 1 scrittura =
    1 pacchetto, little-endian, lat/lon in gradi × 10⁷:
  - `0x01 BEGIN`: u16 nPunti, i32 lat0, i32 lon0 (origine)
  - `0x02 POINTS`: u16 indicePrimo, u8 n, n × (i32 lat, i32 lon) — fino a 30
    punti per pacchetto con MTU 247, in ordine
  - `0x03 END`: u16 nPunti → calcolo delle curve se il conteggio torna
  - `0x04 POS`: i32 lat, i32 lon, u16 velocità (0,1 km/h), u16 rotta (0,1°)
  - `0x05 CLEAR`
  - Capacità: 12 km di percorso (2400 campioni da 5 m, ~36 KB di RAM).

### ⚠️ Avvertenza di sicurezza

La modalità CURVA è **solo un aiuto indicativo**. Non usarla per decidere
quando e quanto frenare:
- la posizione GPS di un telefono/modulo ha **ritardo tipico ~1 s** (a 70 km/h
  sono ~20 m) ed errore di 3–10 m, peggiore sotto gli alberi e in valle;
- la traccia GPX può essere imprecisa, vecchia o non coincidere con la strada
  (cantieri, varianti, tornanti disegnati "a spigolo");
- il grado dipende solo dalla geometria: **non conosce** asfalto, bagnato,
  ghiaia, foglie, pendenza, visibilità, traffico, curve cieche;
- in caso di dubbio vale sempre ciò che vedi sulla strada. Guarda la strada,
  non il display; rispetta i limiti e il codice della strada.

---

## Installazione dell'ambiente di sviluppo

### Opzione A – PlatformIO (consigliata)
1. Installa **VS Code** e l'estensione **PlatformIO IDE**,
   *oppure* da terminale:
   ```bash
   python3 -m venv ~/.venv-pio
   ~/.venv-pio/bin/pip install platformio
   ```
2. Apri la cartella `hud-firmware` (le librerie vengono scaricate in automatico).
3. Compila e carica:
   ```bash
   pio run -e esp32dev -t upload          # ESP32 DevKit
   pio run -e xiao_esp32c3 -t upload      # XIAO ESP32-C3
   pio device monitor                     # log seriale 115200
   ```

### Opzione B – Arduino IDE 2.x
1. *File → Preferenze → URL gestore schede*: aggiungi
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`,
   poi installa **esp32 by Espressif** (testato con core 2.0.17).
2. *Gestore librerie*: installa **ChronosESP32** (installa anche NimBLE-Arduino
   e ESP32Time), **Adafruit SSD1306**, **Adafruit GFX Library**.
3. Crea una cartella `HUD-Moto` e copiaci tutti i file di `src/` e
   `include/config.h`; rinomina `main.cpp` in `HUD-Moto.ino`.
4. Scheda: *ESP32 Dev Module* oppure *XIAO_ESP32C3*. Per la XIAO C3 i pin
   6/7 vengono scelti se definisci `HUD_BOARD_XIAO_C3`: il modo più semplice
   è aggiungere in cima a `config.h` la riga `#define HUD_BOARD_XIAO_C3`
   (e attivare *USB CDC On Boot* per il monitor seriale).
5. Carica.

### Flash
- ESP32 DevKit: se il caricamento non parte ("Connecting…"), tieni premuto
  **BOOT** finché non inizia la scrittura.
- XIAO C3: se la porta non compare, tieni premuto **BOOT**, collega l'USB,
  rilascia, poi carica.

---

## Telefono: app HUD Nav

L'app è il sorgente `android/` (package `it.diy.hudnav`). Si installa l'APK di
debug sul telefono; non è sul Play Store. Cerca il BLE `HUD-Moto`, manda
percorso e posizione, e dal pannello Display cambia layout e specchio.
Aprire il monitor seriale sull'ESP32 DevKit resetta la scheda (DTR del CP210x)
e il collegamento va rifatto.

Compilare e installare (dalla cartella `android/`, con il telefono in debug USB):

```bash
gradlew installDebug
```

I giri sul Pixel 4a e sull'emulatore sono in `TEST_TELEFONO_ESP.md` e
`TEST_APP_EMULATORE.md`.

### Chronos (ancora nel firmware, non è il percorso attuale)

Il codice accetta ancora Chronos. Non è il canale su cui si riprende il lavoro.

1. Installa **Chronos** (fbiego) dal Play Store:
   <https://play.google.com/store/apps/details?id=com.fbiego.chronos>
   (serve Chronos ≥ 3.7.5 per la navigazione).
2. Concedi i permessi richiesti (Bluetooth/dispositivi vicini, notifiche,
   esecuzione in background; disattiva l'ottimizzazione batteria per Chronos).
3. **Non** associare l'ESP32 dalle impostazioni Bluetooth di Android.
4. Accoppiamento: in Chronos → scheda **Watches** → **Pair New Devices** →
   **Search** → seleziona **HUD-Moto**. Va fatto una sola volta; poi si
   riconnette da solo quando l'HUD è acceso.
5. **Abilita la navigazione per l'HUD**: dopo la connessione, in Chronos apri
   **ESP32 Manager** (scheda Watch) → **Navigation** → attivala. Se appare
   disattivata o non funziona, spegni e riaccendi l'interruttore.
6. **Accesso alle notifiche**: in Chronos → scheda **Notifications** →
   **Self Test**; se fallisce tocca **Notification Access** e abilita Chronos.
   (Chronos legge la navigazione dalla notifica persistente del navigatore.)
7. Avvia la navigazione in **Google Maps**: sull'HUD compaiono freccia,
   distanza e via. Fermando la navigazione l'HUD torna all'orologio.

### Waze: è supportato? (risposta onesta)
- La documentazione di Chronos/ChronosESP32 cita esplicitamente solo
  **Google Maps** e **OsmAnd** come sorgenti di navigazione (README di
  chronos-watchy: "Turn-by-turn directions from Google Maps or OSMAnd"; la
  libreria stessa mostra "Start navigation on Google maps"; la pagina Navio
  parla di "OSM or Maps"). **Waze non è indicato come supportato** e non ho
  trovato conferme che Chronos ne interpreti la navigazione in modo nativo.
- Il firmware include un **fallback sperimentale** (`WAZE_NOTIFICATION_FALLBACK`):
  se in Chronos abiliti **Waze nell'elenco delle app di cui inoltrare le
  notifiche**, il testo delle notifiche Waze viene mostrato come istruzione
  (e se contiene "300 m" / "1,2 km" la distanza va in grande), ma **senza
  freccia** e con aggiornamenti solo quando Waze aggiorna la sua notifica.
  Non testato sul campo: con Waze le notifiche di navigazione devono essere
  attive (Android → App → Waze → Notifiche).
- Consiglio: per la moto usa **Google Maps** (o OsmAnd, che fornisce anche la
  velocità) per avere freccia, distanza e via affidabili.

---

## Specchio per beam splitter (MIRROR_X)

Guardando il display **direttamente** lascia `#define MIRROR_X 0`.
Se lo guardi **riflesso** su un vetrino/beam splitter il testo appare
rovesciato: imposta `#define MIRROR_X 1` in `include/config.h` e ricarica il
firmware, oppure tieni premuto **BOOT** per 1,5 s per commutarlo al volo (la
scelta viene memorizzata). Se il riflesso risulta capovolto in verticale usa
anche `MIRROR_Y 1`. Il disegno avviene sempre in un canvas 128×64 in RAM e
viene ribaltato solo nella copia verso l'OLED (`display.cpp`).

---

## Risoluzione problemi

| Problema | Soluzione |
|---|---|
| Display nero | Controlla VCC 3V3/GND/SDA/SCL; prova `OLED_ADDR 0x3D`; sul monitor seriale cerca "SSD1306 non trovato". |
| Immagine disturbata in moto | Cavi I2C corti e intrecciati, pull-up 4,7 kΩ, alimentazione filtrata; resta a 100 kHz. |
| "HUD-Moto" non appare nella ricerca | Rimuovilo dalle impostazioni Bluetooth di Android se l'avevi associato lì; riavvia l'ESP32; concedi all'app HUD Nav "Dispositivi nelle vicinanze" e la posizione. |
| Connesso ma solo orologio, con l'app HUD Nav | La navigazione sull'app non è partita, oppure il percorso non è arrivato: sul seriale deve comparire `ROUTE percorso`. |
| "Nav OFF: vedi Chronos" | È la risposta di Chronos, non dell'app HUD Nav. Con l'app propria non è il messaggio atteso. |
| Waze non mostra nulla | Waze passa solo dal fallback notifiche di Chronos. L'app HUD Nav non usa Waze. |
| Freccia Chronos ma niente distanza/via | Vale solo se si usa Chronos: Google Maps deve tenere la notifica di navigazione. |
| Indicatore lampeggiante in alto a destra | Nessun aggiornamento da >30 s (dato forse vecchio). |
| Si disconnette spesso | Disattiva l'ottimizzazione batteria per HUD Nav; tieni il telefono vicino. Aprire il monitor seriale resetta l'ESP32 DevKit. |
| Testo al contrario | Cambia `MIRROR_X` (o premi a lungo BOOT). |

---

## Consumo memoria (build v1.1 con CURVA, core Arduino-ESP32 2.0.17)

| Ambiente | Flash | RAM statica |
|---|---|---|
| esp32dev | 697 KB / 1280 KB (53,2 %) | 95,1 KB / 320 KB (29,0 %) |
| xiao_esp32c3 | 594 KB / 1280 KB (45,3 %) | 81,3 KB / 320 KB (24,8 %) |

(La RAM statica include ~46 KB del percorso CURVA: 12 km a passo 5 m.)

Licenze: ChronosESP32 (MIT, fbiego), Adafruit GFX/SSD1306 (BSD).
