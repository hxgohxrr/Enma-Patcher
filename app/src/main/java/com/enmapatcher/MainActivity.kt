package com.enmapatcher

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enmapatcher.model.PatchState
import com.enmapatcher.ui.MainScreen
import com.enmapatcher.ui.PatchScreen
import com.enmapatcher.ui.SaveScreen
import com.enmapatcher.ui.SettingsScreen
import com.enmapatcher.ui.theme.EnmaPatcherTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#1C1B1F")
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContent {
            EnmaPatcherTheme {
                EnmaNavGraph(viewModel)
            }
        }
    }
}

@Composable
private fun EnmaNavGraph(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val patchState by viewModel.patchState.collectAsState()

    NavHost(navController = navController, startDestination = "main") {

        composable("main") {
            MainScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToPatch = {
                    viewModel.patch()
                    navController.navigate("patch")
                },
                onNavigateToSaves = { navController.navigate("saves") },
            )
        }

        composable("saves") {
            SaveScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable("patch") {
            PatchScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack("main", inclusive = false) },
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }

    val settings by viewModel.settings.collectAsState()
    LaunchedEffect(patchState) {
        if (patchState is PatchState.Success && settings.autoInstall) {

        }
    }
}
