package ai.opencode.mobile.ui

import ai.opencode.mobile.OpenCodeApplication
import ai.opencode.mobile.ui.chat.ChatScreen
import ai.opencode.mobile.ui.connect.ConnectScreen
import ai.opencode.mobile.ui.files.FilesScreen
import ai.opencode.mobile.ui.projects.ProjectsScreen
import ai.opencode.mobile.ui.sessions.SessionsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
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
    val settingsLoaded by repository.settingsLoaded.collectAsStateWithLifecycle()

    if (!settingsLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!settings.isConfigured) {
        ConnectScreen(onConnected = {})
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectsScreen(
                onOpenProject = {
                    navController.navigate("sessions") { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate("settings") { launchSingleTop = true }
                },
            )
        }

        composable("sessions") { entry ->
            SessionsScreen(
                onBack = { navController.popIfCurrent(entry) },
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
                onBack = { navController.popIfCurrent(entry) },
                onOpenFiles = { id ->
                    navController.navigate("files/$id") { launchSingleTop = true }
                },
            )
        }

        composable("files") { entry ->
            FilesScreen(sessionId = null, onBack = { navController.popIfCurrent(entry) })
        }

        composable(
            route = "files/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            FilesScreen(sessionId = sessionId, onBack = { navController.popIfCurrent(entry) })
        }

        composable("settings") { entry ->
            ConnectScreen(
                onConnected = {},
                showBack = true,
                onBack = { navController.popIfCurrent(entry) },
            )
        }
    }
}

/**
 * Pops [entry] only while it is the top of the stack. During the exit
 * animation the leaving screen still takes taps: a quick second tap on its back
 * arrow popped the start destination too and left a blank screen.
 */
private fun NavHostController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}
