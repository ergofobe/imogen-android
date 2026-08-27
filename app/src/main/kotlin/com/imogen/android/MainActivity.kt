package com.imogen.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imogen.android.ui.ImogenApp
import com.imogen.android.ui.RootViewModel
import com.imogen.android.ui.theme.ImogenTheme

/**
 * The only activity.
 *
 * Single-task, so a pairing link tapped while the app is already open arrives at the
 * instance that is running rather than starting a second one behind it — which is what
 * `onNewIntent` is for, and what makes scanning a code from a phone browser work at all.
 */
class MainActivity : ComponentActivity() {

    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink = intent?.data?.toString()

        setContent {
            ImogenTheme {
                val model: RootViewModel = viewModel(
                    factory = RootViewModel.factory(application as ImogenApplication),
                )
                ImogenApp(
                    model = model,
                    deepLink = deepLink,
                    onDeepLinkHandled = { deepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.data?.toString()
    }
}
