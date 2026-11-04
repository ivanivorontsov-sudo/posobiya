package org.ivanivorontsov.posobiya

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Measure(
    val id: String, val title: String, val category: String, val level: String,
    val who: String, val amount: String, val howTo: String, val law: String, val lawUrl: String,
    val start: LocalDate?, val end: LocalDate?, val endNote: String?, val source: String,
    val checked: String, val verified: Boolean, val tags: List<String>
) {
    val levelTitle get() = when (level) { "federal" -> "Россия"; "region" -> "Приморский край"; "city" -> "Владивосток"; else -> level }
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        val hay = (title + " " + category + " " + who + " " + amount + " " + law + " " + tags.joinToString(" ")).lowercase()
        return q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { hay.contains(it) }
    }
}

enum class Status { ACTIVE, ENDING, EXPIRED, NOT_STARTED }

object Expiry {
    const val ENDING_DAYS = 90L
    fun daysLeft(m: Measure, today: LocalDate): Long? = m.end?.let { ChronoUnit.DAYS.between(today, it) }
    fun status(m: Measure, today: LocalDate, window: Long = ENDING_DAYS): Status {
        if (m.start != null && today.isBefore(m.start)) return Status.NOT_STARTED
        val d = daysLeft(m, today) ?: return Status.ACTIVE
        return when { d < 0 -> Status.EXPIRED; d <= window -> Status.ENDING; else -> Status.ACTIVE }
    }
    /** Меры, которые заканчиваются в ближайшие [window] дней, по возрастанию даты. */
    fun ending(all: List<Measure>, today: LocalDate, window: Long = ENDING_DAYS) =
        all.filter { status(it, today, window) == Status.ENDING }.sortedBy { it.end }
    /** Все меры с датой окончания (для раздела «Заканчивается»): сначала ближайшие, затем истёкшие. */
    fun withDeadline(all: List<Measure>, today: LocalDate) =
        all.filter { it.end != null }.sortedWith(compareBy({ if (it.end!!.isBefore(today)) 1 else 0 }, { it.end }))
    fun label(m: Measure, today: LocalDate): String? {
        val d = daysLeft(m, today) ?: return null
        return when {
            d < 0 -> "Срок истёк ${fmt(m.end!!)}"
            d == 0L -> "Заканчивается сегодня"
            d <= ENDING_DAYS -> "Осталось $d дн. (до ${fmt(m.end!!)})"
            else -> "Действует до ${fmt(m.end!!)}"
        }
    }
    fun fmt(d: LocalDate) = "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)
}

object Db {
    fun parse(json: String): List<Measure> {
        val arr: JSONArray = JSONObject(json).getJSONArray("measures")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun s(k: String) = o.optString(k, "")
            fun d(k: String) = if (o.isNull(k) || s(k).isBlank()) null else LocalDate.parse(s(k))
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
            Measure(s("id"), s("title"), s("category"), s("level"), s("who"), s("amount"), s("howTo"), s("law"), s("lawUrl"),
                d("start"), d("end"), if (o.isNull("endNote")) null else s("endNote"), s("source"), s("checked"), o.optBoolean("verified", false), tags)
        }
    }
    fun generated(json: String): String = JSONObject(json).optString("generated")
}
