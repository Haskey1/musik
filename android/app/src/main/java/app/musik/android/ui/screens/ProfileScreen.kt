package app.musik.android.ui.screens

import androidx.compose.material.icons.filled.BarChart
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musik.android.BuildConfig
import app.musik.android.data.Health
import app.musik.android.data.Labels
import app.musik.android.data.OfflineStore
import app.musik.android.data.Profile
import app.musik.android.data.RadioRule
import app.musik.android.data.Settings
import app.musik.android.data.ShareLink
import app.musik.android.data.formatBytes
import app.musik.android.playback.ArtistSkips
import app.musik.android.ui.App
import app.musik.android.ui.ChipButton
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MusikColors
import app.musik.android.ui.Routes
import app.musik.android.ui.ScrollToTopOnReselect
import app.musik.android.ui.StatusBarScrim
import app.musik.android.ui.displayStyle
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal val LocalProfileBack = staticCompositionLocalOf<() -> Unit> { {} }

private enum class ProfilePage { Taste, Statistics, Playback, Downloads, Shares, Rules, Server }

@Composable
fun ProfileScreen() {
    val state = App.state
    val settings = App.settings
    val offline = App.offline
    var page by remember { mutableStateOf<ProfilePage?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var health by remember { mutableStateOf<Health?>(null) }
    var shares by remember { mutableStateOf<List<ShareLink>>(emptyList()) }
    var rules by remember { mutableStateOf<List<RadioRule>>(emptyList()) }
    var mobileStream by remember { mutableStateOf(settings.mobileStream) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var crossfade by remember { mutableStateOf(settings.crossfade) }
    var crossfadeSeconds by remember { mutableStateOf(settings.crossfadeSeconds) }
    var skipAlbums by remember { mutableStateOf(settings.crossfadeSkipAlbums) }
    var prefetchNext by remember { mutableStateOf(settings.prefetchNext) }

    LaunchedEffect(Unit) {
        coroutineScope {
            val p = async { runCatching { App.api.profile() }.getOrNull() }
            val h = async { runCatching { App.api.health() }.getOrNull() }
            val s = async { runCatching { App.api.shares() }.getOrNull() }
            val r = async { runCatching { App.api.rules() }.getOrNull() }
            profile = p.await() ?: profile
            health = h.await() ?: health
            shares = s.await() ?: shares
            rules = (r.await() ?: rules).filter { it.targetType in listOf("artist", "track", "song", "album") }
        }
    }

    BackHandler(enabled = page != null) { page = null }

    CompositionLocalProvider(LocalProfileBack provides { page = null }) {
    Box(Modifier.fillMaxSize()) {
        when (page) {
            null -> ProfileRoot(profile, offline) { page = it }
            ProfilePage.Taste -> TastePage(profile) { page = ProfilePage.Statistics }
            ProfilePage.Statistics -> ListeningStatisticsScreen()
            ProfilePage.Playback -> PlaybackPage(
                mobileStream = mobileStream,
                onMobileStream = { mobileStream = it; settings.mobileStream = it },
                crossfade = crossfade,
                onCrossfade = { crossfade = it; settings.crossfade = it },
                crossfadeSeconds = crossfadeSeconds,
                onCrossfadeSeconds = { crossfadeSeconds = it },
                onCrossfadeFinished = { settings.crossfadeSeconds = crossfadeSeconds },
                skipAlbums = skipAlbums,
                onSkipAlbums = { skipAlbums = it; settings.crossfadeSkipAlbums = it },
                haptics = haptics,
                onHaptics = { haptics = it; settings.haptics = it },
                onEqualizer = { showEqualizer = true },
            )
            ProfilePage.Downloads -> DownloadsPage(
                offline = offline,
                prefetchNext = prefetchNext,
                onPrefetchNext = { prefetchNext = it; settings.prefetchNext = it },
                onClear = { confirmClear = true },
            )
            ProfilePage.Shares -> SharesPage(shares, onChanged = { shares = it })
            ProfilePage.Rules -> RulesPage(rules, onChanged = { rules = it })
            ProfilePage.Server -> ServerPage(settings, health, onLogout = { confirmLogout = true })
        }
        StatusBarScrim()
    }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Сохранённые треки останутся на телефоне.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; state.logout() }) { Text("Выйти", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
            containerColor = MusikColors.surfaceHigh,
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить все сохранённые треки?") },
            text = { Text("Файлы будут удалены с телефона. Это действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; offline.removeAll() }) { Text("Удалить", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
            containerColor = MusikColors.surfaceHigh,
        )
    }
    if (showEqualizer) EqualizerSheet { showEqualizer = false }
}

@Composable
private fun ProfileRoot(profile: Profile?, offline: OfflineStore, onOpen: (ProfilePage) -> Unit) {
    val scroll = rememberScrollState()
    ScrollToTopOnReselect(Routes.PROFILE) { scroll.animateScrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Профиль", Modifier.padding(top = 12.dp), style = displayStyle(32))
        Column(Modifier.fillMaxWidth().background(MusikColors.surface, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Твой профиль", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Вкус, статистика и настройки", color = MusikColors.muted)
        }
        TasteCard(profile) { onOpen(ProfilePage.Taste) }
        ProfileGroup {
            ProfileRow("Статистика", "За неделю и год · треки и артисты", Icons.Filled.BarChart, MusikColors.teal) { onOpen(ProfilePage.Statistics) }
        }
        ProfileGroup {
            ProfileRow(
                "Воспроизведение", "Качество, переходы, вибрация", Icons.Filled.PlayArrow, Color(0xFFFF8A3D),
            ) { onOpen(ProfilePage.Playback) }
            ProfileDivider()
            ProfileRow(
                "Загрузки и память", "${offline.saved.size} · ${formatBytes(offline.totalBytes)}",
                Icons.Filled.DownloadForOffline, Color(0xFF24C8D8),
            ) { onOpen(ProfilePage.Downloads) }
        }
        ProfileGroup {
            ProfileRow("Share radio", "Ссылки на эфир для друзей", Icons.Filled.Podcasts, Color(0xFFFF3B5C)) {
                onOpen(ProfilePage.Shares)
            }
            ProfileDivider()
            ProfileRow("Скрыто и реже", "Треки и артисты, которые ты убрал", Icons.Filled.VisibilityOff, Color(0xFFD93BE8)) {
                onOpen(ProfilePage.Rules)
            }
        }
        ProfileGroup {
            ProfileRow(
                "Сервер и аккаунт", App.settings.baseUrl.substringAfter("://"), Icons.Filled.Storage, Color(0xFF9299A2),
            ) { onOpen(ProfilePage.Server) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TasteCard(profile: Profile?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MusikColors.surfaceHigh, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Вкус", color = MusikColors.muted, style = MaterialTheme.typography.bodyMedium)
                Text(
                    Labels.maturity(App.player.maturity ?: profile?.maturity),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MusikColors.muted)
        }
        LinearProgressIndicator(
            progress = { (profile?.confidence ?: 0.0).coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = MusikColors.accentBright, trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun ProfileGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MusikColors.surfaceHigh, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
        content = content,
    )
}

@Composable
private fun ProfileRow(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(color, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MusikColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MusikColors.muted)
    }
}

@Composable
private fun ProfileDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp), color = Color.White.copy(alpha = 0.07f))
}

@Composable
private fun DetailPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val back = LocalProfileBack.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(title, style = displayStyle(28))
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TastePage(initial: Profile?, onStatistics: () -> Unit) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(initial) }
    var lo by remember { mutableStateOf((initial?.exploreLo ?: 0.1).toFloat().coerceIn(0f, 0.6f)) }
    var hi by remember { mutableStateOf((initial?.exploreHi ?: 0.4).toFloat().coerceIn(0.1f, 0.8f)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try {
            val fresh = App.api.profile()
            profile = fresh
            lo = (fresh.exploreLo ?: 0.1).toFloat().coerceIn(0f, 0.6f)
            hi = (fresh.exploreHi ?: 0.4).toFloat().coerceIn(lo + 0.05f, 0.8f)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { error = "Не удалось загрузить профиль. ${e.localizedMessage.orEmpty()}" }
    }
    LaunchedEffect(Unit) { load() }
    DetailPage("Радио") {
        Section("Твой вкус") {
            Text(Labels.maturity(profile?.maturity ?: App.player.maturity), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (profile?.maturity == "ready") "Дальше радио опирается на лайки и то, что ты дослушиваешь."
                else "Слушай и отмечай любимое — так радио запоминает твой вкус.", color = MusikColors.muted)
            profile?.let { p ->
                LinearProgressIndicator(progress = { (p.confidence ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MusikColors.accent)
                listOf("${p.nPositive ?: 0}" to "лайков", "${p.nNegative ?: 0}" to "скипов", "${((p.exploreRatio ?: 0.0) * 100).roundToInt()}%" to "нового сейчас").forEach { (value, label) ->
                    Column(Modifier.fillMaxWidth().background(MusikColors.background.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text(value, style = displayStyle(28))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MusikColors.muted)
                    }
                }
            }
            error?.let { Text(it, color = MusikColors.muted); ChipButton(onClick = { scope.launch { load() } }) { Text("Попробовать снова") } }
        }
        if (profile != null) Section("Сколько нового в радио") {
            Text("Знакомое — похожее на лайки. Новое — соседнее, забытое и случайное.", color = MusikColors.muted)
            Text("не меньше · ${(lo * 100).roundToInt()}%")
            Slider(value = lo, onValueChange = { lo = it; if (lo >= hi) hi = (lo + 0.05f).coerceAtMost(0.8f) }, valueRange = 0f.. 0.6f, steps = 11)
            Text("не больше · ${(hi * 100).roundToInt()}%")
            Slider(value = hi, onValueChange = { hi = it; if (hi <= lo) lo = (hi - 0.05f).coerceAtLeast(0f) }, valueRange = 0.1f.. 0.8f, steps = 13)
            Text("Нового будет от ${(lo * 100).roundToInt()}% до ${(hi * 100).roundToInt()}%", color = MusikColors.muted)
            ChipButton(onClick = {
                saving = true
                scope.launch {
                    try {
                        App.api.send("PUT", "/api/profile/explore", body = app.musik.android.data.jsonBody("explore_lo" to lo, "explore_hi" to hi))
                        load()
                        App.state.show("Настройки радио сохранены")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) { App.state.show(e.localizedMessage ?: "Не удалось сохранить настройки")
                    } finally { saving = false }
                }
            }, enabled = !saving) { Text(if (saving) "Сохраняю…" else "Сохранить") }
        }
        profile?.topArtists?.takeIf { it.isNotEmpty() }?.let { top ->
            Section("Часто играет") { top.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { nav.openArtist(a.artist) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.artist, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MusikColors.muted)
                }
            } }
        }
        ChipButton(onClick = onStatistics) { Icon(Icons.Filled.BarChart, null); Text("За неделю и год") }
    }
}

@Composable
private fun PlaybackPage(
    mobileStream: Boolean,
    onMobileStream: (Boolean) -> Unit,
    crossfade: Boolean,
    onCrossfade: (Boolean) -> Unit,
    crossfadeSeconds: Int,
    onCrossfadeSeconds: (Int) -> Unit,
    onCrossfadeFinished: () -> Unit,
    skipAlbums: Boolean,
    onSkipAlbums: (Boolean) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onEqualizer: () -> Unit,
) {
    DetailPage("Воспроизведение") {
        Section("Качество") {
            Toggle(
                "Мобильный поток (AAC/MP3)", mobileStream,
                "Сервер отдаёт перекодированную версию, если она готова, иначе — оригинал. Выключи, чтобы всегда слушать оригинал (FLAC и т. п.).",
                onMobileStream,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEqualizer).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Эквалайзер", Modifier.weight(1f))
                Text(if (App.equalizer.enabled) "включён" else "выключен", color = MusikColors.muted)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Filled.Tune, null, tint = MusikColors.muted)
            }
        }
        Section("Переходы между треками") {
            Toggle("Кроссфейд", crossfade, "Конец трека плавно перетекает в начало следующего.", onCrossfade)
            if (crossfade) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Длительность", Modifier.weight(1f))
                    Text("$crossfadeSeconds с", color = MusikColors.muted)
                }
                Slider(
                    value = crossfadeSeconds.toFloat(), onValueChange = { onCrossfadeSeconds(it.roundToInt()) },
                    onValueChangeFinished = onCrossfadeFinished, valueRange = 1f..12f, steps = 10,
                    colors = SliderDefaults.colors(thumbColor = MusikColors.accent, activeTrackColor = MusikColors.accent),
                )
                Toggle("Альбомы без наложения", skipAlbums, "Треки одного альбома идут встык, без наложения.", onSkipAlbums)
            }
        }
        Section("Вибрация", "Лёгкая вибрация при лайке, снятии лайка и дизлайке.") {
            Toggle("Тактильный отклик", haptics, onChange = onHaptics)
        }
    }
}

@Composable
private fun DownloadsPage(offline: OfflineStore, prefetchNext: Boolean, onPrefetchNext: (Boolean) -> Unit, onClear: () -> Unit) {
    DetailPage("Загрузки и память") {
        Section(
            "Автоскачивание",
            "Лайкнутые треки сохраняются на телефон и удаляются, когда снимаешь ♥. Следующие треки загружаются заранее, чтобы музыка не обрывалась при плохой связи.",
        ) {
            Toggle("Сохранять лайкнутые на телефон", offline.autoSaveFavorites) {
                offline.updateAutoSave(it)
                if (it) App.state.perform { App.state.refreshFavorites() }
            }
            Toggle("Подгружать следующие треки", prefetchNext, onChange = onPrefetchNext)
        }
        Section("На телефоне") {
            Info("Сохранено", "${offline.saved.size}")
            Info("Занято", formatBytes(offline.totalBytes))
            if (offline.saved.isNotEmpty()) {
                TextButton(onClick = onClear, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DeleteOutline, null, tint = Color(0xFFFF6B6B))
                    Spacer(Modifier.size(8.dp))
                    Text("Удалить все сохранённые", color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun SharesPage(shares: List<ShareLink>, onChanged: (List<ShareLink>) -> Unit) {
    val state = App.state
    val context = LocalContext.current
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("musik", text))
        state.show("Ссылка скопирована")
    }
    DetailPage("Share radio") {
        Section("Эфир по ссылке", "Слушать радио в любом плеере (VLC, браузер). Вкус от этого не меняется.") {
            if (shares.isEmpty()) EmptyHint("Ссылок пока нет")
            shares.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name ?: "Эфир", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.url, color = if (s.active == false) MusikColors.muted.copy(alpha = 0.5f) else MusikColors.muted,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        s.listenCount?.takeIf { it > 0 }?.let {
                            Text("слушали $it раз", color = MusikColors.teal, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = { copy(s.url) }) { Icon(Icons.Filled.ContentCopy, "Копировать", tint = MusikColors.muted) }
                    IconButton(onClick = { shareText(context, s.url) }) { Icon(Icons.Filled.Share, "Поделиться", tint = MusikColors.muted) }
                    IconButton(onClick = {
                        state.perform {
                            App.api.revokeShare(s.token)
                            onChanged(shares.filter { it.token != s.token })
                            state.show("Ссылка отозвана")
                        }
                    }) { Icon(Icons.Filled.Delete, "Отозвать", tint = MusikColors.accent) }
                }
            }
            ChipButton(onClick = {
                state.perform {
                    val created = App.api.createShare()
                    onChanged(runCatching { App.api.shares() }.getOrNull() ?: shares)
                    created.url?.let { copy(it); shareText(context, it) }
                }
            }) { Icon(Icons.Filled.AddCircleOutline, null, Modifier.size(18.dp)); Text("Новая ссылка на эфир") }
        }
    }
}

@Composable
private fun RulesPage(rules: List<RadioRule>, onChanged: (List<RadioRule>) -> Unit) {
    val state = App.state
    DetailPage("Скрыто и реже") {
        Section(
            "Правила радио",
            "Скрыть трек или артиста — в меню трека или «⋮» в плеере. После трёх быстрых скипов подряд артист сам попадает в «реже».",
        ) {
            if (rules.isEmpty()) EmptyHint("Ничего не скрыто")
            rules.forEach { rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ruleTitle(rule), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (rule.action == "block") "скрыто" else "реже",
                            color = if (rule.action == "block") MusikColors.accent else MusikColors.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = {
                        state.perform {
                            App.api.deleteRule(rule.ruleId)
                            if (rule.targetType == "artist") ArtistSkips.reset(App.settings, rule.targetKey)
                            onChanged(rules.filter { it.ruleId != rule.ruleId })
                            state.show("Вернул")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp), tint = MusikColors.accentBright)
                        Spacer(Modifier.size(4.dp))
                        Text("Вернуть", color = MusikColors.accentBright)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerPage(settings: Settings, health: Health?, onLogout: () -> Unit) {
    DetailPage("Сервер и аккаунт") {
        Section("Сервер") {
            Info("Адрес", settings.baseUrl)
            Info(
                "Режим",
                when {
                    settings.hasCustomServer -> "Пользовательский адрес"
                    settings.baseUrl == Settings.builtInFallback && Settings.builtInFallback.isNotEmpty() -> "Fallback CDN"
                    else -> "Основной домен"
                },
            )
            health?.let { h ->
                Info("Версия", listOfNotNull(h.version, h.apiVersion).joinToString(" · "))
                h.tracks?.let { Info("Треков", it.toString()) }
            }
            Info("Приложение", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            TextButton(onClick = onLogout, Modifier.fillMaxWidth()) { Text("Выйти", color = Color(0xFFFF6B6B)) }
        }
    }
}

private fun ruleTitle(rule: RadioRule): String = when (rule.targetType) {
    "artist" -> "Артист: ${rule.targetKey}"
    "album" -> "Альбом: ${rule.targetKey.replace("|", " — ")}"
    else -> "Трек"
}

@Composable
fun Section(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = MusikColors.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Column(
            Modifier.fillMaxWidth().background(MusikColors.surface, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
        )
        footer?.let { Text(it, color = MusikColors.muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun Toggle(title: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = MusikColors.accent, checkedThumbColor = Color.White),
            )
        }
        note?.let { Text(it, color = MusikColors.muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), color = MusikColors.muted)
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


