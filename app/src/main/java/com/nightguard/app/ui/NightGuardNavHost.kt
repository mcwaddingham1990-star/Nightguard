package com.nightguard.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nightguard.app.ui.pause.PauseScreen
import com.nightguard.app.ui.report.ReportScreen
import com.nightguard.app.ui.setup.SetupScreen
import com.nightguard.app.ui.timeline.TimelineScreen

@Composable
fun NightGuardNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "setup") {
        composable("setup") {
            SetupScreen(
                onContinue = {
                    navController.navigate("timeline") {
                        popUpTo("setup") { inclusive = true }
                    }
                }
            )
        }
        composable("timeline") {
            TimelineScreen(
                onOpenSetup = { navController.navigate("setup") },
                onOpenReport = { navController.navigate("report") },
                onOpenPause = { navController.navigate("pause") }
            )
        }
        composable("report") {
            ReportScreen()
        }
        composable("pause") {
            PauseScreen(onDone = { navController.popBackStack() })
        }
    }
}
