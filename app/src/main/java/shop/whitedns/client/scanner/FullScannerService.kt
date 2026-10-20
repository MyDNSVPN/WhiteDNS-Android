package shop.whitedns.client.scanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import shop.whitedns.client.MainActivity
import shop.whitedns.client.R

class FullScannerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("full_scanner", scannerText(this, R.string.scanner_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(2041, notification(scannerText(this, R.string.scanner_notification_preparing)))
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, "full_scanner")
        .setSmallIcon(R.drawable.ic_notification).setContentTitle(scannerText(this, R.string.scanner_brand))
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 2041, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, scannerText(this, R.string.scanner_notification_stop), PendingIntent.getService(this, 2042, Intent(this, FullScannerService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            scope.launch { ScannerRepository.get(application).stop() }
        }
        if (!watching) {
            watching = true
            scope.launch {
                val scanner = ScannerRepository.get(application)
                while (true) {
                    val status = JSONObject(scanner.snapshot())
                    if (!status.optBoolean("busy")) { stopSelf(); break }
                    getSystemService(NotificationManager::class.java).notify(2041, notification(scannerText(this@FullScannerService, R.string.scanner_status_summary, scannerText(this@FullScannerService, scannerStateResource(status.optString("state"))), status.optInt("done"), status.optInt("open"))))
                    delay(2000)
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
