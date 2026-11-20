package it.diy.hudnav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var speaker: Speaker
    private val stops = ArrayList<PlaceHit>()
    private var choices = emptyList<RoutePlan>()
    private var chosen = 0
    private var addingStop = false
    private var editing: Int? = null
    private var avoidMotorway = false
    private var sheetExpanded = false
    private var keepChoice = false
    private var tellStretch = false
    private var baselineMeters = -1.0
    private var navFollowArmed = false
    private var snapRoute = false
    private var routePts = emptyList<LatLon>()
    private var routeCum = DoubleArray(0)
    private var routeFrame: Geo.RouteFrame? = null
    private val glidePoint = GeoPoint(0.0, 0.0)
    private val lookPoint = GeoPoint(0.0, 0.0)
    private val centerPoint = GeoPoint(0.0, 0.0)
    private var goalS = 0.0
    private var shownS = 0.0
    private var spokenKey = ""
    private var stickyStatus = ""
    private val ghostLines = ArrayList<Polyline>()
    private val viaMarkers = ArrayList<Marker>()
    private var destMarker: Marker? = null
    private var riderMarker: Marker? = null
    private var casing: Polyline? = null
    private var line: Polyline? = null
    private val poiMarkers = ArrayList<Marker>()
    private val cameraMarkers = ArrayList<Marker>()
    private var planCamGen = 0
    private var planCamBusy = false
    private val pixA = android.graphics.Point()
    private val pixB = android.graphics.Point()
    private val pinBitmaps = HashMap<Int, Bitmap>()
    /** Solo il percorso. Il nome del punto e Overpass non lo fanno aspettare. */
    private val net = Executors.newSingleThreadExecutor()
    private val names = Executors.newSingleThreadExecutor()
    private val side = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private var pendingSim = false
    private var follow = false
    private var holdFollow = false
    private var headingUp = false
    private var haveGoal = false
    private var haveShown = false
    private var goalLat = 0.0
    private var goalLon = 0.0
    private var goalBearing = 0f
    private var shownLat = 0.0
    private var shownLon = 0.0
    private var shownBearing = 0f
    private var restoring = true
    private var previewGen = 0
    private var previewWhenLocated = false
    private var brightToken = 0
    private var shown = emptyList<LatLon>()
    private var lastSpoken = ""
    private var located = false

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val location = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (location) {
            listenIdle()
            startNav(pendingSim)
        } else status("Serve la posizione per navigare.")
    }

    private val idleFix = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (HudState.running.value == true) return
            val here = LatLon(location.latitude, location.longitude)
            HudState.rider.value = here
            if (!located) {
                located = true
                map.controller.setCenter(GeoPoint(here.lat, here.lon))
            }
            if (previewWhenLocated && stops.isNotEmpty() && HudState.running.value != true) preview()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HudLink.init(this)
        speaker = Speaker(this)
        speaker.onReady = { ok ->
            if (!ok && speaker.enabled) runOnUiThread { status("Voce italiana non disponibile sul telefono.") }
        }
        Configuration.getInstance().userAgentValue = "HUD-Moto-nav/1.0"
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.setTilesScaledToDpi(true)
        map.setMapOrientation(0f)
        map.isHorizontalMapRepetitionEnabled = false
        map.isVerticalMapRepetitionEnabled = false
        map.controller.setZoom(14.0)
        centerOnDevice()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                return HudState.running.value != true
            }
            override fun longPressHelper(p: GeoPoint) = false
        }))
        var tapX = 0f
        var tapY = 0f
        var tapAt = 0L
        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapX = event.x
                    tapY = event.y
                    tapAt = event.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    follow = false
                    ui.removeCallbacks(resumeFollow)
                    paintLocate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = event.x - tapX
                        val dy = event.y - tapY
                        val slop = 18f * resources.displayMetrics.density
                        val tap = dx * dx + dy * dy <= slop * slop && event.eventTime - tapAt < 350
                        if (tap && !markerUnder(event)) {
                            val p = map.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                            placeOnMap(p)
                        }
                    }
                    if (!holdFollow && HudState.running.value == true) {
                        ui.removeCallbacks(resumeFollow)
                        ui.postDelayed(resumeFollow, 4000)
                    }
                }
            }
            false
        }
        headingUp = ctrlPrefs().getBoolean("heading", false)
        paintHeading()
        findViewById<TextView>(R.id.heading).setOnClickListener {
            headingUp = !headingUp
            ctrlPrefs().edit().putBoolean("heading", headingUp).apply()
            paintHeading()
            if (!headingUp) map.setMapOrientation(0f)
            else if (follow) stepGlide()
        }

        findViewById<Button>(R.id.search).setOnClickListener { search() }
        findViewById<EditText>(R.id.query).setOnEditorActionListener { _, _, _ ->
            search(); true
        }
        findViewById<TextView>(R.id.go).setOnClickListener { arm(false) }
        findViewById<TextView>(R.id.sim).setOnClickListener { arm(true) }
        findViewById<TextView>(R.id.stop).setOnClickListener {
            startService(Intent(this, NavService::class.java).setAction(NavService.ACTION_STOP))
        }
        findViewById<TextView>(R.id.locate).setOnClickListener { recenter() }
        findViewById<View>(R.id.speed_disc).setOnClickListener { cycleSimSpeed() }
        findViewById<TextView>(R.id.overview).setOnClickListener { showAll() }
        findViewById<Button>(R.id.fav).setOnClickListener { toggleFav() }
        findViewById<TextView>(R.id.hud).setOnClickListener { toggleHud() }
        findViewById<TextView>(R.id.cat_fuel).setOnClickListener { loadPois("fuel") }
        findViewById<TextView>(R.id.cat_park).setOnClickListener { loadPois("parking") }
        findViewById<TextView>(R.id.cat_food).setOnClickListener { loadPois("food") }
        findViewById<TextView>(R.id.cat_aid).setOnClickListener { loadPois("aid") }
        findViewById<Button>(R.id.lay_icon).setOnClickListener { pickLayout(0) }
        findViewById<Button>(R.id.lay_map).setOnClickListener { pickLayout(1) }
        findViewById<Button>(R.id.lay_curve).setOnClickListener { pickLayout(2) }

        val box = ctrlPrefs()
        findViewById<Slider>(R.id.bright).value = box.getInt("bright", 255).coerceIn(0, 255).toFloat()
        findViewById<MaterialSwitch>(R.id.mirror).isChecked = box.getBoolean("mirror", false)
        findViewById<MaterialSwitch>(R.id.power).isChecked = box.getBoolean("power", true)
        findViewById<MaterialSwitch>(R.id.voice).isChecked = speaker.enabled
        paintLayouts(box.getInt("layout", 0))
        findViewById<Slider>(R.id.bright).addOnChangeListener { _, value, fromUser ->
            if (!fromUser || restoring) return@addOnChangeListener
            val token = ++brightToken
            val level = value.toInt()
            ui.postDelayed({
                if (token != brightToken) return@postDelayed
                ctrlPrefs().edit().putInt("bright", level).apply()
                sendCtrl(3, level, "luminosità $level")
            }, 280)
        }
        findViewById<MaterialSwitch>(R.id.mirror).setOnCheckedChangeListener { _, on ->
            if (restoring) return@setOnCheckedChangeListener
            ctrlPrefs().edit().putBoolean("mirror", on).apply()
            sendCtrl(2, if (on) 1 else 0, if (on) "specchio attivo" else "specchio spento")
        }
        findViewById<MaterialSwitch>(R.id.power).setOnCheckedChangeListener { _, on ->
            if (restoring) return@setOnCheckedChangeListener
            ctrlPrefs().edit().putBoolean("power", on).apply()
            sendCtrl(4, if (on) 1 else 0, if (on) "acceso" else "spento")
        }
        findViewById<MaterialSwitch>(R.id.voice).setOnCheckedChangeListener { _, on ->
            if (restoring) return@setOnCheckedChangeListener
            speaker.enabled = on
            if (on) speaker.say("Voce attiva")
        }
        restoring = false

        HudState.status.observe(this) { text ->
            val view = findViewById<TextView>(R.id.status)
            val shownText = text.orEmpty()
            view.text = shownText
            view.visibility = if (shownText.isBlank()) View.GONE else View.VISIBLE
            val keep = sticky(shownText)
            if (keep) stickyStatus = shownText
            ui.removeCallbacks(expireStatus)
            if (shownText.isNotBlank() && !keep) ui.postDelayed(expireStatus, 5000)
            if (HudState.running.value == true && HudState.eta.value.isNullOrBlank()) {
                findViewById<TextView>(R.id.eta).text = if (shownText.isBlank()) "Cerco la strada…" else shownText
            }
        }
        HudState.maneuverDist.observe(this) { showBanner() }
        HudState.maneuver.observe(this) {
            findViewById<TextView>(R.id.maneuver).text = it
            showBanner()
            speak(it)
        }
        HudState.maneuverMeters.observe(this) { speak(HudState.maneuver.value) }
        HudState.turn.observe(this) { findViewById<TextView>(R.id.turn_icon).text = arrow(it ?: 0) }
        HudState.road.observe(this) { text ->
            val view = findViewById<TextView>(R.id.road)
            view.text = text
            view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        HudState.thenNext.observe(this) { text ->
            val view = findViewById<TextView>(R.id.then_next)
            view.text = text
            view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        HudState.alert.observe(this) { text ->
            val view = findViewById<TextView>(R.id.alert)
            view.text = text
            view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            if (!text.isNullOrBlank() && text != lastAlert) {
                lastAlert = text
                speaker.say(text)
            }
            if (text.isNullOrBlank()) lastAlert = ""
        }
        HudState.remainDist.observe(this) { findViewById<TextView>(R.id.remain_dist).text = it }
        HudState.remainTime.observe(this) { findViewById<TextView>(R.id.remain_time).text = it }
        HudState.eta.observe(this) { text ->
            val view = findViewById<TextView>(R.id.eta)
            if (text.isNullOrBlank() && HudState.running.value == true) {
                view.text = HudState.status.value?.takeIf { it.isNotBlank() } ?: "Cerco la strada…"
            } else {
                view.text = text
            }
            if (!text.isNullOrBlank() && navFollowArmed) {
                navFollowArmed = false
                engageFollow(snap = true, zoom = true)
            }
        }
        HudState.speed.observe(this) { paintSpeed(it) }
        HudState.limit.observe(this) { paintLimit(it) }
        HudState.running.observe(this) { on -> applyRunning(on == true) }
        HudState.track.observe(this) { pts ->
            if (!pts.isNullOrEmpty()) {
                routePts = pts
                routeCum = Geo.cumulate(pts)
                routeFrame = Geo.RouteFrame(routePts, routeCum)
                if (HudState.running.value == true) drawTrack(pts)
            } else if (HudState.running.value != true) {
                routePts = emptyList()
                routeCum = DoubleArray(0)
                routeFrame = null
                snapRoute = false
                if (stops.isNotEmpty() && choices.isNotEmpty()) drawChoices()
                else if (stops.isEmpty()) drawTrack(emptyList())
            }
        }
        HudState.rider.observe(this) { it?.let { moveRider(it) } }
        HudState.destination.observe(this) { it?.let { moveDest(it) } }
        HudState.cameras.observe(this) { drawCameras(it) }
        HudState.pois.observe(this) { drawPois(it) }
        refreshPlaces()
        findViewById<View>(R.id.sheet_handle).setOnClickListener {
            sheetExpanded = !sheetExpanded
            paintSheet()
        }
        findViewById<View>(R.id.add_stop).setOnClickListener {
            addingStop = !addingStop
            if (addingStop) editing = null
            paintTrip()
            status(if (addingStop) "Cerca o tocca la mappa: la tappa va prima dell'arrivo." else "")
        }
        findViewById<TextView>(R.id.avoid).setOnClickListener {
            avoidMotorway = !avoidMotorway
            paintAvoid()
            saveTrip()
            if (stops.isNotEmpty()) preview()
        }
        val trip = TripStore.load(this)
        avoidMotorway = trip.avoid
        paintAvoid()
        if (trip.stops.isNotEmpty()) {
            stops.clear()
            stops.addAll(trip.stops)
            chosen = trip.choice
            keepChoice = true
            paintTrip()
            preview()
        }
        paintSheet()
        applyOrientation()
    }

    private val expireStatus = Runnable {
        val current = HudState.status.value.orEmpty()
        val keep = sticky(current)
        if (!keep) HudState.status.value = stickyStatus
    }

    private fun paintSheet() {
        val running = HudState.running.value == true
        val show = if (sheetExpanded && !running) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cats_scroll).visibility = show
        findViewById<View>(R.id.credit).visibility = if (sheetExpanded && HudState.running.value != true) View.VISIBLE else View.GONE
        if (!sheetExpanded) closeHits()
        refreshPlaces()
        findViewById<TextView>(R.id.sheet_handle).text = if (sheetExpanded) "Compatta" else "Mostra altro"
        val scroll = findViewById<View>(R.id.sheet_scroll)
        val fraction = when {
            running -> 0.32f
            sheetExpanded -> 0.48f
            else -> 0.34f
        }
        val cap = (resources.displayMetrics.heightPixels * fraction).toInt()
        scroll.layoutParams.height = cap
        scroll.requestLayout()
        scroll.post {
            val body = findViewById<View>(R.id.sheet_body)
            val want = if (body.height in 1 until cap) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                cap
            }
            if (scroll.layoutParams.height != want) {
                scroll.layoutParams.height = want
                scroll.requestLayout()
            }
        }
    }

    private fun paintAvoid() {
        val view = findViewById<TextView>(R.id.avoid)
        view.setTextColor(if (avoidMotorway) colorOf(R.color.on_accent) else colorOf(R.color.ink))
        view.setBackgroundResource(if (avoidMotorway) R.drawable.bg_primary else R.drawable.bg_chip)
    }

    private fun applyOrientation() {
        val land = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = findViewById<LinearLayout>(R.id.root)
        val mapFrame = findViewById<View>(R.id.map_frame)
        val bottom = findViewById<View>(R.id.bottom_col)
        if (land) {
            root.orientation = LinearLayout.HORIZONTAL
            mapFrame.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f)
            val side = (340 * resources.displayMetrics.density).toInt()
            bottom.layoutParams = LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT)
        } else {
            root.orientation = LinearLayout.VERTICAL
            mapFrame.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            bottom.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun saveTrip() {
        TripStore.savePlan(this, stops, chosen, avoidMotorway)
    }

    private var lastAlert = ""

    private fun ctrlPrefs() = getSharedPreferences("hud_ctrl", MODE_PRIVATE)

    private fun colorOf(id: Int) = ContextCompat.getColor(this, id)

    private fun status(text: String) {
        HudState.status.value = text
    }

    private val resumeFollow = Runnable {
        if (HudState.running.value == true && !holdFollow) engageFollow(snap = false, zoom = false)
    }

    private val glide = object : Runnable {
        override fun run() {
            val moving = follow && haveGoal
            if (moving) stepGlide()
            ui.postDelayed(this, if (moving) 50 else 500)
        }
    }

    private fun applyRunning(on: Boolean) {
        if (on) {
            navFollowArmed = true
            follow = false
            holdFollow = true
            ui.removeCallbacks(resumeFollow)
            HudState.pois.value = emptyList()
            closeHits()
            paintLocate()
            findViewById<View>(R.id.credit).visibility = View.GONE
        } else {
            navFollowArmed = false
            follow = false
            holdFollow = false
            ui.removeCallbacks(resumeFollow)
            map.setMapOrientation(0f)
            paintLocate()
        }
        findViewById<View>(R.id.sheet_nav).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sheet_idle).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.search_bar).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.sim).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.go).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.stop).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.overview).visibility = if (on || shown.size > 1) View.VISIBLE else View.GONE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!on) {
            lastSpoken = ""
            spokenKey = ""
        }
        paintSheet()
        paintLimit(HudState.limit.value)
        drawCameras(HudState.cameras.value ?: emptyList())
        if (!on) {
            val plan = choices.getOrNull(chosen)
            if (plan != null && !planCamBusy && HudState.cameras.value.isNullOrEmpty()) {
                loadPlanCameras(plan.points)
            }
        }
    }

    private fun showBanner() {
        val text = HudState.maneuver.value
        findViewById<View>(R.id.banner).visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.maneuver_dist).text = HudState.maneuverDist.value ?: ""
    }

    private fun speak(maneuver: String?) {
        if (maneuver.isNullOrBlank() || HudState.running.value != true) return
        val meters = HudState.maneuverMeters.value ?: -1
        val bucket = when (meters) {
            in 350..700 -> "500"
            in 120..349 -> "200"
            in 0..119 -> "now"
            else -> return
        }
        val key = "$maneuver|$bucket"
        if (key == spokenKey) return
        spokenKey = key
        val lead = when (bucket) {
            "500" -> "Tra 500 metri, "
            "200" -> "Tra 200 metri, "
            else -> ""
        }
        speaker.say(lead + maneuver.replaceFirstChar { it.lowercase() })
    }

    private fun arrow(turn: Int): String = when (turn) {
        1 -> "↖"
        2 -> "←"
        3 -> "↙"
        4 -> "↗"
        5 -> "→"
        6 -> "↘"
        7 -> "↩"
        8 -> "⟳"
        9 -> "●"
        else -> "↑"
    }

    private fun paintSpeed(kmh: String?) {
        val view = findViewById<TextView>(R.id.speed)
        view.text = if (kmh.isNullOrBlank()) "—" else kmh
        val lim = HudState.limit.value?.toIntOrNull()
        val spd = kmh?.toIntOrNull()
        view.setTextColor(
            if (lim != null && spd != null && spd > lim + 4) colorOf(R.color.overspeed) else colorOf(R.color.on_banner)
        )
    }

    private fun paintLimit(text: String?) {
        val view = findViewById<TextView>(R.id.limit)
        if (text.isNullOrBlank()) {
            if (HudState.running.value == true) {
                view.visibility = View.VISIBLE
                view.text = "n.d."
            } else {
                view.visibility = View.GONE
            }
        } else {
            view.visibility = View.VISIBLE
            view.text = text
        }
        paintSpeed(HudState.speed.value)
    }

    private fun missingPerms(): Array<String> {
        val need = ArrayList<String>()
        fun add(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        return need.toTypedArray()
    }

    private fun arm(simulate: Boolean) {
        pendingSim = simulate
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (location) startNav(simulate) else askPerms.launch(missingPerms())
    }

    private fun startNav(simulate: Boolean) {
        val d = stops.lastOrNull()
        if (d == null) {
            status("Prima scegli l'arrivo.")
            return
        }
        hideKeyboard()
        addingStop = false
        editing = null
        previewGen++
        val name = stops.joinToString(" · ") { it.name.substringBefore(",").take(22) }
        val intent = Intent(this, NavService::class.java)
            .putExtra(NavService.EXTRA_LAT, d.lat)
            .putExtra(NavService.EXTRA_LON, d.lon)
            .putExtra(NavService.EXTRA_NAME, name)
            .putExtra(NavService.EXTRA_SIM, simulate)
            .putExtra(NavService.EXTRA_LATS, stops.map { it.lat }.toDoubleArray())
            .putExtra(NavService.EXTRA_LONS, stops.map { it.lon }.toDoubleArray())
            .putExtra(NavService.EXTRA_CHOICE, chosen)
            .putExtra(NavService.EXTRA_AVOID, avoidMotorway)
        findViewById<EditText>(R.id.query).setText("")
        saveTrip()
        val here = HudState.rider.value ?: lastKnown()
        if (here != null) {
            intent.putExtra(NavService.EXTRA_FROM_LAT, here.lat)
            intent.putExtra(NavService.EXTRA_FROM_LON, here.lon)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun search() {
        val q = findViewById<EditText>(R.id.query).text.toString().trim()
        if (q.length < 2) return
        hideKeyboard()
        status("Cerco…")
        val bias = HudState.rider.value ?: mapCenter()
        names.execute {
            val found = try {
                Net.search(q, bias)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread { showHits(found, "Nessun risultato.", poi = false) }
        }
    }

    private fun loadPois(kind: String) {
        hideKeyboard()
        val center = HudState.rider.value ?: mapCenter()
        status("Cerco sulla mappa…")
        side.execute {
            val found = try {
                Net.pois(center, kind)
            } catch (_: Exception) {
                runOnUiThread { status("Ricerca non riuscita. Riprova.") }
                return@execute
            }
            runOnUiThread {
                HudState.pois.value = found
                showHits(found, "Nessun punto qui intorno.", poi = true)
            }
        }
    }

    private fun showHits(found: List<PlaceHit>, empty: String, poi: Boolean) {
        val hits = findViewById<LinearLayout>(R.id.hits)
        val scroll = findViewById<ScrollView>(R.id.hits_scroll)
        hits.removeAllViews()
        if (found.isEmpty()) {
            scroll.visibility = View.GONE
            status(empty)
            return
        }
        sheetExpanded = true
        paintSheet()
        val row = (72 * resources.displayMetrics.density).toInt()
        val cap = (240 * resources.displayMetrics.density).toInt()
        scroll.layoutParams.height = minOf(cap, row * (found.size + 1))
        scroll.visibility = View.VISIBLE
        status("")
        hits.addView(TextView(this).apply {
            text = "Chiudi"
            setTextAppearance(R.style.TextAppearance_HudNav_LabelLarge)
            setTextColor(colorOf(R.color.accent))
            val h = (8 * resources.displayMetrics.density).toInt()
            val v = (12 * resources.displayMetrics.density).toInt()
            setPadding(h, v + 8, h, v)
            setOnClickListener { closeHits() }
        })
        for (hit in found) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val h = (8 * resources.displayMetrics.density).toInt()
                val v = (14 * resources.displayMetrics.density).toInt()
                setPadding(h, v, h, v)
                val out = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener {
                    closeHits()
                    if (poi) askPlace(hit) else placePicked(hit, recenter = true)
                }
            }
            rowView.addView(TextView(this).apply {
                text = hit.name
                setTextAppearance(R.style.TextAppearance_HudNav_BodyLarge)
                setTextColor(colorOf(R.color.ink))
            })
            if (hit.kind.isNotBlank() && hit.kind !in setOf("fuel", "parking", "food", "aid")) {
                rowView.addView(TextView(this).apply {
                    text = hit.kind
                    setTextAppearance(R.style.TextAppearance_HudNav_BodySmall)
                    setTextColor(colorOf(R.color.sub))
                })
            }
            if (hits.childCount > 1) {
                hits.addView(View(this).apply {
                    setBackgroundColor(colorOf(R.color.line))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    )
                })
            }
            hits.addView(rowView)
        }
    }

    private fun closeHits() {
        findViewById<LinearLayout>(R.id.hits).removeAllViews()
        findViewById<View>(R.id.hits_scroll).visibility = View.GONE
    }

    private fun askPlace(hit: PlaceHit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(hit.name)
            .setMessage(hit.kind.ifBlank { "Punto trovato sulla mappa" })
            .setPositiveButton("Portami qui") { _, _ -> setDestination(hit, recenter = true) }
            .setNeutralButton("Aggiungi tappa") { _, _ ->
                if (stops.isEmpty()) setDestination(hit, recenter = true) else addStop(hit)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun placePicked(hit: PlaceHit, recenter: Boolean) {
        val edit = editing
        when {
            edit != null && edit in stops.indices -> replaceStop(edit, hit)
            addingStop && stops.isNotEmpty() -> addStop(hit)
            else -> setDestination(hit, recenter)
        }
    }

    /** Tocca la mappa: mette l'arrivo, o la tappa se stai aggiungendo o modificando. */
    private fun placeOnMap(p: GeoPoint): Boolean {
        if (HudState.running.value == true) return false
        if (findViewById<View>(R.id.hits_scroll).visibility == View.VISIBLE) closeHits()
        val raw = PlaceHit(p.latitude, p.longitude, "Punto sulla mappa")
        val edit = editing
        val index = when {
            edit != null && edit in stops.indices -> {
                replaceStop(edit, raw, remember = false)
                edit
            }
            addingStop && stops.isNotEmpty() -> {
                val at = stops.size - 1
                addStop(raw, remember = false)
                if (stops.getOrNull(at)?.lat == raw.lat && stops.getOrNull(at)?.lon == raw.lon) at else -1
            }
            else -> {
                setDestination(raw, recenter = false, remember = false)
                stops.lastIndex
            }
        }
        if (index >= 0) nameMapPoint(index, raw, previewGen)
        return true
    }

    private fun markerUnder(event: MotionEvent): Boolean {
        for (i in map.overlays.indices.reversed()) {
            val marker = map.overlays[i] as? Marker ?: continue
            if (marker === riderMarker) continue
            if (marker.hitTest(event, map)) return true
        }
        return false
    }

    private fun setDestination(hit: PlaceHit, recenter: Boolean, remember: Boolean = true) {
        stops.clear()
        stops.add(hit)
        addingStop = false
        editing = null
        chosen = 0
        if (remember) Memory.remember(this, hit)
        refreshPlaces()
        paintTrip()
        findViewById<TextView>(R.id.summary).text = "Calcolo il percorso…"
        findViewById<EditText>(R.id.query).setText("")
        status("Arrivo: ${hit.name}")
        if (recenter) {
            follow = false
            holdFollow = true
            ui.removeCallbacks(resumeFollow)
        }
        preview()
    }

    private fun addStop(hit: PlaceHit, remember: Boolean = true) {
        if (stops.isEmpty()) {
            setDestination(hit, recenter = true, remember = remember)
            return
        }
        if (stops.size >= 7) {
            status("Puoi aggiungere fino a 6 tappe.")
            addingStop = false
            paintTrip()
            return
        }
        if (stops.any { Geo.hav(LatLon(it.lat, it.lon), LatLon(hit.lat, hit.lon)) < 40 }) {
            status("Questo punto c'è già nel percorso.")
            return
        }
        stops.add(stops.size - 1, hit)
        addingStop = false
        editing = null
        chosen = 0
        if (remember) Memory.remember(this, hit)
        refreshPlaces()
        paintTrip()
        findViewById<TextView>(R.id.summary).text = "Calcolo il percorso…"
        status("Tappa aggiunta prima dell'arrivo.")
        preview()
    }

    private fun replaceStop(index: Int, hit: PlaceHit, remember: Boolean = true) {
        if (index !in stops.indices) return
        val clash = stops.withIndex().any { (i, other) ->
            i != index && Geo.hav(LatLon(other.lat, other.lon), LatLon(hit.lat, hit.lon)) < 40
        }
        if (clash) {
            status("Questo punto c'è già nel percorso.")
            paintStopMarkers()
            return
        }
        stops[index] = hit
        editing = null
        addingStop = false
        chosen = 0
        if (remember) Memory.remember(this, hit)
        refreshPlaces()
        paintTrip()
        findViewById<TextView>(R.id.summary).text = "Calcolo il percorso…"
        status(if (index == stops.lastIndex) "Arrivo aggiornato." else "Tappa ${index + 1} aggiornata.")
        preview()
    }

    private fun moveStop(from: Int, to: Int) {
        if (from !in stops.indices || to !in stops.indices || from == to) return
        val hit = stops.removeAt(from)
        stops.add(to, hit)
        editing = null
        addingStop = false
        tellStretch = true
        chosen = 0
        paintTrip()
        findViewById<TextView>(R.id.summary).text = "Calcolo il percorso…"
        status("Ordine aggiornato.")
        preview()
    }

    private fun removeStop(index: Int) {
        if (index !in stops.indices) return
        stops.removeAt(index)
        addingStop = false
        editing = when (val current = editing) {
            null -> null
            index -> null
            else -> if (current > index) current - 1 else current
        }
        chosen = 0
        if (stops.isEmpty()) {
            clearTrip()
            return
        }
        paintTrip()
        findViewById<TextView>(R.id.summary).text = "Calcolo il percorso…"
        preview()
    }

    private fun clearTrip() {
        stops.clear()
        choices = emptyList()
        chosen = 0
        addingStop = false
        editing = null
        clearGhosts()
        shown = emptyList()
        casing?.let { map.overlays.remove(it) }
        line?.let { map.overlays.remove(it) }
        casing = null
        line = null
        for (m in viaMarkers) map.overlays.remove(m)
        viaMarkers.clear()
        destMarker?.let { map.overlays.remove(it) }
        destMarker = null
        paintTrip()
        paintAlts()
        findViewById<TextView>(R.id.summary).text = ""
        findViewById<TextView>(R.id.dest_title).text = "Scegli l'arrivo"
        findViewById<View>(R.id.fav).visibility = View.GONE
        findViewById<View>(R.id.overview).visibility = View.GONE
        map.invalidate()
    }

    private fun paintTrip() {
        val title = findViewById<TextView>(R.id.dest_title)
        val last = stops.lastOrNull()
        title.text = if (last == null) "Scegli l'arrivo" else last.name.substringBefore(",").ifBlank { last.name }
        findViewById<View>(R.id.fav).visibility = if (last == null) View.GONE else View.VISIBLE
        paintFav()
        val row = findViewById<LinearLayout>(R.id.stops)
        val scroll = findViewById<View>(R.id.stop_row)
        row.removeAllViews()
        if (stops.isEmpty()) {
            scroll.visibility = View.GONE
            paintStopMarkers()
            saveTrip()
            return
        }
        scroll.visibility = View.VISIBLE
        val gap = (8 * resources.displayMetrics.density).toInt()
        stops.forEachIndexed { index, hit ->
            row.addView(stopCard(index, hit, gap))
        }
        val add = findViewById<TextView>(R.id.add_stop)
        add.text = if (addingStop) "Annulla" else getString(R.string.add_stop)
        add.setTextColor(if (addingStop) colorOf(R.color.on_accent) else colorOf(R.color.ink))
        add.setBackgroundResource(if (addingStop) R.drawable.bg_primary else R.drawable.bg_chip)
        paintStopMarkers()
        saveTrip()
        val focus = editing ?: stops.lastIndex
        row.post {
            val card = row.getChildAt(focus) ?: return@post
            findViewById<HorizontalScrollView>(R.id.stop_scroll).smoothScrollTo(card.left, 0)
        }
    }

    private fun stopCard(index: Int, hit: PlaceHit, gap: Int): LinearLayout {
        val selected = editing == index
        val name = hit.name.substringBefore(",").take(18).ifBlank { hit.name }
        val prefix = if (index == stops.lastIndex) "Arrivo" else "Tappa ${index + 1}"
        val ink = if (selected) colorOf(R.color.on_accent) else colorOf(R.color.ink)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (selected) R.drawable.bg_primary else R.drawable.bg_chip
            )
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gap }
            addView(stopBit("$prefix · $name", ink) {
                addingStop = false
                if (editing == index) {
                    editing = null
                    status("")
                } else {
                    editing = index
                    val kind = if (index == stops.lastIndex) "l'arrivo" else "la tappa ${index + 1}"
                    status("Cerca o tocca la mappa per cambiare $kind. Puoi anche trascinare il pin.")
                }
                paintTrip()
            })
            if (index > 0) addView(stopBit("←", ink) { moveStop(index, index - 1) })
            if (index < stops.lastIndex) addView(stopBit("→", ink) { moveStop(index, index + 1) })
            addView(stopBit("×", ink) { removeStop(index) })
        }
    }

    private fun stopBit(label: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_HudNav_LabelLarge)
            setTextColor(color)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad + 6, pad, pad + 6, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextAppearance(R.style.TextAppearance_HudNav_LabelLarge)
            gravity = android.view.Gravity.CENTER
            setTextColor(if (selected) colorOf(R.color.on_accent) else colorOf(R.color.ink))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (selected) R.drawable.bg_primary else R.drawable.bg_chip
            )
            val h = (16 * resources.displayMetrics.density).toInt()
            val v = (10 * resources.displayMetrics.density).toInt()
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun nameMapPoint(index: Int, raw: PlaceHit, gen: Int) {
        names.execute {
            val named = try {
                Net.reverse(LatLon(raw.lat, raw.lon))
            } catch (_: Exception) {
                null
            } ?: return@execute
            runOnUiThread {
                if (gen != previewGen) return@runOnUiThread
                val current = stops.getOrNull(index) ?: return@runOnUiThread
                if (Geo.hav(LatLon(current.lat, current.lon), LatLon(raw.lat, raw.lon)) > 8) return@runOnUiThread
                if (named.name == "Punto sulla mappa") return@runOnUiThread
                stops[index] = PlaceHit(raw.lat, raw.lon, named.name, named.kind)
                Memory.remember(this, stops[index])
                paintTrip()
            }
        }
    }

    private fun preview() {
        val through = stops.map { LatLon(it.lat, it.lon) }
        if (through.isEmpty()) return
        val known = HudState.rider.value ?: lastKnown()
        if (known == null) {
            val center = mapCenter()
            val placed = kotlin.math.abs(center.lat) > 1.0 || kotlin.math.abs(center.lon) > 1.0
            if (!placed) {
                previewWhenLocated = true
                findViewById<TextView>(R.id.summary).text = "Cerco la posizione…"
                return
            }
        }
        previewWhenLocated = false
        val gen = ++previewGen
        val from = known ?: mapCenter()
        net.execute {
            val plans = try {
                Net.routes(from, through, avoidMotorway)
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen == previewGen) {
                        choices = emptyList()
                        planCamGen++
                        planCamBusy = false
                        HudState.cameras.value = emptyList()
                        paintAlts()
                        findViewById<TextView>(R.id.summary).text = e.message ?: "Percorso non trovato"
                    }
                }
                return@execute
            }
            runOnUiThread {
                if (gen != previewGen || HudState.running.value == true) return@runOnUiThread
                choices = plans
                if (!keepChoice) chosen = 0
                keepChoice = false
                chosen = chosen.coerceIn(0, plans.lastIndex)
                drawChoices()
                paintAlts()
                val plan = plans[chosen]
                findViewById<TextView>(R.id.summary).text = "${fmtMin(plan.seconds)} · ${fmtDist(plan.meters)}"
                if (Net.avoidFellBack) status("Senza autostrada non c'è una strada: uso la più veloce.")
                else if (avoidMotorway && Net.usedMotorcycle) status("Percorso moto, senza autostrade.")
                else if (Net.usedMotorcycle) status("Percorso per moto.")
                else if (avoidMotorway) status("Percorso senza autostrade.")
                if (tellStretch && baselineMeters > 0 && plan.meters - baselineMeters > 800) {
                    status("+${fmtDist(plan.meters - baselineMeters)} rispetto all'ordine precedente.")
                }
                tellStretch = false
                baselineMeters = plan.meters
                saveTrip()
                findViewById<View>(R.id.overview).visibility = View.VISIBLE
                showAll()
                loadPlanCameras(plan.points)
            }
        }
    }

    private fun pick(index: Int) {
        if (index !in choices.indices) return
        chosen = index
        saveTrip()
        drawChoices()
        paintAlts()
        val plan = choices[index]
        findViewById<TextView>(R.id.summary).text = "${fmtMin(plan.seconds)} · ${fmtDist(plan.meters)}"
        loadPlanCameras(plan.points)
    }

    private fun paintAlts() {
        val row = findViewById<LinearLayout>(R.id.alts)
        val scroll = findViewById<View>(R.id.alt_scroll)
        row.removeAllViews()
        if (choices.size < 2 || HudState.running.value == true) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val gap = (8 * resources.displayMetrics.density).toInt()
        choices.forEachIndexed { index, plan ->
            val card = chip(
                "${plan.label}\n${fmtMin(plan.seconds)} · ${fmtDist(plan.meters)}",
                selected = index == chosen
            ) { pick(index) }
            card.setLineSpacing(0f, 1.15f)
            (card.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            row.addView(card)
        }
    }

    private fun toggleFav() {
        val hit = stops.lastOrNull() ?: return
        val on = Memory.toggleFavorite(this, hit)
        paintFav()
        refreshPlaces()
        status(if (on) "Aggiunto ai preferiti." else "Tolto dai preferiti.")
    }

    private fun paintFav() {
        val hit = stops.lastOrNull()
        val on = hit != null && Memory.isFavorite(this, hit)
        findViewById<Button>(R.id.fav).text = if (on) "★" else "☆"
    }

    private fun refreshPlaces() {
        val favs = if (sheetExpanded) Memory.favorites(this) else emptyList()
        val recent = if (sheetExpanded) Memory.recents(this) else emptyList()
        fillChips(R.id.favs, R.id.fav_scroll, R.id.fav_label, favs)
        fillChips(R.id.recents, R.id.recent_scroll, R.id.recent_label, recent)
    }

    private fun fillChips(rowId: Int, scrollId: Int, labelId: Int, items: List<PlaceHit>) {
        val row = findViewById<LinearLayout>(rowId)
        row.removeAllViews()
        val show = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(scrollId).visibility = show
        findViewById<View>(labelId).visibility = show
        for (hit in items) {
            val b = TextView(this).apply {
                text = hit.name.substringBefore(",").take(28)
                setTextAppearance(R.style.TextAppearance_HudNav_LabelLarge)
                setTextColor(colorOf(R.color.ink))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                val h = (16 * resources.displayMetrics.density).toInt()
                val v = (10 * resources.displayMetrics.density).toInt()
                setPadding(h, v, h, v)
                val gap = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
                setOnClickListener { placePicked(hit, recenter = true) }
            }
            row.addView(b)
        }
    }

    private fun recenter() {
        val here = HudState.rider.value ?: lastKnown()
        if (here == null) {
            status("Posizione non ancora disponibile.")
            return
        }
        goalLat = here.lat
        goalLon = here.lon
        haveGoal = true
        engageFollow(snap = true, zoom = true)
    }

    private fun showAll() {
        if (shown.size < 2) return
        follow = false
        holdFollow = true
        ui.removeCallbacks(resumeFollow)
        paintLocate()
        map.setMapOrientation(0f)
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        val box = ArrayList<LatLon>()
        if (choices.isEmpty()) box.addAll(shown) else choices.forEach { box.addAll(it.points) }
        for (p in box) {
            if (p.lat > north) north = p.lat
            if (p.lat < south) south = p.lat
            if (p.lon > east) east = p.lon
            if (p.lon < west) west = p.lon
        }
        val density = resources.displayMetrics.density
        val bounds = BoundingBox(north, east, south, west)
        map.post {
            val banner = findViewById<View>(R.id.banner)
            val top = if (banner.visibility == View.VISIBLE && banner.height > 0) {
                banner.height + (16 * density).toInt()
            } else {
                (28 * density).toInt()
            }
            val pad = maxOf(top, (104 * density).toInt())
            if (map.width > pad * 2 && map.height > pad * 2) {
                map.zoomToBoundingBox(bounds, true, pad)
            }
        }
    }

    private fun toggleHud() {
        val panel = findViewById<View>(R.id.hud_panel)
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun pickLayout(which: Int) {
        ctrlPrefs().edit().putInt("layout", which).apply()
        paintLayouts(which)
        sendCtrl(1, which, listOf("icona", "mappa", "curva")[which])
    }

    private fun paintLayouts(which: Int) {
        val ids = intArrayOf(R.id.lay_icon, R.id.lay_map, R.id.lay_curve)
        val radius = resources.getDimensionPixelSize(R.dimen.corner_full)
        val accent = colorOf(R.color.accent)
        val idle = Color.TRANSPARENT
        val ink = colorOf(R.color.ink)
        val onAccent = colorOf(R.color.on_accent)
        for (i in ids.indices) {
            val b = findViewById<com.google.android.material.button.MaterialButton>(ids[i])
            val on = i == which
            b.cornerRadius = radius
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) accent else idle)
            b.setTextColor(if (on) onAccent else ink)
        }
    }

    private fun sendCtrl(cmd: Int, arg: Int, label: String) {
        status("Invio al display…")
        HudLink.control(cmd, arg) { ok ->
            status(if (ok) "Display: $label" else "Display non raggiunto. Scheda accesa e vicina?")
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): LatLon? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val locations = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val fix = try {
                locations.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || fix.time > best.time) best = fix
        }
        val chosen = best ?: return null
        return LatLon(chosen.latitude, chosen.longitude)
    }

    private fun centerOnDevice() {
        val here = lastKnown() ?: return
        located = true
        map.controller.setCenter(GeoPoint(here.lat, here.lon))
    }

    private fun mapCenter(): LatLon {
        val c = map.mapCenter
        return LatLon(c.latitude, c.longitude)
    }

    private fun moveDest(p: LatLon) {
        val marker = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.icon = pin(colorOf(R.color.pin_dest))
            map.overlays.add(it)
            destMarker = it
        }
        marker.position = GeoPoint(p.lat, p.lon)
        marker.title = stops.lastOrNull()?.name ?: "Arrivo"
        marker.infoWindow = null
        val index = stops.lastIndex
        if (index >= 0) bindStopMarker(marker, index)
        map.invalidate()
    }

    private fun moveRider(p: LatLon) {
        ensureRider()
        val kmh = HudState.speed.value?.toIntOrNull() ?: 0
        val kmhMatch = if (kmh >= 8) HudState.bearing.value?.toDouble() ?: Double.NaN else Double.NaN
        val hint = if (snapRoute) goalS else Double.NaN
        val pr = if (routePts.size >= 2 && HudState.running.value == true) {
            routeFrame?.project(p.lat, p.lon, hint, kmhMatch)
        } else {
            null
        }
        if (pr != null && pr.second <= 30.0) {
            snapRoute = true
            goalS = pr.first
            if (!haveShown || kotlin.math.abs(shownS - goalS) > 500) shownS = goalS
            val on = Geo.pointAt(routePts, routeCum, goalS)
            goalLat = on.lat
            goalLon = on.lon
            if (kmh < 5) {
                val ahead = Geo.pointAt(routePts, routeCum, (goalS + 28.0).coerceAtMost(Geo.length(routeCum)))
                if (Geo.hav(on, ahead) > 3) goalBearing = Geo.bearing(on, ahead).toFloat()
            } else {
                goalBearing = HudState.bearing.value ?: goalBearing
            }
        } else {
            snapRoute = false
            goalLat = p.lat
            goalLon = p.lon
            if (kmh >= 5) goalBearing = HudState.bearing.value ?: goalBearing
        }
        haveGoal = true
        if (!follow) {
            val at = if (snapRoute) Geo.pointAt(routePts, routeCum, goalS) else p
            riderMarker?.position = GeoPoint(at.lat, at.lon)
            riderMarker?.rotation = if (headingUp) 0f else goalBearing
            map.invalidate()
        }
    }

    private fun engageFollow(snap: Boolean, zoom: Boolean) {
        follow = true
        holdFollow = false
        ui.removeCallbacks(resumeFollow)
        val here = HudState.rider.value ?: lastKnown()
        if (here != null) {
            goalLat = here.lat
            goalLon = here.lon
            goalBearing = HudState.bearing.value ?: goalBearing
            haveGoal = true
            ensureRider()
            if (snap || !haveShown) {
                shownLat = goalLat
                shownLon = goalLon
                shownBearing = goalBearing
                haveShown = true
            }
        }
        if (zoom && haveGoal) map.controller.setZoom(16.5)
        paintLocate()
        if (haveGoal) stepGlide()
        ui.removeCallbacks(glide)
        ui.post(glide)
    }

    private fun stepGlide() {
        if (!haveShown) {
            shownLat = goalLat
            shownLon = goalLon
            shownBearing = goalBearing
            haveShown = true
        } else if (snapRoute && routePts.size >= 2) {
            shownS += (goalS - shownS) * 0.16
            val on = Geo.pointAt(routePts, routeCum, shownS)
            shownLat = on.lat
            shownLon = on.lon
            var turn = goalBearing - shownBearing
            while (turn > 180f) turn -= 360f
            while (turn < -180f) turn += 360f
            shownBearing += turn * 0.16f
        } else {
            shownLat += (goalLat - shownLat) * 0.16
            shownLon += (goalLon - shownLon) * 0.16
            var turn = goalBearing - shownBearing
            while (turn > 180f) turn -= 360f
            while (turn < -180f) turn += 360f
            shownBearing += turn * 0.16f
        }
        val marker = riderMarker ?: return
        glidePoint.setCoords(shownLat, shownLon)
        marker.position = glidePoint
        marker.rotation = if (headingUp) 0f else shownBearing
        val target = if (headingUp) lookAhead() else glidePoint
        val want = if (headingUp) -shownBearing else 0f
        var spin = want - map.mapOrientation
        while (spin > 180f) spin -= 360f
        while (spin < -180f) spin += 360f
        if (pixelGap(target) < 1.5 && kotlin.math.abs(spin) < 0.4f) return
        if (kotlin.math.abs(spin) >= 0.4f) map.setMapOrientation(want)
        map.controller.setCenter(target)
        map.invalidate()
    }

    private fun pixelGap(target: GeoPoint): Double {
        centerPoint.setCoords(map.mapCenter.latitude, map.mapCenter.longitude)
        val a = map.projection.toPixels(centerPoint, pixA)
        val b = map.projection.toPixels(target, pixB)
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        return kotlin.math.hypot(dx, dy)
    }

    private fun lookAhead(): GeoPoint {
        val meters = 320.0
        val rad = Math.toRadians(shownBearing.toDouble())
        val lat = shownLat + meters * kotlin.math.cos(rad) / 111320.0
        val lon = shownLon + meters * kotlin.math.sin(rad) / (111320.0 * kotlin.math.cos(Math.toRadians(shownLat)))
        lookPoint.setCoords(lat, lon)
        return lookPoint
    }

    private fun ensureRider() {
        if (riderMarker != null) return
        riderMarker = Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.icon = riderArrow()
            it.infoWindow = null
            it.setOnMarkerClickListener { _, _ -> false }
            it.setPanToView(false)
            it.position = GeoPoint(goalLat, goalLon)
            map.overlays.add(it)
        }
    }

    private fun paintHeading() {
        val view = findViewById<TextView>(R.id.heading)
        view.text = if (headingUp) "⟳" else "N"
        view.setTextColor(if (headingUp) colorOf(R.color.on_accent) else colorOf(R.color.ink))
        view.setBackgroundResource(if (headingUp) R.drawable.bg_primary else R.drawable.bg_fab)
    }

    private fun paintLocate() {
        val view = findViewById<TextView>(R.id.locate)
        val paused = HudState.running.value == true && !follow
        view.setTextColor(if (paused) colorOf(R.color.on_accent) else colorOf(R.color.accent))
        view.setBackgroundResource(if (paused) R.drawable.bg_primary else R.drawable.bg_fab)
    }

    private fun riderArrow(): BitmapDrawable {
        val key = 0xA11
        val bmp = pinBitmaps.getOrPut(key) {
            val s = (36 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val made = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(made)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            val cx = s / 2f
            canvas.drawCircle(cx, cx, s * 0.34f, paint)
            paint.color = colorOf(R.color.route)
            canvas.drawCircle(cx, cx, s * 0.28f, paint)
            paint.color = Color.WHITE
            val tip = s * 0.16f
            val base = s * 0.62f
            canvas.drawPath(android.graphics.Path().apply {
                moveTo(cx, tip)
                lineTo(cx - s * 0.16f, base)
                lineTo(cx, base - s * 0.12f)
                lineTo(cx + s * 0.16f, base)
                close()
            }, paint)
            made
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun drawChoices() {
        clearGhosts()
        val plans = choices
        if (plans.isEmpty()) return
        val sel = chosen.coerceIn(0, plans.lastIndex)
        drawTrack(plans[sel].points)
        for (i in plans.indices) {
            if (i == sel) continue
            val geos = plans[i].points.map { GeoPoint(it.lat, it.lon) }
            val ghost = Polyline(map).apply {
                setPoints(geos)
                outlinePaint.color = colorOf(R.color.route_ghost)
                outlinePaint.strokeWidth = 6f * resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                setOnClickListener { _, _, _ ->
                    pick(i)
                    true
                }
            }
            map.overlays.add(0, ghost)
            ghostLines.add(ghost)
        }
        for (m in viaMarkers) raise(m)
        raise(destMarker)
        raise(riderMarker)
        map.invalidate()
    }

    private fun clearGhosts() {
        for (g in ghostLines) map.overlays.remove(g)
        ghostLines.clear()
    }

    private fun paintStopMarkers() {
        for (m in viaMarkers) map.overlays.remove(m)
        viaMarkers.clear()
        val vias = if (stops.size <= 1) emptyList() else stops.dropLast(1)
        vias.forEachIndexed { index, hit ->
            val marker = Marker(map)
            marker.position = GeoPoint(hit.lat, hit.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = hit.name
            marker.icon = numberedPin(index + 1)
            marker.infoWindow = null
            bindStopMarker(marker, index)
            viaMarkers.add(marker)
            map.overlays.add(marker)
        }
        val last = stops.lastOrNull()
        if (last != null) moveDest(LatLon(last.lat, last.lon))
        else {
            destMarker?.let { map.overlays.remove(it) }
            destMarker = null
        }
        map.invalidate()
    }

    private fun bindStopMarker(marker: Marker, index: Int) {
        val live = HudState.running.value != true
        marker.setDraggable(live)
        marker.setOnMarkerClickListener { _, _ ->
            if (!live) return@setOnMarkerClickListener false
            addingStop = false
            if (editing == index) {
                editing = null
                status("")
            } else {
                editing = index
                val kind = if (index == stops.lastIndex) "l'arrivo" else "la tappa ${index + 1}"
                status("Cerca o tocca la mappa per cambiare $kind. Puoi anche trascinare il pin.")
            }
            paintTrip()
            true
        }
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {
                editing = index
                addingStop = false
            }
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                val current = stops.getOrNull(index) ?: return
                val p = marker.position
                val raw = PlaceHit(p.latitude, p.longitude, current.name)
                replaceStop(index, raw, remember = false)
                nameMapPoint(index, raw, previewGen)
            }
        })
    }

    private fun numberedPin(n: Int): BitmapDrawable {
        val key = 0x510000 + n
        val bmp = pinBitmaps.getOrPut(key) {
            val s = (28 * resources.displayMetrics.density).toInt().coerceAtLeast(36)
            val made = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(made)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = colorOf(R.color.route)
            val c = s / 2f
            canvas.drawCircle(c, c, c - 2f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = s * 0.46f
            val y = c - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(n.toString(), c, y, paint)
            made
        }
        return BitmapDrawable(resources, bmp)
    }

    /** La linea non deve mangiare il tocco: senno' non si puo' mettere l'arrivo sulla strada. */
    private fun routeLine(geos: List<GeoPoint>, color: Int, widthDp: Float): Polyline {
        return Polyline(map).apply {
            setPoints(geos)
            outlinePaint.color = color
            outlinePaint.strokeWidth = widthDp * resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            infoWindow = null
            setOnClickListener { _, _, _ -> false }
        }
    }

    private fun drawTrack(pts: List<LatLon>) {
        if (HudState.running.value == true) clearGhosts()
        shown = pts
        casing?.let { map.overlays.remove(it) }
        line?.let { map.overlays.remove(it) }
        if (pts.size < 2) {
            map.invalidate()
            return
        }
        val geos = pts.map { GeoPoint(it.lat, it.lon) }
        val under = routeLine(geos, Color.WHITE, 12f)
        val over = routeLine(geos, colorOf(R.color.route), 8f)
        map.overlays.add(0, under)
        map.overlays.add(1, over)
        casing = under
        line = over
        for (m in viaMarkers) raise(m)
        raise(destMarker)
        raise(riderMarker)
        findViewById<View>(R.id.overview).visibility = View.VISIBLE
        map.invalidate()
    }

    private fun sticky(text: String): Boolean {
        return text.contains("HUD") || text.startsWith("Navigo") ||
            text.startsWith("Simulazione") || text.startsWith("Sei arrivato") ||
            text.startsWith("Fermo")
    }

    private fun loadPlanCameras(pts: List<LatLon>, keep: Boolean = false) {
        if (pts.size < 2) return
        val gen = ++planCamGen
        planCamBusy = true
        if (!keep) HudState.cameras.value = emptyList()
        side.execute {
            val found = try {
                Net.cameras(pts)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (gen != planCamGen) return@runOnUiThread
                planCamBusy = false
                if (!found.isNullOrEmpty()) HudState.cameras.value = found
                else if (!keep && HudState.cameras.value.isNullOrEmpty() && HudState.running.value != true) {
                    loadPlanCameras(pts, keep = true)
                }
            }
        }
    }

    private fun cycleSimSpeed() {
        if (HudState.running.value != true || HudState.simulating.value != true) return
        NavEngine.cycleSimSpeed()
        status("Simulazione a ${NavEngine.simKmh.toInt()} km/h.")
    }

    private fun drawCameras(items: List<RoadAlert>) {
        for (m in cameraMarkers) map.overlays.remove(m)
        cameraMarkers.clear()
        if (HudState.running.value == true) {
            map.invalidate()
            return
        }
        for (item in items) {
            val marker = Marker(map)
            marker.position = GeoPoint(item.lat, item.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = item.label
            marker.infoWindow = null
            marker.setOnMarkerClickListener { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(item.label)
                    .setMessage("Segnalazione fissa di OpenStreetMap, non un avviso live.")
                    .setNegativeButton("Chiudi", null)
                    .show()
                true
            }
            val camera = item.label.startsWith("Autovelox") || item.label.startsWith("Tutor")
            marker.icon = pin(colorOf(if (camera) R.color.pin_camera else R.color.pin_danger), 11)
            cameraMarkers.add(marker)
            map.overlays.add(marker)
        }
        raise(destMarker)
        raise(riderMarker)
        map.invalidate()
    }

    private fun drawPois(items: List<PlaceHit>) {
        for (m in poiMarkers) map.overlays.remove(m)
        poiMarkers.clear()
        for (item in items) {
            val marker = Marker(map)
            marker.position = GeoPoint(item.lat, item.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = item.name
            marker.icon = pin(poiColor(item.kind))
            marker.setOnMarkerClickListener { _, _ ->
                askPlace(item)
                true
            }
            poiMarkers.add(marker)
            map.overlays.add(marker)
        }
        raise(destMarker)
        raise(riderMarker)
        map.invalidate()
    }

    private fun poiColor(kind: String): Int = when (kind) {
        "fuel" -> colorOf(R.color.pin_fuel)
        "parking" -> colorOf(R.color.route)
        "food" -> colorOf(R.color.pin_food)
        else -> colorOf(R.color.pin_danger)
    }

    private fun raise(marker: Marker?) {
        if (marker == null) return
        map.overlays.remove(marker)
        map.overlays.add(marker)
    }

    private fun pin(color: Int, radiusDp: Int = 14): BitmapDrawable {
        val key = color * 31 + radiusDp
        val bmp = pinBitmaps.getOrPut(key) {
            val r = (radiusDp * resources.displayMetrics.density).toInt().coerceAtLeast(10)
            val made = Bitmap.createBitmap(r * 2, r * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(made)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            canvas.drawCircle(r.toFloat(), r.toFloat(), r - 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.WHITE
            canvas.drawCircle(r.toFloat(), r.toFloat(), r - 3.5f, paint)
            made
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000.0)

    private fun fmtMin(sec: Double): String {
        val min = (sec / 60.0).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    @SuppressLint("MissingPermission")
    private fun listenIdle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val locations = getSystemService(LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locations.isProviderEnabled(p)) {
                locations.requestLocationUpdates(p, 2000L, 0f, idleFix, Looper.getMainLooper())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        ui.removeCallbacks(glide)
        ui.post(glide)
        applyOrientation()
        if (missingPerms().isEmpty() || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) listenIdle()
    }

    @SuppressLint("MissingPermission")
    override fun onPause() {
        val locations = getSystemService(LOCATION_SERVICE) as LocationManager
        locations.removeUpdates(idleFix)
        ui.removeCallbacks(glide)
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(glide)
        ui.removeCallbacks(resumeFollow)
        speaker.shutdown()
        net.shutdownNow()
        names.shutdownNow()
        side.shutdownNow()
        super.onDestroy()
    }
}
