package app.musik.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musik.android.BuildConfig
import app.musik.android.data.Health
import app.musik.android.data.Labels
import app.musik.android.data.Profile
import app.musik.android.data.RadioRule
import app.musik.android.data.ShareLink
import app.musik.android.playback.ArtistSkips
import app.musik.android.ui.App
import app.musik.android.ui.StatusBarScrim
import app.musik.android.ui.ChipButton
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.MusikColors
import app.musik.android.ui.displayStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen() {
    val state = App.state
    val settings = App.settings
    val offline = App.offline
    val context = LocalContext.current
    var profile by remember { mutableStateOf<Profile?>(null) }
    var health by remember { mutableStateOf<Health?>(null) }
    var shares by remember { mutableStateOf<List<ShareLink>>(emptyList()) }
    var rules by remember { mutableStateOf<List<RadioRule>>(emptyList()) }
    var mobileStream by remember { mutableStateOf(settings.mobileStream) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }

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

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("musik", text))
        state.show("Ссылка скопирована")
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Профиль", style = displayStyle(32))

        Section("Вкус") {
            val p = profile
            Text(Labels.maturity(App.player.maturity ?: p?.maturity), color = MusikColors.teal, fontWeight = FontWeight.SemiBold)
            if (p != null) {
                Text(
                    "Лайков и дослушанных: ${p.nPositive ?: 0} · скипов: ${p.nNegative ?: 0}" +
                        (p.confidence?.let { " · уверенность ${(it * 100).toInt()}%" } ?: ""),
                    color = MusikColors.muted, style = MaterialTheme.typography.bodySmall,
                )
                p.topArtists?.takeIf { it.isNotEmpty() }?.let { top ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        top.take(12).forEach { a ->
                            Text(
                                "${a.artist} · ${a.count}",
                                Modifier.background(MusikColors.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

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
                            shares = shares.filter { it.token != s.token }
                            state.show("Ссылка отозвана")
                        }
                    }) { Icon(Icons.Filled.Delete, "Отозвать", tint = MusikColors.accent) }
                }
            }
            ChipButton(onClick = {
                state.perform {
                    val created = App.api.createShare()
                    shares = runCatching { App.api.shares() }.getOrNull() ?: shares
                    created.url?.let { copy(it); shareText(context, it) }
                }
            }) { Icon(Icons.Filled.AddCircleOutline, null, Modifier.size(18.dp)); Text("Новая ссылка на эфир") }
        }

        Section(
            "Скрыто и реже",
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
                            if (rule.targetType == "artist") ArtistSkips.reset(settings, rule.targetKey)
                            rules = rules.filter { it.ruleId != rule.ruleId }
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

        Section("Воспроизведение") {
            Toggle(
                "Мобильный поток (AAC/MP3)", mobileStream,
                "Сервер отдаёт перекодированную версию, если она готова, иначе — оригинал. Выключи, чтобы всегда слушать оригинал (FLAC и т. п.).",
            ) {
                mobileStream = it
                settings.mobileStream = it
            }
            Toggle("Сохранять лайкнутые на телефон", offline.autoSaveFavorites) {
                offline.updateAutoSave(it)
                if (it) state.perform { state.refreshFavorites() }
            }
            Row(
                Modifier.fillMaxWidth().clickable { showEqualizer = true }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Эквалайзер", Modifier.weight(1f))
                Text(if (App.equalizer.enabled) "включён" else "выключен", color = MusikColors.muted)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Filled.Tune, null, tint = MusikColors.muted)
            }
        }

        Section("Сервер") {
            Info("Адрес", settings.baseUrl)
            health?.let { h ->
                Info("Версия", listOfNotNull(h.version, h.apiVersion).joinToString(" · "))
                h.tracks?.let { Info("Треков", it.toString()) }
            }
            Info("Приложение", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            TextButton(onClick = { confirmLogout = true }) { Text("Выйти", color = Color(0xFFFF6B6B)) }
        }
        Spacer(Modifier.height(24.dp))
    }

        StatusBarScrim()
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
    if (showEqualizer) EqualizerSheet { showEqualizer = false }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
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
