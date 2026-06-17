package dev.sawitulm.palmannotate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import dev.sawitulm.palmannotate.ui.common.ToastHost
import dev.sawitulm.palmannotate.ui.common.rememberToastState
import dev.sawitulm.palmannotate.ui.navigation.PalmAnnotateNavHost
import dev.sawitulm.palmannotate.ui.theme.PalmAnnotateTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PalmAnnotateTheme {
                // One app-level toast host overlaid on the whole nav graph, exposed via
                // LocalToasts so every screen surfaces feedback through a single system.
                val toasts = rememberToastState()
                CompositionLocalProvider(LocalToasts provides toasts) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            PalmAnnotateNavHost()
                            ToastHost(
                                state = toasts,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}
