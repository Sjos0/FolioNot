package com.sjos.folionote

import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sjos.folionote.engine.updater.UpdateUiState
import com.sjos.folionote.ui.settings.AppUpdateDialog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.ui.drive.DriveBackupSyncScreen
import com.sjos.folionote.ui.drive.DriveViewModel
import com.sjos.folionote.ui.home.HomeScreen
import com.sjos.folionote.ui.home.HomeViewModel
import com.sjos.folionote.ui.notebook.NotebookViewModel
import com.sjos.folionote.ui.notebook.NotebookWorkspaceScreen
import com.sjos.folionote.ui.providers.ProvidersScreen
import com.sjos.folionote.ui.providers.ProvidersViewModel
import com.sjos.folionote.ui.reader.SourceReaderScreen
import com.sjos.folionote.ui.reader.SourceReaderViewModel
import com.sjos.folionote.ui.settings.SettingsScreen
import com.sjos.folionote.ui.settings.SettingsViewModel
import com.sjos.folionote.ui.theme.FolioNoteTheme

object FolioRoutes {
    const val HOME = "home"
    const val NOTEBOOK = "notebook/{notebookId}"
    const val PROVIDERS = "providers"
    const val SETTINGS = "settings"
    const val DRIVE_SYNC = "drive_sync"
    const val READER = "reader/{sourceId}/{page}?chunkId={chunkId}"

    fun notebook(notebookId: String): String = "notebook/${Uri.encode(notebookId)}"
    fun reader(sourceId: String, page: Int, chunkId: String?): String {
        val base = "reader/${Uri.encode(sourceId)}/$page"
        return if (!chunkId.isNullOrBlank()) "$base?chunkId=${Uri.encode(chunkId)}" else base
    }
}

class MainActivity : ComponentActivity() {

    private val pendingSharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as FolioNoteApp).container
        appContainer.appUpdateManager.checkForUpdatesAutomatically()
        handleIncomingIntent(intent, appContainer)

        setContent {
            val settings by appContainer.preferencesStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            val pendingSharedUrl by pendingSharedUrlState

            FolioNoteTheme(themePreference = settings.themePreference) {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val appUpdateState by appContainer.appUpdateManager.uiState.collectAsStateWithLifecycle()
                var updateDismissed by remember { mutableStateOf(false) }
                val currentUpdate = appUpdateState
                val onSettingsScreen = backStackEntry?.destination?.route == FolioRoutes.SETTINGS
                // Aviso de atualização aparece em qualquer tela (não só em Configurações); a tela de Configurações mostra o seu próprio.
                if (currentUpdate is UpdateUiState.UpdateAvailable && !updateDismissed && !onSettingsScreen) {
                    AppUpdateDialog(
                        updateState = currentUpdate,
                        installedVersionLabel = currentUpdate.installedVersionName,
                        activity = this@MainActivity,
                        onStartOrResumeDownload = { update -> appContainer.appUpdateManager.startOrResumeDownload(update) },
                        onCancelDownload = { appContainer.appUpdateManager.cancelDownload() },
                        onRetryCheck = { appContainer.appUpdateManager.checkForUpdates() },
                        onOpenInstallPermissionSettings = {
                            appContainer.appUpdateManager.createUnknownSourcesSettingsIntent()?.let { startActivity(it) }
                        },
                        onLaunchInstaller = { appContainer.appUpdateManager.launchSystemInstaller(this@MainActivity) },
                        onRefreshAfterExternalReturn = { appContainer.appUpdateManager.refreshStateAfterExternalReturn() },
                        onDismiss = {
                            appContainer.appUpdateManager.dismissOrResetToIdle()
                            updateDismissed = true
                        }
                    )
                }

                NavHost(
                    navController = navController,
                    startDestination = FolioRoutes.HOME
                ) {
                    composable(FolioRoutes.HOME) {
                        val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(appContainer))
                        HomeScreen(
                            viewModel = vm,
                            onOpenNotebook = { id -> navController.navigate(FolioRoutes.notebook(id)) },
                            onOpenProviders = { navController.navigate(FolioRoutes.PROVIDERS) },
                            onOpenSettings = { navController.navigate(FolioRoutes.SETTINGS) },
                            onOpenDriveSync = { navController.navigate(FolioRoutes.DRIVE_SYNC) },
                            pendingSharedUrl = pendingSharedUrl,
                            onClearSharedUrl = { pendingSharedUrlState.value = null }
                        )
                    }

                    composable(
                        route = FolioRoutes.NOTEBOOK,
                        arguments = listOf(navArgument("notebookId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val notebookId = backStackEntry.arguments?.getString("notebookId") ?: return@composable
                        val vm: NotebookViewModel = viewModel(
                            key = "nb_$notebookId",
                            factory = NotebookViewModel.Factory(notebookId, appContainer)
                        )
                        NotebookWorkspaceScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onOpenProviders = { navController.navigate(FolioRoutes.PROVIDERS) },
                            onOpenSettings = { navController.navigate(FolioRoutes.SETTINGS) },
                            onOpenReader = { srcId, page, chunkId ->
                                navController.navigate(FolioRoutes.reader(srcId, page, chunkId))
                            }
                        )
                    }

                    composable(FolioRoutes.PROVIDERS) {
                        val vm: ProvidersViewModel = viewModel(factory = ProvidersViewModel.Factory(appContainer))
                        ProvidersScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(FolioRoutes.SETTINGS) {
                        val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(appContainer))
                        SettingsScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onOpenProviders = { navController.navigate(FolioRoutes.PROVIDERS) },
                            onOpenDriveSync = { navController.navigate(FolioRoutes.DRIVE_SYNC) }
                        )
                    }

                    composable(FolioRoutes.DRIVE_SYNC) {
                        val vm: DriveViewModel = viewModel(factory = DriveViewModel.Factory(appContainer))
                        DriveBackupSyncScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate(FolioRoutes.SETTINGS) }
                        )
                    }

                    composable(
                        route = FolioRoutes.READER,
                        arguments = listOf(
                            navArgument("sourceId") { type = NavType.StringType },
                            navArgument("page") { type = NavType.IntType; defaultValue = 1 },
                            navArgument("chunkId") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { backStackEntry ->
                        val sourceId = backStackEntry.arguments?.getString("sourceId") ?: return@composable
                        val page = backStackEntry.arguments?.getInt("page") ?: 1
                        val chunkId = backStackEntry.arguments?.getString("chunkId")
                        val vm: SourceReaderViewModel = viewModel(
                            key = "reader_${sourceId}_${page}_$chunkId",
                            factory = SourceReaderViewModel.Factory(sourceId, page, chunkId, appContainer)
                        )
                        SourceReaderScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val appContainer = (application as FolioNoteApp).container
        handleIncomingIntent(intent, appContainer)
    }

    private fun handleIncomingIntent(intent: Intent?, appContainer: AppContainer) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = appContainer.webClipperEngine.extractFirstUrl(sharedText)
            if (!url.isNullOrBlank()) {
                pendingSharedUrlState.value = url
            }
        }
    }
}
