package com.zmeuk.zmiptv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.scrollBy
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val FAV = GROUP_FAV
private const val ALL = GROUP_ALL
private const val SEARCH = GROUP_SEARCH

private sealed interface GuideRow {
    data class Header(val label: String) : GuideRow
    data class Item(val p: Programme) : GuideRow
}

private fun buildGuide(list: List<Programme>): List<GuideRow> {
    val rows = ArrayList<GuideRow>(list.size + 10)
    var lastDay = ""
    for (p in list) {
        val day = dayLabel(p.start)
        if (day != lastDay) {
            rows += GuideRow.Header(day)
            lastDay = day
        }
        rows += GuideRow.Item(p)
    }
    return rows
}

private suspend fun focusIndex(state: LazyListState, idx: Int, requester: FocusRequester) {
    if (idx < 0) return
    if (state.layoutInfo.visibleItemsInfo.none { it.index == idx }) state.scrollToItem((idx - 3).coerceAtLeast(0))
    requestWhenAttached(requester)
}

/**
 * The requester reaches its row only once that row has been (re)composed with the new focus number, which
 * can be a frame after this coroutine resumes: a request before that throws and the focus stays on a row
 * that has already scrolled away (the "lost slider" on the Bravia). Retry over a few frames.
 */
private suspend fun requestWhenAttached(requester: FocusRequester) {
    repeat(5) {
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.isSuccess) return
    }
}

/**
 * Continuous scrolling while ▲ ▼ is held: every frame the list moves by holdRowsPerSec × dt and the row
 * nearest the middle of the screen becomes the highlighted one ([onRow]). Ends when cancelled (key up)
 * or when the list cannot move any further.
 */
private suspend fun holdScroll(
    state: LazyListState, offset: Int, shown: List<Channel>, down: Boolean, onRow: (Int) -> Unit,
) {
    val t0 = withFrameNanos { it }
    var last = t0
    while (true) {
        val now = withFrameNanos { it }
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val info = state.layoutInfo
        val rows = info.visibleItemsInfo.filter { it.index >= offset }
        val rowPx = rows.map { it.size }.average().toFloat().takeIf { it > 0f } ?: 80f
        val delta = holdRowsPerSec((now - t0) / 1e9f) * rowPx * dt * (if (down) 1f else -1f)
        val moved = state.scrollBy(delta)
        val after = state.layoutInfo
        val centre = (after.viewportStartOffset + after.viewportEndOffset) / 2
        after.visibleItemsInfo.filter { it.index >= offset }
            .minByOrNull { abs(it.offset + it.size / 2 - centre) }
            ?.let { shown.getOrNull(it.index - offset) }
            ?.let { onRow(it.number) }
        if (moved == 0f) return // end of the list
    }
}

/** One focus move at a time: a new ▲▼ press cancels the move still in flight from the previous one. */
private class FocusMover(private val scope: CoroutineScope) {
    private var job: Job? = null
    fun move(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }
}

/**
 * ▲ ▼ move the focus by hand, one row: Compose's own focus search lays out list items beyond the
 * screen one after another and, with thousands of channels, froze the TV for 5 s (ANR, Bravia).
 */
private suspend fun stepFocus(state: LazyListState, idx: Int, down: Boolean, requester: FocusRequester, center: Boolean = false) {
    val visible = state.layoutInfo.visibleItemsInfo
    if (center) {
        // fast steps: the row stays in the middle and the list runs under it, which reads as scrolling, not jumping
        state.scrollToItem((idx - visible.size / 2).coerceAtLeast(0))
    } else if (visible.none { it.index == idx }) {
        // target at the bottom when going down, at the top when going up
        if (down) state.scrollToItem((idx - visible.size + 2).coerceAtLeast(0)) else state.scrollToItem(idx)
    }
    requestWhenAttached(requester)
}

/**
 * ▲ ▼ held on the channel list: after [HOLD_AFTER] auto-repeats (~0.3 s) the list scrolls continuously
 * (see holdScroll), with the highlighted row kept near the middle of the screen. [press] counts repeats:
 * presses faster than [STEP_FAST_MS] apart in the same direction.
 */
private class HoldCounter {
    private var lastAt = 0L
    private var lastDown = false
    private var streak = 0

    fun press(down: Boolean): Int {
        val now = SystemClock.elapsedRealtime()
        streak = if (down == lastDown && now - lastAt < STEP_FAST_MS) streak + 1 else 0
        lastAt = now
        lastDown = down
        return streak
    }

    companion object {
        const val HOLD_AFTER = 6
        private const val STEP_FAST_MS = 250L
    }
}

/** Rows per second while ▲ ▼ is held: from 12 up to 120 over 4 s. 500 channels take about 6 s. */
private fun holdRowsPerSec(heldSec: Float) = 12f + (heldSec / 4f).coerceAtMost(1f) * 108f

@Composable
fun ChannelBrowser(
    vm: TvViewModel,
    pl: Playlist,
    now: Long,
    onClose: () -> Unit,
    onPlay: (index: Int, group: String) -> Unit,
    onArchive: (index: Int, startSec: Long, group: String) -> Unit,
    onSettings: () -> Unit,
) {
    val epg = vm.epg
    val cur = pl.channels.getOrNull(vm.current)
    val initialGroup = remember { vm.effectiveContextGroup() }
    var group by remember { mutableStateOf(initialGroup) }
    // the group under the cursor; applied to `group` after a short pause so fast ▲▼ is not slowed by list rebuilds
    var focusedGroup by remember { mutableStateOf(initialGroup) }
    var enterChannelsTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var guideFor by remember { mutableStateOf<Channel?>(null) }
    var guideOpenTick by remember { mutableIntStateOf(0) }
    var focusNumber by remember { mutableIntStateOf(cur?.number ?: -1) }
    var focusTick by remember { mutableIntStateOf(0) }
    var searchTick by remember { mutableIntStateOf(0) }
    /** Favourites only: number of the channel being moved with ▲▼ (null = not reordering). */
    var moving by remember { mutableStateOf<Int?>(null) }
    var reorderTick by remember { mutableIntStateOf(0) }
    fun contextOf(c: Channel) = if (group == SEARCH) c.group else group

    val groups = remember(pl) { listOf(FAV, ALL, SEARCH) + pl.groups }
    // Favourites are snapshotted when the group is opened, so un-starring does not yank the focused row away.
    // event channels with nothing on air are left out (favourites keep them: chosen by hand)
    val shown = remember(pl, group, query, reorderTick, EventChannels.idle) {
        when (group) {
            FAV -> vm.favoriteChannels()
            ALL -> vm.withoutIdleEvents(pl.channels)
            SEARCH -> {
                val q = query.lowercase().trim()
                if (q.isEmpty()) emptyList() else vm.withoutIdleEvents(pl.channels).filter { q in it.name.lowercase() }.take(300)
            }
            else -> vm.withoutIdleEvents(pl.byGroup[group].orEmpty())
        }
    }
    val guide = remember(guideFor, epg) { guideFor?.let { buildGuide(epg?.programmes(it).orEmpty()) }.orEmpty() }
    fun guideNowIndex() = guide.indexOfFirst { it is GuideRow.Item && now < it.p.stop }

    val groupState = rememberLazyListState()
    val channelState = rememberLazyListState()
    val guideState = rememberLazyListState()
    val groupFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    /** ◀ from a channel goes back to the selected group, not to whatever group row is nearest on screen. */
    fun focusSelectedGroup() {
        focusedGroup = group
        scope.launch { focusIndex(groupState, groups.indexOf(group), groupFocus) }
    }
    val channelFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val holdCounter = remember { HoldCounter() }
    val mover = remember { FocusMover(scope) }
    /** ▲ ▼ held: the channel list scrolls on its own until the key goes up. */
    var holdActive by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
        if (!holdActive) return
        holdActive = false
        // the real focus is still on the row the hold started from: bring it to the highlighted one
        mover.move { requestWhenAttached(channelFocus) }
    }
    /** Guide row that holds guideFocus; -1 = the programme on air. */
    var guideFocusIdx by remember { mutableIntStateOf(-1) }
    val searchFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val channelOffset = if (group == SEARCH) 1 else 0 // the search field is item 0

    // initial focus / refocus a channel row
    LaunchedEffect(focusTick) {
        var idx = shown.indexOfFirst { it.number == focusNumber }
        if (idx < 0 && focusTick > 0 && shown.isNotEmpty()) { idx = 0; focusNumber = shown[0].number; withFrameNanos { } }
        when {
            idx >= 0 -> focusIndex(channelState, idx + channelOffset, channelFocus)
            focusTick == 0 || focusTick > 0 -> {
                groupState.scrollToItem((groups.indexOf(group) - 3).coerceAtLeast(0))
                withFrameNanos { }
                runCatching { groupFocus.requestFocus() }
            }
        }
    }
    LaunchedEffect(focusedGroup) {
        if (focusedGroup != group) {
            delay(300)
            group = focusedGroup
        }
    }
    // ▶ from the group column: go to the playing channel if it is in this group, otherwise the first one
    LaunchedEffect(enterChannelsTick) {
        if (enterChannelsTick == 0) return@LaunchedEffect
        withFrameNanos { }
        val idx = shown.indexOfFirst { it.number - 1 == vm.current }.let { if (it < 0) 0 else it }
        val target = shown.getOrNull(idx) ?: return@LaunchedEffect
        focusNumber = target.number
        withFrameNanos { }
        focusIndex(channelState, idx + channelOffset, channelFocus)
    }
    var firstGroupEffect by remember { mutableStateOf(true) }
    LaunchedEffect(group) {
        if (firstGroupEffect) firstGroupEffect = false else channelState.scrollToItem(0)
    }
    LaunchedEffect(guideFor) {
        guideFocusIdx = -1
        val idx = guideNowIndex()
        if (idx >= 0) guideState.scrollToItem((idx - 2).coerceAtLeast(0))
    }
    LaunchedEffect(guideOpenTick) {
        guideFocusIdx = -1
        if (guideOpenTick > 0) focusIndex(guideState, guideNowIndex(), guideFocus)
    }

    LaunchedEffect(searchTick) {
        if (searchTick > 0) {
            channelState.scrollToItem(0)
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }

    fun closeGuide() {
        focusNumber = guideFor?.number ?: focusNumber
        guideFor = null
        focusTick++
    }

    fun handleBack() {
        if (moving != null) {
            moving = null
        } else if (guideFor != null) {
            closeGuide()
        } else {
            onClose()
        }
    }
    // Fallback when nothing inside is focused
    BackHandler { handleBack() }

    Row(
        Modifier
            .fillMaxHeight()
            // Handle Back here: otherwise Compose first spends the press on clearing focus.
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && e.type == KeyEventType.KeyUp) {
                    stopHold()
                    return@onPreviewKeyEvent false
                }
                if (code != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) handleBack()
                true
            }
            // reached only when no row consumed the key, i.e. the focus got lost (a row scrolled away and
            // was disposed): put it back instead of leaving the remote dead
            .onKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                if (!nav) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) focusTick++
                true
            }
            .background(Brush.horizontalGradient(listOf(Color(0xF7101216), Color(0xEE101216), Color(0xCC101216), Color.Transparent)))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- groups
        if (guideFor == null) {
            LazyColumn(Modifier.width(190.dp).fillMaxHeight(), state = groupState) {
                items(groups) { g ->
                    val label = if (g == FAV) "${groupLabel(g)} (${vm.favorites.size})" else groupLabel(g)
                    TvItem(
                        modifier = if (g == focusedGroup) Modifier.focusRequester(groupFocus) else Modifier,
                        selected = g == focusedGroup,
                        onFocused = { focusedGroup = g },
                        onClick = {
                            focusedGroup = g
                            group = g
                            if (g == SEARCH) searchTick++ else enterChannelsTick++
                        },
                        onKeyDown = { code ->
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    // focusedGroup, not g: a ▲▼ press just before may still be moving the focus
                                    group = focusedGroup
                                    if (focusedGroup == SEARCH) searchTick++ else enterChannelsTick++
                                    true
                                }
                                // nothing to the left; left to Compose, the focus search lays out the whole list (ANR)
                                KeyEvent.KEYCODE_DPAD_LEFT -> true
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    val down = code == KeyEvent.KEYCODE_DPAD_DOWN
                                    // from focusedGroup, not g: it is already updated by an earlier press still in flight
                                    val next = groups.indexOf(focusedGroup).let { if (it < 0) groups.indexOf(g) else it } + if (down) 1 else -1
                                    // past the last group: the ⚙ Settings row (item groups.size of the same column)
                                    if (next == groups.size) mover.move { stepFocus(groupState, groups.size, true, settingsFocus) }
                                    groups.getOrNull(next)?.let { ng ->
                                        focusedGroup = ng
                                        mover.move { stepFocus(groupState, next, down, groupFocus) }
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    ) {
                        Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    TvItem(
                        modifier = Modifier.focusRequester(settingsFocus),
                        onClick = onSettings,
                        onKeyDown = { code ->
                            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                                focusedGroup = groups.last()
                                mover.move { stepFocus(groupState, groups.lastIndex, false, groupFocus) }
                            }
                            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        },
                    ) {
                        Text(T.settings, color = Dim, fontSize = 15.sp)
                    }
                }
            }
        }

        // ---------------- channels
        LazyColumn(Modifier.width(if (guideFor == null) 400.dp else 330.dp).fillMaxHeight(), state = channelState) {
            if (group == SEARCH) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(T.searchPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        when (group) {
                            FAV -> T.favEmpty
                            SEARCH -> if (query.isBlank()) T.searchHint else T.nothingFound
                            else -> T.empty
                        },
                        color = Dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, c -> c.number }) { pos, c ->
                val isCurrent = c.number - 1 == vm.current
                val nowProg = epg?.at(c, now)
                TvItem(
                    modifier = if (c.number == focusNumber) Modifier.focusRequester(channelFocus) else Modifier,
                    selected = isCurrent || c == guideFor,
                    focusedOverride = if (holdActive) c.number == focusNumber else null,
                    onFocused = {
                        focusNumber = c.number
                        if (guideFor != null) guideFor = c
                    },
                    // the row the frame is on (focusNumber): after a hold the real focus can lag a frame behind
                    onClick = {
                        val r = shown.firstOrNull { it.number == focusNumber } ?: c
                        if (moving == r.number) moving = null else onPlay(r.number - 1, contextOf(r))
                    },
                    // Favourites: long OK = move (the Bravia remote has no Menu key), long OK again = remove;
                    // other groups: long OK = add to / remove from favourites
                    onLongClick = {
                        when {
                            moving == c.number -> { vm.toggleFavorite(c); moving = null }
                            moving != null -> Unit
                            group == FAV -> moving = c.number
                            else -> vm.toggleFavorite(c)
                        }
                    },
                    onKeyDown = { code ->
                        if (moving == c.number) {
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (vm.moveFavorite(c, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) {
                                        reorderTick++
                                        focusNumber = c.number
                                        focusTick++
                                    }
                                }
                                KeyEvent.KEYCODE_MENU -> {
                                    // remove from favourites
                                    vm.toggleFavorite(c)
                                    moving = null
                                }
                                KeyEvent.KEYCODE_BACK -> moving = null
                            }
                            return@TvItem true // ◀ ▶ and the rest are ignored while moving
                        }
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { focusSelectedGroup(); true }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val down = code == KeyEvent.KEYCODE_DPAD_DOWN
                                if (holdActive) return@TvItem true // auto-repeats while the list scrolls on its own
                                if (holdCounter.press(down) >= HoldCounter.HOLD_AFTER && moving == null) {
                                    holdActive = true
                                    holdJob = scope.launch { holdScroll(channelState, channelOffset, shown, down) { focusNumber = it } }
                                    return@TvItem true
                                }
                                // from focusNumber, not pos: earlier presses may not have moved the focus yet
                                val from = shown.indexOfFirst { it.number == focusNumber }.let { if (it < 0) pos else it }
                                val next = from + if (down) 1 else -1
                                val target = shown.getOrNull(next)
                                when {
                                    target != null -> {
                                        focusNumber = target.number
                                        mover.move { stepFocus(channelState, next + channelOffset, down, channelFocus) }
                                    }
                                    !down && group == SEARCH -> scope.launch { runCatching { searchFocus.requestFocus() } }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                                if (group == FAV) moving = c.number else vm.toggleFavorite(c)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                // always consumed: a default focus search to the right froze the TV for 5 s (ANR)
                                val r = shown.firstOrNull { it.number == focusNumber } ?: c
                                if (epg != null && epg.programmes(r).isNotEmpty()) {
                                    guideFor = r
                                    guideOpenTick++
                                }
                                true
                            }
                            else -> false
                        }
                    },
                ) {
                    ChannelRow(c, if (group == SEARCH) c.number else pos + 1, nowProg, now, vm.isFavorite(c), epg?.icon(c) ?: c.logo,
                        moving = moving == c.number)
                }
            }
            if (group == FAV && shown.isNotEmpty()) {
                item {
                    Text(
                        if (moving != null) T.favMoveHint
                        else T.favHint,
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // ---------------- programme guide of one channel
        val gc = guideFor
        if (gc != null) {
            val idx = gc.number - 1
            val archiveFrom = now - gc.catchupDays * 86_400_000L + 60_000
            val nowIdx = guideNowIndex()
            LazyColumn(Modifier.width(430.dp).fillMaxHeight(), state = guideState) {
                itemsIndexed(guide) { i, row ->
                    when (row) {
                        is GuideRow.Header -> Text(
                            row.label, color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp),
                        )
                        is GuideRow.Item -> {
                            val p = row.p
                            val isNow = p.start <= now && now < p.stop
                            val inArchive = gc.hasArchive && p.start < now && p.start >= archiveFrom
                            TvItem(
                                modifier = if (i == (if (guideFocusIdx >= 0) guideFocusIdx else nowIdx)) Modifier.focusRequester(guideFocus) else Modifier,
                                selected = isNow,
                                onClick = {
                                    when {
                                        inArchive -> onArchive(idx, p.start / 1000, contextOf(gc))
                                        isNow -> onPlay(idx, contextOf(gc))
                                    }
                                },
                                // ◀ = back to the channel, explicitly: Compose's own focus search into the long channel list hangs
                                onKeyDown = { code ->
                                    when (code) {
                                        KeyEvent.KEYCODE_DPAD_LEFT -> { closeGuide(); true }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            val down = code == KeyEvent.KEYCODE_DPAD_DOWN
                                            // next programme row, skipping the day headers
                                            val from = if (guideFocusIdx >= 0) guideFocusIdx else i
                                            val range = if (down) (from + 1..guide.lastIndex) else (from - 1 downTo 0)
                                            range.firstOrNull { guide[it] is GuideRow.Item }?.let { next ->
                                                guideFocusIdx = next
                                                mover.move { stepFocus(guideState, if (!down && next > 0 && guide[next - 1] is GuideRow.Header) next - 1 else next, down, guideFocus) }
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            ) { focused ->
                                GuideItem(p, isNow, inArchive, p.stop <= now, focused)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(c: Channel, num: Int, nowProg: Programme?, now: Long, fav: Boolean, logo: String?, moving: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (moving) Text("⇅", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        else Text("$num", color = Dim, fontSize = 12.sp, modifier = Modifier.width(38.dp))
        Logo(logo, Modifier.size(width = 46.dp, height = 30.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowProg != null) {
                Text(nowProg.title, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val frac = ((now - nowProg.start).toFloat() / (nowProg.stop - nowProg.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac },
                    color = Color(0xCCFFFFFF),
                    trackColor = Color(0x33FFFFFF),
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth().height(2.dp),
                )
            }
        }
        if (fav) Text(" ★", color = Gold, fontSize = 14.sp)
        if (c.hasArchive) Text(" ⟲", color = Dim, fontSize = 14.sp)
    }
}

@Composable
private fun GuideItem(p: Programme, isNow: Boolean, inArchive: Boolean, isPast: Boolean, focused: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hhmm(p.start),
                color = if (isPast && !inArchive && !focused) Color(0x88FFFFFF) else Color.White,
                fontSize = 14.sp, modifier = Modifier.width(48.dp),
            )
            Text(
                p.title.ifBlank { "—" },
                color = if (isPast && !inArchive && !focused) Color(0x88FFFFFF) else Color.White,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (isNow) Text(" ●", color = Color(0xFFFF5252), fontSize = 12.sp)
            if (inArchive) Text(" ⟲", color = if (focused) Color.White else Gold, fontSize = 14.sp)
        }
        if (focused) {
            p.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xDDFFFFFF), fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 48.dp, top = 2.dp))
            }
            Text(
                when {
                    isNow && inArchive -> T.guideNowArchive
                    isNow -> T.guideNow
                    inArchive -> T.guideArchive
                    isPast -> T.guideNotInArchive
                    else -> T.guideNotStarted
                },
                color = Color(0xCCFFFFFF), fontSize = 11.sp, modifier = Modifier.padding(start = 48.dp, top = 2.dp),
            )
        }
    }
}
