package ai.opencode.mobile

import ai.opencode.mobile.ui.AppRoot
import ai.opencode.mobile.ui.theme.OpenCodeTheme
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * AppCompatActivity rather than ComponentActivity: the per-app language set via
 * AppCompatDelegate is applied (and stored on Android < 13) only for AppCompat.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeTheme {
                // Gives every screen the theme's background and content colour.
                // Screens without a Scaffold (connect, settings) otherwise drew
                // text in the default black, unreadable on the dark theme.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    /**
     * uiMode is handled without recreating the activity (Compose follows the
     * theme by itself), but the system bar icons were styled once in onCreate:
     * after switching to the dark theme they stayed dark on a dark background.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
}
