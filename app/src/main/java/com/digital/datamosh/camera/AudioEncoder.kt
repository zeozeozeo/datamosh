package com.digital.datamosh.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioEncoder(
    private val context: Context,
    private val writer: MuxWriter,
    private val onError: (Throwable) -> Unit,
) {
    private val sampleRate = 44_100
    private val channelCount = 1
    private val capturing = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var record: AudioRecord? = null
    private var samplesQueued = 0L

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(::runLoop, "DatamoshAudio").apply { start() }
    }

    fun setCapturing(value: Boolean) {
        capturing.set(value)
    }

    fun stop() {
        capturing.set(false)
        running.set(false)
        thread?.join(1_500)
        thread = null
    }

    private fun runLoop() {
        try {
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission is not granted" }
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 16_384),
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone could not be initialized"
            }
            record = audioRecord

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            encoder.configure(
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channelCount,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(minBuffer, 8_192))
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            encoder.start()
            audioRecord.startRecording()
            val pcm = ByteArray(maxOf(minBuffer, 4_096))

            while (running.get()) {
                val read = audioRecord.read(pcm, 0, pcm.size)
                if (!capturing.get()) {
                    drain(encoder, false)
                    continue
                }
                if (read > 0) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        encoder.getInputBuffer(index)?.apply {
                            clear()
                            put(pcm, 0, read)
                        }
                        val pts = samplesQueued * 1_000_000L / sampleRate
                        samplesQueued += read / 2L
                        encoder.queueInputBuffer(index, 0, read, pts, 0)
                    }
                }
                drain(encoder, false)
            }
            val eosIndex = encoder.dequeueInputBuffer(20_000)
            if (eosIndex >= 0) {
                encoder.queueInputBuffer(
                    eosIndex,
                    0,
                    0,
                    samplesQueued * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            repeat(20) {
                if (drain(encoder, true)) return@repeat
            }
        } catch (error: Throwable) {
            onError(error)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            record = null
            codec = null
        }
    }

    private fun drain(encoder: MediaCodec, wait: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.setAudioFormat(encoder.outputFormat)
                else -> if (index >= 0) {
                    val output = encoder.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(data)
                        writer.writeAudio(data, info.presentationTimeUs, info.flags)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return true
                }
            }
        }
    }
}
