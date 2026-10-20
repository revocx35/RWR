package io.github.revocx35.rwr.ui

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.revocx35.rwr.server.RwrAuthentik
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** "Sign in with authentik" for an RWR server behind authentik forward auth; [onSignedIn] retries. */
@Composable
fun AuthentikPrompt(activity: Activity, baseUrl: String, pin: String?, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("The server is protected by authentik. Sign in with your authentik account to reach it.")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    RwrAuthentik.signIn(activity, baseUrl, pin, null)
                    onSignedIn()
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                } catch (e: IOException) {
                    error = e.message ?: "authentik sign-in failed."
                } catch (e: Exception) {
                    error = "authentik sign-in failed."
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Waiting for authentik…" else "Sign in with authentik") }
    if (busy) TextButton(onClick = { RwrAuthentik.cancel() }) { Text("Cancel") }
}
