package com.kaiwa.chat

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records one voice note to a file in the app's cache.
 *
 * ## Why AAC and not Opus
 *
 * Opus would be the better format: smaller for the same quality, and it is what the
 * transcription box's own examples use. It is not an option here. Android ships an
 * Opus *decoder* but no Opus *encoder* - this device lists `OMX.google.opus.decoder`
 * and nothing else for `audio/opus` - and the encoder was not added to the platform
 * until API 29 regardless. Shipping one in the APK would mean native libraries and an
 * ABI constraint, which this app exists to avoid.
 *
 * What the device can actually produce is AAC-LC, AMR and FLAC. AAC in an MPEG-4
 * container wins: FLAC is lossless and far too big for speech, and AMR-WB is audibly
 * poor. PyAV decodes m4a on the far side, so no conversion is needed there either.
 *
 * At 16 kHz mono and 32 kbps - which is plenty for speech - five minutes comes to
 * under 1.5 MB, comfortably inside the 25 MB the reverse proxy accepts.
 *
 * Recordings land in the cache directory, so they are app-private, need no storage
 * permission, and go away on their own if a delete is ever missed.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    /**
     * Starts recording and returns the file the audio is going to.
     *
     * Throws if the microphone cannot be had - it is already in use, or the hardware
     * refused - and leaves nothing behind when it does.
     */
    @Suppress("DEPRECATION")
    fun start(): File {
        cancel()

        // A name of its own for every recording. The upload of the last one can still be
        // running - even for a screen that is gone - and it deletes its file once it is done,
        // which must never be the file this one is being written to.
        val file = File(context.cacheDir, "$FILE_PREFIX${System.currentTimeMillis()}$FILE_SUFFIX")

        // The no-argument constructor is deprecated in favour of MediaRecorder(Context),
        // which only exists from API 31. This app has a floor of API 27 and a ceiling of
        // "the one handset it was written for", so the old form is the right one.
        val media = MediaRecorder()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(SAMPLE_RATE_HZ)
            media.setAudioEncodingBitRate(BITRATE_BPS)
            media.setOutputFile(file.absolutePath)
            media.prepare()
            media.start()
        } catch (e: Exception) {
            try {
                media.release()
            } catch (ignored: Exception) {
                // Nothing useful to do: the original failure is the one that matters.
            }
            file.delete()
            throw e
        }

        recorder = media
        target = file
        return file
    }

    /**
     * Stops recording and returns the finished file, or null if nothing usable was
     * captured - a stop in the first fraction of a second produces no frames at all,
     * which MediaRecorder reports by throwing.
     */
    fun stop(): File? {
        val media = recorder ?: return null
        recorder = null
        val file = target
        target = null

        val captured = try {
            media.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                media.release()
            } catch (ignored: Exception) {
                // As above: release() must not mask the real result.
            }
        }

        if (!captured || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /** Throws away whatever is being recorded and hands the microphone back. */
    fun cancel() {
        val media = recorder ?: return
        val file = target
        recorder = null
        target = null
        try {
            media.stop()
        } catch (e: Exception) {
            // Cancelling a recorder that captured nothing throws; that is fine here.
        }
        try {
            media.release()
        } catch (e: Exception) {
            // Same.
        }
        file?.delete()
    }

    private companion object {
        const val FILE_PREFIX = "voice-note-"
        const val FILE_SUFFIX = ".m4a"
        const val SAMPLE_RATE_HZ = 16_000
        const val BITRATE_BPS = 32_000
    }
}
