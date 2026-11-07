package com.zmeuk.zmiptv

data class Channel(
    /** 1-based position in the playlist. */
    val number: Int,
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    /** Archive depth in days (tvg-rec / catchup-days), 0 = no archive. */
    val catchupDays: Int,
    /** catchup="..." attribute: shift, append, default, flussonic… null = provider default (shift). */
    val catchupType: String?,
    val catchupSource: String?,
) {
    /** Declared in the playlist AND the server really has recordings (see ArchiveStatus). */
    val hasArchive get() = catchupDays > 0 && !ArchiveStatus.isDead(name)
}

data class Playlist(
    val channels: List<Channel>,
    val epgUrls: List<String>,
) {
    val groups: List<String> = channels.map { it.group }.distinct()
    val byGroup: Map<String, List<Channel>> = channels.groupBy { it.group }
    val byName: Map<String, Channel> = channels.associateBy { it.name }
}

/** M3U / M3U8 (IPTV flavour) parser: #EXTM3U header attrs, #EXTINF attrs + name, #EXTGRP. */
object M3uParser {
    private val attrRe = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    const val NO_GROUP = "Без группы"

    fun parse(text: String): Playlist {
        val channels = mutableListOf<Channel>()
        var header = emptyMap<String, String>()
        var attrs: Map<String, String>? = null
        var name = ""
        var extGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> header = attrs(line)
                line.startsWith("#EXTINF") -> {
                    val comma = nameComma(line)
                    if (comma >= 0) {
                        attrs = attrs(line.substring(0, comma))
                        name = line.substring(comma + 1).trim()
                    } else {
                        attrs = attrs(line)
                        name = ""
                    }
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val a = attrs ?: emptyMap()
                    fun attr(key: String) = a[key]?.takeIf { it.isNotBlank() } ?: header[key]?.takeIf { it.isNotBlank() }
                    channels += Channel(
                        number = channels.size + 1,
                        name = name.ifEmpty { a["tvg-name"] ?: line.substringAfterLast('/') },
                        url = line,
                        group = (a["group-title"] ?: extGroup)?.takeIf { it.isNotBlank() } ?: NO_GROUP,
                        logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                        tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                        catchupDays = (attr("catchup-days") ?: attr("tvg-rec") ?: attr("timeshift"))?.toIntOrNull() ?: 0,
                        catchupType = attr("catchup"),
                        catchupSource = attr("catchup-source"),
                    )
                    attrs = null
                    name = ""
                    extGroup = null
                }
            }
        }
        val epgUrls = listOfNotNull(header["url-tvg"], header["x-tvg-url"], header["tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return Playlist(channels, epgUrls)
    }

    private fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The comma separating attributes from the display name — the first one outside quotes. */
    private fun nameComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
