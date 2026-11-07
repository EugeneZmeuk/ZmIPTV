package com.zmeuk.zmiptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** One block of the remote control cheat sheet: title and "button" to "what it does" rows. */
class HelpSection(val title: String, vararg rows: Pair<String, String>) {
    val rows: List<Pair<String, String>> = rows.toList()
}

/** Settings → "Remote control buttons": every button of every screen, scrolled with ▲ ▼. */
@Composable
fun RemoteHelpScreen(onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> 160f
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> -160f
                    KeyEvent.KEYCODE_PAGE_DOWN -> 600f
                    KeyEvent.KEYCODE_PAGE_UP -> -600f
                    else -> return@onKeyEvent false
                }
                scope.launch { scroll.animateScrollBy(step) }
                true
            }
            .focusable()
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text(T.helpTitle, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(T.helpFooter, color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (section in T.help) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(section.title, color = Gold, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    for ((button, action) in section.rows) {
                        Row {
                            Text(button, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(250.dp))
                            Text(action, color = Dim, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
