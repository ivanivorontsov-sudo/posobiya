package org.ivanivorontsov.posobiya

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DbTest {
    private val json = File("src/main/assets/measures.json").readText()
    private val all = Db.parse(json)

    @Test fun loadsAllMeasures() {
        assertTrue(all.size >= 30)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { m ->
            assertTrue(m.id, m.title.isNotBlank() && m.amount.isNotBlank() && m.who.isNotBlank() && m.howTo.isNotBlank())
            assertTrue(m.id, m.source.startsWith("http"))
            assertTrue(m.id, m.level in setOf("federal", "region", "city"))
            if (!m.verified) assertTrue(m.id, m.amount.contains("не проверено", true))
        }
        assertTrue(all.any { it.level == "region" } && all.any { it.level == "city" })
    }

    @Test fun matkapital() {
        val m = all.first { it.title.contains("Материнский", true) && it.level == "federal" }
        assertTrue(m.amount.contains("728 921"))
        assertEquals(LocalDate.of(2030, 12, 31), m.end)
    }

    private fun mk(end: LocalDate?, start: LocalDate? = null) = Measure("x", "t", "c", "federal", "w", "a", "h", "l", "", start, end, null, "", "", true, emptyList())

    @Test fun expiryLogic() {
        val today = LocalDate.of(2026, 11, 4)
        assertEquals(Status.ACTIVE, Expiry.status(mk(null), today))
        assertEquals(Status.ENDING, Expiry.status(mk(LocalDate.of(2026, 12, 31)), today))
        assertEquals(57L, Expiry.daysLeft(mk(LocalDate.of(2026, 12, 31)), today))
        assertEquals(Status.ENDING, Expiry.status(mk(today), today))
        assertEquals(Status.EXPIRED, Expiry.status(mk(today.minusDays(1)), today))
        assertEquals(Status.ACTIVE, Expiry.status(mk(LocalDate.of(2030, 12, 31)), today))
        assertEquals(Status.NOT_STARTED, Expiry.status(mk(null, today.plusDays(5)), today))
        assertEquals("Заканчивается сегодня", Expiry.label(mk(today), today))
    }

    @Test fun endingSectionFromDb() {
        val today = LocalDate.of(2026, 11, 4)
        val soon = Expiry.ending(all, today)
        assertTrue(soon.any { it.title.contains("550") })
        assertEquals(soon.map { it.end }, soon.sortedBy { it.end }.map { it.end })
        assertTrue(Expiry.ending(all, LocalDate.of(2031, 6, 1)).none { it.end!!.isBefore(LocalDate.of(2031, 6, 1)) })
    }

    @Test fun lawFilter() {
        val js = """{"items":[{"eoNumber":"1","complexName":"Закон Приморского края от 01.11.2026 № 1-КЗ \"О социальной поддержке многодетных семей\"","publishDateShort":"2026-11-02T00:00:00"},
            {"eoNumber":"2","complexName":"Закон Курской области о бюджете","publishDateShort":"2026-11-02"},
            {"eoNumber":"3","complexName":"Федеральный закон от 01.11.2026 № 5-ФЗ \"О государственных пособиях гражданам, имеющим детей\"","publishDateShort":"2026-11-02"}]}"""
        val d = Laws.parseList(js)
        assertEquals(listOf("1", "3"), d.map { it.eo }); assertTrue(d[0].regional)
    }

    @Test fun apiBodyHasSearchAndThinking() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val b = Ai.body(Settings(ctx), listOf(Msg("user", "привет")))
        assertEquals("qwen3.8-omni-flash", b.getString("model"))
        assertTrue(b.getBoolean("enable_search") && b.getBoolean("enable_thinking") && b.getBoolean("stream"))
        assertEquals("agent", b.getJSONObject("search_options").getString("search_strategy"))
    }
}
