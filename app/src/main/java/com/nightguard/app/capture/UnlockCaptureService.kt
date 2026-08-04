package com.nightguard.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.nightguard.app.R
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.SecureImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires a single front-camera capture whenever it is started, then logs the
 * encrypted photo path to the timeline and stops. Triggered on unlock, on
 * sensitive Settings screens, and when NightGuard's own permissions get
 * revoked (see UnlockReceiver, NightGuardAccessibilityService, AppUsageMonitorService).
 */
class UnlockCaptureService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureReason: String = "Device unlocked"
    @Volatile private var isCapturing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        if (isCapturing) return START_NOT_STICKY
        isCapturing = true
        captureReason = intent?.getStringExtra(EXTRA_REASON) ?: "Device unlocked"
        captureAndStop()
        return START_NOT_STICKY
    }

    private fun captureAndStop() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val imageCapture = ImageCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
            } catch (e: Exception) {
                stopSelf()
                return@addListener
            }

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = imageProxyToJpegBytes(image)
                        image.close()
                        provider.unbindAll()
                        persistAndFinish(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        provider.unbindAll()
                        isCapturing = false
                        stopSelf()
                    }
                }
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun persistAndFinish(jpegBytes: ByteArray) {
        scope.launch {
            val timestamp = System.currentTimeMillis()
            val store = SecureImageStore(applicationContext)
            val file = store.newFileFor(timestamp)
            store.encryptedFile(file).openFileOutput().use { it.write(jpegBytes) }

            TimelineRepository(applicationContext).log(
                type = EventType.UNLOCK_SELFIE,
                detail = captureReason,
                photoPath = file.absolutePath,
                timestamp = timestamp
            )
            isCapturing = false
            stopSelf()
        }
    }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun buildNotification(): Notification {
        val channelId = "nightguard_capture"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "NightGuard unlock capture", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("NightGuard")
            .setContentText("Recording unlock event")
            .setSmallIcon(R.drawable.ic_shield)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        const val EXTRA_REASON = "reason"

        /** Starts a one-shot front-camera capture, tagging the resulting timeline entry with [reason]. */
        fun start(context: Context, reason: String) {
            val intent = Intent(context, UnlockCaptureService::class.java).putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
