package it.diy.hudnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Percorso pianificato, così un ricreo dell'activity non lo perde. */
object TripStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trip", Context.MODE_PRIVATE)

    data class Trip(
        val stops: List<PlaceHit>,
        val choice: Int,
        val avoid: Boolean,
        val guiding: Boolean
    )

    fun load(ctx: Context): Trip {
        val p = prefs(ctx)
        val stops = try {
            val arr = JSONArray(p.getString("stops", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaceHit(o.getDouble("lat"), o.getDouble("lon"), o.getString("name"), o.optString("kind"))
            }
        } catch (_: Exception) {
            emptyList()
        }
        return Trip(
            stops,
            p.getInt("choice", 0),
            p.getBoolean("avoid", false),
            p.getBoolean("guiding", false)
        )
    }

    fun savePlan(ctx: Context, stops: List<PlaceHit>, choice: Int, avoid: Boolean) {
        val arr = JSONArray()
        for (s in stops) {
            arr.put(JSONObject().put("lat", s.lat).put("lon", s.lon).put("name", s.name).put("kind", s.kind))
        }
        prefs(ctx).edit()
            .putString("stops", arr.toString())
            .putInt("choice", choice)
            .putBoolean("avoid", avoid)
            .apply()
    }

    fun setGuiding(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("guiding", on).apply()
    }
}
