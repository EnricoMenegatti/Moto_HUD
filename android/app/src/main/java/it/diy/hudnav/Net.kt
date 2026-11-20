package it.diy.hudnav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class RoutePlan(
    val points: List<LatLon>,
    val steps: List<Step>,
    val meters: Double,
    val seconds: Double,
    val label: String,
    val byCar: Boolean = false,
    val forks: List<RoadFork> = emptyList()
)

data class PlaceHit(val lat: Double, val lon: Double, val name: String, val kind: String = "")

object Net {
    private const val UA = "HUD-Moto-nav/1.0"

    init {
        // Sull'emulatore l'IPv6 non risponde e ogni richiesta aspetta il timeout.
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }

    private val fetch = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "hud-route").apply { isDaemon = true }
    }

    fun search(q: String, bias: LatLon?): List<PlaceHit> {
        val sb = StringBuilder("https://photon.komoot.io/api/?limit=6&q=")
        sb.append(URLEncoder.encode(q, "UTF-8"))
        if (bias != null) sb.append("&lat=").append(bias.lat).append("&lon=").append(bias.lon)
        val json = JSONObject(get(sb.toString()))
        val features = json.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<PlaceHit>()
        val seen = HashSet<String>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.optJSONObject("properties") ?: JSONObject()
            val lat = coords.getDouble(1)
            val lon = coords.getDouble(0)
            val name = p.optString("name").ifBlank { p.optString("city") }.ifBlank { "Punto" }
            val city = listOf(p.optString("city"), p.optString("state")).filter { it.isNotBlank() }.joinToString(", ")
            val key = name.lowercase() + "@" + city.lowercase()
            if (!seen.add(key)) continue
            val dist = if (bias == null) "" else formatKm(Geo.hav(bias, LatLon(lat, lon)))
            val kind = kindLabel(p.optString("osm_value").ifBlank { p.optString("type") })
            val detail = listOf(kind, city, dist).filter { it.isNotBlank() }.joinToString(" · ")
            out.add(PlaceHit(lat, lon, name, detail))
        }
        return out
    }

    fun reverse(at: LatLon): PlaceHit? {
        val json = JSONObject(get("https://photon.komoot.io/reverse?lon=${at.lon}&lat=${at.lat}", 5000, 12000))
        val f = json.optJSONArray("features")?.optJSONObject(0) ?: return null
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        val p = f.optJSONObject("properties") ?: JSONObject()
        val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
        val name = p.optString("name").ifBlank { street }
        val feature = LatLon(coords.getDouble(1), coords.getDouble(0))
        return tappedPlace(at, feature, name, street, p.optString("city"))
    }

    /** Il tocco resta dove è stato messo. Un posto lontano non sposta il pin e non gli dà il nome. */
    internal fun tappedPlace(tap: LatLon, feature: LatLon?, name: String, street: String, city: String): PlaceHit {
        val near = feature != null && Geo.hav(tap, feature) <= 60.0
        if (!near) return PlaceHit(tap.lat, tap.lon, "Punto sulla mappa", "")
        val label = name.ifBlank { street }.ifBlank { city }.ifBlank { "Punto sulla mappa" }
        return PlaceHit(tap.lat, tap.lon, label, city)
    }

    fun route(from: LatLon, to: LatLon): RoutePlan = routes(from, listOf(to)).first()

    /** Percorsi da from attraverso i punti, l'ultimo è l'arrivo. Il primo è il più veloce. */
    fun routes(
        from: LatLon,
        through: List<LatLon>,
        avoidMotorway: Boolean = false,
        heading: Double? = null
    ): List<RoutePlan> {
        if (through.isEmpty()) throw IllegalStateException("Nessuna strada")
        val coords = buildString {
            append(from.lon).append(',').append(from.lat)
            for (p in through) append(';').append(p.lon).append(',').append(p.lat)
        }
        val deadline = System.currentTimeMillis() + 8000
        val motoCall = fetch.submit<JSONObject?> {
            try {
                askValhalla(coords, avoidMotorway, heading)
            } catch (_: Exception) {
                null
            }
        }
        val carCall = fetch.submit<JSONObject?> {
            try {
                askOsrm(coords, alternatives = true)
            } catch (_: Exception) {
                null
            }
        }
        val raw = ArrayList<RoutePlan>()
        val motoJson = await(motoCall, 8000)
        var moto = false
        if (motoJson != null && motoJson.optString("code") == "Ok") {
            collect(motoJson, raw, byCar = false)
            moto = raw.isNotEmpty()
        }
        val needCar = raw.isEmpty() || (!avoidMotorway && raw.size < 2)
        if (needCar) {
            val left = if (raw.isEmpty()) deadline - System.currentTimeMillis() else 700L
            val carJson = await(carCall, left.coerceAtLeast(0))
            if (carJson != null && carJson.optString("code") == "Ok") {
                collect(carJson, raw, byCar = moto)
            } else if (raw.isEmpty()) {
                val plain = try {
                    askOsrm(coords, alternatives = false)
                } catch (_: Exception) {
                    null
                }
                if (plain != null && plain.optString("code") == "Ok") collect(plain, raw, byCar = false)
            }
        } else {
            carCall.cancel(true)
        }
        if (raw.isEmpty()) throw IllegalStateException("Nessuna strada")
        usedMotorcycle = moto
        avoidFellBack = avoidMotorway && !moto
        return raw.mapIndexed { i, plan ->
            val label = if (moto && i == 0 && !plan.byCar) motoLabel(plan) else routeLabel(plan, i, raw)
            plan.copy(label = label)
        }
    }

    private fun <T> await(call: java.util.concurrent.Future<T>, ms: Long): T? {
        return try {
            call.get(ms, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            call.cancel(true)
            null
        }
    }

    private fun collect(json: JSONObject, into: ArrayList<RoutePlan>, byCar: Boolean) {
        val routes = json.optJSONArray("routes") ?: return
        for (i in 0 until routes.length()) {
            if (into.size == 3) return
            val plan = parseRoute(routes.getJSONObject(i))
            if (plan.points.size < 2) continue
            val dup = into.any {
                kotlin.math.abs(it.seconds - plan.seconds) < 45 &&
                    kotlin.math.abs(it.meters - plan.meters) < 400
            }
            if (!dup) into.add(if (byCar) plan.copy(byCar = true) else plan)
        }
    }

    /** Vero se l'ultima richiesta «evita autostrade» è tornata indietro alla strada normale. */
    @Volatile
    var avoidFellBack = false
        private set

    /** Vero se l'ultimo percorso principale è calcolato col profilo moto. */
    @Volatile
    var usedMotorcycle = false
        private set

    /** Profilo motorcycle di Valhalla. Senza autostrada: use_highways=0. Mai i sentieri. */
    private fun askValhalla(coords: String, avoidMotorway: Boolean, heading: Double? = null): JSONObject? {
        val locations = org.json.JSONArray()
        var first = true
        for (part in coords.split(';')) {
            val bits = part.split(',')
            if (bits.size < 2) continue
            val lon = bits[0].toDoubleOrNull() ?: continue
            val lat = bits[1].toDoubleOrNull() ?: continue
            val loc = JSONObject().put("lon", lon).put("lat", lat)
            if (first && heading != null && !heading.isNaN()) {
                val deg = ((heading % 360.0) + 360.0) % 360.0
                loc.put("heading", deg.toInt())
            }
            first = false
            locations.put(loc)
        }
        if (locations.length() < 2) return null
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "motorcycle")
            .put(
                "costing_options",
                JSONObject().put(
                    "motorcycle",
                    JSONObject()
                        .put("use_highways", if (avoidMotorway) 0 else 1)
                        .put("use_trails", 0)
                )
            )
            .put("alternates", 2)
            .put("format", "osrm")
            .put("shape_format", "geojson")
            .put("generalize", 10)
            .put("directions_options", JSONObject().put("language", "it-IT"))
        return try {
            JSONObject(postJson("https://valhalla1.openstreetmap.de/route", body.toString(), 4000, 7000))
        } catch (_: Exception) {
            null
        }
    }

    private fun askOsrm(coords: String, alternatives: Boolean): JSONObject {
        val alt = if (alternatives) "&alternatives=3" else ""
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "$coords?overview=full&geometries=geojson&steps=true$alt"
        return try {
            JSONObject(get(url, 4000, 7000))
        } catch (_: Exception) {
            JSONObject().put("code", "Error")
        }
    }

    private fun parseRoute(route: JSONObject): RoutePlan {
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(LatLon(c.getDouble(1), c.getDouble(0)))
        }
        val steps = ArrayList<Step>()
        val junctions = ArrayList<Pair<LatLon, List<List<LatLon>>>>()
        val legs = route.optJSONArray("legs")
        var at = 0.0
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val arr = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until arr.length()) {
                    val st = arr.getJSONObject(si)
                    val man = st.optJSONObject("maneuver") ?: JSONObject()
                    val dist = st.optDouble("distance", 0.0)
                    val dur = st.optDouble("duration", if (dist > 0) dist / 13.9 else 0.0)
                    val type = man.optString("type")
                    val modifier = man.optString("modifier")
                    val road = st.optString("name")
                    val exit = man.optInt("exit", 0)
                    val lastLeg = li == legs.length() - 1
                    steps.add(Step(at, dur, Phrase.maneuver(type, modifier, road, exit, lastLeg), turnCode(type, modifier), road, exit))
                    junctions.addAll(junctionArms(st))
                    at += dist
                }
            }
            foldEntryIntoRoundabout(steps)
        }
        val thinned = Geo.thin(pts, 8.0)
        val cum = Geo.cumulate(thinned)
        val meters = route.optDouble("distance", Geo.length(cum))
        val seconds = route.optDouble("duration", steps.sumOf { it.durS })
        val forks = Geo.placeForks(junctions, Geo.RouteFrame(thinned, cum))
        return RoutePlan(thinned, steps, meters, seconds, "", forks = forks)
    }

    private fun motoLabel(plan: RoutePlan): String {
        val road = roadsOf(plan.steps).firstOrNull()
        return if (road.isNullOrBlank()) "Profilo moto" else "Profilo moto · $road"
    }

    internal fun routeLabel(plan: RoutePlan, index: Int, all: List<RoutePlan>): String {
        val mine = roadsOf(plan.steps)
        val fastest = roadsOf(all.first().steps).firstOrNull()
        val road = if (index == 0) mine.firstOrNull() else mine.firstOrNull { it != fastest } ?: mine.firstOrNull()
        val via = if (road.isNullOrBlank()) "" else " · $road"
        val car = if (plan.byCar && all.firstOrNull()?.byCar == false) " · in auto" else ""
        if (index == 0) return "Più veloce$via"
        val extraMin = ((plan.seconds - all.first().seconds) / 60.0).roundToInt()
        val time = when {
            extraMin > 0 -> "+$extraMin min"
            extraMin < 0 -> "$extraMin min"
            else -> "Simile"
        }
        return "$time$via$car"
    }

    private fun roadsOf(steps: List<Step>): List<String> {
        val score = LinkedHashMap<String, Double>()
        for (step in steps) {
            val name = step.road.trim()
            if (name.isBlank() || name == "-") continue
            score[name] = (score[name] ?: 0.0) + step.durS
        }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Ogni incrocio del tratto, anche quelli che si attraversano dritti. */
    private fun junctionArms(step: JSONObject): List<Pair<LatLon, List<List<LatLon>>>> {
        val intersections = step.optJSONArray("intersections") ?: return emptyList()
        val out = ArrayList<Pair<LatLon, List<List<LatLon>>>>()
        for (k in 0 until intersections.length()) {
            val junction = intersections.optJSONObject(k) ?: continue
            val loc = junction.optJSONArray("location") ?: continue
            if (loc.length() < 2) continue
            val bearingsArr = junction.optJSONArray("bearings") ?: continue
            val bearings = ArrayList<Double>(bearingsArr.length())
            for (i in 0 until bearingsArr.length()) bearings.add(bearingsArr.getDouble(i))
            val taken = HashSet<Int>()
            if (junction.has("in") && !junction.isNull("in")) taken.add(junction.getInt("in"))
            if (junction.has("out") && !junction.isNull("out")) taken.add(junction.getInt("out"))
            val at = LatLon(loc.getDouble(1), loc.getDouble(0))
            val arms = Geo.forkArms(at, bearings, taken)
            if (arms.isNotEmpty()) out.add(at to arms)
        }
        return out
    }

    /** L'ingresso in rotonda è spesso una svolta corta: sull'icona deve essere la rotonda. */
    internal fun foldEntryIntoRoundabout(steps: MutableList<Step>) {
        var i = 0
        while (i < steps.size - 1) {
            val cur = steps[i]
            val next = steps[i + 1]
            val gap = next.atM - cur.atM
            val entry = cur.turn in 0..7
            if (next.turn == 8 && entry && gap in 0.0..80.0) {
                steps[i] = next.copy(atM = cur.atM, durS = cur.durS + next.durS)
                steps.removeAt(i + 1)
                continue
            }
            i++
        }
    }

    private fun isRoundabout(type: String): Boolean =
        type == "roundabout" || type == "rotary" || type == "roundabout turn" ||
            type == "exit roundabout" || type == "exit rotary"

    private fun turnCode(type: String, modifier: String): Int = when {
        type == "arrive" -> 9
        isRoundabout(type) -> 8
        modifier == "uturn" -> 7
        modifier == "sharp right" -> 6
        modifier == "right" -> 5
        modifier == "slight right" -> 4
        modifier == "sharp left" -> 3
        modifier == "left" -> 2
        modifier == "slight left" -> 1
        else -> 0
    }

    private fun kindLabel(raw: String): String = when (raw) {
        "city", "town", "village" -> "città"
        "suburb", "neighbourhood", "quarter" -> "quartiere"
        "house", "address" -> "indirizzo"
        "station", "halt" -> "stazione"
        "fuel" -> "benzina"
        "parking" -> "parcheggio"
        else -> ""
    }

    private fun formatKm(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)

    /** Benzina, parcheggio, cibo o soccorso intorno a un punto. Nodi e aree. */
    fun pois(center: LatLon, kind: String): List<PlaceHit> {
        val filter = when (kind) {
            "fuel" -> """nwr["amenity"="fuel"](around:8000,${center.lat},${center.lon});"""
            "parking" -> """nwr["amenity"="parking"](around:4000,${center.lat},${center.lon});"""
            "food" -> """nwr["amenity"~"^(restaurant|fast_food|cafe)$"](around:3000,${center.lat},${center.lon});"""
            else -> """nwr["amenity"~"^(hospital|pharmacy)$"](around:8000,${center.lat},${center.lon});"""
        }
        val fallback = when (kind) {
            "fuel" -> "Distributore"
            "parking" -> "Parcheggio"
            "food" -> "Ristorante"
            else -> "Soccorso"
        }
        val elements = overpass("[out:json][timeout:20];($filter);out center 16;")
        val out = ArrayList<PlaceHit>()
        val seen = HashSet<String>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val at = elementPoint(el) ?: continue
            val key = "${(at.lat * 1e4).toInt()}_${(at.lon * 1e4).toInt()}"
            if (!seen.add(key)) continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            out.add(PlaceHit(at.lat, at.lon, tagName(tags, fallback), kind))
        }
        return out
    }

    /**
     * Autovelox, tutor e pericoli fissi vicino al percorso.
     * Sono i dati di OpenStreetMap, non il flusso live di Waze.
     */
    fun cameras(pts: List<LatLon>): List<RoadAlert> {
        if (pts.size < 2) return emptyList()
        val cum = Geo.cumulate(pts)
        val len = Geo.length(cum)
        val spacing = (len / 10.0).coerceIn(2000.0, 7000.0)
        val samples = ArrayList<LatLon>()
        var s = 0.0
        while (s <= len && samples.size < 12) {
            samples.add(Geo.pointAt(pts, cum, s))
            s += spacing
        }
        val radius = (spacing / 2.0 + 500.0).toInt().coerceIn(1500, 4500)
        val clauses = samples.joinToString("") { p ->
            "node[\"highway\"=\"speed_camera\"](around:$radius,${p.lat},${p.lon});" +
                "node[\"enforcement\"~\"^(maxspeed|average_speed)$\"](around:$radius,${p.lat},${p.lon});" +
                "node[\"hazard\"](around:$radius,${p.lat},${p.lon});"
        }
        val elements = overpass("[out:json][timeout:25];($clauses);out body 60;")
        val out = ArrayList<RoadAlert>()
        val seen = HashSet<String>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "node") continue
            val lat = el.optDouble("lat")
            val lon = el.optDouble("lon")
            val key = "${(lat * 1e4).toInt()}_${(lon * 1e4).toInt()}"
            if (!seen.add(key)) continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val label = alertLabel(tags) ?: continue
            out.add(RoadAlert(lat, lon, label))
        }
        return keepOnRoute(pts, cum, out)
    }

    /**
     * Strade intorno al pilota, per la mini-mappa: le diramazioni, non la traccia.
     * Al massimo sei tratti, otto punti l'uno, nel raggio che sta nello schermo.
     */
    fun sideRoads(center: LatLon, frame: Geo.RouteFrame): List<List<LatLon>> {
        val lat = "%.5f".format(Locale.US, center.lat)
        val lon = "%.5f".format(Locale.US, center.lon)
        val elements = overpass(
            "[out:json][timeout:8];" +
                "way(around:170,$lat,$lon)[\"highway\"]" +
                "[\"highway\"!~\"^(footway|pedestrian|cycleway|path|steps|corridor|bridleway|service|track|proposed|construction|platform|raceway)$\"];" +
                "out geom 24;"
        )
        val ways = ArrayList<Pair<String, List<LatLon>>>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway")
            if (highway.isBlank()) continue
            val geom = el.optJSONArray("geometry") ?: continue
            val pts = ArrayList<LatLon>(geom.length())
            for (g in 0 until geom.length()) {
                val p = geom.optJSONObject(g) ?: continue
                if (!p.has("lat") || !p.has("lon")) continue
                pts.add(LatLon(p.optDouble("lat"), p.optDouble("lon")))
            }
            if (pts.size >= 2) ways.add(highway to pts)
        }
        return sidePolylines(ways, center, frame)
    }

    /** Tieni i tratti che si staccano dalla traccia. Quello su cui sei già è la linea spessa. */
    internal fun sidePolylines(
        ways: List<Pair<String, List<LatLon>>>,
        center: LatLon,
        frame: Geo.RouteFrame
    ): List<List<LatLon>> {
        val found = ArrayList<List<LatLon>>()
        val skip = setOf(
            "footway", "pedestrian", "cycleway", "path", "steps", "corridor",
            "bridleway", "service", "track", "proposed", "construction", "platform", "raceway"
        )
        for ((kind, pts) in ways) {
            if (kind in skip) continue
            for (run in clipNear(pts, center, 175.0)) {
                val thin = Geo.thin(run, 22.0)
                val line = capLine(thin, 8)
                if (line.size < 2) continue
                var leaves = false
                var near = false
                for (p in line) {
                    val lateral = frame.project(p.lat, p.lon)?.second ?: continue
                    if (lateral > 18.0) leaves = true
                    if (lateral < 160.0) near = true
                }
                if (leaves && near) found.add(line)
            }
        }
        found.sortBy { line -> line.minOf { Geo.hav(center, it) } }
        return found.take(6)
    }

    private fun clipNear(pts: List<LatLon>, center: LatLon, radius: Double): List<List<LatLon>> {
        val runs = ArrayList<List<LatLon>>()
        var cur = ArrayList<LatLon>()
        for (p in pts) {
            if (Geo.hav(center, p) <= radius) cur.add(p)
            else if (cur.size >= 2) {
                runs.add(cur)
                cur = ArrayList()
            } else {
                cur = ArrayList()
            }
        }
        if (cur.size >= 2) runs.add(cur)
        return runs
    }

    private fun capLine(pts: List<LatLon>, max: Int): List<LatLon> {
        if (pts.size <= max) return pts
        val out = ArrayList<LatLon>(max)
        for (i in 0 until max) out.add(pts[(i * (pts.size - 1)) / (max - 1)])
        return out
    }

    /** Pochi pallini, solo quelli sulla strada: autovelox distanziati, poi qualche pericolo. */
    private fun keepOnRoute(pts: List<LatLon>, cum: DoubleArray, found: List<RoadAlert>): List<RoadAlert> {
        if (found.isEmpty()) return found
        val frame = Geo.RouteFrame(pts, cum)
        val placed = ArrayList<Triple<RoadAlert, Double, Boolean>>()
        for (alert in found) {
            val pr = frame.project(alert.lat, alert.lon) ?: continue
            if (pr.second > 40.0) continue
            val camera = alert.label.startsWith("Autovelox") || alert.label.startsWith("Tutor")
            placed.add(Triple(alert, pr.first, camera))
        }
        placed.sortBy { it.second }
        val kept = ArrayList<RoadAlert>()
        var lastCamera = -1_000_000.0
        for (item in placed) {
            if (!item.third || item.second - lastCamera < 700.0) continue
            kept.add(item.first)
            lastCamera = item.second
            if (kept.size == 10) break
        }
        var lastHazard = -1_000_000.0
        var hazards = 0
        for (item in placed) {
            if (item.third || hazards == 4) continue
            if (item.second - lastHazard < 1200.0) continue
            if (kept.any { kotlin.math.abs((frame.project(it.lat, it.lon)?.first ?: 0.0) - item.second) < 400.0 }) continue
            kept.add(item.first)
            lastHazard = item.second
            hazards++
        }
        return kept
    }

    /**
     * Limiti lungo il percorso, una volta sola.
     * Il numero arriva prima da Valhalla, lo stesso servizio del percorso: se Overpass tace,
     * il disco ha comunque il cartello. Overpass, a pezzi, aggiorna quando risponde.
     * Un pezzo che Overpass lascia cadere (429, timeout) si richiede per tutta la guida.
     * Finché non risponde, il disco tiene l'ultimo numero invece di passare a «n.d.».
     * Non si interroga il pin a ogni spostamento.
     */
    fun speedLimits(
        pts: List<LatLon>,
        still: () -> Boolean = { true },
        onUpdate: (List<Speed.Mark>, Boolean) -> Unit = { _, _ -> }
    ): List<Speed.Mark> {
        if (pts.size < 2) return emptyList()
        val line = Geo.thin(pts, 280.0)
        if (line.size < 2) return emptyList()
        val frame = Geo.RouteFrame(pts, Geo.cumulate(pts))
        val found = ArrayList<Speed.Mark>()
        val prior = valhallaLimits(frame, pts)
        if (prior.isNotEmpty()) found.addAll(prior)
        val missed = ArrayList<List<LatLon>>()
        var open = false
        fun publish() {
            if (found.isEmpty()) return
            onUpdate(collapseLimits(found), open)
        }
        fun take(slice: List<LatLon>): Boolean {
            return try {
                val added = limitMarks(frame, slice)
                if (added.isNotEmpty()) {
                    found.addAll(added)
                    publish()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        if (found.isNotEmpty()) publish()
        var from = 0
        while (from < line.size && still()) {
            val to = minOf(line.size, from + 24)
            if (to - from < 2) break
            val slice = ArrayList(line.subList(from, to))
            if (!take(slice)) missed.add(slice)
            if (to >= line.size) break
            from = to - 1
            if (still()) {
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    return collapseLimits(found)
                }
            }
        }
        open = missed.isNotEmpty()
        if (open) publish()
        var pause = 8000L
        var left = missed.toList()
        while (left.isNotEmpty() && still()) {
            try {
                Thread.sleep(pause)
            } catch (_: InterruptedException) {
                return collapseLimits(found)
            }
            if (!still()) return collapseLimits(found)
            val stillMissed = ArrayList<List<LatLon>>()
            for (slice in left) {
                if (!still()) return collapseLimits(found)
                if (!take(slice)) stillMissed.add(slice)
            }
            left = stillMissed
            open = left.isNotEmpty()
            publish()
            pause = (pause * 2).coerceAtMost(30000L)
        }
        return collapseLimits(found)
    }

    private fun collapseLimits(found: List<Speed.Mark>): List<Speed.Mark> {
        val sorted = found.sortedBy { it.atM }
        val kept = ArrayList<Speed.Mark>()
        for (mark in sorted) {
            val last = kept.lastOrNull()
            if (last != null && mark.atM - last.atM < 40.0) {
                if (mark.lateral < last.lateral) kept[kept.lastIndex] = mark
                continue
            }
            if (last == null || mark.limit != last.limit || mark.atM - last.atM >= 350.0) kept.add(mark)
        }
        return kept
    }

    /**
     * Cartelli già nei dati di Valhalla, lungo la linea del percorso.
     * Se il servizio non risponde, la lista è vuota e resta la richiesta a Overpass.
     */
    private fun valhallaLimits(frame: Geo.RouteFrame, pts: List<LatLon>): List<Speed.Mark> {
        var step = 200.0
        var line = Geo.thin(pts, step)
        while (line.size > 180 && step < 650.0) {
            step += 150.0
            line = Geo.thin(pts, step)
        }
        if (line.size < 2) return emptyList()
        val shape = org.json.JSONArray()
        for (p in line) shape.put(JSONObject().put("lat", p.lat).put("lon", p.lon))
        val attrs = org.json.JSONArray()
            .put("edge.speed_limit")
            .put("edge.begin_shape_index")
            .put("shape")
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", "motorcycle")
            .put("shape_match", "map_snap")
            .put("filters", JSONObject().put("action", "include").put("attributes", attrs))
        val json = try {
            JSONObject(postJson("https://valhalla1.openstreetmap.de/trace_attributes", body.toString(), 5000, 10000))
        } catch (_: Exception) {
            return emptyList()
        }
        val geom = Geo.decodePolyline(json.optString("shape"), 1_000_000.0)
        val edges = json.optJSONArray("edges") ?: return emptyList()
        if (geom.isEmpty()) return emptyList()
        val found = ArrayList<Speed.Mark>()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (!edge.has("begin_shape_index")) continue
            val raw = edge.opt("speed_limit")
            val limit = Speed.posted(if (raw is Number) raw.toInt() else edge.optInt("speed_limit", 0)) ?: continue
            val idx = edge.optInt("begin_shape_index")
            if (idx !in geom.indices) continue
            val p = geom[idx]
            val pr = frame.project(p.lat, p.lon) ?: continue
            if (pr.second > 40.0) continue
            found.add(Speed.Mark(pr.first, limit, pr.second))
        }
        return found
    }

    private fun limitMarks(frame: Geo.RouteFrame, slice: List<LatLon>): List<Speed.Mark> {
        val corridor = slice.joinToString(",") { p ->
            "%.5f".format(Locale.US, p.lat) + "," + "%.5f".format(Locale.US, p.lon)
        }
        val elements = overpass(
            "[out:json][timeout:25];way(around:45,$corridor)[\"maxspeed\"];out geom;"
        )
        val found = ArrayList<Speed.Mark>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            if (!Speed.drives(tags.optString("highway"))) continue
            val limit = Speed.parse(tags.optString("maxspeed")) ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            for (g in 0 until geom.length()) {
                val p = geom.optJSONObject(g) ?: continue
                val pr = frame.project(p.optDouble("lat"), p.optDouble("lon")) ?: continue
                if (pr.second > 35.0) continue
                found.add(Speed.Mark(pr.first, limit, pr.second))
            }
        }
        return found
    }

    private fun tagName(tags: JSONObject, fallback: String): String {
        for (k in listOf("name", "brand", "operator")) {
            val v = tags.optString(k)
            if (v.isNotBlank()) return v
        }
        return fallback
    }

    private fun alertLabel(tags: JSONObject): String? {
        val limit = tags.optString("maxspeed").trim()
        val base = when {
            tags.optString("highway") == "speed_camera" -> "Autovelox"
            tags.optString("enforcement") == "average_speed" -> "Tutor"
            tags.optString("enforcement") == "maxspeed" -> "Autovelox"
            tags.has("hazard") -> hazardLabel(tags.optString("hazard"))
            else -> return null
        }
        return if (limit.isNotBlank() && (base == "Autovelox" || base == "Tutor")) "$base $limit" else base
    }

    private fun hazardLabel(h: String): String = when (h) {
        "curve", "curves" -> "Curva pericolosa"
        "animal", "animals" -> "Animali sulla strada"
        "slippery" -> "Fondo sdrucciolevole"
        "school_zone" -> "Zona scolastica"
        "pedestrians", "children" -> "Pedoni"
        "cyclists" -> "Ciclisti"
        "falling_rocks" -> "Caduta massi"
        "crosswind" -> "Vento laterale"
        "queues" -> "Code frequenti"
        else -> "Pericolo"
    }

    private fun elementPoint(el: JSONObject): LatLon? {
        if (el.has("lat") && el.has("lon")) return LatLon(el.optDouble("lat"), el.optDouble("lon"))
        val center = el.optJSONObject("center") ?: return null
        if (!center.has("lat") || !center.has("lon")) return null
        return LatLon(center.optDouble("lat"), center.optDouble("lon"))
    }

    private val overpassServers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val overpassGate = Any()

    private fun overpass(query: String): org.json.JSONArray {
        synchronized(overpassGate) {
            var error: Exception? = null
            val bodyForm = "data=" + URLEncoder.encode(query, "UTF-8")
            for (server in overpassServers) {
                try {
                    val json = JSONObject(post(server, bodyForm))
                    // Un timeout lascia «elements» a metà. Non è la strada intera:
                    // si prova l'altro server, non si tiene il pezzo.
                    if (json.has("remark")) {
                        error = IllegalStateException(json.optString("remark").ifBlank { "Overpass" })
                        continue
                    }
                    return json.optJSONArray("elements") ?: org.json.JSONArray()
                } catch (e: Exception) {
                    error = e
                    if (e.message?.contains("429") == true) {
                        try {
                            Thread.sleep(1500)
                        } catch (_: InterruptedException) {
                            throw e
                        }
                    }
                }
            }
            throw error ?: IllegalStateException("Overpass")
        }
    }

    private fun get(url: String, connectMs: Int = 12000, readMs: Int = 20000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Rete $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, json: String, connectMs: Int = 15000, readMs: Int = 25000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Rete $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, form: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(form.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Rete $code")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
