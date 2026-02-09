package com.example.zombie_map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.IOException
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --- CLASSES ---
class Zombie(val marker: Marker) {
    var path: ArrayList<GeoPoint>? = null
    var pathIndex: Int = 0
    var isCalculating: Boolean = false
    var lastCalcTime: Long = 0
}

class SafeZone(val center: GeoPoint, val radius: Double, val polygon: Polygon)

class MainActivity : AppCompatActivity() {

    // UI ELEMENTS
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var txtTimer: TextView
    private lateinit var btnPause: Button
    private lateinit var btnMenu: Button
    private lateinit var gameUIContainer: View

    private lateinit var menuContainer: View
    private lateinit var menuContent: View
    private lateinit var btnPlay: Button
    private lateinit var radioEasy: RadioButton
    private lateinit var radioNormal: RadioButton
    private lateinit var radioHard: RadioButton
    private lateinit var seekRadius: Slider
    private lateinit var seekObjectives: Slider
    private lateinit var lblRadius: TextView
    private lateinit var lblObjectives: TextView

    private lateinit var vibrator: Vibrator

    // GAME DATA
    private val zombies = ArrayList<Zombie>()
    private val objectives = ArrayList<Marker>()
    private val safeZones = ArrayList<SafeZone>()
    private var extractionMarker: Marker? = null

    private val handler = Handler(Looper.getMainLooper())
    private var gameRunning = false
    private var isPaused = false
    private var startTime = 0L
    private var timeInPause = 0L
    private var score = 0
    private var lastGlobalApiCallTime = 0L // File d'attente serveur
    private var lastVibrationTime = 0L

    // DEFAULT PARAMS
    private var GAME_RADIUS = 500.0
    private var ZOMBIE_COUNT = 10
    private var OBJECTIVE_COUNT = 5
    private val SAFE_ZONE_RADIUS = 35.0
    private var ZOMBIE_SPEED = 0.0000008

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Plein écran moderne
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        setContentView(R.layout.activity_main)

        // Init Views
        gameUIContainer = findViewById(R.id.gameUIContainer)
        menuContainer = findViewById(R.id.menuContainer)
        menuContent = findViewById(R.id.menuContent)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        map = findViewById(R.id.map)
        txtTimer = findViewById(R.id.txtTimer)
        btnPause = findViewById(R.id.btnPause)
        btnMenu = findViewById(R.id.btnMenu)
        btnPlay = findViewById(R.id.btnPlay)
        radioEasy = findViewById(R.id.radioEasy)
        radioNormal = findViewById(R.id.radioNormal)
        radioHard = findViewById(R.id.radioHard)
        seekRadius = findViewById(R.id.seekRadius)
        seekObjectives = findViewById(R.id.seekObjectives)
        lblRadius = findViewById(R.id.lblRadius)
        lblObjectives = findViewById(R.id.lblObjectives)

        // GESTION INSETS (Empêche l'interface de passer sous la caméra/encoche)
        ViewCompat.setOnApplyWindowInsetsListener(menuContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + 24, bars.top + 24, bars.right + 24, bars.bottom + 24)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(gameUIContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + 16, bars.top + 16, bars.right + 16, bars.bottom + 16)
            insets
        }

        // Config Map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(48.8583, 2.2944))
        applyNightMode()

        // Sliders Listeners
        seekRadius.addOnChangeListener { _, value, _ ->
            lblRadius.text = "ZONE DE JEU: ${value.toInt()}m"
        }
        seekObjectives.addOnChangeListener { _, value, _ ->
            lblObjectives.text = "OBJECTIFS: ${value.toInt()}"
        }

        // Buttons
        btnPlay.setOnClickListener {
            readMenuSettings()
            menuContainer.visibility = View.GONE
            gameUIContainer.visibility = View.VISIBLE
            spawnGame()
        }

        btnMenu.setOnClickListener {
            gameRunning = false
            handler.removeCallbacks(gameLoop)
            gameUIContainer.visibility = View.GONE
            menuContainer.visibility = View.VISIBLE
        }

        btnPause.setOnClickListener { togglePause() }

        checkPermissionsAndStartGps()
    }

    private fun readMenuSettings() {
        if (radioEasy.isChecked) {
            ZOMBIE_COUNT = 10
            ZOMBIE_SPEED = 0.0000008 // Marche lente
        } else if (radioNormal.isChecked) {
            ZOMBIE_COUNT = 15
            ZOMBIE_SPEED = 0.0000018 // Jogging
        } else {
            ZOMBIE_COUNT = 20
            ZOMBIE_SPEED = 0.0000035 // Sprint
        }
        GAME_RADIUS = seekRadius.value.toDouble()
        OBJECTIVE_COUNT = seekObjectives.value.toInt()
    }

    private fun applyNightMode() {
        // Matrice pour inverser les couleurs (Effet Radar)
        val inverseMatrix = floatArrayOf(
            -1.0f, 0.0f, 0.0f, 0.0f, 255f,
            0.0f, -1.0f, 0.0f, 0.0f, 255f,
            0.0f, 0.0f, -1.0f, 0.0f, 255f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f
        )
        map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix(inverseMatrix)))
    }

    private fun togglePause() {
        if (!gameRunning) return
        isPaused = !isPaused
        if (isPaused) {
            btnPause.text = "REPRENDRE"
            btnPause.background.setTint(Color.GREEN)
        } else {
            btnPause.text = "PAUSE"
            btnPause.background.setTint(Color.parseColor("#FFB300"))
            startTime += (System.currentTimeMillis() - timeInPause)
        }
        timeInPause = System.currentTimeMillis()
    }

    // --- LOGIQUE SPAWN ---
    private fun spawnGame() {
        if (!::locationOverlay.isInitialized || locationOverlay.myLocation == null) {
            Toast.makeText(this, "Attends le signal GPS !", Toast.LENGTH_SHORT).show()
            gameUIContainer.visibility = View.GONE
            menuContainer.visibility = View.VISIBLE
            return
        }

        val myPos = locationOverlay.myLocation

        map.overlays.removeAll { it is Marker || it is Polygon }
        zombies.clear()
        objectives.clear()
        safeZones.clear()
        extractionMarker = null
        score = 0
        isPaused = false
        btnPause.text = "PAUSE"

        drawGameBoundary(myPos)

        for (i in 1..3) createSafeZoneStrictlyInside(myPos)
        for (i in 1..OBJECTIVE_COUNT) spawnSmartObjectiveInside(i, myPos)

        // Spawn progressif pour ne pas surcharger le serveur OSRM
        for (i in 1..ZOMBIE_COUNT) {
            handler.postDelayed({
                if (gameRunning) spawnSmartZombieInside(myPos)
            }, (i * 300).toLong())
        }

        map.overlays.add(locationOverlay)
        map.invalidate()

        startTime = System.currentTimeMillis()
        gameRunning = true
        handler.removeCallbacks(gameLoop)
        handler.post(gameLoop)

        Toast.makeText(this, "SURVIS ! ${OBJECTIVE_COUNT} OBJECTIFS", Toast.LENGTH_LONG).show()
    }

    // --- MOUVEMENT & INTELLIGENCE ---
    private fun moveZombie(zombie: Zombie, target: GeoPoint) {
        val currentTime = System.currentTimeMillis()

        if (zombie.path == null || zombie.pathIndex >= zombie.path!!.size) {
            if (!zombie.isCalculating) {
                // File d'attente serveur (1200ms)
                if (currentTime - lastGlobalApiCallTime > 1200) {
                    lastGlobalApiCallTime = currentTime
                    zombie.lastCalcTime = currentTime
                    getRouteForZombie(zombie, target)
                } else {
                    // Fallback : Marche tout droit
                    moveLinearly(zombie, target, ZOMBIE_SPEED * 0.5)
                }
            }
        } else {
            val nextPoint = zombie.path!![zombie.pathIndex]
            val distToNext = distance(zombie.marker.position, nextPoint)
            if (distToNext < ZOMBIE_SPEED) {
                zombie.marker.position = nextPoint
                zombie.pathIndex++
            } else {
                val ratio = ZOMBIE_SPEED / distToNext
                val newLat = zombie.marker.position.latitude + (nextPoint.latitude - zombie.marker.position.latitude) * ratio
                val newLon = zombie.marker.position.longitude + (nextPoint.longitude - zombie.marker.position.longitude) * ratio
                zombie.marker.position = GeoPoint(newLat, newLon)
            }
        }
    }

    private fun moveLinearly(zombie: Zombie, target: GeoPoint, speed: Double) {
        val distTotal = distance(zombie.marker.position, target)
        if (distTotal > speed) {
            val ratio = speed / distTotal
            val newLat = zombie.marker.position.latitude + (target.latitude - zombie.marker.position.latitude) * ratio
            val newLon = zombie.marker.position.longitude + (target.longitude - zombie.marker.position.longitude) * ratio
            zombie.marker.position = GeoPoint(newLat, newLon)
        }
    }

    // --- SERVER API CALLS ---
    private fun spawnSmartZombieInside(centerRef: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val maxDistDegrees = GAME_RADIUS / 111319.0
        val dist = (0.2 + Random.nextDouble() * 0.7) * maxDistDegrees
        val rawLat = centerRef.latitude + dist * cos(angle)
        val rawLon = centerRef.longitude + dist * sin(angle)

        callOsrmNearest(rawLat, rawLon) { point -> createZombieMarker(point) }
    }

    private fun spawnSmartObjectiveInside(index: Int, centerRef: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val maxDistDegrees = GAME_RADIUS / 111319.0
        val dist = Random.nextDouble() * maxDistDegrees
        val rawLat = centerRef.latitude + dist * cos(angle)
        val rawLon = centerRef.longitude + dist * sin(angle)

        callOsrmNearest(rawLat, rawLon) { point -> createMarkerObjective(index, point) }
    }

    // Factorisation de l'appel "Nearest"
    private fun callOsrmNearest(lat: Double, lon: Double, callback: (GeoPoint) -> Unit) {
        val client = OkHttpClient()
        val url = "https://router.project-osrm.org/nearest/v1/walking/$lon,$lat?number=1"
        val request = Request.Builder().url(url).header("User-Agent", "ZombieApp/1.0").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback(GeoPoint(lat, lon)) }
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.body?.string()?.let { jsonString ->
                        val json = JSONObject(jsonString)
                        val waypoints = json.optJSONArray("waypoints")
                        var finalPoint = GeoPoint(lat, lon)
                        if (waypoints != null && waypoints.length() > 0) {
                            val location = waypoints.getJSONObject(0).getJSONArray("location")
                            finalPoint = GeoPoint(location.getDouble(1), location.getDouble(0))
                        }
                        runOnUiThread { callback(finalPoint) }
                    }
                } catch (e: Exception) { runOnUiThread { callback(GeoPoint(lat, lon)) } }
            }
        })
    }

    private fun getRouteForZombie(zombie: Zombie, target: GeoPoint) {
        zombie.isCalculating = true
        val start = zombie.marker.position
        val client = OkHttpClient()
        val url = "https://router.project-osrm.org/route/v1/walking/${start.longitude},${start.latitude};${target.longitude},${target.latitude}?overview=full&geometries=geojson"
        val request = Request.Builder().url(url).header("User-Agent", "ZombieApp/1.0").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { zombie.isCalculating = false }
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) { zombie.isCalculating = false; return }
                    response.body?.string()?.let { jsonString ->
                        val jsonObject = JSONObject(jsonString)
                        val routes = jsonObject.getJSONArray("routes")
                        if (routes.length() > 0) {
                            val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                            val newPath = ArrayList<GeoPoint>()
                            for (i in 0 until coords.length()) {
                                val c = coords.getJSONArray(i)
                                newPath.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                            }
                            runOnUiThread {
                                zombie.path = newPath
                                zombie.pathIndex = 0
                                zombie.isCalculating = false
                            }
                        } else { zombie.isCalculating = false }
                    }
                } catch (e: Exception) { zombie.isCalculating = false }
            }
        })
    }

    // --- MARKER CREATION ---
    private fun createZombieMarker(pos: GeoPoint) {
        if (!gameRunning) return
        val zMarker = Marker(map)
        zMarker.position = pos
        zMarker.title = "ZOMBIE"
        zMarker.icon = ContextCompat.getDrawable(this, R.drawable.ic_zombie_circle)
        zMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.overlays.add(zMarker)
        zombies.add(Zombie(zMarker))
        map.invalidate()
    }

    private fun createMarkerObjective(index: Int, pos: GeoPoint) {
        val checkpoint = Marker(map)
        checkpoint.position = pos
        checkpoint.title = "Objectif $index"
        checkpoint.icon = ContextCompat.getDrawable(this, R.drawable.ic_objective_pin)
        map.overlays.add(checkpoint)
        objectives.add(checkpoint)
        map.invalidate()
    }

    // --- HELPERS (Zones, Boundaries, Distances) ---
    private fun drawGameBoundary(center: GeoPoint) {
        val circlePoints = ArrayList<GeoPoint>()
        for (i in 0..360 step 5) {
            circlePoints.add(GeoPoint(center.latitude + (GAME_RADIUS / 111319f) * cos(Math.toRadians(i.toDouble())),
                center.longitude + (GAME_RADIUS / (111319f * cos(Math.toRadians(center.latitude)))) * sin(Math.toRadians(i.toDouble()))))
        }
        val boundary = Polygon()
        boundary.points = circlePoints
        boundary.fillPaint.color = Color.TRANSPARENT
        boundary.outlinePaint.color = Color.RED
        boundary.outlinePaint.strokeWidth = 5f
        map.overlays.add(0, boundary)
    }

    private fun createSafeZoneStrictlyInside(centerRef: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val maxDistAvailable = GAME_RADIUS - SAFE_ZONE_RADIUS
        val maxDistDegrees = maxDistAvailable / 111319.0
        val dist = (0.2 + Random.nextDouble() * 0.8) * maxDistDegrees
        val zoneLat = centerRef.latitude + dist * cos(angle)
        val zoneLon = centerRef.longitude + dist * sin(angle)
        val center = GeoPoint(zoneLat, zoneLon)

        val circlePoints = ArrayList<GeoPoint>()
        for (i in 0..360 step 10) {
            circlePoints.add(GeoPoint(zoneLat + (SAFE_ZONE_RADIUS / 111319f) * cos(Math.toRadians(i.toDouble())),
                zoneLon + (SAFE_ZONE_RADIUS / (111319f * cos(Math.toRadians(zoneLat)))) * sin(Math.toRadians(i.toDouble()))))
        }
        val zonePoly = Polygon()
        zonePoly.points = circlePoints
        zonePoly.fillPaint.color = Color.argb(60, 0, 255, 0)
        zonePoly.outlinePaint.color = Color.GREEN
        map.overlays.add(0, zonePoly)
        safeZones.add(SafeZone(center, SAFE_ZONE_RADIUS, zonePoly))
    }

    private fun spawnExtraction(myPos: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val maxDistDegrees = GAME_RADIUS / 111319.0
        val dist = (0.85 + Random.nextDouble() * 0.1) * maxDistDegrees
        val exLat = myPos.latitude + dist * cos(angle)
        val exLon = myPos.longitude + dist * sin(angle)

        extractionMarker = Marker(map)
        extractionMarker?.position = GeoPoint(exLat, exLon)
        extractionMarker?.title = "EXTRACTION"
        extractionMarker?.icon = ContextCompat.getDrawable(this, R.drawable.ic_extraction_pin)
        map.overlays.add(extractionMarker)
        map.invalidate()
        Toast.makeText(applicationContext, "EXTRACTION DISPONIBLE !", Toast.LENGTH_LONG).show()
    }

    // --- BOUCLE DE JEU ---
    private val gameLoop = object : Runnable {
        override fun run() {
            if (!gameRunning) return

            if (isPaused) {
                timeInPause = System.currentTimeMillis()
                handler.postDelayed(this, 100)
                return
            }

            val millis = System.currentTimeMillis() - startTime
            val seconds = (millis / 1000).toInt()
            txtTimer.text = String.format("%02d:%02d", seconds / 60, seconds % 60)

            val myPos = locationOverlay.myLocation

            if (myPos != null) {
                // Check Boundary
                if (distance(myPos, map.mapCenter as GeoPoint) > (GAME_RADIUS / 111319.0)) {
                    txtTimer.setTextColor(Color.RED)
                    txtTimer.text = "HORS ZONE !"
                } else {
                    txtTimer.setTextColor(Color.WHITE)
                }

                // Check Safe Zone
                var isSafe = false
                for (zone in safeZones) {
                    if (distance(myPos, zone.center) < (zone.radius / 111319.0)) {
                        isSafe = true
                        txtTimer.setTextColor(Color.GREEN)
                        txtTimer.text = "SÉCURISÉ"
                        break
                    }
                }

                // Update Zombies
                var closestZombieDist = 9999.0
                for (zombie in zombies) {
                    if (!isSafe) {
                        moveZombie(zombie, myPos)
                        val dist = distance(myPos, zombie.marker.position)
                        if (dist < closestZombieDist) closestZombieDist = dist
                        if (dist < 0.00008) {
                            gameOver("TU ES MORT !")
                            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                            return
                        }
                    }
                }
                if (!isSafe) manageVibration(closestZombieDist)

                // Update Objectives
                val collected = ArrayList<Marker>()
                for (obj in objectives) {
                    if (distance(myPos, obj.position) < 0.0002) collected.add(obj)
                }
                for (obj in collected) {
                    map.overlays.remove(obj)
                    objectives.remove(obj)
                    score++
                    Toast.makeText(applicationContext, "Objectif ! ($score/$OBJECTIVE_COUNT)", Toast.LENGTH_SHORT).show()
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                    if (score == OBJECTIVE_COUNT) spawnExtraction(myPos)
                }

                if (extractionMarker != null) {
                    if (distance(myPos, extractionMarker!!.position) < 0.0002) {
                        gameOver("VICTOIRE !")
                        return
                    }
                }
                map.invalidate()
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun manageVibration(closestDist: Double) {
        val currentTime = System.currentTimeMillis()
        if (closestDist < 0.0002) {
            if (currentTime - lastVibrationTime > 1000) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, 255))
                lastVibrationTime = currentTime
            }
        } else if (closestDist < 0.0005) {
            if (currentTime - lastVibrationTime > 2000) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, 100))
                lastVibrationTime = currentTime
            }
        }
    }

    private fun gameOver(message: String) {
        gameRunning = false
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        btnPause.text = "FINI"
    }

    private fun distance(p1: GeoPoint, p2: GeoPoint): Double {
        return sqrt((p1.latitude - p2.latitude).pow(2.0) + (p1.longitude - p2.longitude).pow(2.0))
    }

    private fun checkPermissionsAndStartGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun setupLocationOverlay() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        map.overlays.add(locationOverlay)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        }
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}