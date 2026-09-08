package com.nightguard.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the full timeline (metadata + encrypted photo/audio files, copied as-is --
 * still encrypted, never decrypted here) to the MediaStore Downloads collection, which
 * lives outside the app's private storage. This is what makes a backup survive the app's
 * data being cleared from Settings (one of the ways someone could otherwise wipe the
 * timeline): clearing app data does not touch MediaStore entries the app created.
 *
 * Caveat worth knowing: the manifest is encrypted with the same Jetpack Security master
 * key as everything else on-device. Whether that key itself survives "clear data" (as
 * opposed to uninstall) varies by Android version/OEM and hasn't been verified against a
 * real device -- see the README's "hasn't been built or run" note. If it doesn't, the
 * backup file is still copied out safely, just not decryptable from a fresh install.
 */
object BackupExporter {

    suspend fun exportBackup(context: Context): Boolean {
        val repo = TimelineRepository(context)
        val events = repo.eventsBetween(0L, Long.MAX_VALUE)
        if (events.isEmpty()) return true

        val timestamp = System.currentTimeMillis()
        val folderName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val relativeDir = "Download/NightGuard_Backups/$folderName"

        val manifestFile = writeEncryptedManifest(context, events)
        val manifestUploaded = copyToDownloads(context, manifestFile, "timeline.json.enc", relativeDir)
        manifestFile.delete()
        if (!manifestUploaded) return false

        for (event in events) {
            event.photoPath?.let { path -> copyToDownloads(context, File(path), File(path).name, relativeDir) }
            event.audioPath?.let { path -> copyToDownloads(context, File(path), File(path).name, relativeDir) }
        }
        return true
    }

    private fun writeEncryptedManifest(context: Context, events: List<TimelineEvent>): File {
        val array = JSONArray()
        for (e in events) {
            array.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("type", e.type.name)
                    put("timestamp", e.timestamp)
                    put("packageName", e.packageName)
                    put("appLabel", e.appLabel)
                    put("detail", e.detail)
                    put("photoFile", e.photoPath?.let { File(it).name })
                    put("audioFile", e.audioPath?.let { File(it).name })
                    put("latitude", e.latitude)
                    put("longitude", e.longitude)
                    put("locationAccuracyMeters", e.locationAccuracyMeters)
                }
            )
        }
        val json = JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("events", array)
        }.toString()

        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val tempPlain = File.createTempFile("manifest_", ".json", context.cacheDir)
        tempPlain.writeText(json)
        val tempEncrypted = File.createTempFile("manifest_", ".json.enc", context.cacheDir)
        tempEncrypted.delete() // EncryptedFile refuses to write over an existing file
        EncryptedFile.Builder(context, tempEncrypted, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()
            .openFileOutput()
            .use { out -> tempPlain.inputStream().use { it.copyTo(out) } }
        tempPlain.delete()
        return tempEncrypted
    }

    private fun copyToDownloads(context: Context, source: File, displayName: String, relativeDir: String): Boolean {
        if (!source.exists()) return false

        // MediaStore.Downloads (and scoped storage generally) only exists from API 29;
        // minSdk here is 26, so older devices fall back to writing the legacy public
        // Downloads directory directly (requires WRITE_EXTERNAL_STORAGE, maxSdkVersion 28).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeDir.removePrefix("Download/"))
            if (!dir.exists() && !dir.mkdirs()) return false
            return runCatching { File(dir, displayName).outputStream().use { out -> source.inputStream().use { it.copyTo(out) } } }.isSuccess
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        }.isSuccess
    }
}
