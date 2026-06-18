package dev.sawitulm.palmannotate.ui.common

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Centralized toast system — info/success/error, auto-dismiss ~4s.
 * Port of the JS #toast-container with .toast info|success|error classes.
 */

data class ToastMessage(
    val text: String,
    val type: ToastType = ToastType.INFO,
)

enum class ToastType { INFO, SUCCESS, ERROR }

/**
 * App-level toast handle. Hoisted once in MainActivity and provided here so any screen can
 * surface feedback through ONE system: `LocalToasts.current.success(...)`. This replaces the
 * former mix of Scaffold SnackbarHost, android.widget.Toast, and inline Snackbars.
 */
val LocalToasts = staticCompositionLocalOf<ToastState> {
    error("No ToastState provided — wrap content in CompositionLocalProvider(LocalToasts provides …)")
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

class ToastState {
    var message by mutableStateOf<ToastMessage?>(null)
        private set

    fun show(text: String, type: ToastType = ToastType.INFO) {
        message = ToastMessage(text, type)
    }

    fun success(text: String) = show(text, ToastType.SUCCESS)
    fun error(text: String) = show(text, ToastType.ERROR)
    fun info(text: String) = show(text, ToastType.INFO)
    fun dismiss() { message = null }
}

@Composable
fun ToastHost(
    state: ToastState = rememberToastState(),
    modifier: Modifier = Modifier,
) {
    val msg = state.message

    LaunchedEffect(msg) {
        if (msg != null) {
            delay(4000)
            state.dismiss()
        }
    }

    // Insets so the toast clears the system navigation bar (edge-to-edge would otherwise draw
    // it under the gesture pill / nav buttons) and lifts above the keyboard when one is open.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        ) {
            msg?.let { toast ->
                val bgColor = when (toast.type) {
                    ToastType.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
                    ToastType.ERROR -> MaterialTheme.colorScheme.errorContainer
                    ToastType.INFO -> MaterialTheme.colorScheme.inverseSurface
                }
                val textColor = when (toast.type) {
                    ToastType.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
                    ToastType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    ToastType.INFO -> MaterialTheme.colorScheme.inverseOnSurface
                }
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = bgColor,
                    contentColor = textColor,
                    action = {
                        TextButton(onClick = { state.dismiss() }) { Text("OK") }
                    },
                ) {
                    Text(toast.text, maxLines = 3)
                }
            }
        }
    }
}
