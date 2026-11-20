package it.diy.hudnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Destinazioni recenti e preferite, sul telefono. */
object Memory {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun recents(ctx: Context): List<PlaceHit> = load(ctx, "recent")
    fun favorites(ctx: Context): List<PlaceHit> = load(ctx, "fav")

    fun remember(ctx: Context, hit: PlaceHit) {
        val all = recents(ctx).filterNot { same(it, hit) }.toMutableList()
        all.add(0, hit)
        save(ctx, "recent", all.take(8))
    }

    fun isFavorite(ctx: Context, hit: PlaceHit): Boolean = favorites(ctx).any { same(it, hit) }

    fun toggleFavorite(ctx: Context, hit: PlaceHit): Boolean {
        val was = isFavorite(ctx, hit)
        val all = favorites(ctx).filterNot { same(it, hit) }.toMutableList()
        if (!was) all.add(0, hit)
        save(ctx, "fav", all.take(12))
        return !was
    }

    private fun same(a: PlaceHit, b: PlaceHit): Boolean =
        abs(a.lat - b.lat) < 0.0004 && abs(a.lon - b.lon) < 0.0004

    private fun load(ctx: Context, key: String): List<PlaceHit> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaceHit(o.getDouble("lat"), o.getDouble("lon"), o.getString("name"), o.optString("kind"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, key: String, items: List<PlaceHit>) {
        val arr = JSONArray()
        for (p in items) {
            arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("name", p.name).put("kind", p.kind))
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }
}
