@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zmeuk.zmiptv

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Like DefaultMediaSourceFactory, but HLS accepts I-frames that are not IDR as keyframes.
 *
 * Some channels never send an IDR frame, only I-frames with a recovery point (СПАС orig, Матч ТВ FHD,
 * KHL Prime HD orig: interlaced, coded as fields). By default ExoPlayer waits for an IDR to start
 * decoding, so it loads the whole buffer and never shows a frame.
 */
class StreamSourceFactory(dataSource: DataSource.Factory) : MediaSource.Factory {
    private val hls = HlsMediaSource.Factory(dataSource)
        .setExtractorFactory(DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true))
        // DefaultMediaSourceFactory turns this on; without it streams with WebVTT subtitles fail to play
        .experimentalParseSubtitlesDuringExtraction(true)
    private val other = DefaultMediaSourceFactory(dataSource)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        hls.setDrmSessionManagerProvider(provider)
        other.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        hls.setLoadErrorHandlingPolicy(policy)
        other.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = other.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val local = mediaItem.localConfiguration
        val type = local?.let { Util.inferContentTypeForUriAndMimeType(it.uri, it.mimeType) }
        return if (type == C.CONTENT_TYPE_HLS) hls.createMediaSource(mediaItem) else other.createMediaSource(mediaItem)
    }
}
