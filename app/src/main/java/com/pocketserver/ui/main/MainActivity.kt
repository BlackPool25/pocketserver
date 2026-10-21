package com.pocketserver.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocketserver.PocketServerApplication
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.navigation.AppShell
import com.pocketserver.ui.theme.PocketTheme

/**
 * Main Activity for PocketServer.
 *
 * Implements the Single-Activity Jetpack Compose + Material 3 UI Architecture
 * with Navigation3, Edge-to-Edge display, and the calm developer tool aesthetic.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PocketServerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as PocketServerApplication
        viewModel = PocketServerViewModel(
            context = applicationContext,
            database = app.database,
            wakeCoordinator = app.wakeCoordinator,
            searchDb = app.searchDb,
            pocketForgeManager = app.pocketForgeManager
        )

        setContent {
            PocketTheme {
                AppShell(viewModel = viewModel)
            }
        }
    }
}
