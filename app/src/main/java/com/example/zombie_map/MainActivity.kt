package com.example.zombie_map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class ZombieState { WANDERING, CHASING }

class Zombie(val marker: Marker, val spawnLocation: GeoPoint) {
    var state = ZombieState.WANDERING
    var wanderTarget: GeoPoint? = null
    // Chaque zombie a sa propre personnalité de vitesse (0.8x à 1.3x)
    val speedFactor: Double = 0.8 + Random.nextDouble() * 0.5
}

class Objective(val marker: Marker, val halo: Polygon)

class MainActivity : AppCompatActivity(), LocationListener {

    // UI
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay

    // HUD
    private lateinit var gameUIContainer: View
    private lateinit var topControls: View
    private lateinit var bottomHud: View
    private lateinit var pauseOverlay: View
    private lateinit var btnResume: Button

    private lateinit var txtTimer: TextView
    private lateinit var txtObjectives: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var txtNoise: TextView
    private lateinit var btnPause: View
    private lateinit var btnStop: View
    private lateinit var heart1: ImageView
    private lateinit var heart2: ImageView
    private lateinit var heart3: ImageView

    // MENU
    private lateinit var menuContainer: View
    private lateinit var menuContent: View
    private lateinit var btnPlay: Button
    private lateinit var btnShare: Button
    private lateinit var lblResult: TextView
    private lateinit var radioEasy: RadioButton
    private lateinit var radioNormal: RadioButton
    private lateinit var radioHard: RadioButton
    private lateinit var seekRadius: Slider
    private lateinit var seekObjectives: Slider
    private lateinit var lblRadius: TextView
    private lateinit var lblObjectives: TextView

    private lateinit var vibrator: Vibrator
    private lateinit var locationManager: LocationManager

    // DATA
    private val zombies = ArrayList<Zombie>()
    private val objectives = ArrayList<Objective>()
    private var extractionMarker: Marker? = null
    private var extractionHalo: Polygon? = null
    private var playerPathLine: Polyline? = null

    private var gameRunning = false
    private var isPaused = false
    private var startTime = 0L
    private var timeInPause = 0L
    private var score = 0
    private var lives = 3
    private var lastPos: GeoPoint? = null
    private var currentKmph = 0.0f

    // PARAMETRES
    private var GAME_RADIUS = 500.0
    private var OBJECTIVE_TOTAL = 5
    private var ZOMBIE_COUNT = 30
    private var ZOMBIE_SPEED_CHASE = 0.0000020
    private var ZOMBIE_SPEED_WANDER = 0.0000008

    // DETECTIONS (Tu peux ajuster ces valeurs si besoin)
    private val VISUAL_RADIUS = 0.00020 // ~20m (Aveugle)
    private val HEARING_RADIUS = 0.00200 // ~200m (Ouïe fine)
    private val NOISE_THRESHOLD_KMH = 5.0f // Seuil de vitesse
    private val WANDER_RADIUS = 0.0027

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // BINDINGS
        map = findViewById(R.id.map)
        gameUIContainer = findViewById(R.id.gameUIContainer)
        topControls = findViewById(R.id.topControls)
        bottomHud = findViewById(R.id.bottomHud)

        pauseOverlay = findViewById(R.id.pauseOverlay)
        btnResume = findViewById(R.id.btnResume)

        menuContainer = findViewById(R.id.menuContainer)
        menuContent = findViewById(R.id.menuContent)

        txtTimer = findViewById(R.id.txtTimer)
        txtObjectives = findViewById(R.id.txtObjectives)
        txtSpeed = findViewById(R.id.txtSpeed)
        txtNoise = findViewById(R.id.txtNoise)

        heart1 = findViewById(R.id.heart1)
        heart2 = findViewById(R.id.heart2)
        heart3 = findViewById(R.id.heart3)

        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)

        btnPlay = findViewById(R.id.btnPlay)
        btnShare = findViewById(R.id.btnShare)
        lblResult = findViewById(R.id.lblResult)

        radioEasy = findViewById(R.id.radioEasy)
        radioNormal = findViewById(R.id.radioNormal)
        radioHard = findViewById(R.id.radioHard)
        seekRadius = findViewById(R.id.seekRadius)
        seekObjectives = findViewById(R.id.seekObjectives)
        lblRadius = findViewById(R.id.lblRadius)
        lblObjectives = findViewById(R.id.lblObjectives)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // INSETS
        ViewCompat.setOnApplyWindowInsetsListener(menuContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + 24, bars.top + 24, bars.right + 24, bars.bottom + 24)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(topControls) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + v.paddingTop, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomHud) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom + v.paddingBottom)
            insets
        }

        // MAP SETUP
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(19.0)
        map.controller.setCenter(GeoPoint(48.8583, 2.2944))

        val inverseMatrix = floatArrayOf(
            0.6f, 0f, 0f, 0f, 0f,
            0f, 0.6f, 0f, 0f, 0f,
            0f, 0f, 0.6f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix(inverseMatrix)))

        // LISTENERS
        seekRadius.addOnChangeListener { _, value, _ -> lblRadius.text = "ZONE: ${value.toInt()}m" }
        seekObjectives.addOnChangeListener { _, value, _ -> lblObjectives.text = "OBJECTIFS: ${value.toInt()}" }

        btnPlay.setOnClickListener {
            readMenuSettings()
            menuContainer.visibility = View.GONE
            gameUIContainer.visibility = View.VISIBLE
            spawnGame()
        }

        btnShare.setOnClickListener { shareGameResult() }
        btnPause.setOnClickListener { togglePause() }
        btnResume.setOnClickListener { togglePause() }
        btnStop.setOnClickListener { gameOver(false) }

        checkPermissionsAndStartGps()
    }

    private fun togglePause() {
        if (!gameRunning) return
        isPaused = !isPaused

        if (isPaused) {
            pauseOverlay.visibility = View.VISIBLE
            timeInPause = System.currentTimeMillis()
        } else {
            pauseOverlay.visibility = View.GONE
            startTime += (System.currentTimeMillis() - timeInPause)
            handler.post(gameLoop)
        }
    }

    private fun readMenuSettings() {
        // NOMBRE FIXE
        ZOMBIE_COUNT = 20000

        // DIFFICULTE = VITESSE
        if (radioEasy.isChecked) {
            ZOMBIE_SPEED_CHASE = 0.0000010
        } else if (radioNormal.isChecked) {
            ZOMBIE_SPEED_CHASE = 0.0000025
        } else {
            ZOMBIE_SPEED_CHASE = 0.0000055
        }

        GAME_RADIUS = seekRadius.value.toDouble()
        OBJECTIVE_TOTAL = seekObjectives.value.toInt()
    }

    private fun spawnGame() {
        if (!::locationOverlay.isInitialized || locationOverlay.myLocation == null) {
            Toast.makeText(this, "Attends le GPS...", Toast.LENGTH_SHORT).show()
            menuContainer.visibility = View.VISIBLE
            gameUIContainer.visibility = View.GONE
            return
        }

        val myPos = locationOverlay.myLocation

        map.overlays.removeAll { it is Marker || it is Polyline || it is Polygon }
        map.overlays.add(locationOverlay)
        zombies.clear()
        objectives.clear()
        extractionMarker = null
        extractionHalo = null
        score = 0
        lives = 3
        lastPos = myPos
        isPaused = false
        pauseOverlay.visibility = View.GONE

        updateHud() // <-- CETTE FONCTION EST MAINTENANT BIEN DÉFINIE PLUS BAS

        // Traceur
        playerPathLine = Polyline()
        playerPathLine?.outlinePaint?.color = Color.CYAN
        playerPathLine?.outlinePaint?.strokeWidth = 8f
        map.overlays.add(0, playerPathLine)

        // Objectifs
        for (i in 1..OBJECTIVE_TOTAL) spawnSmartObjective(i, myPos)

        // Zombies (20 km)
        val worldSizeKm = 20.0
        val worldSizeDeg = worldSizeKm / 111.32
        for (i in 1..ZOMBIE_COUNT) {
            val angle = Random.nextDouble() * 2 * Math.PI
            val dist = sqrt(Random.nextDouble()) * worldSizeDeg
            val zLat = myPos.latitude + dist * cos(angle)
            val zLon = myPos.longitude + dist * sin(angle)
            createZombie(GeoPoint(zLat, zLon))
        }

        spawnExtraction(myPos, locked = true)

        map.invalidate()
        startTime = System.currentTimeMillis()
        gameRunning = true
        handler.removeCallbacks(gameLoop)
        handler.post(gameLoop)

        Toast.makeText(this, "RÉCUPÈRE LES $OBJECTIVE_TOTAL SACS !", Toast.LENGTH_LONG).show()
    }

    // --- MISE A JOUR GPS POUR VITESSE ---
    override fun onLocationChanged(location: Location) {
        if (!gameRunning) return

        val speed = location.speed * 3.6f
        currentKmph = speed

        txtSpeed.text = String.format("%.1f km/h", currentKmph)

        if (currentKmph > NOISE_THRESHOLD_KMH) {
            txtNoise.text = "BRUYANT (DÉTECTION LARGE !)"
            txtNoise.setTextColor(Color.RED)
            txtSpeed.setTextColor(Color.RED)
        } else {
            txtNoise.text = "SILENCIEUX (INVISIBLE)"
            txtNoise.setTextColor(Color.GREEN)
            txtSpeed.setTextColor(Color.GREEN)
        }
    }

    private val gameLoop = object : Runnable {
        override fun run() {
            if (!gameRunning) return
            if (isPaused) return

            val now = System.currentTimeMillis()
            val millis = now - startTime
            val seconds = (millis / 1000).toInt()
            txtTimer.text = String.format("%02d:%02d", seconds / 60, seconds % 60)

            val myPos = locationOverlay.myLocation ?: return

            if (lastPos != null && distance(lastPos!!, myPos) > 0.000005) {
                playerPathLine?.addPoint(myPos)
                lastPos = myPos
            }

            // --- IA ZOMBIE ---
            val zombiesChasing = ArrayList<Zombie>()

            for (zombie in zombies) {
                val distToPlayer = distance(zombie.marker.position, myPos)

                // 1. DÉTECTION
                if (zombie.state == ZombieState.WANDERING) {
                    // A. Visuelle (Courte)
                    if (distToPlayer < VISUAL_RADIUS) {
                        zombie.state = ZombieState.CHASING
                        zombie.wanderTarget = null
                        zombiesChasing.add(zombie)
                    }
                    // B. Sonore (Longue si bruyant)
                    else if (currentKmph > NOISE_THRESHOLD_KMH && distToPlayer < HEARING_RADIUS) {
                        zombie.state = ZombieState.CHASING
                        zombie.wanderTarget = null
                        zombiesChasing.add(zombie)
                    }
                }

                // 2. MOUVEMENT
                if (zombie.state == ZombieState.CHASING) {
                    if (distToPlayer > HEARING_RADIUS * 1.5) {
                        zombie.state = ZombieState.WANDERING
                        moveWandering(zombie)
                    } else {
                        val realSpeed = ZOMBIE_SPEED_CHASE * zombie.speedFactor
                        moveLinearly(zombie, myPos, realSpeed)

                        if (distToPlayer < 0.00005) {
                            playerHit(zombie)
                            if (lives <= 0) return
                        }
                        zombiesChasing.add(zombie)
                    }
                } else {
                    moveWandering(zombie)
                }
            }

            // 3. MEUTE
            for (hunter in zombiesChasing) {
                for (other in zombies) {
                    if (other.state == ZombieState.WANDERING) {
                        if (distance(hunter.marker.position, other.marker.position) < 0.001) {
                            other.state = ZombieState.CHASING
                        }
                    }
                }
            }

            // OBJECTIFS
            val collected = ArrayList<Objective>()
            for (obj in objectives) {
                if (distance(myPos, obj.marker.position) < 0.00015) collected.add(obj)
            }
            for (obj in collected) {
                map.overlays.remove(obj.marker)
                map.overlays.remove(obj.halo)
                objectives.remove(obj)
                score++
                updateHud()
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

                if (score == OBJECTIVE_TOTAL) {
                    spawnExtraction(myPos, locked = false)
                }
            }

            // FIN
            if (extractionMarker != null && distance(myPos, extractionMarker!!.position) < 0.00015) {
                if (score >= OBJECTIVE_TOTAL) {
                    gameOver(true)
                    return
                }
            }

            map.invalidate()
            handler.postDelayed(this, 100)
        }
    }

    // --- LA FONCTION QUI MANQUAIT EST ICI ---
    private fun updateHud() {
        txtObjectives.text = "$score/$OBJECTIVE_TOTAL"
        heart1.visibility = if (lives >= 1) View.VISIBLE else View.INVISIBLE
        heart2.visibility = if (lives >= 2) View.VISIBLE else View.INVISIBLE
        heart3.visibility = if (lives >= 3) View.VISIBLE else View.INVISIBLE
    }
    // ------------------------------------------

    private fun spawnExtraction(center: GeoPoint, locked: Boolean) {
        val pos: GeoPoint
        if (extractionMarker != null) {
            pos = extractionMarker!!.position
            map.overlays.remove(extractionMarker)
            if (extractionHalo != null) map.overlays.remove(extractionHalo)
        } else {
            val angle = Random.nextDouble() * 2 * Math.PI
            val minRatio = 0.8
            val randomRatio = minRatio + Random.nextDouble() * (1.0 - minRatio)
            val distMeters = GAME_RADIUS * randomRatio
            val distDeg = distMeters / 111319.0
            val lat = center.latitude + distDeg * cos(angle)
            val lon = center.longitude + distDeg * sin(angle)
            pos = GeoPoint(lat, lon)
        }

        extractionMarker = Marker(map)
        extractionMarker?.position = pos
        extractionMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        if (locked) {
            extractionMarker?.title = "EXTRACTION (VERROUILLÉE)"
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_extraction_pin)?.mutate()
            icon?.setTint(Color.RED)
            extractionMarker?.icon = icon
        } else {
            extractionMarker?.title = "EXTRACTION (OUVERTE)"
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_extraction_pin)?.mutate()
            icon?.setTint(Color.GREEN)
            extractionMarker?.icon = icon

            val haloPoints = ArrayList<GeoPoint>()
            val radiusMeters = 30.0
            for (i in 0..360 step 10) {
                haloPoints.add(GeoPoint(
                    pos.latitude + (radiusMeters / 111319f) * cos(Math.toRadians(i.toDouble())),
                    pos.longitude + (radiusMeters / (111319f * cos(Math.toRadians(pos.latitude)))) * sin(Math.toRadians(i.toDouble()))
                ))
            }
            extractionHalo = Polygon()
            extractionHalo?.points = haloPoints
            extractionHalo?.fillPaint?.color = Color.argb(60, 0, 255, 0)
            extractionHalo?.outlinePaint?.color = Color.GREEN
            map.overlays.add(0, extractionHalo)

            Toast.makeText(this, "EXTRACTION DÉVERROUILLÉE ! FONCE !", Toast.LENGTH_LONG).show()
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        map.overlays.add(extractionMarker)
    }

    private fun spawnSmartObjective(index: Int, center: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val maxRadiusDeg = GAME_RADIUS / 111319.0
        val dist = sqrt(Random.nextDouble()) * maxRadiusDeg
        val lat = center.latitude + dist * cos(angle)
        val lon = center.longitude + dist * sin(angle)
        val pos = GeoPoint(lat, lon)

        val haloPoints = ArrayList<GeoPoint>()
        val visualRadius = 20.0
        for (i in 0..360 step 10) {
            haloPoints.add(GeoPoint(
                lat + (visualRadius / 111319f) * cos(Math.toRadians(i.toDouble())),
                lon + (visualRadius / (111319f * cos(Math.toRadians(lat)))) * sin(Math.toRadians(i.toDouble()))
            ))
        }
        val halo = Polygon()
        halo.points = haloPoints
        halo.fillPaint.color = Color.argb(60, 0, 100, 255)
        halo.outlinePaint.color = Color.BLUE
        halo.outlinePaint.strokeWidth = 2f

        val checkpoint = Marker(map)
        checkpoint.position = pos
        checkpoint.icon = ContextCompat.getDrawable(this, R.drawable.ic_bag)
        checkpoint.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        map.overlays.add(0, halo)
        map.overlays.add(checkpoint)
        objectives.add(Objective(checkpoint, halo))
    }

    private fun playerHit(zombie: Zombie) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val pushBackDist = 0.0003
        zombie.marker.position = GeoPoint(zombie.marker.position.latitude + pushBackDist * cos(angle), zombie.marker.position.longitude + pushBackDist * sin(angle))

        // --- CORRECTION : SUPPRESSION DE LA LIGNE "zombie.path = null" QUI PLANTAIT ---
        // Le zombie repart juste en arrière après avoir frappé

        lives--
        updateHud()
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        Toast.makeText(this, "TOUCHÉ ! Vies: $lives", Toast.LENGTH_SHORT).show()
        if (lives <= 0) gameOver(false)
    }

    private fun createZombie(pos: GeoPoint) {
        val zMarker = Marker(map)
        zMarker.position = pos
        zMarker.icon = ContextCompat.getDrawable(this, R.drawable.ic_zombie_circle)
        zMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.overlays.add(zMarker)
        zombies.add(Zombie(zMarker, pos))
    }

    private fun moveWandering(zombie: Zombie) {
        if (zombie.wanderTarget == null) {
            val angle = Random.nextDouble() * 2 * Math.PI
            val dist = Random.nextDouble() * WANDER_RADIUS
            val wLat = zombie.spawnLocation.latitude + dist * cos(angle)
            val wLon = zombie.spawnLocation.longitude + dist * sin(angle)
            zombie.wanderTarget = GeoPoint(wLat, wLon)
        }
        val target = zombie.wanderTarget!!
        if (distance(zombie.marker.position, target) < ZOMBIE_SPEED_WANDER) {
            if (Random.nextBoolean()) zombie.wanderTarget = null
        } else {
            moveLinearly(zombie, target, ZOMBIE_SPEED_WANDER)
        }
    }

    private fun moveLinearly(zombie: Zombie, target: GeoPoint, speed: Double) {
        val distTotal = distance(zombie.marker.position, target)
        if (distTotal > 0) {
            val ratio = speed / distTotal
            val newLat = zombie.marker.position.latitude + (target.latitude - zombie.marker.position.latitude) * ratio
            val newLon = zombie.marker.position.longitude + (target.longitude - zombie.marker.position.longitude) * ratio
            zombie.marker.position = GeoPoint(newLat, newLon)
        }
    }

    private fun gameOver(victory: Boolean) {
        gameRunning = false
        gameUIContainer.visibility = View.GONE
        pauseOverlay.visibility = View.GONE
        menuContainer.visibility = View.VISIBLE
        lblResult.visibility = View.VISIBLE
        btnShare.visibility = View.VISIBLE

        if (victory) {
            lblResult.text = "VICTOIRE !\nTemps: ${txtTimer.text}"
            lblResult.setTextColor(Color.GREEN)
        } else {
            lblResult.text = "GAME OVER"
            lblResult.setTextColor(Color.RED)
        }
    }

    private fun shareGameResult() {
        val rootView = window.decorView.rootView
        val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        rootView.draw(canvas)
        try {
            val cachePath = File(externalCacheDir, "my_images/")
            cachePath.mkdirs()
            val file = File(cachePath, "zombie_run.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()
            val contentUri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "image/png"
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(shareIntent, "Partager"))
        } catch (e: IOException) { }
    }

    private fun distance(p1: GeoPoint, p2: GeoPoint): Double {
        return sqrt((p1.latitude - p2.latitude).pow(2.0) + (p1.longitude - p2.longitude).pow(2.0))
    }

    private fun checkPermissionsAndStartGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
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
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
            } catch (e: SecurityException) {}
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}