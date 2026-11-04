package org.ivanivorontsov.posobiya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_MODEL = "qwen3.8-omni-flash"
const val DEFAULT_MODEL_TITLE = "Qwen3.8-Omni-Flash"

const val SYSTEM_PROMPT = "Ты — эксперт по социальной поддержке граждан Российской Федерации: пособия, выплаты, льготы, компенсации, налоговые вычеты, льготная ипотека, " +
    "меры для семей с детьми, многодетных, пенсионеров, инвалидов, студентов, безработных, участников СВО и их семей. Пользователь живёт во Владивостоке (Приморский край), " +
    "поэтому всегда отдельно указывай федеральные меры, меры Приморского края и муниципальные меры Владивостока. ОБЯЗАТЕЛЬНО перед ответом ищи в интернете актуальные " +
    "законы и постановления (publication.pravo.gov.ru, consultant.ru, garant.ru, sfr.gov.ru, gosuslugi.ru, primorsky.ru, vlc.ru) — суммы и условия могли измениться. " +
    "Для каждой меры пиши: кому положено, размер (с датой, на которую он действует), как оформить, правовое основание и ссылку на источник. " +
    "Обязательно сообщай дату окончания меры или приёма заявлений, если она есть, и предупреждай, если мера скоро заканчивается или уже закончилась. " +
    "Никогда не выдумывай суммы: если не нашёл подтверждения, прямо скажи, что сумму нужно уточнить в СФР, соцзащите (КГКУ «ЦСПН Приморского края») или МФЦ. Отвечай по-русски, понятно и структурированно."

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var mode: String get() = p.getString("mode", "web")!!; set(v) = p.edit().putString("mode", v).apply()
    var apiBase: String get() = p.getString("apiBase", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")!!; set(v) = p.edit().putString("apiBase", v.trimEnd('/')).apply()
    var apiKey: String get() = p.getString("apiKey", "")!!; set(v) = p.edit().putString("apiKey", v.trim()).apply()
    var model: String get() = p.getString("model", DEFAULT_MODEL)!!; set(v) = p.edit().putString("model", v.trim()).apply()
    var thinking: Boolean get() = p.getBoolean("thinking", true); set(v) = p.edit().putBoolean("thinking", v).apply()
    var search: Boolean get() = p.getBoolean("search", true); set(v) = p.edit().putBoolean("search", v).apply()
    var site: String get() = p.getString("site", "qwen")!!; set(v) = p.edit().putString("site", v).apply()
    var notify: Boolean get() = p.getBoolean("notify", true); set(v) = p.edit().putBoolean("notify", v).apply()
}

data class Msg(val role: String, val text: String, val thinking: String = "")

object Ai {
    fun body(s: Settings, history: List<Msg>, withThinkingFlag: Boolean = true): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val b = JSONObject().put("model", s.model).put("messages", msgs).put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", false))
        if (s.model.contains("omni")) b.put("modalities", JSONArray().put("text"))
        if (s.search) {
            b.put("enable_search", true)
            val opts = JSONObject().put("enable_source", true)
            if (s.model.contains("omni")) opts.put("search_strategy", "agent")
            b.put("search_options", opts)
        }
        if (withThinkingFlag) b.put("enable_thinking", s.thinking)
        if (!s.thinking) b.put("reasoning_effort", "none")
        return b
    }

    /** Потоковый запрос к OpenAI-совместимому API DashScope (Qwen). onDelta получает (думание, ответ). */
    fun ask(s: Settings, history: List<Msg>, onDelta: (String, String) -> Unit) {
        if (s.apiKey.isBlank()) throw RuntimeException("Нет API-ключа DashScope. Укажите его в настройках или используйте режим «Веб-чат».")
        try { stream(s, body(s, history, true), onDelta) }
        catch (e: ParamError) { stream(s, body(s, history, false), onDelta) }
    }

    private class ParamError(m: String) : RuntimeException(m)

    private fun stream(s: Settings, body: JSONObject, onDelta: (String, String) -> Unit) {
        val c = URL("${s.apiBase}/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 30000; c.readTimeout = 300000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer ${s.apiKey}")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText()?.take(400) ?: ""
            if (code == 400 && err.contains("enable_thinking")) throw ParamError(err)
            throw RuntimeException("HTTP $code $err")
        }
        val think = StringBuilder(); val ans = StringBuilder()
        c.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val d = try { JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") } catch (e: Exception) { null } ?: continue
                val r = d.optString("reasoning_content", ""); val t = d.optString("content", "")
                if (r.isNotEmpty() && r != "null") think.append(r)
                if (t.isNotEmpty() && t != "null") ans.append(t)
                onDelta(think.toString(), ans.toString())
            }
        }
        if (ans.isBlank()) throw RuntimeException("Пустой ответ модели")
    }
}
