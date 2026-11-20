package it.diy.hudnav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Calcola la strada, la manda all'HUD e tiene aggiornati posizione e deviazioni.
 * Tutto il lavoro sta su un solo thread, così i pacchetti BLE non si accavallano.
 */
class NavEngine(private val app: Context, private val onIdle: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val extra = Executors.newFixedThreadPool(2)
    private val clock = Executors.newSingleThreadExecutor()
    private val locations = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val running = AtomicBoolean(false)
    private val fixGate = Object()
    private var latest: Location? = null

    private var route = listOf<LatLon>()
    private var cum = DoubleArray(0)
    private var steps = listOf<Step>()
    private var forks = listOf<RoadFork>()
    private var stops = listOf<LatLon>()
    private var stopAt = DoubleArray(0)
    private var avoidMotorway = false
    @Volatile
    private var sentTo = 0.0
    private val sending = AtomicBoolean(false)
    private var offStreak = 0
    private var lastReroute = 0L
    private var camGen = 0
    private var simS = 0.0
    private var along = 0.0
    private var lastPoke = 0L
    private val linking = AtomicBoolean(false)
    private var limits = emptyList<Speed.Mark>()
    private var limitsOpen = false
    @Volatile
    private var limitGen = 0
    private val posted = HashMap<String, String>()
    private val simulating = AtomicBoolean(false)

    init {
        HudLink.init(app)
    }

    private class Along(val label: String, val atM: Double)

    @Volatile
    private var routeFrame: Geo.RouteFrame? = null
    @Volatile
    private var sideGen = 0
    private var forkKey: String? = null
    private var shownForks = listOf<RoadFork>()
    private var sideS = -1.0
    private val sideBusy = AtomicBoolean(false)

    @Volatile
    private var alongAlerts = emptyList<Along>()

    private var lastAlertText = ""
    private var stepHint = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            synchronized(fixGate) {
                latest = location
                fixGate.notifyAll()
            }
            if (running.get()) worker.execute { onFix(location) }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start(
        stops: List<LatLon>,
        name: String,
        choice: Int = 0,
        avoidMotorway: Boolean = false,
        simulate: Boolean = false,
        from: LatLon? = null
    ) {
        worker.execute {
            try {
                runStart(stops, name, choice, avoidMotorway, simulate, from)
            } catch (e: Exception) {
                status(e.message ?: "Errore")
                runStop(fromUser = false)
            }
        }
    }

    fun stop() {
        worker.execute { runStop(fromUser = true) }
    }

    fun shutdown() {
        running.set(false)
        worker.shutdownNow()
        extra.shutdownNow()
        clock.shutdownNow()
        HudLink.close()
        removeUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun runStart(
        stops: List<LatLon>,
        name: String,
        choice: Int,
        avoidMotorway: Boolean,
        simulate: Boolean,
        origin: LatLon?
    ) {
        runStopQuiet()
        if (stops.isEmpty()) {
            status("Prima scegli l'arrivo.")
            runStop(fromUser = false)
            return
        }
        this.stops = stops
        this.avoidMotorway = avoidMotorway
        val dest = stops.last()
        HudState.destination.postValue(dest)
        val from = if (simulate) {
            status(if (name.isBlank()) "Simulazione. Calcolo la strada…" else "Simulazione verso $name…")
            origin ?: lastKnown() ?: run {
                status("Per la simulazione serve la posizione del telefono.")
                runStop(fromUser = false)
                return
            }
        } else {
            status(if (name.isBlank()) "Attendo il GPS…" else "Verso $name. Attendo il GPS…")
            requestUpdates()
            val here = awaitFix(20000) ?: run {
                status("GPS non disponibile.")
                runStop(fromUser = false)
                return
            }
            LatLon(here.latitude, here.longitude)
        }
        HudState.rider.postValue(from)
        status("Calcolo la strada…")
        val plans = Net.routes(from, stops, avoidMotorway)
        val plan = plans.getOrElse(choice.coerceIn(0, plans.lastIndex)) { plans.first() }
        if (plan.points.size < 2) {
            status("Nessuna strada.")
            runStop(fromUser = false)
            return
        }
        install(plan)
        running.set(true)
        TripStore.setGuiding(app, true)
        HudState.running.postValue(true)
        status("Cerco l'HUD…")
        if (simulate) HudState.simulating.postValue(true)
        val linked = HudLink.connect(4000)
        if (!running.get()) return
        val sent = linked && sendWindow(0.0)
        val phoneOnly = !sent
        if (simulate) {
            simS = 0.0
            simulating.set(true)
            simKmh = 70.0
            status(
                if (phoneOnly) "Simulazione sul telefono. HUD non trovato, riprovo."
                else "Simulazione a ${simKmh.toInt()} km/h."
            )
            publishProgress(0.0, simKmh, 0.0)
            driveSim()
        } else {
            status(if (phoneOnly) "Navigo sul telefono. HUD non trovato, riprovo." else "HUD collegato. Puoi spegnere lo schermo.")
            publishProgress(0.0, 0.0, 0.0)
        }
        if (phoneOnly) pokeHud()
    }

    /** Avanza il pilota sul percorso, senza GPS. In fondo si ferma. */
    private fun driveSim() {
        clock.execute {
            while (simulating.get() && running.get()) {
                worker.execute { advanceSim() }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    private fun advanceSim() {
        if (!simulating.get() || !running.get() || route.size < 2) return
        val len = Geo.length(cum)
        val pace = simKmh
        simS += pace / 3.6
        if (simS >= len - 15) {
            simS = len
            val end = Geo.pointAt(route, cum, simS)
            HudState.rider.postValue(end)
            publishProgress(simS, 0.0, 0.0)
            simulating.set(false)
            HudState.simulating.postValue(false)
            status("Sei arrivato.")
            return
        }
        val here = Geo.pointAt(route, cum, simS)
        val ahead = Geo.pointAt(route, cum, (simS + 20.0).coerceAtMost(len))
        val course = Geo.bearing(here, ahead)
        HudState.rider.postValue(here)
        if (simS > sentTo - 2500 && sentTo < len - 80) {
            sendWindow((simS - 200).coerceAtLeast(0.0))
        }
        writeHud(HudBle.pos(here.lat, here.lon, pace, course))
        publishProgress(simS, pace, course)
    }

    private fun install(plan: RoutePlan) {
        route = plan.points
        cum = Geo.cumulate(route)
        routeFrame = Geo.RouteFrame(route, cum)
        steps = plan.steps
        forks = plan.forks
        stepHint = 0
        alongAlerts = emptyList()
        lastAlertText = ""
        sentTo = 0.0
        offStreak = 0
        stopAt = DoubleArray(stops.size) { i ->
            Geo.project(route, cum, stops[i].lat, stops[i].lon)?.first ?: Double.MAX_VALUE
        }
        HudState.track.postValue(route)
        sideGen++
        forkKey = null
        shownForks = emptyList()
        sideS = -1.0
        loadLimits(route)
        loadCameras(route)
    }

    private fun onFix(loc: Location) {
        if (!running.get() || simulating.get()) return
        val lat = loc.latitude
        val lon = loc.longitude
        val kmh = if (loc.hasSpeed() && loc.speed >= 0) loc.speed * 3.6 else 0.0
        val course = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        HudState.rider.postValue(LatLon(lat, lon))
        val courseForMatch = if (kmh >= 8.0) course else Double.NaN
        val pr = routeFrame?.project(lat, lon, along, courseForMatch)
            ?: Geo.project(route, cum, lat, lon, along, courseForMatch)
        val acc = if (loc.hasAccuracy()) loc.accuracy else 99f
        if (pr != null && acc <= 30f && pr.second > 45.0) {
            offStreak++
            if (offStreak >= 3 && System.currentTimeMillis() - lastReroute > 20000) {
                offStreak = 0
                reroute(lat, lon, pr.first, courseForMatch)
                return
            }
        } else {
            offStreak = 0
        }
        if (pr != null && pr.first > sentTo - 2500 && sentTo < Geo.length(cum) - 80) {
            sendWindow((pr.first - 200).coerceAtLeast(0.0))
        }
        writeHud(HudBle.pos(lat, lon, kmh, course))
        if (pr != null) publishProgress(pr.first, kmh, course)
        else {
            postText("speed", HudState.speed, kmh.roundToInt().toString())
            HudState.bearing.postValue(course.toFloat())
            applyCachedLimit(along)
        }
    }

    private fun reroute(lat: Double, lon: Double, progress: Double, courseDeg: Double) {
        lastReroute = System.currentTimeMillis()
        status("Deviazione: calcolo la strada nuova…")
        val remain = remainStops(progress)
        val plan = try {
            val heading = if (courseDeg.isNaN()) null else courseDeg
            Net.routes(LatLon(lat, lon), remain, avoidMotorway, heading = heading).firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (plan == null || plan.points.size < 2) return
        stops = remain
        install(plan)
        sendWindow(0.0)
        status("Strada aggiornata.")
        publishProgress(0.0, 0.0, 0.0)
    }

    /** Tappe ancora davanti, più l'arrivo. */
    private fun remainStops(progress: Double): List<LatLon> {
        if (stops.isEmpty()) return emptyList()
        val ahead = ArrayList<LatLon>()
        for (i in 0 until stops.lastIndex) {
            if (i < stopAt.size && stopAt[i] > progress + 80) ahead.add(stops[i])
        }
        ahead.add(stops.last())
        return ahead
    }

    private fun sendWindow(fromS: Double): Boolean {
        if (route.size < 2) return false
        if (!sending.compareAndSet(false, true)) return sentTo > fromS
        val (pts, endS) = Geo.window(route, cum, fromS)
        if (pts.size < 2) {
            sending.set(false)
            return false
        }
        return try {
            HudLink.call(false, 60) { ble ->
                if (!ble.isReady()) return@call false
                val wrote = sendChunks(ble, pts, ble.pointsPerPacket()) || sendChunks(ble, pts, 2)
                if (wrote) sentTo = endS
                wrote
            }
        } finally {
            sending.set(false)
        }
    }

    private fun sendChunks(ble: HudBle, pts: List<LatLon>, per: Int): Boolean {
        if (!ble.writeBlocking(HudBle.begin(pts.size, pts[0].lat, pts[0].lon))) return false
        var i = 0
        while (i < pts.size) {
            val chunk = pts.subList(i, minOf(pts.size, i + per))
            if (!ble.writeBlocking(HudBle.points(i, chunk))) return false
            i += chunk.size
        }
        return ble.writeBlocking(HudBle.end(pts.size))
    }

    private fun pokeHud() {
        val now = System.currentTimeMillis()
        if (now - lastPoke < 12000) return
        if (!linking.compareAndSet(false, true)) return
        lastPoke = now
        extra.execute {
            try {
                val ok = HudLink.connect(4000)
                if (ok && running.get() && !sending.get()) {
                    worker.execute { if (running.get()) sendWindow((along - 200).coerceAtLeast(0.0)) }
                }
            } finally {
                linking.set(false)
            }
        }
    }

    private fun loadLimits(pts: List<LatLon>) {
        val gen = ++limitGen
        limits = emptyList()
        postText("limit", HudState.limit, "")
        extra.execute {
            try {
                Net.speedLimits(pts, still = { gen == limitGen && running.get() }) { marks, open ->
                    worker.execute {
                        if (gen != limitGen || !running.get()) return@execute
                        limits = marks
                        limitsOpen = open
                        applyCachedLimit(along)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun applyCachedLimit(s: Double) {
        postText("limit", HudState.limit, Speed.along(limits, s, holdGap = limitsOpen)?.toString().orEmpty())
    }

    private fun postText(key: String, live: MutableLiveData<String>, value: String) {
        if (posted.put(key, value) == value) return
        live.postValue(value)
    }

    private fun loadCameras(pts: List<LatLon>) {
        val gen = ++camGen
        extra.execute {
            val found = try {
                Net.cameras(pts)
            } catch (_: Exception) {
                return@execute
            }
            if (gen != camGen || !running.get()) return@execute
            val frame = routeFrame
            val placed = ArrayList<Along>()
            if (frame != null) {
                for (c in found) {
                    val pr = frame.project(c.lat, c.lon) ?: continue
                    if (pr.second > 80.0) continue
                    placed.add(Along(c.label, pr.first))
                }
                placed.sortBy { it.atM }
            }
            alongAlerts = placed
            if (found.isNotEmpty()) HudState.cameras.postValue(found)
        }
    }

    private fun writeHud(bytes: ByteArray) {
        if (!HudLink.connected) return
        HudLink.call(false) { ble -> ble.isReady() && ble.writeBlocking(bytes) }
    }

    private fun nextIndex(s: Double): Int {
        var i = stepHint.coerceIn(0, steps.size)
        if (i > 0 && steps[i - 1].atM > s + 12) i = 0
        while (i < steps.size && steps[i].atM <= s + 12) i++
        stepHint = i
        return i
    }

    private fun publishProgress(s: Double, kmh: Double, course: Double) {
        along = s
        val remain = (Geo.length(cum) - s).coerceAtLeast(0.0)
        val arrived = remain < 40.0
        val sec = if (arrived) 0.0 else remainSeconds(s)
        if (arrived) {
            postText("mdist", HudState.maneuverDist, "")
            HudState.maneuverMeters.postValue(0)
            postText("mtext", HudState.maneuver, "Sei arrivato")
            postText("then", HudState.thenNext, "")
            HudState.turn.postValue(9)
            postText("rdist", HudState.remainDist, "0 m")
            postText("rtime", HudState.remainTime, "0 min")
            postText("eta", HudState.eta, fmtClock(System.currentTimeMillis()))
            postText("trip", HudState.trip, "0 m · 0 min")
        } else {
            val i = nextIndex(s)
            val next = if (i < steps.size) steps[i] else null
            if (next == null) {
                postText("mdist", HudState.maneuverDist, "")
                HudState.maneuverMeters.postValue(-1)
                postText("mtext", HudState.maneuver, "")
                postText("then", HudState.thenNext, "")
                HudState.turn.postValue(0)
            } else {
                val distM = (next.atM - s).coerceAtLeast(0.0)
                postText("mdist", HudState.maneuverDist, fmtDist(distM))
                HudState.maneuverMeters.postValue(distM.roundToInt())
                postText("mtext", HudState.maneuver, next.text)
                HudState.turn.postValue(next.turn)
                val after = if (i + 1 < steps.size) steps[i + 1] else null
                postText("then", HudState.thenNext, if (after == null) "" else Phrase.thenNext(after.text))
            }
            val etaMs = System.currentTimeMillis() + (sec * 1000.0).toLong()
            val distText = fmtDist(remain)
            val timeText = fmtMin(sec)
            val clock = fmtClock(etaMs)
            postText("trip", HudState.trip, "$distText · $timeText · $clock")
            postText("rdist", HudState.remainDist, distText)
            postText("rtime", HudState.remainTime, timeText)
            postText("eta", HudState.eta, clock)
        }
        postText("speed", HudState.speed, kmh.roundToInt().toString())
        HudState.bearing.postValue(course.toFloat())
        val onRoad = steps.lastOrNull { it.atM <= s + 8 }?.road.orEmpty()
        postText("road", HudState.road, onRoad)
        publishAlert(s)
        sendGuide(s, remain, sec)
        applyCachedLimit(s)
        maybeSides(s)
        if (!HudLink.connected && !sending.get()) pokeHud()
    }

    /** Bracci già mostrati restano finché l'incrocio non è passato. Un salto GPS non li cancella. */
    private fun maybeSides(s: Double) {
        if (!HudLink.connected || sideBusy.get()) return
        sideS = s
        val next = Geo.latchForks(forks, shownForks, s)
        val lines = next.flatMap { it.arms }
        val key = next.joinToString(";") { "${it.atM.toInt()}:${it.arms.size}" }
        if (key == forkKey) return
        if (!sideBusy.compareAndSet(false, true)) return
        val gen = sideGen
        extra.execute {
            try {
                if (gen != sideGen || !running.get() || !HudLink.connected) return@execute
                val sent = HudLink.call(false, 8) { ble ->
                    if (!ble.isReady()) return@call false
                    val room = ble.payloadRoom().coerceIn(48, 220)
                    for (pkt in sidePackets(lines, room)) {
                        if (!ble.writeBlocking(pkt)) return@call false
                    }
                    true
                }
                if (sent && gen == sideGen) {
                    forkKey = key
                    shownForks = next
                }
            } finally {
                sideBusy.set(false)
            }
        }
    }

    private fun sendGuide(s: Double, remain: Double, sec: Double) {
        val etaMs = System.currentTimeMillis() + (sec * 1000.0).toLong()
        val z = Instant.ofEpochMilli(etaMs).atZone(ZoneId.systemDefault())
        val i = nextIndex(s)
        val next = if (i < steps.size) steps[i] else null
        val arrived = remain < 40.0
        val dist = if (arrived || next == null) 0 else (next.atM - s).roundToInt().coerceAtLeast(0)
        val turn = if (arrived) 9 else guideCode(next?.turn ?: 0, next?.exit ?: 0)
        val text = if (arrived) "Sei arrivato" else next?.text ?: ""
        writeHud(HudBle.guide(turn, dist, remain.roundToInt(), sec.roundToInt(), z.hour * 60 + z.minute, text))
    }

    private fun remainSeconds(s: Double): Double {
        if (steps.isEmpty()) return ((Geo.length(cum) - s).coerceAtLeast(0.0)) / 13.9
        var t = 0.0
        for (i in steps.indices) {
            val start = steps[i].atM
            val end = if (i + 1 < steps.size) steps[i + 1].atM else Geo.length(cum)
            if (end <= s) continue
            val span = (end - start).coerceAtLeast(1.0)
            val from = maxOf(s, start)
            val dur = if (steps[i].durS > 0) steps[i].durS else span / 13.9
            t += dur * (end - from) / span
        }
        return t
    }

    private fun publishAlert(s: Double) {
        var best: Along? = null
        var bestAhead = 700.0
        for (c in alongAlerts) {
            val ahead = c.atM - s
            if (ahead < 30.0) continue
            if (ahead > 650.0) break
            if (ahead < bestAhead) {
                best = c
                bestAhead = ahead
            }
        }
        val text = if (best == null) "" else "${best.label} tra ${bestAhead.roundToInt()} m"
        if (text == lastAlertText) return
        lastAlertText = text
        HudState.alert.postValue(text)
    }

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000.0)

    private fun fmtMin(sec: Double): String {
        if (sec < 45) return "0 min"
        val min = (sec / 60.0).roundToInt()
        if (min <= 0) return "0 min"
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun fmtClock(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(CLOCK)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): LatLon? {
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val fix = try {
                locations.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || fix.time > best.time) best = fix
        }
        val chosen = best ?: return null
        if (System.currentTimeMillis() - chosen.time > 10 * 60 * 1000) return null
        return LatLon(chosen.latitude, chosen.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            if (locations.isProviderEnabled(p)) {
                locations.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun awaitFix(timeoutMs: Long): Location? {
        val now = System.currentTimeMillis()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val last = locations.getLastKnownLocation(p) ?: continue
            if (now - last.time < 120000) return last
        }
        synchronized(fixGate) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (latest == null && System.currentTimeMillis() < deadline) fixGate.wait(250)
            return latest
        }
    }

    private fun runStop(fromUser: Boolean) {
        simulating.set(false)
        HudState.simulating.postValue(false)
        val was = running.getAndSet(false)
        removeUpdates()
        HudLink.call(false) { ble ->
            if (ble.isReady()) ble.writeBlocking(HudBle.clear())
            ble.close()
            true
        }
        if (was) {
            HudState.running.postValue(false)
            HudState.maneuverDist.postValue("")
            HudState.maneuverMeters.postValue(-1)
            HudState.maneuver.postValue("")
            HudState.thenNext.postValue("")
            HudState.track.postValue(emptyList())
            HudState.pois.postValue(emptyList())
            HudState.trip.postValue("")
            HudState.remainDist.postValue("")
            HudState.remainTime.postValue("")
            HudState.eta.postValue("")
            HudState.road.postValue("")
            HudState.turn.postValue(0)
            HudState.limit.postValue("")
            HudState.speed.postValue("")
            HudState.alert.postValue("")
            camGen++
            limitGen++
            sideGen++
            forkKey = null
            shownForks = emptyList()
            sideS = -1.0
            limits = emptyList()
            limitsOpen = false
            posted.clear()
            alongAlerts = emptyList()
            lastAlertText = ""
            TripStore.setGuiding(app, false)
            if (fromUser) status("Fermo.")
            onIdle()
        }
    }

    private fun runStopQuiet() {
        simulating.set(false)
        running.set(false)
        removeUpdates()
        HudLink.call(false) { it.close(); true }
    }

    @SuppressLint("MissingPermission")
    private fun removeUpdates() {
        try {
            locations.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    private fun status(text: String) {
        HudState.status.postValue(text)
    }

    companion object {
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        @Volatile
        var simKmh = 70.0

        fun cycleSimSpeed() {
            simKmh = when {
                simKmh < 55 -> 70.0
                simKmh < 90 -> 110.0
                else -> 40.0
            }
        }
    }
}
