# Test HUD Nav sull'emulatore — stato al 5 ottobre 2026, sera

> Diario chiuso il 7 ottobre 2026. Lo stato del progetto, e da dove si riprende, è in `NOTE_PROGETTO.md`.

App `it.diy.hudnav` (versionCode 1). Primo giro sull'emulatore `sdk_gphone16k_x86_64`, Android 37, 1080×2424, 420 dpi, partenza Bologna circa 44.495, 11.343. Rete reale. Nessun HUD BLE nei paraggi. L'app non è crashata.

Questo file segue i problemi di quel giro. Qui sotto c'è cosa è stato sistemato nel codice e cosa è ancora aperto. Non è un secondo giro completo sull'emulatore.

## Cosa reggeva già

- Ricerca Photon, percorso, alternativa quando il router ne trova più di una, tempo e km coerenti con l'anteprima.
- Tappe, frecce di ordine, ×, pin numerato, preferiti e recenti.
- Navigazione: banner, ETA, km che scendono, velocità 0 da fermo, deviazione dopo tre fix fuori rotta.
- HUD assente: messaggio chiaro e la guida continua sul telefono.
- Stop dalla notifica: torna al foglio con l'arrivo ancora lì.
- Avvia senza arrivo: «Prima scegli l'arrivo.» Una lettera in cerca non parte.

## Sistemato

### 1. Foglio e tastiera

Il foglio parte compatto («Mostra altro» / «Compatta») e, da aperto, scorre. La ricerca sta sotto la mappa, non dentro. La tastiera non riduce più la mappa a zero: la finestra usa `adjustPan`, così il campo resta visibile. In orizzontale la mappa sta a sinistra e il foglio a destra. Il pannello Display si apre sopra la mappa, non la schiaccia. In guida il foglio si rimisura e resta basso, così la mappa tiene la maggior parte dello schermo. I chip (categorie, recenti, preferiti, tappe) hanno altezza elastica e uno scroll orizzontale, così con il carattere grande l'ultima voce non resta tagliata sul bordo.

### 2. Panoramica e sguardo avanti

«Tutto» aspetta che la mappa abbia una misura e lascia un margine almeno quanto il banner, il tachimetro e i tasti a destra. In seguimento con la bussola accesa lo sguardo avanti è 320 m, non 70.

### 3. Limite

Il disco prende la strada percorribile più vicina, entro 25 m, e ignora marciapiedi, piste e vicoli di servizio. Se il dato manca, in guida si legge «n.d.». Sopra il limite di più di 4 km/h la velocità diventa rossa.

### 4. Freccia

Sotto i 5 km/h la direzione è quella del tratto, non il nord del GPS. In guida la freccia scorre lungo la linea. La bussola ruota subito se il seguimento è attivo; se hai appena premuto «Tutto», la rotazione parte al ricentro.

### 5. Il viaggio resta

Tappe, alternativa scelta, «evita autostrade» e «sto navigando» sono salvati e tornano all'apertura. A fine guida la linea e i punti di interesse spariscono. Se il sistema chiude il processo durante la guida, il servizio riparte con l'ultima rotta.

### 6. Risultati e punti di interesse

La lista ha un'altezza vera, «Chiudi», e un tocco sulla mappa la chiude. Sotto ogni riga ci sono tipo e distanza. Un distributore chiede «Portami qui» o «Aggiungi tappa» e non sostituisce il viaggio da solo. Il tocco sulla mappa prende il nome del posto. Il campo di ricerca si svuota dopo la scelta. I punti di interesse spariscono all'avvio della guida.

### 7. Frasi e voce

Le manovre sono frasi intere, con l'uscita della rotonda. La tappa intermedia non dice «Arrivo». La voce avvisa a 500 m, 200 m e all'incrocio. Se l'italiano non è installato, lo stato lo scrive.

### 8. Tappe

«＋ Tappa» sta fisso a destra dello scroll. Dopo un riordino la modifica si spegne e lo scroll torna sul chip. Se l'ordine allunga il percorso di più di 800 m, lo dice.

### 9. Display, Bluetooth, simulazione

Il collegamento al display non blocca più gli aggiornamenti di posizione. Durante il calcolo la mappa resta in panoramica e la riga dell'orario dice «Cerco la strada…». La simulazione si ferma con «Sei arrivato.» e non ricomincia il giro. In simulazione, un tocco sul tachimetro passa da 40 a 70 a 110 km/h e lo scrive nello stato. Alla partenza torna a 70.

### 10. Il resto già chiuso

I messaggi brevi spariscono dopo 5 secondi e non coprono «HUD non trovato». Se c'è una sola alternativa, il tempo non è ripetuto in un secondo chip. «Evita autostrade» chiede un percorso senza autostrada (il server OSRM pubblico non lo sa fare; lo fa un altro servizio). Se non esiste, resta la più veloce e lo dice. Il glide della mappa si ferma quando l'app va in pausa. Per navigare basta la posizione: Bluetooth e notifiche non sono obbligatori, e l'app si installa anche senza BLE. Ci sono test sulle frasi, sulla scelta del limite e sullo snap della freccia alla linea.

Gli autovelox e i pericoli fissi, in pianificazione, si toccano e aprono una scheda. In guida i pallini spariscono, così non coprono la freccia; l'avviso nel banner resta. Sulla mappa restano solo quelli sulla strada: al massimo dieci autovelox, distanziati, e quattro pericoli.

## Verifica sul Pixel 4a, build delle 23:15

APK di debug installato sul telefono e simulazione verso Il Sole (6,5 km, «Evita autostrade»), scheda HUD-Moto 1.1.0 accesa. Aprire la seriale resetta la scheda.

Regge, visto a schermo e sul seriale:

- Il viaggio è tornato all'apertura: Il Sole, 16 min, 6,5 km, «Percorso senza autostrade.»
- Ricerca sotto la mappa, foglio compatto, «＋ Tappa» visibile, nome intero nel titolo. In guida la mappa occupa quasi tutto lo schermo.
- Pin di partenza e arrivo entrambi visibili nella panoramica.
- Frasi intere: «Continua a sinistra, in Via della Repubblica», «Poi a fine strada a sinistra, in Via Ventiquattro Maggio», «Svolta a destra, in Via Egidio Forcellini».
- Freccia sulla linea blu, strada in verticale con la bussola accesa.
- Disco «n.d.» quando il limite manca. Tachimetro: un tocco passa da 70 a 110 e lo stato dice «Simulazione a 110 km/h.»
- Arrivo fermo su «Sei arrivato», Via Marcel Proust, velocità 0. Non è ripartito il giro.
- `Termina` torna al foglio con lo stesso viaggio e scrive «Fermo.»
- La scheda ha ricevuto il collegamento, il percorso da 6160 m (21 curve) e una posizione.

Questi cinque punti della build delle 23:15 sono stati corretti dopo. La verifica sul telefono è nel giro delle 23:41, più sotto:

- L'invio del percorso non viene più dato per fallito a metà. L'attesa arriva a 60 secondi, e la scheda non riceve di nuovo lo stesso percorso finché la finestra già mandata non sta per finire. Lo stato non scrive più «Invio n/245» a ogni pacchetto, così non copre «HUD non trovato» né «Simulazione».
- «Sei arrivato.» resta nello stato, non torna a «Simulazione a 110 km/h.» dopo 5 secondi.
- A destinazione la riga è «0 min» e «0 m», non «1 min».
- La notifica si aggiorna quando cambia la manovra o lo stato. Chilometri e minuti, da soli, non la riscrivono più di una volta ogni 12 secondi.
- Il tocco sul tachimetro cambia 40 / 70 / 110 per tutta la simulazione, anche se lo stato in quel momento non inizia con «Simulazione».

## Verifica sul Pixel 4a, APK delle 23:41

Installato l'APK di debug delle 23:41 sul Pixel 4a. Simulazione verso Via Salboro, 5,2 km, 11 min, «Evita autostrade». Scheda HUD-Moto 1.1.0 sulla seriale. Aprire la porta ha resettato la scheda prima della prova.

I cinque punti di sopra, visti adesso a schermo e sul seriale:

- L'invio è riuscito. Lo stato è passato da «Cerco l'HUD…» a «Simulazione a 70 km/h.» senza «Invio n/245» e senza «HUD non trovato». Sul seriale un solo `ROUTE percorso 5185 m, 22 curve`, più una posizione. Nei tre minuti a 110 km/h non è arrivato un secondo percorso. Alle 23:15 il percorso da 6160 m tornava circa ogni 13 secondi.
- «Sei arrivato.» era ancora nello stato alle 23:55, circa mezzo minuto dopo l'arrivo. Non è tornato «Simulazione a 110 km/h.»
- All'arrivo la riga è «0 min» e «0 m», velocità 0, banner «Sei arrivato» su Via Salboro. Il giro non è ripartito.
- Le notifiche `hud_nav` non sono state scartate. Quando la frase resta ferma il ritmo è circa uno ogni 12 secondi. I posti più fitti, anche a un secondo, cadono mentre a 110 km/h cambia la manovra, e tre ravvicinate all'arrivo. Il log non riporta il testo di ogni notifica, quindi non separo manovra e minuti una per una. La raffica di «Invio n/245» non c'è più.
- Un tocco sul tachimetro, con lo stato già «Simulazione a 70 km/h.», è passato a 110 e lo stato lo ha scritto. Non ho toccato durante «Cerco l'HUD…»: quella fase finisce prima che la simulazione fissi i 70 km/h.

`Termina` ha riportato il foglio con Via Salboro, 11 min, 5,2 km, «Evita autostrade» e stato «Fermo.»

## Sistemato dopo il giro delle 23:41

Rivisto sul Pixel 4a il 6 ottobre 2026, verso le 12:00. Dettaglio nel giro qui sotto.

- Il percorso principale usa il profilo moto: niente sentieri, e con «Evita autostrade» anche niente autostrade. Lo stato dice «Percorso per moto.» oppure «Percorso moto, senza autostrade.» Se quel servizio non risponde, si torna alla strada in auto e, senza autostrada, lo dice. Le alternative, quando ci sono, restano quelle dell'auto. Le segnalazioni live di Waze restano fuori.
- A ogni secondo non si ricostruisce più la geometria del percorso per freccia e autovelox: i metri locali si calcolano una volta. La posizione non viene scritta sul display se non è collegato. L'avviso dell'autovelox non viene ripubblicato se il testo non cambia.
- In pianificazione gli autovelox sulla mappa sono solo quelli sulla strada, al massimo una decina, più qualche pericolo. Un tocco apre la scheda.

## Verifica sul Pixel 4a, 6 ottobre 2026, mezzogiorno

APK già installato (aggiornato alle 09:54, con le stringhe del profilo moto). Scheda HUD-Moto 1.1.0 su COM7, MAC `c8:2e:18:8e:b2:fa`. Aprire la seriale ha resettato la scheda. Il telefono era su Chronos; l'app HUD si è aperta sul viaggio salvato: Via Enrico Bernardi, 38 min, 23,8 km, chip «Evita autostrade» acceso.

Visto a schermo e sul seriale:

- All'apertura lo stato era «Percorso moto, senza autostrade.»
- Spento «Evita autostrade», lo stato è diventato «Percorso per moto.» Sono comparse due scelte: «Profilo moto · Via Roma», 38 min, 23,8 km, e «Simile · Via Navegauro», 35 min, 23,5 km. La seconda è più corta di tre minuti e resta etichettata «Simile», perché il confronto tratta come simile qualunque alternativa che non sia più lenta della prima.
- Simulazione partita senza «HUD non trovato» e senza «Invio n/245». Stato «Simulazione a 70 km/h.», poi un tocco sul tachimetro: 110 e «Simulazione a 110 km/h.»
- Frase intera in guida: «Alla rotonda prendi la seconda uscita, in Via Romea», con «Poi prosegui…». Freccia sulla linea, mappa in verticale.
- Sul seriale un solo collegamento BLE, rimasto su. Prima finestra `ROUTE percorso 10995 m, 38 curve` e una posizione `45.37077 11.92699`. Circa cinque minuti dopo, a 110 km/h, la finestra successiva `10995 m, 48 curve`. Non è tornato lo stesso percorso ogni 13 secondi. La finestra è da 11 km, quindi su un viaggio da 24 km ne arriva un'altra quando la prima sta per finire.
- Con la manovra ferma le notifiche `hud_nav` sono a circa 12 secondi (12:01:31, 12:01:43, 12:01:55). Nessuno scarto «Shedding» in quel tratto.
- Il tasto BOOT della scheda, premuto durante la simulazione, ha scritto `layout = CURVA`, poi `ICONA`, poi `MAPPA`.

## Giro a fondo, 6 ottobre 2026, 12:10–12:26

Stessa scheda, stesso APK, seriale ancora aperta (non l'ho resettata). La simulazione delle 12:00 è arrivata da sola.

### Arrivo del viaggio lungo

Alle 12:12, a destinazione: banner «Sei arrivato» su Via Enrico Bernardi, tachimetro 0, riga «0 min» e «0 m», orario 12:08. Lo stato «Sei arrivato.» era ancora lì quattro minuti dopo. Non è ripartito il giro.

`Termina` ha riportato il foglio: stesso titolo, 38 min, 23,8 km, le due scelte, e per un attimo «Fermo.»

### Profilo moto e alternativa

- Tocco su «Simile · Via Navegauro»: il riepilogo è passato a 35 min, 23,5 km. Sulla mappa in panoramica la linea scelta è blu (est, Via Roma) e l'altra è grigia (ovest, verso Monselice).
- «Evita autostrade» riacceso: le scelte spariscono, il riepilogo diventa 38 min, 23,7 km, lo stato «Percorso moto, senza autostrade.» Su questo viaggio la lunghezza cambia di poco.
- Ricerca «Monselice»: due righe, «Veneto · 19,8 km» e «stazione · Monselice, Veneto · 19,7 km». Non l'ho scelta.
- «Benzina», con il foglio aperto: «Nessun punto qui intorno.» Né lista né pallini.

### Display

Dal pannello, in ordine, il seriale ha scritto `layout = CURVA`, `layout = ICONA`, `MIRROR_X = 1`, `MIRROR_X = 0`, `layout = MAPPA`. Specchio lasciato spento, layout lasciato su Mappa. Voce già spenta, non l'ho accesa. «Display acceso» era già attivo.

### Seconda simulazione, per il limite

Ripartita alle 12:21 con «Evita autostrade» acceso, 70 km/h. In un minuto e mezzo i km sono scesi da 23,6 a 22,1, coerenti con i 70. Un tocco sul tachimetro alle 12:24 è passato a 110 e lo stato lo ha scritto. Alle 12:25 restavano 18,7 km: l'ho fermata, non ho aspettato il secondo arrivo.

Fino alle 12:25, circa quattro minuti, il disco del limite non c'era: né un numero né «n.d.». Nessun avviso di autovelox nel banner. Sul primo viaggio il disco è comparso solo all'arrivo, con «50», e da fermo è diventato «n.d.».

In panoramica, dopo il primo `Termina`, sulla mappa non c'erano pallini arancioni o rossi.

### Stato che torna indietro

«Fermo.» non resta. Dopo circa cinque secondi torna l'ultima frase appiccicosa.

- Dopo il primo arrivo, il foglio è tornato a «Sei arrivato.»
- Dopo la seconda simulazione, alle 12:26 il foglio diceva «Simulazione a 110 km/h.» con la guida già chiusa e il tasto Simula di nuovo visibile.

### Scheda, seconda corsa

Un solo `ROUTE percorso 10995 m, 39 curve`. Nessun secondo invio nei quattro minuti (la finestra è da 11 km). Nessun `BLE disconnesso` dopo `Termina`, almeno per il mezzo minuto in cui ho guardato il seriale.

## Sistemato nel codice, 6 ottobre 2026, pomeriggio

Dopo il giro delle 12:10. È nel sorgente dell'app, con test su etichette e limite lungo il tratto. L'APK delle 14:08, nel giro sotto, è questo codice.

- «Fermo.» è una frase che resta. Dopo `Termina` non torna più «Sei arrivato.» né «Simulazione a …».
- Il disco c'è appena parte la guida. Senza dato si legge «n.d.». Il numero arriva da una sola richiesta sulle strade del percorso, e segue il tratto: all'arrivo, da fermo, non si cancella. Non si chiama più Overpass a ogni spostamento.
- In pianificazione si caricano gli autovelox del percorso scelto. Un tocco apre la scheda. In guida i pallini restano nascosti. `Termina` non svuota più la lista, così sulla mappa ricompaiono.
- «Benzina», parcheggi, cibo e soccorso cercano nodi e aree. Se `overpass-api.de` non risponde (oggi un 504) si prova `overpass.kumi.systems`. Se falliscono entrambi, lo stato dice «Ricerca non riuscita. Riprova.» Una zona davvero vuota resta «Nessun punto qui intorno.»
- Valhalla restituisce anche le alternative col profilo moto, pure con «Evita autostrade». L'auto si aggiunge solo se il profilo moto ne ha data una sola e le autostrade sono ammesse, e l'etichetta dice «in auto». Tre minuti in meno si leggono «-3 min», non «Simile».
- Chilometri, minuti e le altre scritte partono solo quando il testo cambia. A guida ferma la mappa non ridisegna venti volte al secondo; in seguimento continua a scorrere con la freccia.

## Rifatti sul Pixel 4a, 6 ottobre 2026, ore 14

APK di debug ricostruito alle 14:08 e installato sul Pixel 4a. Scheda ancora HUD-Moto 1.1.0, seriale aperta da questa mattina (nessun reset). Viaggio di partenza: Via Enrico Bernardi, «Evita autostrade» acceso.

- Alternative col profilo moto, anche con il chip acceso: «Profilo moto · Via Roma», 38 min, 23,8 km, e «+5 min · Via Navegauro», 43 min, 25,5 km. Non più «Simile» sulla via più corta in auto.
- Sulla mappa, prima di guidare, una fila di pallini arancioni lungo la linea blu. Un tocco ha aperto la scheda «Autovelox 50», con «Segnalazione fissa di OpenStreetMap, non un avviso live.»
- «Benzina» ha elencato Q8 Easy Stanga, Esso Express e IP. «Portami qui» sul Q8 ha sostituito l'arrivo.
- Sul Q8 (19 min, 7,6 km, «Evita autostrade» ancora acceso) le scelte sono «Profilo moto · Via Piovese» e «-1 min · Via Vigonovese», 18 min, 8,4 km. L'etichetta «in auto» non è uscita: c'erano già due percorsi moto, e l'auto si aggiunge solo se il profilo moto ne dà uno solo.
- Simulazione: il disco era «n.d.» subito, poi 50, poi 30, 40 e di nuovo 50 seguendo la strada. A 70 con limite 30 il numero del tachimetro è rosso. Un tocco lo ha portato a 110.
- Arrivo al Q8: «Sei arrivato», 0 min, 0 m, tachimetro 0, disco ancora «50» anche 25 secondi dopo, da fermo.
- `Termina`: «Fermo.» alle 14:21 e ancora alle 14:22, con Q8, 19 min, 7,6 km. Non è tornato «Sei arrivato.» né «Simulazione a 110 km/h.»
- Sulla scheda un solo `ROUTE percorso 7530 m, 40 curve`. Nessun secondo invio. Nessun `BLE disconnesso` dopo `Termina`.
- Dopo questo `Termina`, in panoramica sul Q8 non ho visto pallini. Sul viaggio di Bernardi, prima di partire, c'erano. Se la guida parte prima che la ricerca finisca, a fine corsa la mappa può restare senza.

## Dopo il giro delle 14

Nel codice, dopo l'APK delle 14:08. Rimesso sul Pixel alle 14:41, giro qui sotto.

La ricerca degli autovelox che parte in pianificazione arriva sulla mappa anche se nel frattempo è partita la guida. In corsa i pallini restano nascosti; a `Termina` si disegnano, oppure appena la ricerca risponde se finisce dopo. Se quella ricerca fallisce proprio dopo `Termina`, ne parte un'altra. Una risposta vuota non cancella i pallini che la guida aveva già messo. `Termina` non interrompe una ricerca ancora aperta.

«in auto» resta l'etichetta del ripiego, e solo quando il profilo moto ne ha dato uno solo. Alle 14 i due viaggi ne avevano già due, quindi non è uscita.

## Pallini dopo Termina, Pixel 4a, 6 ottobre 2026, ore 14:41

APK ricostruito alle 14:37 (sorgente delle 14:35) e installato. Dal foglio del Q8 ho scelto il recente Via Enrico Bernardi e ho premuto Simula appena il riepilogo è diventato 38 min, 23,7 km, senza aspettare i pallini.

- In guida, subito, il disco era «n.d.» e sulla mappa non c'era arancione.
- `Termina` dopo pochi secondi: stato «Fermo.», viaggio Via Enrico Bernardi, 38 min, 23,7 km.
- In panoramica, appena tornato al foglio, i pallini arancioni erano di nuovo sulla linea (lo stesso tratto di prima, intorno al centro della mappa).
- La scheda del pallino non l'ho riaperta: è arrivata una chiamata e ha coperto lo schermo. Alle 14 il tocco sullo stesso tipo di pallino aveva aperto «Autovelox 50».
- Sul seriale, per quei pochi secondi di guida, un solo `ROUTE percorso 10995 m, 39 curve`.

## Da rifare sul Pixel

Niente di questo punto. Font di sistema, modalità notte e GPS dell'emulatore restano come li hai lasciati tu.

Sull'OLED, in CURVA, la strada non deve sparire mentre arriva la finestra dopo. Il dettaglio è in `TEST_TELEFONO_ESP.md`. Il flash delle 15:16 è fatto: sul seriale le tre finestre della corsa verso Via Enrico Bernardi sono arrivate intere, fino a «Sei arrivato» e, dopo `Termina`, «Fermo.». L'OLED non è stato guardato a occhio.

## Disco sul viaggio lungo, Pixel 4a, 6 ottobre 2026, ore 15:51

APK ricostruito alle 15:51 e installato. Stesso arrivo, Via Enrico Bernardi ad Arre, 38 min, 23,7 km, «Evita autostrade» acceso. Simulazione portata a 110 km/h.

- A 20,8 km dall'arrivo il disco era «50». Stato «Simulazione a 110 km/h.»
- A 9,8 km, a 5,6 km e all'arrivo da fermo il disco era «n.d.». «Sei arrivato», 0 min, 0 m, tachimetro 0.
- `Termina`: «Fermo.», riepilogo ancora 38 min e 23,7 km.

Lo stesso percorso, chiesto a parte, ha i limiti fino a circa 14,5 km (30, 40, 50, 60, 90). Il pezzo dopo, Overpass non l'ha dato: 429 e timeout, risposta vuota. Sul telefono il disco era già «n.d.» intorno al 14° km, dove quel limite c'era. Un pezzo che non risponde veniva saltato e non richiesto di nuovo: superato l'ultimo cartello tenuto, di più di 1,2 km, il disco resta «n.d.».

Nel sorgente, dopo l'APK delle 15:51, il pezzo caduto si richiede per tutta la guida. Finché non risponde, il disco tiene l'ultimo numero anche oltre 1,2 km dall'ultimo cartello. Quando il pezzo arriva, il numero segue la strada. Da rifare sullo stesso viaggio: a 9,8 km dall'arrivo il disco non deve restare «n.d.» se il limite sulla strada c'è, o se quel pezzo non ha ancora risposto.

## Disco sul viaggio lungo, Pixel 4a, 6 ottobre 2026, ore 17:45

APK delle 17:45 installato: è quello in cui un pezzo senza risposta si richiede di nuovo e, nell'attesa, il disco tiene l'ultimo numero. Arrivo Via Enrico Bernardi ad Arre, «Evita autostrade» acceso, riepilogo 36 min e 22,9 km. Simulazione a 110 km/h.

- Subito «n.d.», poi ancora a 19,8 km, a 16,2 km e a 9,6 km dall'arrivo.
- All'arrivo da fermo, ancora «n.d.». «Sei arrivato», 0 min, 0 m, tachimetro 0. Dopo una quarantina di secondi, fermo, era ancora «n.d.».
- `Termina`: «Fermo.», stesso riepilogo.

Nello stesso intervallo, dal PC, `overpass-api.de` non ha aperto la connessione e `overpass.kumi.systems` è andato in timeout. Nessun pezzo è arrivato, quindi non c'era un numero da tenere. Il caso «tieni l'ultimo numero mentre un pezzo è in attesa» non si è visto.

## Disco da Valhalla, nel sorgente dopo l'APK delle 17:45

Il percorso di quel giro l'aveva calcolato Valhalla, e lì i cartelli ci sono già: sullo stesso arrivo, Via Enrico Bernardi ad Arre, circa 23,3 km, i limiti taggati sono 30, 50, 60 e 90 (circa 2,8 km senza cartello). Il disco ora li chiede a Valhalla appena parte la guida, prima di Overpass. Se Overpass risponde, aggiorna i pezzi; se tace, resta il numero di Valhalla. Un pezzo Overpass ancora senza risposta tiene l'ultimo numero, come prima.

Non è sull'APK delle 17:45. Da rifare sullo stesso arrivo: il disco non deve restare «n.d.» per tutta la corsa se Valhalla ha risposto.

## Rotonde, strade vicine, cavalcavia — 7 ottobre 2026, sera

APK messo sul Pixel e simulazione verso Via Enrico Bernardi, a 110 km/h, fermata a circa 17 km dall'arrivo.

- Icona del telefono sulla rotonda: ⟳, non una freccia. Testo «Alla rotonda prendi la seconda uscita, in Via Romea», poi «Alla rotonda prendi la prima uscita, in Via Roma». Il poi diceva «Poi alla rotonda, in Via Roma».
- Disco «50» da subito, ancora a 17 km. `Termina`: «Fermo.»
- Nel codice, una svolta entro 80 m prima della rotonda diventa la rotonda, così all'ingresso l'icona non resta una freccia. Le strade vicine non si fermano più dopo un salto di 90 m e restano finché l'incrocio non è passato. Sul cavalcavia la posizione resta sul tratto già in corso; se ricalcola, manda anche la direzione. L'OLED non è stato guardato: va visto in un giro vero.
