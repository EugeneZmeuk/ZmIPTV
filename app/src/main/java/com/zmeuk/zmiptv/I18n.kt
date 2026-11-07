package com.zmeuk.zmiptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** UI languages. English is the default. */
enum class Lang(val code: String, val title: String, val strings: Strings) {
    EN("en", "English", En),
    RU("ru", "Русский", Ru),
    PL("pl", "Polski", Pl),
    BE("be", "Беларуская", Be),
    UK("uk", "Українська", Uk);

    val locale: Locale get() = Locale(code)

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** Current UI language; a Compose state, so the whole UI switches at once. */
object I18n {
    var lang by mutableStateOf(Lang.EN)
}

/** Strings of the current language. Reading it inside a composable subscribes to language changes. */
val T: Strings get() = I18n.lang.strings

/** Display name of a group key (internal keys stay the same in every language). */
fun groupLabel(key: String): String = when (key) {
    GROUP_FAV -> T.groupFav
    GROUP_ALL -> T.groupAll
    GROUP_SEARCH -> T.groupSearch
    M3uParser.NO_GROUP -> T.noGroup
    else -> key
}

interface Strings {
    // settings
    val settingsTitle: String
    val playlistUrl: String
    val epgUrl: String
    val save: String
    val reloadAll: String
    fun buffer(level: Int): String
    fun keyUp(inverted: Boolean): String
    /** 0 = lower quality allowed, 1 = same quality or better, 2 = off (see Prefs.backupMode). */
    fun backupMode(mode: Int): String
    fun stats(channels: Int, groups: Int, favorites: Int): String
    /** Settings → full list of remote buttons (RemoteHelp.kt). */
    val helpButton: String
    val helpTitle: String
    val helpFooter: String
    /** Settings → enter the playlist from a phone (QrInput.kt); the qrPage* strings are shown on the phone. */
    val qrButton: String
    val qrTitle: String
    val qrSteps: String
    val qrManual: String
    val qrNoNetwork: String
    val qrFooter: String
    val qrPagePlaylist: String
    val qrPageEpg: String
    val qrPageSend: String
    val qrPageDone: String
    val qrPageBad: String
    val help: List<HelpSection>

    // channel list
    val settings: String
    val searchPlaceholder: String
    val favEmpty: String
    val searchHint: String
    val nothingFound: String
    val empty: String
    val favMoveHint: String
    val favHint: String
    val guideNowArchive: String
    val guideNow: String
    val guideArchive: String
    val guideNotInArchive: String
    val guideNotStarted: String
    val groupFav: String
    val groupAll: String
    val groupSearch: String
    val noGroup: String

    // player: errors and backup streams
    val reasonSlow: String
    val reasonStuck: String
    val reasonDown: String
    val reasonNoPicture: String
    val reasonJerky: String
    fun backup(from: String, reason: String, to: String): String
    fun streamUnavailable(code: String): String
    fun playbackError(code: String, n: Int, total: Int, delayS: Int): String

    // loading
    val loadingPlaylist: String
    val playlistEmpty: String
    fun playlistFailed(msg: String): String
    fun showingCached(msg: String): String
    val epgNoUrl: String
    val epgLoading: String
    fun epgLoaded(n: Int, total: Int): String
    fun epgFailed(msg: String): String

    // player screen
    val noArchive: String
    val live: String
    fun noChannel(digits: String): String
    val pressBackAgain: String
    val archiveUnavailable: String
    fun archiveBadge(time: String): String
    fun archiveDays(n: Int): String
    fun seekingTo(time: String): String
    fun pausedAt(time: String): String
    fun archivePos(time: String, minBehind: Long): String
    val epgNotLoaded: String
    fun next(time: String, title: String): String
    val hintsArchive: String
    val hintsLive: String
    /** Live on a channel with archive. */
    val hintsLiveArchive: String

    // audio tracks
    val audioTitle: String
    fun audioTrack(n: Int): String
    val audioOnlyOne: String
    fun audioHint(n: Int): String
}

object En : Strings {
    override val audioTitle = "🔊 Audio track"
    override fun audioTrack(n: Int) = "Track $n"
    override val audioOnlyOne = "This channel has only one audio track"
    override fun audioHint(n: Int) = " · long OK — audio ($n)"
    override fun backupMode(mode: Int) = when (mode) {
        1 -> "Backup stream: same quality or better only"
        2 -> "Backup stream: off (the channel never changes by itself)"
        else -> "Backup stream: lower quality allowed (FHD → HD)"
    }
    override val settingsTitle = "ZmIPTV — settings"
    override val playlistUrl = "Playlist URL (M3U / M3U8)"
    override val epgUrl = "TV guide URL, XMLTV (empty — taken from the playlist)"
    override val save = "Save"
    override val reloadAll = "Reload all"
    override fun buffer(level: Int) = when (level) {
        1 -> "Live buffer: larger (≈40 s behind live)"
        2 -> "Live buffer: maximum (≈45 s behind live)"
        else -> "Live buffer: normal (≈30 s behind live)"
    }
    override fun keyUp(inverted: Boolean) = if (inverted) "▲ button — next channel" else "▲ button — previous channel"
    override fun stats(channels: Int, groups: Int, favorites: Int) = "Channels: $channels, groups: $groups, favourites: $favorites"
    override val helpButton = "🎮  Remote control buttons"
    override val helpTitle = "Remote control buttons"
    override val helpFooter = "▲ ▼ — scroll · Back — close"
    override val qrButton = "📱  Enter from a phone (QR code)"
    override val qrTitle = "Enter the playlist from a phone"
    override val qrSteps = "Connect the phone to the same network as the TV, scan the code, paste the links and send them."
    override val qrManual = "Or open this address in the phone browser:"
    override val qrNoNetwork = "The TV is not connected to a local network."
    override val qrFooter = "Back — close"
    override val qrPagePlaylist = "Playlist URL"
    override val qrPageEpg = "TV guide URL, XMLTV (optional; empty keeps the current one)"
    override val qrPageSend = "Send to TV"
    override val qrPageDone = "Sent to the TV. You can close this page."
    override val qrPageBad = "Enter a link starting with http:// or https://."
    override val help = listOf(
        HelpSection(
            "Watching live",
            "▲ / ▼" to "previous / next channel of the current group (direction: setting «▲ button»)",
            "CH+ / CH−" to "next / previous channel",
            "OK, ◀" to "channel list",
            "hold ◀" to "rewind 30 s back (channels with archive ⟲); keep holding — faster",
            "▶, Info" to "channel panel: programme now and next",
            "hold OK, Audio" to "audio track of the channel (remembered for the channel)",
            "⏯" to "pause; play goes on from the same moment (channels with archive)",
            "⏪ / ⏩" to "rewind / forward 5 min",
            "0–9" to "channel by its number in the current group",
            "Back" to "hide the panel; twice — exit",
        ),
        HelpSection(
            "Archive and pause",
            "OK, ⏯" to "pause / play",
            "◀ / ▶" to "30 s back / forward; hold — faster",
            "⏪ / ⏩" to "5 min back / forward",
            "hold OK" to "audio track",
            "Back" to "back to live; then ◀ — channel list",
        ),
        HelpSection(
            "Channel list",
            "▲ / ▼" to "groups and channels; hold — faster (★ Favourites, All channels, Search, provider groups, ⚙ Settings at the end)",
            "▶ on a group, OK" to "open the group",
            "◀ on a channel" to "back to the groups",
            "OK on a channel" to "watch",
            "hold OK" to "add to / remove from ★ Favourites",
            "▶ on a channel" to "TV guide of the channel",
            "Back" to "close the list",
        ),
        HelpSection(
            "★ Favourites",
            "hold OK on a channel" to "change the order: ⇅ appears",
            "▲ / ▼" to "move the channel",
            "OK or Back" to "done",
            "hold OK again" to "remove from favourites",
        ),
        HelpSection(
            "TV guide",
            "▲ / ▼" to "programmes and days",
            "OK on a past programme ⟲" to "watch it from the archive",
            "OK on the current one" to "watch from its start (with archive) or live",
            "◀, Back" to "back to the channels",
        ),
        HelpSection(
            "Settings",
            "OK on a field" to "edit the address; OK on the keyboard — done",
            "OK on an item" to "change the value",
            "Back" to "close the settings",
        ),
    )

    override val settings = "⚙  Settings"
    override val searchPlaceholder = "Channel name"
    override val favEmpty = "No favourites yet.\nLong-press OK or press Menu on a channel to add it."
    override val searchHint = "Press OK on the field and type a name"
    override val nothingFound = "Nothing found"
    override val empty = "Empty"
    override val favMoveHint = "▲ ▼ — move · OK or Back — done · long OK — remove from favourites"
    override val favHint = "long OK on a channel — change order"
    override val guideNowArchive = "OK — watch from the start"
    override val guideNow = "OK — watch"
    override val guideArchive = "OK — watch from the archive"
    override val guideNotInArchive = "Not in the archive"
    override val guideNotStarted = "Not started yet"
    override val groupFav = "★ Favourites"
    override val groupAll = "All channels"
    override val groupSearch = "🔍 Search"
    override val noGroup = "No group"

    override val reasonSlow = "is too slow"
    override val reasonStuck = "froze"
    override val reasonDown = "is unavailable"
    override val reasonNoPicture = "shows no picture on this TV"
    override val reasonJerky = "stutters"
    override fun backup(from: String, reason: String, to: String) = "\"$from\" $reason — switching to \"$to\""
    override fun streamUnavailable(code: String) = "Stream unavailable ($code).\nChoose another channel."
    override fun playbackError(code: String, n: Int, total: Int, delayS: Int) =
        "Playback error ($code). Retry $n of $total in $delayS s…"

    override val loadingPlaylist = "Loading the playlist…"
    override val playlistEmpty = "the playlist has no channels"
    override fun playlistFailed(msg: String) = "Couldn't load the playlist: $msg"
    override fun showingCached(msg: String) = "$msg (showing the saved one)"
    override val epgNoUrl = "TV guide URL is not set"
    override val epgLoading = "Loading the TV guide…"
    override fun epgLoaded(n: Int, total: Int) = "TV guide: $n of $total channels"
    override fun epgFailed(msg: String) = "Couldn't load the TV guide: $msg"

    override val noArchive = "This channel has no archive"
    override val live = "Live"
    override fun noChannel(digits: String) = "No channel $digits in this list"
    override val pressBackAgain = "Press Back again to exit"
    override val archiveUnavailable = "The archive is not available for this time.\n◀ ▶ — another time · Back — live"
    override fun archiveBadge(time: String) = "⟲ ARCHIVE  $time"
    override fun archiveDays(n: Int) = " · archive: $n " + if (n == 1) "day" else "days"
    override fun seekingTo(time: String) = "⏩ Seeking to $time"
    override fun pausedAt(time: String) = "❚❚ Paused · $time"
    override fun archivePos(time: String, minBehind: Long) = "⟲ Archive · $time · $minBehind min behind live"
    override val epgNotLoaded = "TV guide not loaded"
    override fun next(time: String, title: String) = "Next $time  $title"
    override val hintsArchive = "OK — pause · ◀ ▶ — ±30 s · ⏪ ⏩ — ±5 min · Back — live, then ◀ — channel list"
    override val hintsLive = "▲▼ — channels · OK / ◀ — channel list · ▶ — info · Back — hide"
    override val hintsLiveArchive = "▲▼ — channels · OK / ◀ — channel list · hold ◀ ▶ — rewind · ⏯ — pause · Back — hide"
}

object Ru : Strings {
    override val audioTitle = "🔊 Звуковая дорожка"
    override fun audioTrack(n: Int) = "Дорожка $n"
    override val audioOnlyOne = "У этого канала одна звуковая дорожка"
    override fun audioHint(n: Int) = " · долгое OK — звук ($n)"
    override fun backupMode(mode: Int) = when (mode) {
        1 -> "Запасной поток: только того же качества или лучше"
        2 -> "Запасной поток: выключен (канал сам не меняется)"
        else -> "Запасной поток: можно хуже качеством (FHD → HD)"
    }
    override val settingsTitle = "ZmIPTV — настройки"
    override val playlistUrl = "Ссылка на плейлист (M3U / M3U8)"
    override val epgUrl = "Ссылка на телепрограмму XMLTV (пусто — из плейлиста)"
    override val save = "Сохранить"
    override val reloadAll = "Обновить всё"
    override fun buffer(level: Int) = when (level) {
        1 -> "Буфер эфира: увеличенный (≈40 с от эфира)"
        2 -> "Буфер эфира: максимальный (≈45 с от эфира)"
        else -> "Буфер эфира: обычный (≈30 с от эфира)"
    }
    override fun keyUp(inverted: Boolean) = if (inverted) "Кнопка ▲ — следующий канал" else "Кнопка ▲ — предыдущий канал"
    override fun stats(channels: Int, groups: Int, favorites: Int) = "Каналов: $channels, групп: $groups, в избранном: $favorites"
    override val helpButton = "🎮  Кнопки пульта"
    override val helpTitle = "Кнопки пульта"
    override val helpFooter = "▲ ▼ — прокрутка · Назад — закрыть"
    override val qrButton = "📱  Ввести с телефона (QR-код)"
    override val qrTitle = "Ввод плейлиста с телефона"
    override val qrSteps = "Подключите телефон к той же сети, что и телевизор, отсканируйте код, вставьте ссылки и отправьте."
    override val qrManual = "Или откройте этот адрес в браузере телефона:"
    override val qrNoNetwork = "Телевизор не подключён к локальной сети."
    override val qrFooter = "Назад — закрыть"
    override val qrPagePlaylist = "Ссылка на плейлист"
    override val qrPageEpg = "Ссылка на телепрограмму XMLTV (необязательно; если пусто, останется текущая)"
    override val qrPageSend = "Отправить на телевизор"
    override val qrPageDone = "Отправлено на телевизор. Эту страницу можно закрыть."
    override val qrPageBad = "Введите ссылку, которая начинается с http:// или https://."
    override val help = listOf(
        HelpSection(
            "Просмотр эфира",
            "▲ / ▼" to "предыдущий / следующий канал текущей группы (направление — настройка «Кнопка ▲»)",
            "CH+ / CH−" to "следующий / предыдущий канал",
            "OK, ◀" to "список каналов",
            "удерживать ◀" to "перемотка на 30 с назад (каналы с архивом ⟲); держать дольше — быстрее",
            "▶, Info" to "панель канала: что идёт сейчас и дальше",
            "удерживать OK, Audio" to "звуковая дорожка канала (запоминается для канала)",
            "⏯" to "пауза; продолжение с того же места (каналы с архивом)",
            "⏪ / ⏩" to "перемотка на 5 мин назад / вперёд",
            "0–9" to "канал по номеру в текущей группе",
            "Назад" to "скрыть панель; дважды — выход",
        ),
        HelpSection(
            "Архив и пауза",
            "OK, ⏯" to "пауза / продолжить",
            "◀ / ▶" to "30 с назад / вперёд; удерживать — быстрее",
            "⏪ / ⏩" to "5 мин назад / вперёд",
            "удерживать OK" to "звуковая дорожка",
            "Назад" to "в прямой эфир; затем ◀ — список каналов",
        ),
        HelpSection(
            "Список каналов",
            "▲ / ▼" to "группы и каналы; удерживать — быстрее (★ Избранное, Все каналы, Поиск, группы провайдера, в конце ⚙ Настройки)",
            "▶ на группе, OK" to "открыть группу",
            "◀ на канале" to "назад к группам",
            "OK на канале" to "смотреть",
            "удерживать OK" to "добавить в ★ Избранное / убрать",
            "▶ на канале" to "телепрограмма канала",
            "Назад" to "закрыть список",
        ),
        HelpSection(
            "★ Избранное",
            "удерживать OK на канале" to "изменить порядок: появится ⇅",
            "▲ / ▼" to "передвинуть канал",
            "OK или Назад" to "готово",
            "ещё раз удерживать OK" to "убрать из избранного",
        ),
        HelpSection(
            "Телепрограмма",
            "▲ / ▼" to "передачи и дни",
            "OK на прошедшей передаче ⟲" to "смотреть из архива",
            "OK на текущей" to "смотреть с начала (если есть архив) или эфир",
            "◀, Назад" to "назад к каналам",
        ),
        HelpSection(
            "Настройки",
            "OK на поле" to "изменить адрес; OK на клавиатуре — готово",
            "OK на пункте" to "сменить значение",
            "Назад" to "закрыть настройки",
        ),
    )

    override val settings = "⚙  Настройки"
    override val searchPlaceholder = "Название канала"
    override val favEmpty = "Избранного пока нет.\nДолгое нажатие OK или кнопка «Меню» на канале — добавить."
    override val searchHint = "Нажмите OK на поле и введите название"
    override val nothingFound = "Ничего не найдено"
    override val empty = "Пусто"
    override val favMoveHint = "▲ ▼ — переместить · OK или Назад — готово · долгое OK — убрать из избранного"
    override val favHint = "долгое OK на канале — изменить порядок"
    override val guideNowArchive = "OK — смотреть с начала"
    override val guideNow = "OK — смотреть"
    override val guideArchive = "OK — смотреть из архива"
    override val guideNotInArchive = "Нет в архиве"
    override val guideNotStarted = "Ещё не началась"
    override val groupFav = "★ Избранное"
    override val groupAll = "Все каналы"
    override val groupSearch = "🔍 Поиск"
    override val noGroup = "Без группы"

    override val reasonSlow = "тормозит"
    override val reasonStuck = "завис"
    override val reasonDown = "недоступен"
    override val reasonNoPicture = "не показывает картинку на этом ТВ"
    override val reasonJerky = "дёргается"
    override fun backup(from: String, reason: String, to: String) = "«$from» $reason — включаю «$to»"
    override fun streamUnavailable(code: String) = "Поток недоступен ($code).\nВыберите другой канал."
    override fun playbackError(code: String, n: Int, total: Int, delayS: Int) =
        "Ошибка воспроизведения ($code). Повтор $n из $total через $delayS с…"

    override val loadingPlaylist = "Загружаю плейлист…"
    override val playlistEmpty = "в плейлисте нет каналов"
    override fun playlistFailed(msg: String) = "Не удалось загрузить плейлист: $msg"
    override fun showingCached(msg: String) = "$msg (показан сохранённый)"
    override val epgNoUrl = "Ссылка на телепрограмму не задана"
    override val epgLoading = "Загружаю телепрограмму…"
    override fun epgLoaded(n: Int, total: Int) = "Телепрограмма: $n из $total каналов"
    override fun epgFailed(msg: String) = "Не удалось загрузить телепрограмму: $msg"

    override val noArchive = "У этого канала нет архива"
    override val live = "Прямой эфир"
    override fun noChannel(digits: String) = "Нет канала $digits в этом списке"
    override val pressBackAgain = "Нажмите «Назад» ещё раз для выхода"
    override val archiveUnavailable = "Архив за это время недоступен.\n◀ ▶ — другое время · Назад — прямой эфир"
    override fun archiveBadge(time: String) = "⟲ АРХИВ  $time"
    override fun archiveDays(n: Int) = " · архив $n дн."
    override fun seekingTo(time: String) = "⏩ Перемотка к $time"
    override fun pausedAt(time: String) = "❚❚ Пауза · $time"
    override fun archivePos(time: String, minBehind: Long) = "⟲ Архив · $time · −$minBehind мин от эфира"
    override val epgNotLoaded = "Телепрограмма не загружена"
    override fun next(time: String, title: String) = "Далее $time  $title"
    override val hintsArchive = "OK — пауза · ◀ ▶ — ±30 с · ⏪ ⏩ — ±5 мин · Назад — в эфир, затем ◀ — список каналов"
    override val hintsLive = "▲▼ — каналы · OK / ◀ — список каналов · ▶ — инфо · Назад — скрыть"
    override val hintsLiveArchive = "▲▼ — каналы · OK / ◀ — список каналов · удерживать ◀ ▶ — перемотка · ⏯ — пауза · Назад — скрыть"
}

object Pl : Strings {
    override val audioTitle = "🔊 Ścieżka dźwiękowa"
    override fun audioTrack(n: Int) = "Ścieżka $n"
    override val audioOnlyOne = "Ten kanał ma tylko jedną ścieżkę dźwiękową"
    override fun audioHint(n: Int) = " · długie OK — dźwięk ($n)"
    override fun backupMode(mode: Int) = when (mode) {
        1 -> "Strumień zapasowy: tylko ta sama lub lepsza jakość"
        2 -> "Strumień zapasowy: wyłączony (kanał nie zmienia się sam)"
        else -> "Strumień zapasowy: dozwolona niższa jakość (FHD → HD)"
    }
    override val settingsTitle = "ZmIPTV — ustawienia"
    override val playlistUrl = "Adres playlisty (M3U / M3U8)"
    override val epgUrl = "Adres programu TV, XMLTV (puste — z playlisty)"
    override val save = "Zapisz"
    override val reloadAll = "Odśwież wszystko"
    override fun buffer(level: Int) = when (level) {
        1 -> "Bufor na żywo: zwiększony (≈40 s opóźnienia)"
        2 -> "Bufor na żywo: maksymalny (≈45 s opóźnienia)"
        else -> "Bufor na żywo: normalny (≈30 s opóźnienia)"
    }
    override fun keyUp(inverted: Boolean) = if (inverted) "Przycisk ▲ — następny kanał" else "Przycisk ▲ — poprzedni kanał"
    override fun stats(channels: Int, groups: Int, favorites: Int) = "Kanały: $channels, grupy: $groups, ulubione: $favorites"
    override val helpButton = "🎮  Przyciski pilota"
    override val helpTitle = "Przyciski pilota"
    override val helpFooter = "▲ ▼ — przewijanie · Wstecz — zamknij"
    override val qrButton = "📱  Wpisz z telefonu (kod QR)"
    override val qrTitle = "Wpisywanie playlisty z telefonu"
    override val qrSteps = "Połącz telefon z tą samą siecią co telewizor, zeskanuj kod, wklej adresy i wyślij."
    override val qrManual = "Albo otwórz ten adres w przeglądarce telefonu:"
    override val qrNoNetwork = "Telewizor nie jest połączony z siecią lokalną."
    override val qrFooter = "Wstecz — zamknij"
    override val qrPagePlaylist = "Adres playlisty"
    override val qrPageEpg = "Adres programu TV, XMLTV (opcjonalnie; puste pole zachowa obecny)"
    override val qrPageSend = "Wyślij na telewizor"
    override val qrPageDone = "Wysłano na telewizor. Możesz zamknąć tę stronę."
    override val qrPageBad = "Wpisz adres zaczynający się od http:// lub https://."
    override val help = listOf(
        HelpSection(
            "Oglądanie na żywo",
            "▲ / ▼" to "poprzedni / następny kanał bieżącej grupy (kierunek — ustawienie «Przycisk ▲»)",
            "CH+ / CH−" to "następny / poprzedni kanał",
            "OK, ◀" to "lista kanałów",
            "przytrzymaj ◀" to "cofnięcie o 30 s (kanały z archiwum ⟲); dłużej — szybciej",
            "▶, Info" to "panel kanału: co teraz i co dalej",
            "przytrzymaj OK, Audio" to "ścieżka dźwiękowa kanału (zapamiętywana dla kanału)",
            "⏯" to "pauza; wznowienie od tego samego miejsca (kanały z archiwum)",
            "⏪ / ⏩" to "5 min wstecz / do przodu",
            "0–9" to "kanał według numeru w bieżącej grupie",
            "Wstecz" to "ukryj panel; dwa razy — wyjście",
        ),
        HelpSection(
            "Archiwum i pauza",
            "OK, ⏯" to "pauza / wznów",
            "◀ / ▶" to "30 s wstecz / do przodu; przytrzymaj — szybciej",
            "⏪ / ⏩" to "5 min wstecz / do przodu",
            "przytrzymaj OK" to "ścieżka dźwiękowa",
            "Wstecz" to "na żywo; potem ◀ — lista kanałów",
        ),
        HelpSection(
            "Lista kanałów",
            "▲ / ▼" to "grupy i kanały; przytrzymaj — szybciej (★ Ulubione, Wszystkie, Szukaj, grupy dostawcy, na końcu ⚙ Ustawienia)",
            "▶ na grupie, OK" to "otwórz grupę",
            "◀ na kanale" to "wróć do grup",
            "OK na kanale" to "oglądaj",
            "przytrzymaj OK" to "dodaj do ★ Ulubionych / usuń",
            "▶ na kanale" to "program TV kanału",
            "Wstecz" to "zamknij listę",
        ),
        HelpSection(
            "★ Ulubione",
            "przytrzymaj OK na kanale" to "zmień kolejność: pojawi się ⇅",
            "▲ / ▼" to "przesuń kanał",
            "OK lub Wstecz" to "gotowe",
            "ponownie przytrzymaj OK" to "usuń z ulubionych",
        ),
        HelpSection(
            "Program TV",
            "▲ / ▼" to "audycje i dni",
            "OK na minionej audycji ⟲" to "oglądaj z archiwum",
            "OK na bieżącej" to "oglądaj od początku (z archiwum) lub na żywo",
            "◀, Wstecz" to "wróć do kanałów",
        ),
        HelpSection(
            "Ustawienia",
            "OK na polu" to "edytuj adres; OK na klawiaturze — gotowe",
            "OK na pozycji" to "zmień wartość",
            "Wstecz" to "zamknij ustawienia",
        ),
    )

    override val settings = "⚙  Ustawienia"
    override val searchPlaceholder = "Nazwa kanału"
    override val favEmpty = "Brak ulubionych.\nPrzytrzymaj OK lub naciśnij Menu na kanale, aby go dodać."
    override val searchHint = "Naciśnij OK na polu i wpisz nazwę"
    override val nothingFound = "Nic nie znaleziono"
    override val empty = "Pusto"
    override val favMoveHint = "▲ ▼ — przesuń · OK lub Wstecz — gotowe · długie OK — usuń z ulubionych"
    override val favHint = "długie OK na kanale — zmień kolejność"
    override val guideNowArchive = "OK — oglądaj od początku"
    override val guideNow = "OK — oglądaj"
    override val guideArchive = "OK — oglądaj z archiwum"
    override val guideNotInArchive = "Brak w archiwum"
    override val guideNotStarted = "Jeszcze się nie zaczął"
    override val groupFav = "★ Ulubione"
    override val groupAll = "Wszystkie kanały"
    override val groupSearch = "🔍 Szukaj"
    override val noGroup = "Bez grupy"

    override val reasonSlow = "działa zbyt wolno"
    override val reasonStuck = "zawiesił się"
    override val reasonDown = "jest niedostępny"
    override val reasonNoPicture = "nie pokazuje obrazu na tym TV"
    override val reasonJerky = "zacina się"
    override fun backup(from: String, reason: String, to: String) = "„$from” $reason — włączam „$to”"
    override fun streamUnavailable(code: String) = "Strumień niedostępny ($code).\nWybierz inny kanał."
    override fun playbackError(code: String, n: Int, total: Int, delayS: Int) =
        "Błąd odtwarzania ($code). Ponowna próba $n z $total za $delayS s…"

    override val loadingPlaylist = "Wczytuję playlistę…"
    override val playlistEmpty = "playlista nie zawiera kanałów"
    override fun playlistFailed(msg: String) = "Nie udało się wczytać playlisty: $msg"
    override fun showingCached(msg: String) = "$msg (pokazano zapisaną)"
    override val epgNoUrl = "Nie podano adresu programu TV"
    override val epgLoading = "Wczytuję program TV…"
    override fun epgLoaded(n: Int, total: Int) = "Program TV: $n z $total kanałów"
    override fun epgFailed(msg: String) = "Nie udało się wczytać programu TV: $msg"

    override val noArchive = "Ten kanał nie ma archiwum"
    override val live = "Na żywo"
    override fun noChannel(digits: String) = "Brak kanału $digits na tej liście"
    override val pressBackAgain = "Naciśnij Wstecz jeszcze raz, aby wyjść"
    override val archiveUnavailable = "Archiwum z tego czasu jest niedostępne.\n◀ ▶ — inny czas · Wstecz — na żywo"
    override fun archiveBadge(time: String) = "⟲ ARCHIWUM  $time"
    override fun archiveDays(n: Int) = " · archiwum: $n " + if (n == 1) "dzień" else "dni"
    override fun seekingTo(time: String) = "⏩ Przewijanie do $time"
    override fun pausedAt(time: String) = "❚❚ Pauza · $time"
    override fun archivePos(time: String, minBehind: Long) = "⟲ Archiwum · $time · $minBehind min za transmisją na żywo"
    override val epgNotLoaded = "Program TV nie został wczytany"
    override fun next(time: String, title: String) = "Następnie $time  $title"
    override val hintsArchive = "OK — pauza · ◀ ▶ — ±30 s · ⏪ ⏩ — ±5 min · Wstecz — na żywo, potem ◀ — lista kanałów"
    override val hintsLive = "▲▼ — kanały · OK / ◀ — lista kanałów · ▶ — informacje · Wstecz — ukryj"
    override val hintsLiveArchive = "▲▼ — kanały · OK / ◀ — lista kanałów · przytrzymaj ◀ ▶ — przewijanie · ⏯ — pauza · Wstecz — ukryj"
}

object Be : Strings {
    override val audioTitle = "🔊 Гукавая дарожка"
    override fun audioTrack(n: Int) = "Дарожка $n"
    override val audioOnlyOne = "У гэтага канала адна гукавая дарожка"
    override fun audioHint(n: Int) = " · доўгае OK — гук ($n)"
    override fun backupMode(mode: Int) = when (mode) {
        1 -> "Запасны паток: толькі той жа якасці або лепшай"
        2 -> "Запасны паток: выключаны (канал сам не мяняецца)"
        else -> "Запасны паток: можна горшай якасці (FHD → HD)"
    }
    override val settingsTitle = "ZmIPTV — налады"
    override val playlistUrl = "Спасылка на плэйліст (M3U / M3U8)"
    override val epgUrl = "Спасылка на тэлепраграму XMLTV (пуста — з плэйліста)"
    override val save = "Захаваць"
    override val reloadAll = "Абнавіць усё"
    override fun buffer(level: Int) = when (level) {
        1 -> "Буфер эфіру: павялічаны (≈40 с ад эфіру)"
        2 -> "Буфер эфіру: максімальны (≈45 с ад эфіру)"
        else -> "Буфер эфіру: звычайны (≈30 с ад эфіру)"
    }
    override fun keyUp(inverted: Boolean) = if (inverted) "Кнопка ▲ — наступны канал" else "Кнопка ▲ — папярэдні канал"
    override fun stats(channels: Int, groups: Int, favorites: Int) = "Каналаў: $channels, груп: $groups, у абраным: $favorites"
    override val helpButton = "🎮  Кнопкі пульта"
    override val helpTitle = "Кнопкі пульта"
    override val helpFooter = "▲ ▼ — пракрутка · Назад — закрыць"
    override val qrButton = "📱  Увесці з тэлефона (QR-код)"
    override val qrTitle = "Увод плэйліста з тэлефона"
    override val qrSteps = "Падключыце тэлефон да той жа сеткі, што і тэлевізар, адсканіруйце код, устаўце спасылкі і адпраўце."
    override val qrManual = "Або адкрыйце гэты адрас у браўзеры тэлефона:"
    override val qrNoNetwork = "Тэлевізар не падключаны да лакальнай сеткі."
    override val qrFooter = "Назад — закрыць"
    override val qrPagePlaylist = "Спасылка на плэйліст"
    override val qrPageEpg = "Спасылка на тэлепраграму XMLTV (неабавязкова; калі пуста, застанецца бягучая)"
    override val qrPageSend = "Адправіць на тэлевізар"
    override val qrPageDone = "Адпраўлена на тэлевізар. Гэту старонку можна закрыць."
    override val qrPageBad = "Увядзіце спасылку, якая пачынаецца з http:// або https://."
    override val help = listOf(
        HelpSection(
            "Прагляд эфіру",
            "▲ / ▼" to "папярэдні / наступны канал бягучай групы (кірунак — налада «Кнопка ▲»)",
            "CH+ / CH−" to "наступны / папярэдні канал",
            "OK, ◀" to "спіс каналаў",
            "утрымліваць ◀" to "перамотка на 30 с назад (каналы з архівам ⟲); даўжэй — хутчэй",
            "▶, Info" to "панэль канала: што ідзе зараз і далей",
            "утрымліваць OK, Audio" to "гукавая дарожка канала (запамінаецца для канала)",
            "⏯" to "паўза; працяг з таго ж месца (каналы з архівам)",
            "⏪ / ⏩" to "перамотка на 5 хв назад / наперад",
            "0–9" to "канал па нумары ў бягучай групе",
            "Назад" to "схаваць панэль; двойчы — выхад",
        ),
        HelpSection(
            "Архіў і паўза",
            "OK, ⏯" to "паўза / працягнуць",
            "◀ / ▶" to "30 с назад / наперад; утрымліваць — хутчэй",
            "⏪ / ⏩" to "5 хв назад / наперад",
            "утрымліваць OK" to "гукавая дарожка",
            "Назад" to "у прамы эфір; потым ◀ — спіс каналаў",
        ),
        HelpSection(
            "Спіс каналаў",
            "▲ / ▼" to "групы і каналы; утрымліваць — хутчэй (★ Абранае, Усе каналы, Пошук, групы правайдара, у канцы ⚙ Налады)",
            "▶ на групе, OK" to "адкрыць групу",
            "◀ на канале" to "назад да груп",
            "OK на канале" to "глядзець",
            "утрымліваць OK" to "дадаць у ★ Абранае / прыбраць",
            "▶ на канале" to "тэлепраграма канала",
            "Назад" to "закрыць спіс",
        ),
        HelpSection(
            "★ Абранае",
            "утрымліваць OK на канале" to "змяніць парадак: з'явіцца ⇅",
            "▲ / ▼" to "перасунуць канал",
            "OK або Назад" to "гатова",
            "яшчэ раз утрымліваць OK" to "прыбраць з абранага",
        ),
        HelpSection(
            "Тэлепраграма",
            "▲ / ▼" to "перадачы і дні",
            "OK на мінулай перадачы ⟲" to "глядзець з архіва",
            "OK на бягучай" to "глядзець з пачатку (калі ёсць архіў) або эфір",
            "◀, Назад" to "назад да каналаў",
        ),
        HelpSection(
            "Налады",
            "OK на полі" to "змяніць адрас; OK на клавіятуры — гатова",
            "OK на пункце" to "змяніць значэнне",
            "Назад" to "закрыць налады",
        ),
    )

    override val settings = "⚙  Налады"
    override val searchPlaceholder = "Назва канала"
    override val favEmpty = "Абранага пакуль няма.\nДоўгі націск OK або кнопка «Меню» на канале — дадаць."
    override val searchHint = "Націсніце OK на полі і ўвядзіце назву"
    override val nothingFound = "Нічога не знойдзена"
    override val empty = "Пуста"
    override val favMoveHint = "▲ ▼ — перамясціць · OK або Назад — гатова · доўгае OK — прыбраць з абранага"
    override val favHint = "доўгае OK на канале — змяніць парадак"
    override val guideNowArchive = "OK — глядзець з пачатку"
    override val guideNow = "OK — глядзець"
    override val guideArchive = "OK — глядзець з архіва"
    override val guideNotInArchive = "Няма ў архіве"
    override val guideNotStarted = "Яшчэ не пачалася"
    override val groupFav = "★ Абранае"
    override val groupAll = "Усе каналы"
    override val groupSearch = "🔍 Пошук"
    override val noGroup = "Без групы"

    override val reasonSlow = "тармозіць"
    override val reasonStuck = "завіс"
    override val reasonDown = "недаступны"
    override val reasonNoPicture = "не паказвае карцінку на гэтым ТБ"
    override val reasonJerky = "тузаецца"
    override fun backup(from: String, reason: String, to: String) = "«$from» $reason — уключаю «$to»"
    override fun streamUnavailable(code: String) = "Паток недаступны ($code).\nВыберыце іншы канал."
    override fun playbackError(code: String, n: Int, total: Int, delayS: Int) =
        "Памылка прайгравання ($code). Паўтор $n з $total праз $delayS с…"

    override val loadingPlaylist = "Загружаю плэйліст…"
    override val playlistEmpty = "у плэйлісце няма каналаў"
    override fun playlistFailed(msg: String) = "Не ўдалося загрузіць плэйліст: $msg"
    override fun showingCached(msg: String) = "$msg (паказаны захаваны)"
    override val epgNoUrl = "Спасылка на тэлепраграму не зададзена"
    override val epgLoading = "Загружаю тэлепраграму…"
    override fun epgLoaded(n: Int, total: Int) = "Тэлепраграма: $n з $total каналаў"
    override fun epgFailed(msg: String) = "Не ўдалося загрузіць тэлепраграму: $msg"

    override val noArchive = "У гэтага канала няма архіва"
    override val live = "Прамы эфір"
    override fun noChannel(digits: String) = "Няма канала $digits у гэтым спісе"
    override val pressBackAgain = "Націсніце «Назад» яшчэ раз, каб выйсці"
    override val archiveUnavailable = "Архіў за гэты час недаступны.\n◀ ▶ — іншы час · Назад — прамы эфір"
    override fun archiveBadge(time: String) = "⟲ АРХІЎ  $time"
    override fun archiveDays(n: Int) = " · архіў $n дн."
    override fun seekingTo(time: String) = "⏩ Перамотка да $time"
    override fun pausedAt(time: String) = "❚❚ Паўза · $time"
    override fun archivePos(time: String, minBehind: Long) = "⟲ Архіў · $time · −$minBehind хв ад эфіру"
    override val epgNotLoaded = "Тэлепраграма не загружана"
    override fun next(time: String, title: String) = "Далей $time  $title"
    override val hintsArchive = "OK — паўза · ◀ ▶ — ±30 с · ⏪ ⏩ — ±5 хв · Назад — у эфір, потым ◀ — спіс каналаў"
    override val hintsLive = "▲▼ — каналы · OK / ◀ — спіс каналаў · ▶ — інфа · Назад — схаваць"
    override val hintsLiveArchive = "▲▼ — каналы · OK / ◀ — спіс каналаў · утрымліваць ◀ ▶ — перамотка · ⏯ — паўза · Назад — схаваць"
}

object Uk : Strings {
    override val audioTitle = "🔊 Звукова доріжка"
    override fun audioTrack(n: Int) = "Доріжка $n"
    override val audioOnlyOne = "У цього каналу одна звукова доріжка"
    override fun audioHint(n: Int) = " · довге OK — звук ($n)"
    override fun backupMode(mode: Int) = when (mode) {
        1 -> "Запасний потік: лише тієї ж якості або кращої"
        2 -> "Запасний потік: вимкнено (канал сам не змінюється)"
        else -> "Запасний потік: можна гіршої якості (FHD → HD)"
    }
    override val settingsTitle = "ZmIPTV — налаштування"
    override val playlistUrl = "Посилання на плейлист (M3U / M3U8)"
    override val epgUrl = "Посилання на телепрограму XMLTV (порожньо — з плейлиста)"
    override val save = "Зберегти"
    override val reloadAll = "Оновити все"
    override fun buffer(level: Int) = when (level) {
        1 -> "Буфер ефіру: збільшений (≈40 с від ефіру)"
        2 -> "Буфер ефіру: максимальний (≈45 с від ефіру)"
        else -> "Буфер ефіру: звичайний (≈30 с від ефіру)"
    }
    override fun keyUp(inverted: Boolean) = if (inverted) "Кнопка ▲ — наступний канал" else "Кнопка ▲ — попередній канал"
    override fun stats(channels: Int, groups: Int, favorites: Int) = "Каналів: $channels, груп: $groups, в обраному: $favorites"
    override val helpButton = "🎮  Кнопки пульта"
    override val helpTitle = "Кнопки пульта"
    override val helpFooter = "▲ ▼ — прокручування · Назад — закрити"
    override val qrButton = "📱  Ввести з телефона (QR-код)"
    override val qrTitle = "Введення плейлиста з телефона"
    override val qrSteps = "Підключіть телефон до тієї ж мережі, що й телевізор, відскануйте код, вставте посилання й надішліть."
    override val qrManual = "Або відкрийте цю адресу в браузері телефона:"
    override val qrNoNetwork = "Телевізор не підключено до локальної мережі."
    override val qrFooter = "Назад — закрити"
    override val qrPagePlaylist = "Посилання на плейлист"
    override val qrPageEpg = "Посилання на телепрограму XMLTV (необов’язково; якщо порожньо, залишиться поточне)"
    override val qrPageSend = "Надіслати на телевізор"
    override val qrPageDone = "Надіслано на телевізор. Цю сторінку можна закрити."
    override val qrPageBad = "Введіть посилання, що починається з http:// або https://."
    override val help = listOf(
        HelpSection(
            "Перегляд ефіру",
            "▲ / ▼" to "попередній / наступний канал поточної групи (напрямок — налаштування «Кнопка ▲»)",
            "CH+ / CH−" to "наступний / попередній канал",
            "OK, ◀" to "список каналів",
            "утримувати ◀" to "перемотування на 30 с назад (канали з архівом ⟲); довше — швидше",
            "▶, Info" to "панель каналу: що йде зараз і далі",
            "утримувати OK, Audio" to "звукова доріжка каналу (запам'ятовується для каналу)",
            "⏯" to "пауза; продовження з того ж місця (канали з архівом)",
            "⏪ / ⏩" to "перемотування на 5 хв назад / вперед",
            "0–9" to "канал за номером у поточній групі",
            "Назад" to "сховати панель; двічі — вихід",
        ),
        HelpSection(
            "Архів і пауза",
            "OK, ⏯" to "пауза / продовжити",
            "◀ / ▶" to "30 с назад / вперед; утримувати — швидше",
            "⏪ / ⏩" to "5 хв назад / вперед",
            "утримувати OK" to "звукова доріжка",
            "Назад" to "у прямий ефір; потім ◀ — список каналів",
        ),
        HelpSection(
            "Список каналів",
            "▲ / ▼" to "групи і канали; утримувати — швидше (★ Обране, Усі канали, Пошук, групи провайдера, наприкінці ⚙ Налаштування)",
            "▶ на групі, OK" to "відкрити групу",
            "◀ на каналі" to "назад до груп",
            "OK на каналі" to "дивитися",
            "утримувати OK" to "додати в ★ Обране / прибрати",
            "▶ на каналі" to "телепрограма каналу",
            "Назад" to "закрити список",
        ),
        HelpSection(
            "★ Обране",
            "утримувати OK на каналі" to "змінити порядок: з'явиться ⇅",
            "▲ / ▼" to "пересунути канал",
            "OK або Назад" to "готово",
            "ще раз утримувати OK" to "прибрати з обраного",
        ),
        HelpSection(
            "Телепрограма",
            "▲ / ▼" to "передачі та дні",
            "OK на минулій передачі ⟲" to "дивитися з архіву",
            "OK на поточній" to "дивитися з початку (якщо є архів) або ефір",
            "◀, Назад" to "назад до каналів",
        ),
        HelpSection(
            "Налаштування",
            "OK на полі" to "змінити адресу; OK на клавіатурі — готово",
            "OK на пункті" to "змінити значення",
            "Назад" to "закрити налаштування",
        ),
    )

    override val settings = "⚙  Налаштування"
    override val searchPlaceholder = "Назва каналу"
    override val favEmpty = "Обраного поки немає.\nДовге натискання OK або кнопка «Меню» на каналі — додати."
    override val searchHint = "Натисніть OK на полі та введіть назву"
    override val nothingFound = "Нічого не знайдено"
    override val empty = "Порожньо"
    override val favMoveHint = "▲ ▼ — перемістити · OK або Назад — готово · довге OK — прибрати з обраного"
    override val favHint = "довге OK на каналі — змінити порядок"
    override val guideNowArchive = "OK — дивитися з початку"
    override val guideNow = "OK — дивитися"
    override val guideArchive = "OK — дивитися з архіву"
    override val guideNotInArchive = "Немає в архіві"
    override val guideNotStarted = "Ще не почалася"
    override val groupFav = "★ Обране"
    override val groupAll = "Усі канали"
    override val groupSearch = "🔍 Пошук"
    override val noGroup = "Без групи"

    override val reasonSlow = "гальмує"
    override val reasonStuck = "завис"
    override val reasonDown = "недоступний"
    override val reasonNoPicture = "не показує картинку на цьому ТБ"
    override val reasonJerky = "смикається"
    override fun backup(from: String, reason: String, to: String) = "«$from» $reason — вмикаю «$to»"
    override fun streamUnavailable(code: String) = "Потік недоступний ($code).\nОберіть інший канал."
    override fun playbackError(code: String, n: Int, total: Int, delayS: Int) =
        "Помилка відтворення ($code). Повтор $n з $total через $delayS с…"

    override val loadingPlaylist = "Завантажую плейлист…"
    override val playlistEmpty = "у плейлисті немає каналів"
    override fun playlistFailed(msg: String) = "Не вдалося завантажити плейлист: $msg"
    override fun showingCached(msg: String) = "$msg (показано збережений)"
    override val epgNoUrl = "Посилання на телепрограму не задано"
    override val epgLoading = "Завантажую телепрограму…"
    override fun epgLoaded(n: Int, total: Int) = "Телепрограма: $n з $total каналів"
    override fun epgFailed(msg: String) = "Не вдалося завантажити телепрограму: $msg"

    override val noArchive = "У цього каналу немає архіву"
    override val live = "Прямий ефір"
    override fun noChannel(digits: String) = "Немає каналу $digits у цьому списку"
    override val pressBackAgain = "Натисніть «Назад» ще раз, щоб вийти"
    override val archiveUnavailable = "Архів за цей час недоступний.\n◀ ▶ — інший час · Назад — прямий ефір"
    override fun archiveBadge(time: String) = "⟲ АРХІВ  $time"
    override fun archiveDays(n: Int) = " · архів $n дн."
    override fun seekingTo(time: String) = "⏩ Перемотування до $time"
    override fun pausedAt(time: String) = "❚❚ Пауза · $time"
    override fun archivePos(time: String, minBehind: Long) = "⟲ Архів · $time · −$minBehind хв від ефіру"
    override val epgNotLoaded = "Телепрограму не завантажено"
    override fun next(time: String, title: String) = "Далі $time  $title"
    override val hintsArchive = "OK — пауза · ◀ ▶ — ±30 с · ⏪ ⏩ — ±5 хв · Назад — в ефір, потім ◀ — список каналів"
    override val hintsLive = "▲▼ — канали · OK / ◀ — список каналів · ▶ — інфо · Назад — сховати"
    override val hintsLiveArchive = "▲▼ — канали · OK / ◀ — список каналів · утримувати ◀ ▶ — перемотування · ⏯ — пауза · Назад — сховати"
}
