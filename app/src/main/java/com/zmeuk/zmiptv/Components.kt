package com.zmeuk.zmiptv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFF2E7DFF)
val Panel = Color(0xE6101216)
val Dim = Color(0xFFAAB0BB)
val Gold = Color(0xFFFFC94D)

const val GROUP_FAV = "★ Избранное"
const val GROUP_ALL = "Все каналы"
const val GROUP_SEARCH = "🔍 Поиск"

/** Focused row: accent at 30 % opacity (70 % transparent) + a solid accent outline so focus stays obvious. */
val FocusFill = Accent.copy(alpha = 0.30f)

fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
fun hhmmss(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))
fun dayLabel(t: Long): String =
    SimpleDateFormat("EEEE, d MMMM", I18n.lang.locale).format(Date(t)).replaceFirstChar { it.titlecase(I18n.lang.locale) }
    .replaceFirstChar { it.uppercase() }

fun isOkKey(code: Int) =
    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * D-pad friendly list row. OK fires on key-up (so the key-down that opened a screen does not
 * click the first item), holding OK fires [onLongClick]. Other keys go to [onKeyDown].
 */
@Composable
fun TvItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onKeyDown: ((Int) -> Boolean)? = null,
    /** When set, the frame follows this instead of the real focus (continuous scrolling of the channel list). */
    focusedOverride: Boolean? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var realFocused by remember { mutableStateOf(false) }
    val focused = focusedOverride ?: realFocused
    var down by remember { mutableStateOf(false) }
    var longDone by remember { mutableStateOf(false) }
    val bg = when {
        focused -> FocusFill
        selected -> Color(0x26FFFFFF)
        else -> Color.Transparent
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (focused) Modifier.border(2.dp, Accent.copy(alpha = 0.9f), RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged {
                realFocused = it.isFocused
                if (it.isFocused) onFocused() else down = false
            }
            .onPreviewKeyEvent { e ->
                val ne = e.nativeKeyEvent
                val code = ne.keyCode
                if (isOkKey(code)) {
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (ne.repeatCount == 0) {
                                down = true
                                longDone = false
                            } else if (down && !longDone && onLongClick != null && (ne.isLongPress || ne.repeatCount >= 6)) {
                                longDone = true
                                onLongClick()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (down && !longDone) onClick()
                            down = false
                            true
                        }
                        else -> false
                    }
                } else if (e.type == KeyEventType.KeyDown && onKeyDown != null) {
                    onKeyDown(code)
                } else {
                    false
                }
            }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        content(focused)
    }
}

@Composable
fun Logo(url: String?, modifier: Modifier = Modifier) {
    if (url == null) {
        Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF)))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
