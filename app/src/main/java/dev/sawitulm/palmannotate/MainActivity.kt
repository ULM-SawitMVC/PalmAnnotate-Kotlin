package dev.sawitulm.palmannotate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.sawitulm.palmannotate.ui.navigation.PalmAnnotateNavHost
import dev.sawitulm.palmannotate.ui.theme.PalmAnnotateTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PalmAnnotateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PalmAnnotateNavHost()
                }
            }
        }
    }
}
