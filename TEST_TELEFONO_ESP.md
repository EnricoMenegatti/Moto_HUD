# Test sul Pixel 4a collegato all'ESP32 — 5 ottobre 2026, sera

> Diario chiuso il 7 ottobre 2026. Lo stato del progetto, e da dove si riprende, è in `NOTE_PROGETTO.md`.

Telefono `Pixel 4a` (Android 13, 1080×2340), app `it.diy.hudnav` 1.0 aggiornata alle 21:00. Scheda su COM7, firmware **HUD-Moto 1.1.0**, nome BLE `HUD-Moto`, MAC `c8:2e:18:8e:b2:fa`, OLED già inizializzato (livello 255). Bluetooth e posizione del telefono accesi, permessi già concessi. Prova fatta con la simulazione verso «Il Sole» (Padova, 6,2 km, 16 min), guardando il seriale della scheda.

Aprire la seriale a 115200 **resetta** la scheda (DTR del CP210x). È successo due volte durante il test: il collegamento BLE cade e l'app deve rifarlo.

## Cosa è arrivato davvero sulla scheda

Quando il link c'è, i pacchetti passano.

- Layout: dal pannello Display, «Curva» è comparso sul seriale come `[HUD] layout = CURVA`. Lo specchio acceso e poi spento: `MIRROR_X = 1` e poi `MIRROR_X = 0`. Lo specchio è stato rimesso spento, com'era prima del test.
- Percorso: `ROUTE percorso 6145 m, 22 curve`, poi più avanti le finestre residue `985 m, 5 curve` e `195 m, 1 curve`. La finestra che scorre con la simulazione funziona.
- Posizione: `45.37077 11.92700` e poi `45.39722 11.88908`. Il firmware scrive in seriale solo la prima posizione di ogni accensione, quindi gli aggiornamenti successivi non si vedono nel log.
- `Termina` chiude il BLE (`BLE disconnesso`, annuncio che riparte) e il foglio del telefono tiene l'arrivo (16 min, 6,2 km). Lo stato torna «Fermo.»

La simulazione sul telefono è coerente: 70 km/h, istruzioni del tipo «Alla rotonda prendi la terza uscita, in Via Piovese» e «Svolta a destra, in Via Egidio Forcellini», arrivo «Sei arrivato» in Via Marcel Proust, vicino al Parco Iris. Il foglio compatto («Mostra altro» / «Compatta») lascia la mappa visibile. Senza limite OSM il disco mostra «n.d.».

## Problemi visti con la scheda accesa

### Il telefono dice che l'HUD non c'è, mentre la scheda riceve

Per tutta la simulazione, anche dopo che la scheda aveva layout, percorso e posizione, la riga in basso è rimasta:

«Simulazione sul telefono. HUD non trovato, riprovo.»

Quel testo viene messo all'inizio, se la prima connessione fallisce, e non viene aggiornato quando il collegamento riesce dopo. A schermo sembrava un fallimento. Sul seriale il trasferimento c'era.

**Miglioramento.** Quando un pacchetto va a buon fine, sostituire quel messaggio con «HUD collegato». Se il link cade di nuovo, dirlo in quel momento, non lasciare la frase vecchia.

### Il riprovo da solo non riaggancia

Dopo un reset della scheda, l'ESP annunciava `HUD-Moto` (`peers=0 adv=1`) e il telefono era già in simulazione con «riprovo». Per circa tre minuti non è arrivata nessuna connessione. Il collegamento è nato subito quando ho premuto **Curva** nel pannello Display: quello forza una connessione nuova.

Quindi il tentativo automatico (circa ogni 12 s) non sta vedendo la scheda, oppure le scan BLE del telefono sono state limitate da Android dopo i tentativi precedenti. Il pulsante del pannello, invece, la trova.

**Miglioramento.** Se `peers` sulla scheda è 0 e il telefono crede di riprovare, il tentativo deve essere una scan vera, non una write su un GATT già morto. Dopo alcuni fallimenti, smettere di scandire di continuo (Android taglia le scan) e riprovare a intervalli più lunghi, oppure riagganciare appena si apre il pannello e all'inizio di ogni finestra di percorso. Conviene anche un indicatore vero: collegato / in cerca / perso.

### La prima sessione ha rinviato tutto il percorso quattro volte, poi è caduta

Subito dopo il primo reset, il telefono era fermo su «Invio 136/245» e poi «Invio 222/245» mentre la scheda era già spenta: scriveva su un collegamento che non c'era più. Quando si è riagganciata, lo stesso percorso da 6145 m è stato accettato **quattro volte**, a circa 13 secondi l'una, e dopo un minuto il BLE è caduto.

Nella sessione successiva le finestre erano giuste (985 m, poi 195 m): il rinvio continuo non è sempre così. Succede quando l'invio precedente non risulta riuscito lato telefono, anche se la scheda lo ha già preso.

**Miglioramento.** Se la scheda ha già il percorso, non ricominciare i 245 punti. Un ack di fine percorso deve bastare. Se le write vanno in timeout, chiudere il GATT e riconnettere, invece di continuare «Invio n/245» per minuti.

### La notifica di navigazione viene aggiornata troppo spesso

Durante la simulazione il sistema registrava la notifica `hud_nav` più volte al secondo, a gruppi di tre. Prima, in fase di invio, Android l'ha anche scartata: «Package enqueue rate is 5.69. Shedding» per `it.diy.hudnav`. Ogni cambiamento di stato, manovra, viaggio e allarme riscrive la notifica, e «Invio n/245» lo fa per ogni pacchetto.

**Miglioramento.** Aggiornare la notifica al massimo un paio di volte al secondo, e non a ogni pacchetto di percorso. In notifica bastano manovra, km e un eventuale allarme.

### All'arrivo restano «1 min» e «0 m»

A destinazione il banner dice «Sei arrivato», la distanza è 0 m, la velocità torna a 0 (la simulazione non è ripartita da capo). Il tempo residuo resta «1 min», perché i minuti sono forzati ad almeno 1. L'orario di arrivo (22:55) era allineato all'orologio del telefono.

**Miglioramento.** Sotto i 30 secondi mostrare «meno di 1 min», e a destinazione togliere il tempo residuo.

## Dettagli minori, visti sul telefono

- I recenti si tagliano: «Il Sole - bar caffetteria en». Il nome intero c'è nel titolo quando lo scegli.
- Il messaggio «HUD non trovato» è rimasto anche dopo aver ricalcolato il percorso e dopo «Fermo.» no, dopo Termina è diventato «Fermo.». Prima, sul foglio di pianificazione, la frase della simulazione precedente restava finché non partiva un'altra azione.
- La voce era già spenta: non l'ho riaccesa.
- Il pannello Display, su questo telefono, sta sopra la mappa e non la schiaccia come sull'emulatore di questo pomeriggio. «Curva» resta evidenziato. Mappa chiara, banner scuro.

## Giro del 6 ottobre 2026, mezzogiorno

Stessa scheda, firmware 1.1.0, COM7. Aprire la seriale all'inizio ha resettato la scheda (`POWERON_RESET`). Poi la porta è rimasta aperta per tutto il giro, quindi i tempi sotto sono dalla stessa accensione.

- Collegamento alle 4,8 s, un solo `BLE connesso`. È rimasto su per entrambe le simulazioni.
- Prima corsa, verso Via Enrico Bernardi, circa 23,8 km, poi a 110 km/h. Tre finestre, una sola posizione:
  - `350081` `ROUTE percorso 10995 m, 38 curve`
  - `361669` `ROUTE posizione 45.37077 11.92699`
  - `646781` `ROUTE percorso 10995 m, 48 curve` (circa cinque minuti dopo)
  - `918263` `ROUTE percorso 7060 m, 29 curve` (verso la fine, finestra più corta)
- Il tasto BOOT, premuto a mano durante la corsa, ha scritto `layout = CURVA`, `ICONA`, `MAPPA`.
- Dal pannello Display del telefono, dopo l'arrivo: `layout = CURVA`, `ICONA`, `MIRROR_X = 1`, `MIRROR_X = 0`, `layout = MAPPA`. Specchio lasciato spento.
- Seconda corsa, «Evita autostrade» acceso, fermata dopo quattro minuti a circa 19 km dall'arrivo. Un solo `1878549 ROUTE percorso 10995 m, 39 curve`. Nessun duplicato.
- `Termina` non ha scritto `BLE disconnesso` nel mezzo minuto successivo.

## I cinque punti di questa sera

Chiusi, e rivisti sul Pixel nel giro del 6 ottobre a mezzogiorno (il dettaglio è in `TEST_APP_EMULATORE.md`):

1. Lo stato passa a «Simulazione a …» quando il collegamento riesce, senza lasciare «HUD non trovato».
2. Nel giro di mezzogiorno il BLE è rimasto su senza premere un layout.
3. Il percorso non torna più ogni 13 secondi: una finestra da circa 11 km, la successiva quando quella sta per finire.
4. Con la manovra ferma la notifica `hud_nav` è circa ogni 12 secondi.
5. All'arrivo la riga è «0 min» e «0 m».

I buchi sull'app sono stati rifatti sul Pixel alle 14, con l'APK delle 14:08. Dettaglio in `TEST_APP_EMULATORE.md`.

Sulla stessa accensione della scheda, la corsa verso Q8 Easy Stanga (7,6 km) ha scritto un solo `8697998 ROUTE percorso 7530 m, 40 curve`. Nessun duplicato e nessun `BLE disconnesso` dopo `Termina`.

Alle 14:41, con l'APK delle 14:37, la guida verso Via Enrico Bernardi è partita prima dei pallini ed è stata chiusa dopo pochi secondi. Sul seriale un solo `10281321 ROUTE percorso 10995 m, 39 curve`. Dopo `Termina` i pallini erano di nuovo sulla mappa. Il dettaglio è in `TEST_APP_EMULATORE.md`.

## Finestra successiva, nel firmware

A mezzogiorno la prima corsa ha cambiato finestra due volte (`10995 m` poi `7060 m`). Al primo pacchetto della finestra nuova la scheda buttava la strada già a schermo: in CURVA, per tutto il trasferimento, compariva «CARICO PERCORSO».

Nel sorgente i punti della finestra nuova restano da parte. La strada a schermo cambia solo quando `END` chiude il conteggio. Un `END` corto o un pacchetto fuori ordine lasciano la strada di prima. Se i punti superano 1000, il telefono li dirada (14 m, poi 22 m): la scheda non ne tiene di più.

## Flash e seconda finestra, 6 ottobre 2026, ore 15:16

Firmware appena compilato flashato su COM7 (ESP32-D0WD-V3, MAC BLE `c8:2e:18:8e:b2:fa`). All'avvio il seriale dice ancora `HUD-Moto fw 1.1.0`: la stringa di versione non è stata alzata. OLED trovato, contrasto `CF`. Il telefono si è riagganciato da solo (`BLE connesso` a 4,8 s).

Dal pannello Display: `[HUD] layout = CURVA`. Simulazione verso Via Enrico Bernardi, poi a 110 km/h.

- `[171894] ROUTE percorso 10995 m, 38 curve`
- `[172047] ROUTE posizione 45.37077 11.92699`
- `[461337] ROUTE percorso 10995 m, 48 curve` (circa 4,8 minuti dopo)
- `[732826] ROUTE percorso 7060 m, 29 curve` (circa 4,5 minuti dopo la seconda)

Stessi numeri della corsa di mezzogiorno, prima del flash. Nessun `ROUTE errore pacchetto` e nessun invio in più. Ogni riga esce solo a trasferimento chiuso.

Arrivo: «Sei arrivato», 0 min, 0 m, tachimetro 0. `Termina`: «Fermo.», riepilogo ancora 38 min e 23,7 km. Il seriale non ha scritto `BLE disconnesso`.

L'OLED non è stato fotografato. «CARICO PERCORSO» si disegna solo a schermo, e solo se non c'è già una strada pronta: con la prima finestra chiusa, la seconda e la terza non devono coprirla. Sul vetro va ancora dato un'occhiata nel momento del passaggio.

Dal secondo tratto in poi, in quel giro, il disco è rimasto «n.d.», anche da fermo all'arrivo. Sul Q8 il numero c'era e restava. La richiesta era una sola sui 23 km: se Overpass si interrompe, risponde con i tratti già trovati e un remark, e quel pezzo veniva usato come se fosse tutto il percorso.

Nel sorgente il corridoio è diviso in pezzi da circa 24 punti. Un pezzo che fallisce non butta gli altri. Una risposta con remark non si tiene: si prova l'altro server. Un pezzo che non risponde si richiede per tutta la guida e, nell'attesa, il disco tiene l'ultimo numero. Con l'APK delle 17:45, sullo stesso arrivo, il disco è rimasto «n.d.» per tutta la corsa: Overpass non ha risposto e non c'era un numero da tenere. Dopo quell'APK il disco chiede il limite a Valhalla insieme al percorso. La sera del 7 ottobre, sullo stesso arrivo, il disco era «50» e l'icona delle rotonde era la rotonda, non una svolta. Il dettaglio è in `TEST_APP_EMULATORE.md`.
