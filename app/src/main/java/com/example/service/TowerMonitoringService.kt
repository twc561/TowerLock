package com.example.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CellLog
import com.example.data.CellRepository
import com.example.location.LocationTracker
import com.example.telephony.CellModel
import com.example.telephony.ITelephonyTracker
import com.example.telephony.TelephonyTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

class TowerMonitoringService : Service() {

    private val binder = LocalBinder()

    private lateinit var telephonyTracker: ITelephonyTracker
    private lateinit var locationTracker: LocationTracker
    private lateinit var repository: CellRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _currentCell = MutableStateFlow(CellModel())
    val currentCell: StateFlow<CellModel> = _currentCell.asStateFlow()

    private val _towerLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val towerLocation: StateFlow<Pair<Double, Double>?> = _towerLocation.asStateFlow()

    private val _resolvedAddress = MutableStateFlow("Locating serving tower...")
    val resolvedAddress: StateFlow<String> = _resolvedAddress.asStateFlow()

    private val _confidenceRange = MutableStateFlow(0)
    val confidenceRange: StateFlow<Int> = _confidenceRange.asStateFlow()

    val userLocation get() = locationTracker.userLocation
    val deviceHeading get() = locationTracker.deviceHeading

    private var iconStyle = "dBm" // "dBm", "band", "tech", "bars"
    private var alertRsearchBelow = -110 // Alert threshold for RSRP
    private var alertOnUnmapped = false
    private var isLoggingPaused = false

    inner class LocalBinder : Binder() {
        fun getService(): TowerMonitoringService = this@TowerMonitoringService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(this, serviceScope)
        repository = CellRepository(database.cellDao(), this)
        telephonyTracker = TelephonyTracker(this)
        locationTracker = LocationTracker(this)

        val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
        telephonyTracker.setPollIntervalSeconds(prefs.getInt("poll_interval", 3))
        telephonyTracker.setGnbBitLength(prefs.getInt("gnb_bits", 24))

        createNotificationChannels()
        startForegroundService()

        // Start listening to cell updates
        serviceScope.launch {
            locationTracker.startLocationTracking()
            telephonyTracker.startMonitoring()

            var previousCell: CellModel? = null

            telephonyTracker.cellState.collectLatest { cell ->
                _currentCell.value = cell
                
                if (!isLoggingPaused && cell.cellId > 0) {
                    // Try to resolve tower location and address
                    val lookup = repository.findTowerLocation(
                        tech = cell.tech,
                        mcc = cell.mcc ?: "310",
                        mnc = cell.mnc ?: "260",
                        area = cell.tac,
                        cid = cell.cellId,
                        openCellIdApiKey = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getString("opencellid_key", null)
                    )

                    val userLoc = locationTracker.userLocation.value
                    var resolvedAddress = lookup.address ?: ""

                    if (resolvedAddress.isBlank() && lookup.lat != null && lookup.lon != null) {
                        resolvedAddress = locationTracker.reverseGeocode(lookup.lat, lookup.lon)
                    } else if (resolvedAddress.isBlank()) {
                        resolvedAddress = "Unmapped cell tower location"
                    }

                    _towerLocation.value = if (lookup.lat != null && lookup.lon != null) {
                        Pair(lookup.lat, lookup.lon)
                    } else {
                        null
                    }
                    _resolvedAddress.value = resolvedAddress
                    _confidenceRange.value = lookup.range

                    // Auto-log cell to Room DB (requires a real GPS fix; never fabricate coordinates)
                    if (userLoc != null) {
                        val logEntry = CellLog(
                            tech = cell.tech,
                            mcc = cell.mcc ?: "310",
                            mnc = cell.mnc ?: "260",
                            operatorName = cell.operatorName ?: "Carrier",
                            cellId = cell.cellId,
                            nodebId = cell.nodebId,
                            sectorId = cell.sectorId,
                            pci = cell.pci,
                            tac = cell.tac,
                            arfcn = cell.arfcn,
                            band = cell.bandName,
                            rsrp = cell.rsrp,
                            rsrq = cell.rsrq,
                            sinr = cell.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = lookup.lat,
                            towerLon = lookup.lon,
                            address = resolvedAddress,
                            source = lookup.source
                        )
                        repository.insertLog(logEntry)
                    }

                    // Update Home Screen Widget
                    com.example.widget.TowerLockWidget.sendWidgetUpdate(this@TowerMonitoringService, cell, resolvedAddress)

                    // Alert rules engine
                    previousCell?.let { prev ->
                        triggerAlertsEngine(prev, cell, lookup.source)
                    }
                    previousCell = cell
                }

                updateNotification(cell)
            }
        }
    }

    private fun triggerAlertsEngine(prev: CellModel, current: CellModel, source: String) {
        // 1. Notify on band change
        if (prev.bandName != current.bandName && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_band", false)) {
            triggerAlert("Band Changed", "Moved from ${prev.bandName} to ${current.bandName}")
        }
        // 2. SA <-> NSA transition
        if (prev.tech != current.tech && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_tech", false)) {
            triggerAlert("Technology Shift", "Connection shifted from ${prev.tech} to ${current.tech}")
        }
        // 3. RSRP below threshold
        if (current.rsrp < alertRsearchBelow && prev.rsrp >= alertRsearchBelow && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_rsrp", false)) {
            triggerAlert("Weak Signal Alert", "RSRP dropped below threshold to ${current.rsrp} dBm")
        }
        // 4. Connecting to an unmapped tower
        if (source == "Unmapped cell" && alertOnUnmapped && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_unmapped", false)) {
            triggerAlert("Unmapped Tower Detected", "Connected to cell ${current.cellId} which is not mapped.")
        }
        // 5. 5G SA to LTE Drop
        val isPrev5gSa = prev.tech == "5G SA"
        val isCurrentLte = current.tech == "4G LTE" || current.tech == "LTE" || current.tech.contains("LTE")
        if (isPrev5gSa && isCurrentLte) {
            val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("alert_5g_drop", true)) {
                val userLoc = locationTracker.userLocation.value
                val locationLabel = if (userLoc != null) {
                    String.format(
                        java.util.Locale.US,
                        "(%.5f, %.5f)",
                        userLoc.latitude,
                        userLoc.longitude
                    )
                } else {
                    "location unavailable"
                }
                triggerAlert("5G Connection Dropped", "Device switched from 5G SA to legacy LTE at: $locationLabel")

                if (prefs.getBoolean("log_drop_coords", true) && userLoc != null) {
                    serviceScope.launch {
                        val dropLog = CellLog(
                            tech = "5G SA to LTE Drop",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = null,
                            towerLon = null,
                            address = "Recorded 5G SA to LTE drop coordinates",
                            source = "5G Drop Monitor"
                        )
                        repository.insertLog(dropLog)
                    }
                }
            }
        }
    }

    private fun triggerAlert(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "Alerts")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_vibrate", true)) {
            builder.setVibrate(longArrayOf(0, 300, 100, 300))
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun startForegroundService() {
        val notification = createNotification(CellModel())
        startForeground(1001, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitoringChannel = NotificationChannel(
                "Monitoring",
                "Tower Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent display of current cellular serving cell stats"
                enableLights(false)
                enableVibration(false)
            }

            val alertsChannel = NotificationChannel(
                "Alerts",
                "Status Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires on cell tower handovers, weak signals, and unmapped cells"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun createNotification(cell: CellModel): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Actions
        val pauseIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_PAUSE"
        }
        val pausePendingIntent = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val snapshotIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_SNAPSHOT"
        }
        val snapshotPendingIntent = PendingIntent.getService(this, 2, snapshotIntent, PendingIntent.FLAG_IMMUTABLE)

        val gnbText = if (cell.nodebId > 0) "gNB: ${cell.nodebId}" else "eNB: ${cell.nodebId}"
        val textSummary = "${cell.tech} | ${cell.bandName} | $gnbText | PCI: ${cell.pci} | RSRP: ${cell.rsrp} dBm"

        val iconText = when (iconStyle) {
            "dBm" -> "${cell.rsrp}"
            "band" -> cell.bandName.substringBefore(" ").replace("n", "").replace("B", "")
            "tech" -> if (cell.tech.contains("5G")) "5G" else "4G"
            else -> "Bars"
        }

        val dynamicIcon = createDynamicIcon(iconText)

        val builder = NotificationCompat.Builder(this, "Monitoring")
            .setContentTitle("TowerLock Active Monitor")
            .setContentText(textSummary)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Technology: ${cell.tech}\n" +
                "Active Band: ${cell.bandName} (${cell.frequencyMhz} MHz)\n" +
                "Serving Cell: $gnbText Sector ${cell.sectorId} (PCI: ${cell.pci})\n" +
                "Signal Level: ${cell.rsrp} dBm | SINR: ${cell.sinr} dB\n" +
                "TAC / Area: ${cell.tac} | MCC-MNC: ${cell.mcc}-${cell.mnc}"
            ))
            .addAction(R.drawable.ic_launcher_foreground, if (isLoggingPaused) "Resume" else "Pause Logging", pausePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Snapshot", snapshotPendingIntent)

        builder.setSmallIcon(dynamicIcon)

        return builder.build()
    }

    private fun updateNotification(cell: CellModel) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, createNotification(cell))
    }

    private fun createDynamicIcon(text: String): androidx.core.graphics.drawable.IconCompat {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background Circle for visual depth
        val bgPaint = Paint().apply {
            color = if (text.startsWith("-") || text.all { it.isDigit() }) {
                val num = text.toIntOrNull() ?: -100
                when {
                    num >= -80 -> 0xFF4CAF50.toInt()
                    num >= -95 -> 0xFF8BC34A.toInt()
                    num >= -110 -> 0xFFFFB74D.toInt()
                    else -> 0xFFE57373.toInt()
                }
            } else {
                0xFF2196F3.toInt()
            }
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = if (text.length > 3) 20f else 26f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        val xPos = canvas.width / 2f
        val yPos = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(text, xPos, yPos, paint)

        return androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PAUSE" -> {
                isLoggingPaused = !isLoggingPaused
                updateNotification(_currentCell.value)
            }
            "ACTION_SNAPSHOT" -> {
                serviceScope.launch {
                    val current = _currentCell.value
                    val userLoc = locationTracker.userLocation.value
                    if (current.cellId > 0 && userLoc != null) {
                        val snapshotLog = CellLog(
                            tech = "${current.tech} (SNAPSHOT)",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = _towerLocation.value?.first,
                            towerLon = _towerLocation.value?.second,
                            address = "Manual snapshot saved by user",
                            source = "Manual Snapshot"
                        )
                        repository.insertLog(snapshotLog)
                        triggerAlert("Snapshot Saved", "Serving cell state recorded in log history.")
                    } else if (current.cellId > 0) {
                        triggerAlert("Snapshot Failed", "GPS location not yet available; try again shortly.")
                    }
                }
            }
            "UPDATE_ICON_STYLE" -> {
                iconStyle = intent.getStringExtra("STYLE") ?: "dBm"
                updateNotification(_currentCell.value)
            }
            "UPDATE_ALERT_THRESHOLDS" -> {
                alertRsearchBelow = intent.getIntExtra("RSRP_THRESHOLD", -110)
                alertOnUnmapped = intent.getBooleanExtra("ALERT_UNMAPPED", false)
            }
            "UPDATE_POLL_INTERVAL" -> {
                telephonyTracker.setPollIntervalSeconds(intent.getIntExtra("POLL_INTERVAL", 3))
            }
            "UPDATE_GNB_BITS" -> {
                telephonyTracker.setGnbBitLength(intent.getIntExtra("GNB_BITS", 24))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        locationTracker.stopLocationTracking()
        telephonyTracker.stopMonitoring()
        serviceScope.cancel()
    }
}
