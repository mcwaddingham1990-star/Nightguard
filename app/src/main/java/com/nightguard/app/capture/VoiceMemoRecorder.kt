package com.nightguard.app.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.SecureAudioStore
import java.io.File

/**
 * Manual, user-initiated voice memo recording -- e.g. "what I remember" notes to play
 * back for a doctor. Deliberately not automatic or ambient: NightGuard's other capture
 * paths (selfies) trigger on events without asking, but recording audio of whatever's
 * happening around the phone raises a different order of privacy/legal concern (it can
 * capture other people's conversations without their consent), so this only ever
 * records when you press the button.
 */
class VoiceMemoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    @Volatile var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        if (isRecording) return false
        val temp = File.createTempFile("memo_", ".m4a", context.cacheDir)
        tempFile = temp

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(temp.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            isRecording = true
            true
        } catch (e: Exception) {
            mr.release()
            temp.delete()
            tempFile = null
            false
        }
    }

    /** Stops recording, encrypts the result, logs it to the timeline, and cleans up the plaintext temp file. */
    suspend fun stopAndSave(reason: String = "Voice memo"): Boolean {
        val mr = recorder ?: return false
        val temp = tempFile ?: return false
        isRecording = false
        recorder = null
        tempFile = null

        try {
            mr.stop()
        } catch (e: Exception) {
            // stop() throws if start() never produced valid data (e.g. recording <1s).
        }
        mr.release()

        if (!temp.exists() || temp.length() == 0L) {
            temp.delete()
            return false
        }

        val timestamp = System.currentTimeMillis()
        val store = SecureAudioStore(context)
        val destFile = store.newFileFor(timestamp)
        store.encryptedFile(destFile).openFileOutput().use { out -> temp.inputStream().use { it.copyTo(out) } }
        temp.delete()

        TimelineRepository(context).log(
            type = EventType.VOICE_MEMO,
            detail = reason,
            audioPath = destFile.absolutePath,
            timestamp = timestamp
        )
        return true
    }

    fun cancel() {
        val mr = recorder
        val temp = tempFile
        isRecording = false
        recorder = null
        tempFile = null
        try {
            mr?.stop()
        } catch (e: Exception) {
            // ignore -- we're discarding this recording anyway
        }
        mr?.release()
        temp?.delete()
    }
}
