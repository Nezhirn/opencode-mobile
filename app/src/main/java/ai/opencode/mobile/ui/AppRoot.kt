package ai.opencode.mobile.ui

import ai.opencode.mobile.OpenCodeApplication
import ai.opencode.mobile.ui.chat.ChatScreen
import ai.opencode.mobile.ui.connect.ConnectScreen
import ai.opencode.mobile.ui.files.FilesScreen
import ai.opencode.mobile.ui.sessions.SessionsScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repository = (context.applicationContext as OpenCodeApplication).repository

    val settings by repository.settings.collectAsStateWithLifecycle()

    if (!settings.isConfigured) {
        ConnectScreen(onConnected = {})
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "sessions") {
        composable("sessions") {
            SessionsScreen(
                onOpenSession = { sessionId ->
                    navController.navigate("chat/$sessionId") { launchSingleTop = true }
                },
                onOpenFiles = {
                    navController.navigate("files") { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate("settings") { launchSingleTop = true }
                },
            )
        }

        composable(
            route = "chat/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            ChatScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onOpenFiles = { id ->
                    navController.navigate("files/$id") { launchSingleTop = true }
                },
            )
        }

        composable("files") {
            FilesScreen(sessionId = null, onBack = { navController.popBackStack() })
        }

        composable(
            route = "files/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            FilesScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
        }

        composable("settings") {
            ConnectScreen(
                onConnected = {},
                showBack = true,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
