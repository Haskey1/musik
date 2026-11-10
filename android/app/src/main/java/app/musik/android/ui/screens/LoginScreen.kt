package app.musik.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.musik.android.AppState
import app.musik.android.data.Settings
import app.musik.android.ui.MusikColors
import app.musik.android.ui.PrimaryButton
import app.musik.android.ui.displayStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(state: AppState) {
    var baseUrl by rememberSaveable { mutableStateOf(state.settings.baseUrl) }
    var mode by rememberSaveable { mutableStateOf(AppState.LoginMode.Account) }
    var username by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Server address and token sign-in are hidden when the app has a built-in server
    // (friends just type login + password); a long press on the logo shows them.
    var advanced by rememberSaveable { mutableStateOf(Settings.builtInServer.isEmpty()) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                state.login(baseUrl, mode, username, secret)
                secret = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Column(
            Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { advanced = !advanced },
            ),
        ) {
            Text(
                "musik",
                style = displayStyle(56).copy(
                    brush = Brush.horizontalGradient(listOf(MusikColors.accentBright, MusikColors.accent)),
                ),
            )
            Text("Войди под своим логином и паролем.", color = MusikColors.muted)
        }

        if (advanced) {
            Field("Адрес сервера", baseUrl, { baseUrl = it }, "https://music.example.com", KeyboardType.Uri)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppState.LoginMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, AppState.LoginMode.entries.size),
                    ) { Text(if (m == AppState.LoginMode.Account) "Логин и пароль" else "Токен") }
                }
            }
        }

        if (mode == AppState.LoginMode.Account) {
            Field("Логин", username, { username = it }, "как на сайте", KeyboardType.Text)
            Field("Пароль", secret, { secret = it }, "пароль от musik", KeyboardType.Password, secure = true, onDone = ::submit)
        } else {
            Field("Токен устройства", secret, { secret = it }, "из аккаунта на сайте", KeyboardType.Password, secure = true, onDone = ::submit)
        }

        error?.let { Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }

        PrimaryButton(
            onClick = ::submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && baseUrl.isNotBlank() && secret.isNotEmpty(),
        ) {
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
            Text("Подключиться")
            Spacer(Modifier.weight(1f))
        }

        Text(
            "Логин и пароль — те же, что на сайте. Пароль на телефоне не сохраняется.",
            color = MusikColors.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Field(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    type: KeyboardType,
    secure: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MusikColors.muted, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = MusikColors.muted.copy(alpha = 0.6f)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = type,
                autoCorrectEnabled = false,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MusikColors.surface,
                unfocusedContainerColor = MusikColors.surface,
                focusedBorderColor = MusikColors.accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                cursorColor = MusikColors.accent,
            ),
        )
    }
}
