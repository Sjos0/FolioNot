package com.sjos.folionote

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sjos.folionote.core.preferences.AppSettings
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
    const val READER = "reader/{sourceId}/{page}?chunkId={chunkId}"

    fun notebook(notebookId: String): String = "notebook/${Uri.encode(notebookId)}"
    fun reader(sourceId: String, page: Int, chunkId: String?): String {
        val base = "reader/${Uri.encode(sourceId)}/$page"
        return if (!chunkId.isNullOrBlank()) "$base?chunkId=${Uri.encode(chunkId)}" else base
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as FolioNoteApp).container

        setContent {
            val settings by appContainer.preferencesStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            FolioNoteTheme(themePreference = settings.themePreference) {
                val navController = rememberNavController()

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
                            onOpenSettings = { navController.navigate(FolioRoutes.SETTINGS) }
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
                            onOpenProviders = { navController.navigate(FolioRoutes.PROVIDERS) }
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
}
