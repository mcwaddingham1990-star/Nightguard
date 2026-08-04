package com.nightguard.app.util

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SecureImageStore(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val photosDir: File by lazy {
        File(context.filesDir, "unlock_photos").apply { mkdirs() }
    }

    fun newFileFor(timestamp: Long): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        return File(photosDir, "unlock_$name.jpg.enc")
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
