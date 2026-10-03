package ai.opencode.mobile.ui.connect

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ConnectionState
import ai.opencode.mobile.ui.asString
import ai.opencode.mobile.ui.theme.LocalGnomeAccents
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    val viewModel: ConnectViewModel = viewModel(factory = ConnectViewModel.Factory)
    val form by viewModel.form.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()

    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) onConnected()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge: keep clear of the status/navigation bars, cutouts and
            // the keyboard (safeDrawing includes the IME). Applied before the
            // scroll so the viewport shrinks and a focused field can scroll up.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_back))
                }
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.connect_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { value -> viewModel.updateForm { it.copy(baseUrl = value) } },
            label = { Text(stringResource(R.string.connect_server_url)) },
            placeholder = { Text(stringResource(R.string.connect_server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.username,
            onValueChange = { value -> viewModel.updateForm { it.copy(username = value) } },
            label = { Text(stringResource(R.string.connect_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { value -> viewModel.updateForm { it.copy(password = value) } },
            label = { Text(stringResource(R.string.connect_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.connect_insecure_tls),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.connect_insecure_tls_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = form.allowInsecureTls,
                onCheckedChange = { value -> viewModel.updateForm { it.copy(allowInsecureTls = value) } },
            )
        }

        Spacer(Modifier.height(12.dp))
        LanguageSwitch()

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::save,
            enabled = form.baseUrl.isNotBlank() && connection !is ConnectionState.Connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connect_connect))
        }

        Spacer(Modifier.height(20.dp))
        ConnectionStatus(connection)

        saveError?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message.asString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Language tags the app ships translations for, in display order. */
private val APP_LANGUAGES = listOf("ru" to "Русский", "en" to "English")

/**
 * Switches the app language. AppCompat recreates the activity and remembers the
 * choice (the system does on Android 13+, AppCompat's own store before that).
 * The active language is read from the configuration the activity runs with, so
 * a fresh install shows Russian on a Russian system and English otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSwitch() {
    val current = LocalConfiguration.current.locales[0]?.language
    val selected = APP_LANGUAGES.indexOfFirst { it.first == current }.takeIf { it >= 0 }
        ?: APP_LANGUAGES.indexOfFirst { it.first == "en" }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
            APP_LANGUAGES.forEachIndexed { index, (tag, label) ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = {
                        if (index != selected) {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = APP_LANGUAGES.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is ConnectionState.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.connect_connecting), style = MaterialTheme.typography.bodySmall)
            }

            is ConnectionState.Connected -> {
                Text(
                    text = state.serverName?.let { stringResource(R.string.connect_connected_version, it) }
                        ?: stringResource(R.string.connect_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGnomeAccents.current.success,
                )
            }

            is ConnectionState.Error -> {
                Text(
                    text = state.message.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ConnectionState.Disconnected -> {
                Text(
                    text = stringResource(R.string.connect_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
