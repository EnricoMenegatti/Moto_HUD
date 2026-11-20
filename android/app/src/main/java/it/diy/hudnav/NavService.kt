package it.diy.hudnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer

/** Tiene accesi GPS e Bluetooth anche con lo schermo spento. */
class NavService : Service() {
    private var engine: NavEngine? = null
    private var lastNotifKey = ""
    private var lastNotifTitle = ""
    private var lastNotifStatus = ""
    private var lastNotifAt = 0L
    private val refresh = Observer<String> { publishNotif() }

    private fun publishNotif() {
        val title = HudState.maneuver.value?.takeIf { it.isNotBlank() } ?: "HUD Nav"
        val status = HudState.status.value.orEmpty()
        val alert = HudState.alert.value.orEmpty()
        val trip = HudState.trip.value.orEmpty()
        val body = listOf(alert, trip, status).filter { it.isNotBlank() }.joinToString(" · ")
        val key = "$title|$body"
        if (key == lastNotifKey) return
        val now = System.currentTimeMillis()
        val urgent = title != lastNotifTitle || status != lastNotifStatus
        if (!urgent && now - lastNotifAt < 12000) return
        lastNotifKey = key
        lastNotifTitle = title
        lastNotifStatus = status
        lastNotifAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, body))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        HudState.status.observeForever(refresh)
        HudState.maneuver.observeForever(refresh)
        HudState.trip.observeForever(refresh)
        HudState.alert.observeForever(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("HUD Nav", HudState.status.value ?: "HUD Nav")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                if (engine == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    engine?.stop()
                }
            }
            else -> {
                val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
                val lon = intent?.getDoubleExtra(EXTRA_LON, Double.NaN) ?: Double.NaN
                val name = intent?.getStringExtra(EXTRA_NAME) ?: ""
                val latsEarly = intent?.getDoubleArrayExtra(EXTRA_LATS)
                if ((lat.isNaN() || lon.isNaN()) && (latsEarly == null || latsEarly.isEmpty())) {
                    if (TripStore.load(this).guiding) {
                        resumeGuiding()
                        return START_STICKY
                    }
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (engine == null) engine = NavEngine(applicationContext) { stopSelfFromEngine() }
                val sim = intent?.getBooleanExtra(EXTRA_SIM, false) ?: false
                val fromLat = intent?.getDoubleExtra(EXTRA_FROM_LAT, Double.NaN) ?: Double.NaN
                val fromLon = intent?.getDoubleExtra(EXTRA_FROM_LON, Double.NaN) ?: Double.NaN
                val from = if (fromLat.isNaN() || fromLon.isNaN()) null else LatLon(fromLat, fromLon)
                val lats = intent?.getDoubleArrayExtra(EXTRA_LATS)
                val lons = intent?.getDoubleArrayExtra(EXTRA_LONS)
                val choice = intent?.getIntExtra(EXTRA_CHOICE, 0) ?: 0
                val avoid = intent?.getBooleanExtra(EXTRA_AVOID, false) ?: false
                val stops = if (lats != null && lons != null && lats.isNotEmpty() && lats.size == lons.size) {
                    List(lats.size) { LatLon(lats[it], lons[it]) }
                } else if (!lat.isNaN() && !lon.isNaN()) {
                    listOf(LatLon(lat, lon))
                } else {
                    emptyList()
                }
                if (stops.isEmpty()) {
                    resumeGuiding()
                } else {
                    engine?.start(stops, name, choice, avoid, sim, from)
                }
            }
        }
        return START_STICKY
    }

    private fun resumeGuiding() {
        val trip = TripStore.load(this)
        if (!trip.guiding || trip.stops.isEmpty()) return
        if (engine == null) engine = NavEngine(applicationContext) { stopSelfFromEngine() }
        engine?.start(
            trip.stops.map { LatLon(it.lat, it.lon) },
            trip.stops.last().name,
            trip.choice,
            trip.avoid,
            simulate = false,
            from = null
        )
    }

    private fun stopSelfFromEngine() {
        val main = android.os.Handler(mainLooper)
        main.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        HudState.status.removeObserver(refresh)
        HudState.maneuver.removeObserver(refresh)
        HudState.trip.removeObserver(refresh)
        HudState.alert.removeObserver(refresh)
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL, "Navigazione HUD", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "it.diy.hudnav.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIM = "sim"
        const val EXTRA_FROM_LAT = "fromLat"
        const val EXTRA_FROM_LON = "fromLon"
        const val EXTRA_LATS = "lats"
        const val EXTRA_LONS = "lons"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_AVOID = "avoid"
        private const val CHANNEL = "hud_nav"
        private const val NOTIF_ID = 7
    }
}
