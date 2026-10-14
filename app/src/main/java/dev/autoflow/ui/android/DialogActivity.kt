package dev.autoflow.ui.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import dev.autoflow.AutoFlowApp
import dev.autoflow.runtime.DialogBroker
import dev.autoflow.ui.settings.ThemeMode

/** The "User dialog" block's dialog, shown over whatever is on screen. */
class DialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(DialogBroker.EXTRA_ID, -1)
        val request = DialogBroker.request(id)
        if (request == null) {
            finish()
            return
        }
        val prefs = (application as AutoFlowApp).prefs
        val mode = runCatching { ThemeMode.valueOf(prefs.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        setFinishOnTouchOutside(false)
        setContent {
            AppTheme(mode, prefs.dynamicColor) {
                // The flow was stopped or answered elsewhere: close.
                LaunchedEffect(id) {
                    DialogBroker.open.collect { if (id !in it) finish() }
                }
                var text by rememberSaveable { mutableStateOf(request.default) }
                val focus = androidx.compose.runtime.remember { FocusRequester() }
                fun reply(ok: Boolean) {
                    DialogBroker.answer(id, DialogBroker.Answer(ok, text))
                    finish()
                }
                AlertDialog(
                    onDismissRequest = { reply(false) },
                    title = { Text(request.title.ifBlank { request.flowName }) },
                    text = {
                        Column {
                            if (request.message.isNotBlank()) Text(request.message, style = MaterialTheme.typography.bodyLarge)
                            if (request.kind == "input") {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { reply(true) }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                )
                                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { reply(true) }) {
                            Text(
                                when (request.kind) {
                                    "confirm" -> "Yes"
                                    else -> "OK"
                                }
                            )
                        }
                    },
                    dismissButton = when (request.kind) {
                        "message" -> null
                        else -> ({ TextButton(onClick = { reply(false) }) { Text(if (request.kind == "confirm") "No" else "Cancel") } })
                    },
                )
            }
        }
    }
}
