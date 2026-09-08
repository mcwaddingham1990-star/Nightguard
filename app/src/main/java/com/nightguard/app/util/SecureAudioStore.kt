package com.nightguard.app.util

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors SecureImageStore for voice memos: same at-rest AES-256-GCM encryption, local only. */
class SecureAudioStore(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val audioDir: File by lazy {
        File(context.filesDir, "voice_memos").apply { mkdirs() }
    }

    fun newFileFor(timestamp: Long): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        return File(audioDir, "memo_$name.m4a.enc")
    }

    fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    fun decryptToBytes(file: File): ByteArray =
        encryptedFile(file).openFileInput().use { it.readBytes() }
}
