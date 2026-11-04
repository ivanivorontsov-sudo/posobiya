package org.ivanivorontsov.posobiya

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

val Sea = Color(0xFF0B4F8A); val SeaLight = Color(0xFF1E88C7); val Gold = Color(0xFFF2A900); val Warn = Color(0xFFD9480F)

class MainActivity : ComponentActivity() {
    private var tabState = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.channels(this); Notify.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        tabState.intValue = intent.getIntExtra("tab", 0)
        val json = assets.open("measures.json").bufferedReader().use { it.readText() }
        val measures = Db.parse(json); val gen = Db.generated(json)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Sea, secondary = SeaLight, tertiary = Gold, primaryContainer = Color(0xFFD6E9F8))) {
                App(measures, gen, tabState)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); tabState.intValue = intent.getIntExtra("tab", tabState.intValue) }
}

fun open(ctx: android.content.Context, url: String) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(measures: List<Measure>, gen: String, tab: MutableIntState) {
    var selected by remember { mutableStateOf<Measure?>(null) }
    var chatSeed by remember { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }
    BackHandler(enabled = selected != null) { selected = null }
    val titles = listOf("Меры", "Заканчивается", "Законы", "ИИ-чат", "Настройки")
    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Sea, SeaLight))).statusBarsPadding().padding(16.dp, 12.dp)) {
                Column {
                    Text(if (selected != null) "Мера поддержки" else "Пособия · ${titles[tab.intValue]}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Россия · Приморский край · Владивосток", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Default.List, Icons.Default.Warning, Icons.Default.Info, Icons.Default.Email, Icons.Default.Settings)
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab.intValue == i, onClick = { tab.intValue = i; selected = null }, icon = {
                        if (i == 1) { val n = Expiry.ending(measures, today).size
                            BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) { Icon(icons[i], t) } } else Icon(icons[i], t)
                    }, label = { Text(t, fontSize = 10.sp, maxLines = 1) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val s = selected
            if (s != null) Detail(s, today) { q -> chatSeed = q; selected = null; tab.intValue = 3 }
            else when (tab.intValue) {
                0 -> MeasuresScreen(measures, gen, today) { selected = it }
                1 -> EndingScreen(measures, today) { selected = it }
                2 -> LawsScreen { q -> chatSeed = q; tab.intValue = 3 }
                3 -> ChatScreen(chatSeed) { chatSeed = null }
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun MeasureCard(m: Measure, today: LocalDate, onClick: () -> Unit) {
    val st = Expiry.status(m, today)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(m.levelTitle, if (m.level == "federal") Sea else Color(0xFF2B8A3E))
                Chip(m.category, Color.DarkGray)
                if (!m.verified) Chip("не проверено", Warn)
            }
            Spacer(Modifier.height(6.dp))
            Text(m.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(m.amount, color = Sea, fontSize = 14.sp, maxLines = 2)
            Expiry.label(m, today)?.let {
                Text(it, fontSize = 12.sp, color = when (st) { Status.ENDING -> Warn; Status.EXPIRED -> Color.Gray; else -> Color(0xFF2B8A3E) }, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MeasuresScreen(all: List<Measure>, gen: String, today: LocalDate, onOpen: (Measure) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("all") }
    var cat by rememberSaveable { mutableStateOf("Все") }
    val cats = remember { listOf("Все") + all.map { it.category }.distinct() }
    val list = all.filter { (level == "all" || it.level == level) && (cat == "Все" || it.category == cat) && it.matches(q) }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF2F6FA))) {
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text("Поиск: ребёнок, ипотека, пенсия…") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                listOf("all" to "Все уровни", "federal" to "Россия", "region" to "Приморье", "city" to "Владивосток").forEach { (k, t) ->
                    FilterChip(level == k, { level = k }, { Text(t) }, Modifier.padding(4.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                cats.forEach { c -> FilterChip(cat == c, { cat = c }, { Text(c) }, Modifier.padding(4.dp)) }
            }
            Text("Найдено: ${list.size} из ${all.size} · база от ${gen.split("-").reversed().joinToString(".")}", Modifier.padding(14.dp, 4.dp), fontSize = 12.sp, color = Color.Gray)
        }
        items(list, key = { it.id }) { MeasureCard(it, today) { onOpen(it) } }
        item { Text("Справочная информация. Суммы и условия уточняйте в СФР, КГКУ «ЦСПН ПК», МФЦ или на Госуслугах.", Modifier.padding(16.dp), fontSize = 11.sp, color = Color.Gray) }
    }
}

@Composable
fun EndingScreen(all: List<Measure>, today: LocalDate, onOpen: (Measure) -> Unit) {
    val soon = Expiry.ending(all, today)
    val rest = Expiry.withDeadline(all, today).filter { it !in soon }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF2F6FA))) {
        item { Text("Заканчиваются в ближайшие ${Expiry.ENDING_DAYS} дней", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold, color = Warn) }
        if (soon.isEmpty()) item { Text("Нет мер, заканчивающихся в ближайшие 90 дней.", Modifier.padding(16.dp)) }
        items(soon, key = { "s" + it.id }) { MeasureCard(it, today) { onOpen(it) } }
        item { Text("Другие меры со сроком окончания", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold) }
        items(rest, key = { "r" + it.id }) { MeasureCard(it, today) { onOpen(it) } }
    }
}

@Composable
fun Detail(m: Measure, today: LocalDate, ask: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFFF2F6FA)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip(m.levelTitle, Sea); Chip(m.category, Color.DarkGray) }
        Text(m.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        if (!m.verified) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E6))) {
            Text("⚠ Сумма или условия не подтверждены официальным источником. Уточните в соцзащите или спросите ИИ.", Modifier.padding(12.dp), color = Warn, fontSize = 13.sp)
        }
        Expiry.label(m, today)?.let { Text("⏳ $it" + (m.endNote?.let { n -> "\n$n" } ?: ""), Modifier.padding(vertical = 6.dp), color = if (Expiry.status(m, today) == Status.ENDING) Warn else Color.DarkGray, fontWeight = FontWeight.SemiBold) }
        SelectionContainer { Column {
            Section("Размер", m.amount); Section("Кому положено", m.who); Section("Как оформить", m.howTo)
            Section("Правовое основание", m.law)
            Section("Сроки", "Начало: ${m.start?.let(Expiry::fmt) ?: "—"}\nОкончание: ${m.end?.let(Expiry::fmt) ?: "бессрочно / не установлено"}")
            Section("Проверено", "${m.checked} · ${if (m.verified) "по официальному/надёжному источнику" else "НЕ проверено"}")
        } }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.lawUrl.isNotBlank()) OutlinedButton({ open(ctx, m.lawUrl) }) { Text("Закон") }
            if (m.source.isNotBlank()) OutlinedButton({ open(ctx, m.source) }) { Text("Источник") }
        }
        Button({ ask("Расскажи подробно и актуально на сегодня про меру «${m.title}» (${m.levelTitle}). Проверь по свежим законам размер, условия, как оформить во Владивостоке и дату окончания.") },
            Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Спросить ИИ с поиском по законам") }
    }
}

@Composable
fun Section(t: String, body: String) {
    Text(t, fontWeight = FontWeight.Bold, color = Sea, modifier = Modifier.padding(top = 10.dp)); Text(body, fontSize = 15.sp)
}

@Composable
fun LawsScreen(ask: (String) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(Laws.load(ctx)) }
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
    var onlyPrim by rememberSaveable { mutableStateOf(false) }
    fun refresh() { busy = true; msg = ""; scope.launch {
        try { val (fresh, all) = withContext(Dispatchers.IO) { Laws.refresh(ctx) }; docs = all; msg = "Новых документов: ${fresh.size}" }
        catch (e: Exception) { msg = "Не удалось загрузить publication.pravo.gov.ru: ${e.message}" }
        busy = false } }
    LaunchedEffect(Unit) { if (docs.isEmpty()) refresh() }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF2F6FA))) {
        item {
            Column(Modifier.padding(12.dp)) {
                Text("Новые акты о соцподдержке с официального портала правовой информации (федеральные и Приморского края). Проверка — автоматически 2 раза в сутки.", fontSize = 13.sp, color = Color.DarkGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({ refresh() }, enabled = !busy) { Text(if (busy) "Загрузка…" else "Обновить") }
                    Spacer(Modifier.width(8.dp)); FilterChip(onlyPrim, { onlyPrim = !onlyPrim }, { Text("Только Приморье") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Color.Gray)
            }
        }
        items(docs.filter { !onlyPrim || it.regional }, key = { it.eo }) { d ->
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip(if (d.regional) "Приморье" else "Россия", if (d.regional) Color(0xFF2B8A3E) else Sea); Chip("опубл. ${d.published}", Color.DarkGray) }
                    Text(d.name, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Row { TextButton({ open(ctx, d.url) }) { Text("Открыть текст") }
                        TextButton({ ask("Объясни простыми словами, что меняет этот документ для жителей Владивостока и Приморья, кому и какие выплаты/льготы положены и до какого срока: «${d.name}» (${d.url}). Найди его текст и разъяснения.") }) { Text("Разобрать с ИИ") } }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(seed: String?, consumed: () -> Unit) {
    val ctx = LocalContext.current; val st = remember { Settings(ctx) }
    var mode by remember { mutableStateOf(st.mode) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(mode == "web", { mode = "web"; st.mode = "web" }, { Text("Веб-чат") }); Spacer(Modifier.width(6.dp))
            FilterChip(mode == "api", { mode = "api"; st.mode = "api" }, { Text("API") }); Spacer(Modifier.width(8.dp))
            Text("$DEFAULT_MODEL_TITLE · 🧠${if (st.thinking) "вкл" else "выкл"} · 🌐${if (st.search) "вкл" else "выкл"}", fontSize = 11.sp, color = Color.Gray)
        }
        if (mode == "web") WebChatPane(st, seed, consumed) else ApiChatPane(st, seed, consumed)
    }
}

@Composable
fun ColumnScope.WebChatPane(st: Settings, seed: String?, consumed: () -> Unit) {
    var site by remember { mutableStateOf(chatSites.firstOrNull { it.id == st.site } ?: chatSites[0]) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var input by remember { mutableStateOf("") }; var sent by remember { mutableIntStateOf(0) }
    fun send(t: String) { web?.evaluateJavascript("$SEND_JS(${jsq(webPrompt(t, sent == 0))})", null); sent++ }
    LaunchedEffect(seed, web) { if (seed != null && web != null) { kotlinx.coroutines.delay(5000); send(seed); consumed() } }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        chatSites.forEach { c -> FilterChip(site == c, { site = c; st.site = c.id; sent = 0; web?.loadUrl(c.url) }, { Text(c.title) }, Modifier.padding(2.dp)) }
        AssistChip({ web?.evaluateJavascript(QWEN_SETUP_JS, null) }, { Text("⚙ Omni Flash + мышление + поиск") }, Modifier.padding(2.dp))
    }
    AndroidView({ c -> makeChatWebView(c, site.url) { web = it } }, Modifier.fillMaxWidth().weight(1f))
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Вопрос о пособиях (добавит инструкцию и поиск)") }, maxLines = 4)
        IconButton({ if (input.isNotBlank()) { send(input); input = "" } }) { Icon(Icons.Default.Send, "Отправить", tint = Sea) }
    }
}

@Composable
fun ColumnScope.ApiChatPane(st: Settings, seed: String?, consumed: () -> Unit) {
    val scope = rememberCoroutineScope(); val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val ls = rememberLazyListState()
    fun send(t: String) {
        msgs.add(Msg("user", t)); val hist = msgs.toList(); msgs.add(Msg("assistant", "…")); busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { Ai.ask(st, hist) { th, an -> scope.launch(Dispatchers.Main) { msgs[msgs.lastIndex] = Msg("assistant", an.ifBlank { "…" }, th) } } } }
            catch (e: Exception) { msgs[msgs.lastIndex] = Msg("assistant", "Ошибка: ${e.message}") }
            busy = false
        }
    }
    LaunchedEffect(seed) { if (seed != null) { send(seed); consumed() } }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.lastIndex) }
    LazyColumn(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF2F6FA)), state = ls) {
        if (msgs.isEmpty()) item { Text("Спросите, например: «Какие выплаты положены семье с двумя детьми во Владивостоке?»\nМодель: ${st.model}, режим размышления и веб-поиск включены по умолчанию.", Modifier.padding(16.dp), color = Color.Gray) }
        items(msgs) { m ->
            val me = m.role == "user"
            Column(Modifier.fillMaxWidth().padding(8.dp, 4.dp), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                if (m.thinking.isNotBlank()) { var show by remember { mutableStateOf(false) }
                    Text(if (show) "🧠 " + m.thinking else "🧠 Размышления (нажмите)", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.clickable { show = !show }) }
                SelectionContainer { Text(m.text, Modifier.background(if (me) Color(0xFFD6E9F8) else Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) }
            }
        }
    }
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ваш вопрос") }, maxLines = 4)
        IconButton({ if (input.isNotBlank() && !busy) { send(input); input = "" } }, enabled = !busy) { Icon(Icons.Default.Send, "Отправить", tint = Sea) }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current; val st = remember { Settings(ctx) }
    var key by remember { mutableStateOf(st.apiKey) }; var base by remember { mutableStateOf(st.apiBase) }; var model by remember { mutableStateOf(st.model) }
    var th by remember { mutableStateOf(st.thinking) }; var se by remember { mutableStateOf(st.search) }; var no by remember { mutableStateOf(st.notify) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ИИ (режим API)", fontWeight = FontWeight.Bold, color = Sea)
        OutlinedTextField(key, { key = it; st.apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API-ключ DashScope (Alibaba Model Studio)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        OutlinedTextField(base, { base = it; st.apiBase = it }, Modifier.fillMaxWidth(), label = { Text("Адрес API (OpenAI-совместимый)") }, singleLine = true)
        Row { TextButton({ base = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"; st.apiBase = base }) { Text("Сингапур") }
              TextButton({ base = "https://dashscope.aliyuncs.com/compatible-mode/v1"; st.apiBase = base }) { Text("Пекин") } }
        OutlinedTextField(model, { model = it; st.model = it }, Modifier.fillMaxWidth(), label = { Text("Модель") }, singleLine = true)
        TextButton({ model = DEFAULT_MODEL; st.model = model }) { Text("Сбросить на $DEFAULT_MODEL") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(th, { th = it; st.thinking = it }); Spacer(Modifier.width(8.dp)); Text("Режим размышления") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(se, { se = it; st.search = it }); Spacer(Modifier.width(8.dp)); Text("Веб-поиск (агентный)") }
        HorizontalDivider()
        Text("Уведомления", fontWeight = FontWeight.Bold, color = Sea)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(no, { no = it; st.notify = it }); Spacer(Modifier.width(8.dp)); Text("О новых законах и окончании мер (за 90/30/7/1 дн.)") }
        HorizontalDivider()
        Text("Пособия ${BuildConfig.VERSION_NAME}. База мер встроена в приложение; новые акты подтягиваются с publication.pravo.gov.ru. Информация справочная.", fontSize = 12.sp, color = Color.Gray)
    }
}
