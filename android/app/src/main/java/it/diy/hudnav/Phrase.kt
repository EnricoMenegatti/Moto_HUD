package it.diy.hudnav

/** Frasi di manovra e scelta del limite, senza Android, così si possono provare da soli. */
object Phrase {
    fun maneuver(type: String, modifier: String, name: String, exit: Int, lastLeg: Boolean): String {
        val where = via(name)
        val side = side(modifier)
        return when (type) {
            "depart" -> join("Parti", side, where)
            "arrive" -> if (lastLeg) join("Arrivo", where) else join("Tappa", where)
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> roundabout(exit, where)
            "fork" -> join("Al bivio tieni", forkSide(modifier), where)
            "end of road" -> join("A fine strada", side, where)
            "merge" -> join("Immettiti", side, where)
            "continue", "new name" -> join("Continua", side, where)
            "turn" -> join("Svolta", side, where)
            else -> join("Prosegui", side, where)
        }
    }

    fun thenNext(text: String): String {
        if (text.isBlank()) return ""
        return "Poi ${text.replaceFirstChar { it.lowercase() }}"
    }

    fun via(name: String): String {
        val n = name.trim()
        if (n.isBlank() || n == "-") return ""
        val lower = n.lowercase()
        val street = listOf(
            "via ", "viale ", "piazza ", "corso ", "largo ", "vicolo ",
            "piazzale ", "contrada ", "località ", "strada ", "borgo "
        )
        return if (street.any { lower.startsWith(it) }) "in $n" else "su $n"
    }

    private fun roundabout(exit: Int, where: String): String {
        val core = when (exit) {
            1 -> "Alla rotonda prendi la prima uscita"
            2 -> "Alla rotonda prendi la seconda uscita"
            3 -> "Alla rotonda prendi la terza uscita"
            4 -> "Alla rotonda prendi la quarta uscita"
            5 -> "Alla rotonda prendi la quinta uscita"
            else -> if (exit > 0) "Alla rotonda prendi l'uscita $exit" else "Alla rotonda"
        }
        return if (where.isBlank()) core else "$core, $where"
    }

    private fun forkSide(modifier: String): String = when {
        modifier.contains("left") -> "la sinistra"
        modifier.contains("right") -> "la destra"
        else -> "il dritto"
    }

    private fun side(modifier: String): String = when (modifier) {
        "left" -> "a sinistra"
        "right" -> "a destra"
        "slight left" -> "leggermente a sinistra"
        "slight right" -> "leggermente a destra"
        "sharp left" -> "tutta a sinistra"
        "sharp right" -> "tutta a destra"
        "uturn" -> "a inversione"
        "straight" -> "dritto"
        else -> ""
    }

    private fun join(vararg parts: String): String {
        val clean = parts.filter { it.isNotBlank() }
        if (clean.size >= 2) {
            val last = clean.last()
            if (last.startsWith("in ") || last.startsWith("su ")) {
                return clean.dropLast(1).joinToString(" ") + ", " + last
            }
        }
        return clean.joinToString(" ")
    }
}

object Speed {
    data class Mark(val atM: Double, val limit: Int, val lateral: Double = 0.0)

    data class Candidate(val highway: String, val limit: Int, val meters: Double)

    private val skip = setOf(
        "footway", "pedestrian", "cycleway", "path", "steps", "corridor", "bridleway", "service"
    )

    fun drives(highway: String): Boolean = highway !in skip

    /**
     * Limite del tratto su cui ti trovi.
     * Vale dal cartello precedente e resta ancora un po' dopo l'ultimo, così in fondo non sparisce.
     * Con un pezzo di corridoio ancora senza risposta si tiene l'ultimo numero, invece di «n.d.».
     */
    fun along(marks: List<Mark>, s: Double, holdGap: Boolean = false): Int? {
        if (marks.isEmpty()) return null
        val first = marks.first()
        if (s < first.atM) return if (first.atM - s <= 400.0) first.limit else null
        var prev = first
        for (i in 1 until marks.size) {
            val next = marks[i]
            if (s < next.atM) {
                if (s - prev.atM > 1200.0 && next.atM - s > 400.0) return if (holdGap) prev.limit else null
                if (next.atM - s < s - prev.atM && next.atM - s <= 400.0) return next.limit
                return prev.limit
            }
            prev = next
        }
        return if (holdGap || s - prev.atM <= 1200.0) prev.limit else null
    }

    /** Il limite della strada percorribile più vicina, entro 25 m. Non il minimo del vicinato. */
    fun choose(items: List<Candidate>): Int? {
        var best = 25.0
        var limit: Int? = null
        for (item in items) {
            if (item.highway in skip) continue
            if (item.meters < best) {
                best = item.meters
                limit = item.limit
            }
        }
        return limit
    }

    /** Limite scritto sul cartello, in km/h. 0 e 255 non sono un numero da mostrare. */
    fun posted(raw: Int): Int? = if (raw in 5..140) raw else null

    fun parse(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val code = when {
            s.startsWith("it:urban") || s == "urban" -> 50
            s.startsWith("it:rural") || s == "rural" -> 90
            s.startsWith("it:trunk") -> 110
            s.startsWith("it:motorway") || s == "motorway" -> 130
            else -> null
        }
        if (code != null) return code
        val head = s.split(';', ' ').firstOrNull { it.any { c -> c.isDigit() } } ?: return null
        val n = head.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return if (head.contains("mph") || s.contains("mph")) (n * 1.609).toInt() else n
    }
}
