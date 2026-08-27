package com.imogen.android.ui.onboarding

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.R
import com.imogen.android.ui.LinkState
import com.imogen.android.ui.RootViewModel

/**
 * Adding an account.
 *
 * Scanning is the front door and typing an address is the side one, and the screen says
 * so: the pairing button is the one that is filled in. Somebody who has the web interface
 * open — which is nearly everybody, because that is where they made the account — never
 * has to type a hostname on a phone keyboard.
 */
@Composable
fun AddAccountScreen(
    model: RootViewModel,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onLinked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val link by model.link.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Consumed in an effect rather than during composition: the state is shared, and a
    // second screen reading it later would think another account had just arrived.
    LaunchedEffect(link) {
        if (link is LinkState.Linked) {
            model.clearLinkState()
            onLinked()
        }
    }

    if (scanning) {
        ScanPane(
            onScanned = {
                scanning = false
                model.pair(it)
            },
            onCancel = { scanning = false },
            modifier = modifier,
        )
        return
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(28.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_imogen_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                "imogen",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Your photo library, on your own server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(36.dp))

            when (val state = link) {
                is LinkState.Working -> {
                    CircularProgressIndicator()
                    Text(
                        "Connecting…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                else -> {
                    if (typing) {
                        ServerForm(
                            onSubmit = { address ->
                                model.beginBrowserSignIn(address) { url ->
                                    // A Custom Tab, not a WebView: the browser's own
                                    // session and password manager are the whole point,
                                    // and an app that shows its own login form is an app
                                    // asking to be phished.
                                    CustomTabsIntent.Builder().build()
                                        .launchUrl(context, url.toUri())
                                }
                            },
                            onCancel = { typing = false },
                        )
                    } else {
                        Button(
                            onClick = { scanning = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Scan a pairing code") }

                        Text(
                            "In imogen on a computer, open Settings → Devices → Pair a device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp),
                        )

                        OutlinedButton(
                            onClick = { typing = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        ) { Text("Enter a server address") }
                    }

                    if (state is LinkState.Failed) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
        }

        if (canGoBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
    }
}

@Composable
private fun ServerForm(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var address by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            label = { Text("Server address") },
            placeholder = { Text("photos.example.com") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(address) },
            enabled = address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Continue in the browser") }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}

@Composable
private fun ScanPane(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var denied by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        QrScanner(
            onScanned = onScanned,
            onPermissionDenied = { denied = true },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (denied) {
                    "imogen needs the camera to read a pairing code. You can enter the " +
                        "server address instead."
                } else {
                    "Point the camera at the code on your computer."
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
    }
}
