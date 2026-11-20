package it.diy.hudnav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Codice manovra sul filo: 8 è la rotonda, 0x11..0x18 dicono l'uscita. */
fun guideCode(turn: Int, exit: Int): Int =
    if (turn == 8 && exit in 1..8) 0x10 + exit else turn.coerceIn(0, 9)

/**
 * Pacchetti 0x08: laterali intorno al pilota.
 * Il primo ha il bit di sostituzione, così la scheda butta le vecchie.
 * Una lista vuota è un solo pacchetto che le cancella.
 */
fun sidePackets(lines: List<List<LatLon>>, maxBytes: Int = 180): List<ByteArray> {
    val cap = maxBytes.coerceIn(32, 240)
    val usable = lines.map { it.take(8) }.filter { it.size >= 2 }
    if (usable.isEmpty()) return listOf(byteArrayOf(0x08, 1, 0))
    val out = ArrayList<ByteArray>()
    var index = 0
    var replace = true
    while (index < usable.size) {
        val body = ArrayList<Byte>()
        var nSeg = 0
        while (index < usable.size) {
            val line = usable[index]
            val need = 1 + line.size * 8
            if (body.size + need > cap - 3) break
            body.add(line.size.toByte())
            for (p in line) {
                putE7(body, p.lat)
                putE7(body, p.lon)
            }
            nSeg++
            index++
        }
        if (nSeg == 0) {
            index++
            continue
        }
        val pkt = ByteArray(3 + body.size)
        pkt[0] = 0x08
        pkt[1] = if (replace) 1 else 0
        pkt[2] = nSeg.toByte()
        for (i in body.indices) pkt[3 + i] = body[i]
        out.add(pkt)
        replace = false
    }
    return out
}

private fun putE7(into: ArrayList<Byte>, deg: Double) {
    val v = deg * 1e7
    val e = (if (deg >= 0) v + 0.5 else v - 0.5).toInt()
    into.add((e and 0xFF).toByte())
    into.add(((e shr 8) and 0xFF).toByte())
    into.add(((e shr 16) and 0xFF).toByte())
    into.add(((e shr 24) and 0xFF).toByte())
}

data class Step(
    val atM: Double,
    val durS: Double,
    val text: String,
    val turn: Int = 0,
    val road: String = "",
    val exit: Int = 0
)

/** Incrocio lungo il percorso, con i bracci che non stai percorrendo. */
data class RoadFork(val atM: Double, val arms: List<List<LatLon>>)

/** Autovelox o pericolo fisso, da OpenStreetMap. */
data class RoadAlert(val lat: Double, val lon: Double, val label: String)

object Geo {
    private const val R = 6371000.0

    fun hav(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = Math.toRadians(b.lat - a.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Punto a `meters` metri, bearing in gradi orari da nord (come le svolte OSRM). */
    fun destination(from: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val br = Math.toRadians(bearingDeg)
        val d = meters / R
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Bracci di un incrocio che non sono la strada da cui arrivi né quella che prendi.
     * Il tratto parte staccato dal nodo, così non si somma alla traccia spessa.
     * Meno di tre direzioni non è una diramazione.
     */
    fun forkArms(
        at: LatLon,
        bearings: List<Double>,
        taken: Set<Int>,
        reachM: Double = 46.0,
        gapM: Double = 14.0
    ): List<List<LatLon>> {
        if (bearings.size < 3) return emptyList()
        val blocked = ArrayList<Double>(taken.size)
        for (i in taken) if (i in bearings.indices) blocked.add(bearings[i])
        val out = ArrayList<List<LatLon>>()
        for (i in bearings.indices) {
            if (i in taken) continue
            if (blocked.any { angleDelta(bearings[i], it) < 12.0 }) continue
            out.add(listOf(destination(at, bearings[i], gapM), destination(at, bearings[i], reachM)))
        }
        return out
    }

    private fun angleDelta(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Tiene un incrocio solo, se due nodi del percorso sono lo stesso bivio. */
    fun placeForks(raw: List<Pair<LatLon, List<List<LatLon>>>>, frame: RouteFrame): List<RoadFork> {
        val placed = ArrayList<RoadFork>()
        for ((at, arms) in raw) {
            if (arms.isEmpty()) continue
            val pr = frame.project(at.lat, at.lon) ?: continue
            if (pr.second > 35.0) continue
            placed.add(RoadFork(pr.first, arms))
        }
        placed.sortBy { it.atM }
        val kept = ArrayList<RoadFork>()
        for (fork in placed) {
            val prev = kept.lastOrNull()
            if (prev != null && fork.atM - prev.atM < 18.0) {
                if (fork.arms.size > prev.arms.size) kept[kept.lastIndex] = fork
            } else {
                kept.add(fork)
            }
        }
        return kept
    }

    /**
     * Tiene le diramazioni già in mappa finché l'incrocio non è passato,
     * e ne aggiunge di nuove solo se c'è posto. Così un GPS che oscilla
     * non le fa sparire e ricomparire.
     */
    fun latchForks(
        all: List<RoadFork>,
        shown: List<RoadFork>,
        s: Double,
        acquireBack: Double = 40.0,
        acquireAhead: Double = 220.0,
        holdBack: Double = 160.0,
        holdAhead: Double = 280.0,
        maxArms: Int = 8
    ): List<RoadFork> {
        val out = ArrayList<RoadFork>()
        var n = 0
        for (fork in shown) {
            if (fork.atM < s - holdBack || fork.atM > s + holdAhead) continue
            out.add(fork)
            n += fork.arms.size
        }
        val have = out.map { it.atM }.toHashSet()
        val newcomers = all.filter {
            it.atM !in have && it.atM >= s - acquireBack && it.atM <= s + acquireAhead
        }.sortedBy { kotlin.math.abs(it.atM - (s + 40.0)) }
        for (fork in newcomers) {
            if (n + fork.arms.size > maxArms) continue
            out.add(fork)
            n += fork.arms.size
        }
        out.sortBy { it.atM }
        return out
    }

    /** Bracci degli incroci visibili nella mini-mappa. */
    fun forksInView(
        forks: List<RoadFork>,
        s: Double,
        back: Double = 40.0,
        ahead: Double = 130.0,
        maxArms: Int = 8
    ): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        for (fork in forks) {
            if (fork.atM < s - back || fork.atM > s + ahead) continue
            for (arm in fork.arms) {
                if (out.size == maxArms) return out
                out.add(arm)
            }
        }
        return out
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cumulate(pts: List<LatLon>): DoubleArray {
        val c = DoubleArray(pts.size)
        for (i in 1 until pts.size) c[i] = c[i - 1] + hav(pts[i - 1], pts[i])
        return c
    }

    fun length(cum: DoubleArray): Double = if (cum.isEmpty()) 0.0 else cum.last()

    fun pointAt(pts: List<LatLon>, cum: DoubleArray, dist: Double): LatLon {
        if (pts.isEmpty()) return LatLon(0.0, 0.0)
        if (dist <= 0) return pts.first()
        if (dist >= length(cum)) return pts.last()
        var lo = 0
        var hi = cum.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= dist) lo = mid else hi = mid
        }
        val span = cum[hi] - cum[lo]
        val t = if (span < 0.01) 0.0 else (dist - cum[lo]) / span
        return LatLon(
            pts[lo].lat + (pts[hi].lat - pts[lo].lat) * t,
            pts[lo].lon + (pts[hi].lon - pts[lo].lon) * t
        )
    }

    /**
     * Distanza laterale in metri e progressiva lungo il percorso.
     * Con `nearS` resta sul tratto che stai già percorrendo: su un cavalcavia
     * non salta alla strada che passa sotto, anche se in pianta è più vicina.
     * Con la prua, un tratto perpendicolare perde contro quello in cui vai.
     */
    fun project(
        pts: List<LatLon>,
        cum: DoubleArray,
        lat: Double,
        lon: Double,
        nearS: Double = Double.NaN,
        courseDeg: Double = Double.NaN
    ): Pair<Double, Double>? = RouteFrame(pts, cum).project(lat, lon, nearS, courseDeg)

    fun thin(pts: List<LatLon>, stepM: Double): List<LatLon> {
        if (pts.size < 2) return pts
        val out = ArrayList<LatLon>()
        out.add(pts[0])
        for (i in 1 until pts.size) {
            if (hav(out.last(), pts[i]) >= stepM) out.add(pts[i])
        }
        if (hav(out.last(), pts.last()) > 1) out.add(pts.last())
        return out
    }

    /** Finestra di 11 km, come la pagina: la scheda tiene al massimo 12 km. */
    fun window(pts: List<LatLon>, cum: DoubleArray, fromS: Double): Pair<List<LatLon>, Double> {
        val endS = min(length(cum), fromS + 11000.0)
        val raw = ArrayList<LatLon>()
        raw.add(pointAt(pts, cum, fromS.coerceAtLeast(0.0)))
        for (i in pts.indices) {
            if (cum[i] > fromS + 2 && cum[i] < endS - 2) raw.add(pts[i])
        }
        raw.add(pointAt(pts, cum, endS))
        var thinned = thin(raw, 8.0)
        if (thinned.size > 1000) thinned = thin(raw, 14.0)
        if (thinned.size > 1000) thinned = thin(raw, 22.0)
        return thinned to endS
    }

    /** Polilinea di Valhalla, precisione 1e6. Una stringa rotta restituisce i punti già letti. */
    fun decodePolyline(encoded: String, precision: Double): List<LatLon> {
        if (encoded.isEmpty() || precision <= 0.0) return emptyList()
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        fun pull(): Int {
            var shift = 0
            var result = 0
            var b: Int
            do {
                if (index >= encoded.length) throw IllegalArgumentException()
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        return try {
            while (index < encoded.length) {
                lat += pull()
                lon += pull()
                out.add(LatLon(lat / precision, lon / precision))
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    /** Metri locali del percorso, calcolati una volta. La proiezione non rialloca la geometria. */
    class RouteFrame(val pts: List<LatLon>, val cum: DoubleArray) {
        private val x: DoubleArray
        private val y: DoubleArray
        private val lat0: Double
        private val lon0: Double
        private val cos0: Double

        init {
            lat0 = if (pts.isEmpty()) 0.0 else pts[0].lat
            lon0 = if (pts.isEmpty()) 0.0 else pts[0].lon
            cos0 = cos(Math.toRadians(lat0))
            x = DoubleArray(pts.size)
            y = DoubleArray(pts.size)
            for (i in pts.indices) {
                x[i] = Math.toRadians(pts[i].lon - lon0) * R * cos0
                y[i] = Math.toRadians(pts[i].lat - lat0) * R
            }
        }

        fun project(
            lat: Double,
            lon: Double,
            nearS: Double = Double.NaN,
            courseDeg: Double = Double.NaN
        ): Pair<Double, Double>? {
            if (pts.size < 2) return null
            val px = Math.toRadians(lon - lon0) * R * cos0
            val py = Math.toRadians(lat - lat0) * R
            if (!nearS.isNaN()) {
                val local = nearest(px, py, nearS, courseDeg, window = true)
                if (local != null && local.second <= 40.0) return local
            }
            return nearest(px, py, nearS, courseDeg, window = false)
        }

        private fun nearest(
            px: Double,
            py: Double,
            nearS: Double,
            courseDeg: Double,
            window: Boolean
        ): Pair<Double, Double>? {
            var bestScore = 1e18
            var bestS = 0.0
            var bestM = 1e18
            var any = false
            for (i in 0 until pts.size - 1) {
                if (window) {
                    if (cum[i + 1] < nearS - 80.0) continue
                    if (cum[i] > nearS + 250.0) continue
                }
                val vx = x[i + 1] - x[i]
                val vy = y[i + 1] - y[i]
                val l2 = vx * vx + vy * vy
                var t = if (l2 > 0) ((px - x[i]) * vx + (py - y[i]) * vy) / l2 else 0.0
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                val dx = x[i] + vx * t - px
                val dy = y[i] + vy * t - py
                val meters = sqrt(dx * dx + dy * dy)
                var score = meters
                if (!courseDeg.isNaN() && meters < 70.0 && l2 > 64.0) {
                    val seg = bearing(pts[i], pts[i + 1])
                    if (angleDelta(seg, courseDeg) > 55.0) score += 28.0
                }
                if (score < bestScore) {
                    bestScore = score
                    bestS = cum[i] + t * (cum[i + 1] - cum[i])
                    bestM = meters
                    any = true
                }
            }
            return if (any) bestS to bestM else null
        }
    }
}
