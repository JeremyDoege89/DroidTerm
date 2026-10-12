package dev.dreamz.droidterm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible

enum class Screen(val label: String, val icon: ImageVector) {
    TERMINAL("Terminal", Icons.Filled.Terminal),
    TASKS("Tasks", Icons.AutoMirrored.Filled.List),
    BOOKMARKS("Bookmarks", Icons.Filled.Bookmarks),
    SSH("SSH", Icons.Filled.Key),
    ADB("ADB / USB", Icons.Filled.Usb),
}

/** Simple app-wide navigation state (a handful of top-level screens, no back stack needed). */
object Nav {
    var screen by mutableStateOf(Screen.TERMINAL)
    var openTaskId by mutableStateOf<Long?>(null)
}

val TermColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF052E16),
    secondary = Color(0xFF60A5FA),
    background = Color(0xFF101214),
    surface = Color(0xFF16191C),
    surfaceVariant = Color(0xFF22262B),
    surfaceContainer = Color(0xFF1A1D21),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
        setContent { MaterialTheme(colorScheme = TermColors) { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) Nav.screen = Screen.ADB
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRoot() {
    // Hide the bottom bar while the keyboard is up on the terminal so the extra keys sit right above it.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = Nav.openTaskId != null || Nav.screen != Screen.TERMINAL) {
        if (Nav.openTaskId != null) Nav.openTaskId = null else Nav.screen = Screen.TERMINAL
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!(imeVisible && Nav.screen == Screen.TERMINAL)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = Nav.screen == s,
                            onClick = { Nav.screen = s; if (s != Screen.TASKS) Nav.openTaskId = null },
                            icon = { Icon(s.icon, null) },
                            label = { Text(s.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (Nav.screen) {
                Screen.TERMINAL -> TerminalScreen()
                Screen.TASKS -> {
                    val id = Nav.openTaskId
                    if (id == null) TasksScreen() else TaskDetailScreen(id)
                }
                Screen.BOOKMARKS -> BookmarksScreen()
                Screen.SSH -> SshScreen()
                Screen.ADB -> AdbScreen()
            }
        }
    }
}
