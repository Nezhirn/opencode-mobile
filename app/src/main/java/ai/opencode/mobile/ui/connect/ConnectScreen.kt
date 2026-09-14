package ai.opencode.mobile.ui.connect

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ConnectionState
import ai.opencode.mobile.ui.theme.LocalGnomeAccents
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    val viewModel: ConnectViewModel = viewModel(factory = ConnectViewModel.Factory)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()

    var baseUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("opencode") }
    var password by remember { mutableStateOf("") }
    var allowInsecureTls by rememberSaveable { mutableStateOf(false) }
    // Deliberately NOT saveable: the password field is not saveable either, so a
    // restored "already prefilled" flag left the password empty and Connect then
    // overwrote a working credential with a blank one.
    var prefilled by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!prefilled && settings.baseUrl.isNotEmpty()) {
            baseUrl = settings.baseUrl
            username = settings.username
            password = settings.password
            allowInsecureTls = settings.allowInsecureTls
            prefilled = true
        }
    }

    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) onConnected()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
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
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.connect_server_url)) },
            placeholder = { Text(stringResource(R.string.connect_server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.connect_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
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
                checked = allowInsecureTls,
                onCheckedChange = { allowInsecureTls = it },
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.save(baseUrl, username, password, allowInsecureTls) },
            enabled = baseUrl.isNotBlank() && connection !is ConnectionState.Connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connect_connect))
        }

        Spacer(Modifier.height(20.dp))
        ConnectionStatus(connection)

        saveError?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
                    text = state.message,
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
