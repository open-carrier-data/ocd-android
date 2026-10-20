package io.github.open_carrier_data.check.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.open_carrier_data.check.OwnerHook

class MainActivity : ComponentActivity() {
    private val viewModel: CheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No-op in debug and release; the owner build checks for its own leftovers here.
        OwnerHook.onAppStart(applicationContext)
        val toCallTest = intent?.getBooleanExtra(EXTRA_CALL_TEST, false) == true
        setContent {
            OcdCheckTheme {
                CheckScreen(viewModel, toCallTest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the app's settings page with the permission changed, or from starting Shizuku.
        viewModel.refreshIfPermissionChanged()
        viewModel.refreshShizuku()
        // Coming back from the guided switch step's Settings screen: read the phone again.
        viewModel.backFromSettings()
    }

    companion object {
        /** Opens the screen at the guided call test. */
        const val EXTRA_CALL_TEST = "io.github.open_carrier_data.check.CALL_TEST"
    }
}

@Composable
fun OcdCheckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
