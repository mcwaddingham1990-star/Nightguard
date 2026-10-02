package com.nightguard.app.util

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.nightguard.app.data.db.EventType
import org.json.JSONObject
import java.io.File

data class BackupEvent(
    val id: Long,
    val type: EventType,
    val timestamp: Long,
    val packageName: String?,
    val appLabel: String?,
    val detail: String?,
    val photoFile: String?,
    val audioFile: String?,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val isIncognito: Boolean
)

/**
 * Reads back what BackupExporter wrote: lists backup folders under
 * Downloads/NightGuard_Backups/, and decrypts a chosen one's manifest + media on demand,
 * using the same on-device Jetpack Security master key that encrypted them -- this only
 * ever works on the device that made the backup, by design (see BackupExporter's doc
 * comment). Nothing here is cached decrypted; everything is re-decrypted to a cache temp
 * file each time and the caller is responsible for cleaning it up (same pattern as
 * SecureImageStore/SecureAudioStore).
 */
object BackupReader {
    private const val RELATIVE_ROOT = "Download/NightGuard_Backups/"

    fun listBackupFolders(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NightGuard_Backups")
            return dir.listFiles { f -> f.isDirectory }?.map { it.name }?.sortedDescending() ?: emptyList()
        }

        val folders = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(idx) ?: continue
                if (path.startsWith(RELATIVE_ROOT)) {
                    val folder = path.removePrefix(RELATIVE_ROOT).trim('/').substringBefore('/')
                    if (folder.isNotBlank()) folders.add(folder)
                }
            }
        }
        return folders.sortedDescending()
    }

    /** Decrypts timeline.json.enc for [folderName] and parses it; empty list if missing/unreadable. */
    fun readManifest(context: Context, folderName: String): List<BackupEvent> {
        val encFile = copyToCache(context, folderName, "timeline.json.enc") ?: return emptyList()
        val bytes = runCatching { decrypt(context, encFile) }.getOrNull()
        encFile.delete()
        if (bytes == null) return emptyList()

        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("events") ?: return emptyList()
        val result = mutableListOf<BackupEvent>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val type = runCatching { EventType.valueOf(o.getString("type")) }.getOrNull() ?: continue
            result.add(
                BackupEvent(
                    id = o.optLong("id"),
                    type = type,
                    timestamp = o.optLong("timestamp"),
                    packageName = o.stringOrNull("packageName"),
                    appLabel = o.stringOrNull("appLabel"),
                    detail = o.stringOrNull("detail"),
                    photoFile = o.stringOrNull("photoFile"),
                    audioFile = o.stringOrNull("audioFile"),
                    latitude = if (o.isNull("latitude")) null else o.optDouble("latitude"),
                    longitude = if (o.isNull("longitude")) null else o.optDouble("longitude"),
                    locationAccuracyMeters = if (o.isNull("locationAccuracyMeters")) null else o.optDouble("locationAccuracyMeters").toFloat(),
                    isIncognito = o.optBoolean("isIncognito", false)
                )
            )
        }
        return result.sortedByDescending { it.timestamp }
    }

    /** Copies a backup's still-encrypted photo/audio file to a cache temp file and decrypts it. */
    fun readMediaBytes(context: Context, folderName: String, fileName: String): ByteArray? {
        val encFile = copyToCache(context, folderName, fileName) ?: return null
        val bytes = runCatching { decrypt(context, encFile) }.getOrNull()
        encFile.delete()
        return bytes
    }

    private fun decrypt(context: Context, file: File): ByteArray {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build().openFileInput().use { it.readBytes() }
    }

    private fun copyToCache(context: Context, folderName: String, fileName: String): File? {
        val dest = File(File(context.cacheDir, "backup_view").apply { mkdirs() }, "${folderName}_$fileName")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val src = File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NightGuard_Backups/$folderName"),
                fileName
            )
            if (!src.exists()) return null
            return runCatching {
                src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                dest
            }.getOrNull()
        }

        val relativePath = "$RELATIVE_ROOT$folderName/"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val uri = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        } ?: return null

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest
        }.getOrNull()
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
