package ai.opencode.mobile

import ai.opencode.mobile.ui.AppRoot
import ai.opencode.mobile.ui.theme.OpenCodeTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeTheme {
                AppRoot()
            }
        }
    }
}
