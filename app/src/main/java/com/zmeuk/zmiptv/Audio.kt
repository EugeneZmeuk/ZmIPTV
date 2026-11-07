package com.zmeuk.zmiptv

import java.util.Locale

/** One audio track of the current stream (from the stream's PMT, see AudioPids.kt). */
data class AudioTrackItem(
    /** 1-based number among the audio tracks of the stream. */
    val number: Int,
    val pid: Int,
    /** ISO 639-2 code from the stream (rus, eng…), null when the stream does not say. */
    val language: String?,
    /** TS stream type: 0x0F/0x11 AAC, 0x81 AC3, 0x87 E-AC3, 0x03/0x04 MPEG audio. */
    val kind: Int,
    val selected: Boolean,
) {
    /** What is remembered per channel: the language if known, otherwise the track number. */
    val key: String get() = language?.let { "lang:$it" } ?: "idx:$number"

    /** "English · AAC" in the current UI language. */
    fun title(): String {
        val lang = language?.let { code ->
            val locale = Locale(ISO3_TO_2[code] ?: code)
            locale.getDisplayLanguage(I18n.lang.locale)
                .takeIf { it.isNotBlank() && !it.equals(locale.language, ignoreCase = true) }
                ?.replaceFirstChar { it.titlecase(I18n.lang.locale) }
                ?: code
        } ?: T.audioTrack(number)
        val codec = when (kind) {
            0x0F, 0x11 -> "AAC"
            0x81 -> "AC3"
            0x87 -> "E-AC3"
            0x03, 0x04 -> "MP2"
            0xAC -> "AC4"
            else -> null
        }
        return listOfNotNull(lang, codec).joinToString(" · ")
    }

    companion object {
        /** Bibliographic / terminology codes that Locale does not always resolve. */
        private val ISO3_TO_2 = mapOf(
            "rus" to "ru", "eng" to "en", "ukr" to "uk", "bel" to "be", "pol" to "pl", "deu" to "de", "ger" to "de",
            "fra" to "fr", "fre" to "fr", "spa" to "es", "ita" to "it", "por" to "pt", "tur" to "tr", "kaz" to "kk",
            "lit" to "lt", "lav" to "lv", "est" to "et", "ces" to "cs", "cze" to "cs", "slk" to "sk", "slo" to "sk",
            "hrv" to "hr", "srp" to "sr", "bul" to "bg", "ron" to "ro", "rum" to "ro", "hun" to "hu", "nld" to "nl",
            "dut" to "nl", "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi", "ell" to "el", "gre" to "el",
            "heb" to "he", "ara" to "ar", "zho" to "zh", "chi" to "zh", "jpn" to "ja", "kor" to "ko", "geo" to "ka",
            "kat" to "ka", "arm" to "hy", "hye" to "hy", "aze" to "az", "uzb" to "uz",
        )

        fun fromStream(list: List<TsAudio>, chosen: TsAudio?): List<AudioTrackItem> {
            // without a choice ExoPlayer plays the lowest PID of the first kind in the PMT
            val playing = chosen ?: list.firstOrNull()?.let { first -> list.filter { it.kind == first.kind }.minByOrNull { it.pid } }
            return list.mapIndexed { i, a -> AudioTrackItem(i + 1, a.pid, a.language, a.kind, a.pid == playing?.pid) }
        }
    }
}
