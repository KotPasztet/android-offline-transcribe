package com.voiceping.offlinetranscription.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voiceping.offlinetranscription.data.cassette.CassetteRepository
import com.voiceping.offlinetranscription.service.WhisperEngine
import com.voiceping.offlinetranscription.ui.cassette.CassetteScreen
import com.voiceping.offlinetranscription.ui.cassette.CassetteViewModel
import com.voiceping.offlinetranscription.ui.cassette.HomeShelfScreen
import com.voiceping.offlinetranscription.ui.cassette.HomeShelfViewModel
import com.voiceping.offlinetranscription.ui.setup.ModelSetupScreen
import com.voiceping.offlinetranscription.ui.setup.ModelSetupViewModel
import com.voiceping.offlinetranscription.ui.transcription.TranscriptionScreen
import com.voiceping.offlinetranscription.ui.transcription.TranscriptionViewModel
import com.voiceping.offlinetranscription.model.ModelState
import com.voiceping.offlinetranscription.ui.cassette.CrashRecoveryCoordinator

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val CASSETTE = "cassette/{cassetteId}"
    const val TRANSCRIBE = "transcribe" // legacy single-screen recorder, reachable for debugging

    fun cassette(id: Long) = "cassette/$id"
}

@Composable
fun AppNavigation(
    engine: WhisperEngine,
    cassetteRepository: CassetteRepository
) {
    val modelState by engine.modelState.collectAsState()
    val navController = rememberNavController()
    val context = LocalContext.current

    val startDestination = if (modelState == ModelState.Loaded) Routes.HOME else Routes.SETUP
    var hasRunCrashRecovery by remember { mutableStateOf(false) }

    LaunchedEffect(modelState) {
        when (modelState) {
            ModelState.Loaded -> {
                if (!hasRunCrashRecovery) {
                    hasRunCrashRecovery = true
                    // Runs once per app process: pick up any transcription that was
                    // interrupted by a crash last time, or abandon it if it's stuck.
                    CrashRecoveryCoordinator.recoverIfNeeded(context.applicationContext, engine, cassetteRepository)
                }
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute == Routes.SETUP) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            }
            else -> Unit
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            val viewModel = remember { ModelSetupViewModel(engine) }
            val canGoBack = navController.previousBackStackEntry != null
            ModelSetupScreen(
                viewModel = viewModel,
                onCancel = if (canGoBack) {
                    { navController.popBackStack() }
                } else null
            )
        }

        composable(Routes.HOME) {
            val viewModel = remember { HomeShelfViewModel(context.applicationContext, cassetteRepository) }
            HomeShelfScreen(
                viewModel = viewModel,
                onOpenCassette = { id -> navController.navigate(Routes.cassette(id)) },
                onOpenSettings = {
                    // Just open the model picker — don't unload the current model.
                    // Unloading only happens when the user actually picks a
                    // different one (ModelSetupViewModel.selectAndSetup handles
                    // that). Otherwise cancelling would leave the app with no
                    // model loaded and no way back.
                    engine.clearError()
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Routes.CASSETTE,
            arguments = listOf(navArgument("cassetteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val cassetteId = backStackEntry.arguments?.getLong("cassetteId") ?: return@composable
            val viewModel = remember(cassetteId) {
                CassetteViewModel(context.applicationContext, cassetteId, engine, cassetteRepository)
            }
            CassetteScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    engine.clearError()
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.TRANSCRIBE) {
            val viewModel = remember { TranscriptionViewModel(engine) }
            TranscriptionScreen(
                viewModel = viewModel,
                onChangeModel = {
                    engine.clearError()
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.TRANSCRIBE) { inclusive = true }
                    }
                }
            )
        }
    }
}
