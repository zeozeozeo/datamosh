package com.digital.datamosh.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MuxWriter(
    private val context: Context,
    orientationHint: Int,
) : Closeable {
    private data class Sample(
        val video: Boolean,
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    val uri: Uri
    private val descriptor: android.os.ParcelFileDescriptor?
    private val muxer: MediaMuxer
    private val pending = mutableListOf<Sample>()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var closed = false

    init {
        val name = "MOSH_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val legacyFile = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Datamosh/$name",
            ).also { it.parentFile?.mkdirs() }
        } else {
            null
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Datamosh")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Video.Media.DATA,
                    legacyFile?.absolutePath,
                )
            }
        }
        uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
        ) { "Unable to create the gallery item" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
            muxer = MediaMuxer(
                descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        } else {
            descriptor = null
            muxer = MediaMuxer(
                requireNotNull(legacyFile).absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }
        muxer.setOrientationHint(orientationHint)
    }

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        if (closed || videoTrack >= 0) return
        videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        if (closed || audioTrack >= 0) return
        audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    @Synchronized
    fun writeVideo(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        write(Sample(true, data, presentationTimeUs, flags))
    }

    @Synchronized
    fun writeAudio(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        write(Sample(false, data, presentationTimeUs, flags))
    }

    private fun write(sample: Sample) {
        if (closed || sample.bytes.isEmpty()) return
        if (!started) {
            pending += sample
            return
        }
        writeNow(sample)
    }

    private fun maybeStart() {
        if (videoTrack < 0 || audioTrack < 0 || started) return
        muxer.start()
        started = true
        pending.groupBy { it.video }.values.forEach { samples ->
            samples.sortedBy { it.presentationTimeUs }.forEach(::writeNow)
        }
        pending.clear()
    }

    private fun writeNow(sample: Sample) {
        val track = if (sample.video) videoTrack else audioTrack
        if (track < 0) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bytes.size, sample.presentationTimeUs, sample.flags)
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
    }

    @Synchronized
    fun finish(): Uri {
        if (closed) return uri
        closed = true
        if (started) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
        runCatching { descriptor?.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    fun abandon() {
        if (!closed) {
            closed = true
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { descriptor?.close() }
        }
        context.contentResolver.delete(uri, null, null)
    }

    override fun close() {
        abandon()
    }
}
