package org.ivanivorontsov.posobiya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LawDoc(val eo: String, val name: String, val date: String, val published: String, val regional: Boolean) {
    val url get() = "http://publication.pravo.gov.ru/document/$eo"
    fun toJson() = JSONObject().put("eo", eo).put("name", name).put("date", date).put("published", published).put("regional", regional)
    companion object { fun from(o: JSONObject) = LawDoc(o.getString("eo"), o.getString("name"), o.optString("date"), o.optString("published"), o.optBoolean("regional")) }
}

/** Получение новых нормативных актов с официального портала publication.pravo.gov.ru. */
object Laws {
    private val KEYS = listOf("пособи", "выплат", "социальн", "материнск", "семейн", "ипотек", "льгот", "компенсац", "поддержк",
        "многодетн", "специальной военной", "инвалид", "пенси", "прожиточн", "малоимущ", "субсиди", "детей", "ветеран", "студент", "безработ", "гектар")
    private const val PRIM = "приморск"

    fun isRelevant(name: String): Boolean { val n = name.lowercase(); return KEYS.any { n.contains(it) } }
    fun isRegional(name: String) = name.lowercase().contains(PRIM)
    fun isFederal(name: String): Boolean { val n = name.lowercase(); return n.startsWith("федеральный закон") || n.startsWith("указ президента") || n.contains("правительства российской федерации") }

    fun parseList(json: String): List<LawDoc> {
        val items = JSONObject(json).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val name = o.optString("complexName").ifBlank { o.optString("name") }
            val eo = o.optString("eoNumber")
            if (eo.isBlank() || !isRelevant(name) || !(isRegional(name) || isFederal(name))) null
            else LawDoc(eo, name.trim(), o.optString("documentDate").take(10), o.optString("publishDateShort").take(10), isRegional(name))
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Posobiya/1.0 Android"); c.setRequestProperty("Accept", "application/json")
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Загружает свежие документы (последние 3 страницы по 200) и возвращает только новые, которых раньше не видели. */
    fun refresh(ctx: Context): Pair<List<LawDoc>, List<LawDoc>> {
        val found = LinkedHashMap<String, LawDoc>()
        for (page in 1..3) {
            val js = get("http://publication.pravo.gov.ru/api/Documents?PageSize=200&Index=$page")
            parseList(js).forEach { found[it.eo] = it }
        }
        val prefs = ctx.getSharedPreferences("laws", Context.MODE_PRIVATE)
        val old = load(ctx)
        val known = old.map { it.eo }.toSet()
        val fresh = found.values.filter { it.eo !in known }
        val all = (fresh + old).distinctBy { it.eo }.sortedByDescending { it.published }.take(300)
        prefs.edit().putString("docs", JSONArray(all.map { it.toJson() }).toString()).putLong("updated", System.currentTimeMillis()).apply()
        return fresh to all
    }

    fun load(ctx: Context): List<LawDoc> {
        val s = ctx.getSharedPreferences("laws", Context.MODE_PRIVATE).getString("docs", null) ?: return emptyList()
        val a = JSONArray(s); return (0 until a.length()).map { LawDoc.from(a.getJSONObject(it)) }
    }
    fun updated(ctx: Context) = ctx.getSharedPreferences("laws", Context.MODE_PRIVATE).getLong("updated", 0)
}
