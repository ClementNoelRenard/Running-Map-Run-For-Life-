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
import okhttp3.*
import org.json.JSONObject
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
    var path: ArrayList<GeoPoint>? = null
    var pathIndex: Int = 0
    var isCalculating: Boolean = false
    var lastCalcTime: Long = 0
    var wanderTarget: GeoPoint? = null
}

// Nouvelle classe pour gérer le Marker ET le Halo bleu ensemble
class Objective(val marker: Marker, val halo: Polygon)

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay

    // HUD
    private lateinit var gameUIContainer: View
    private lateinit var topControls: View
    private lateinit var bottomHud: View
    private lateinit var pauseOverlay: View // L'écran avec le gros bouton Reprendre
    private lateinit var btnResume: Button

    private lateinit var txtTimer: TextView
    private lateinit var txtObjectives: TextView
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

    // DATA
    private val zombies = ArrayList<Zombie>()
    private val objectives = ArrayList<Objective>() // On stocke des objets complets maintenant
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
    private var distanceTraveled = 0.0

    // PARAMETRES
    private var GAME_RADIUS = 500.0
    private var OBJECTIVE_TOTAL = 5
    private var ZOMBIE_COUNT = 30
    private var ZOMBIE_SPEED_CHASE = 0.0000020
    private var ZOMBIE_SPEED_WANDER = 0.0000008
    private val DETECTION_RADIUS = 0.0009
    private val WANDER_RADIUS = 0.0027

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        setContentView(R.layout.activity_main)

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

        // INSETS (Marges auto)
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

        // Filtre sombre
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

        // PAUSE LOGIC
        btnPause.setOnClickListener { togglePause() }
        btnResume.setOnClickListener { togglePause() }

        // ABANDON
        btnStop.setOnClickListener { gameOver(false) }

        checkPermissionsAndStartGps()
    }

    private fun togglePause() {
        if (!gameRunning) return
        isPaused = !isPaused

        if (isPaused) {
            // Afficher l'écran pause
            pauseOverlay.visibility = View.VISIBLE
            timeInPause = System.currentTimeMillis()
        } else {
            // Cacher l'écran pause
            pauseOverlay.visibility = View.GONE
            startTime += (System.currentTimeMillis() - timeInPause)
            handler.post(gameLoop)
        }
    }

    private fun readMenuSettings() {
        if (radioEasy.isChecked) {
            ZOMBIE_COUNT = 10
            ZOMBIE_SPEED_CHASE = 0.0000010
        } else if (radioNormal.isChecked) {
            ZOMBIE_COUNT = 30
            ZOMBIE_SPEED_CHASE = 0.0000022
        } else {
            ZOMBIE_COUNT = 50
            ZOMBIE_SPEED_CHASE = 0.0000045
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

        // Reset
        map.overlays.removeAll { it is Marker || it is Polyline || it is Polygon }
        map.overlays.add(locationOverlay)
        zombies.clear()
        objectives.clear()
        extractionMarker = null
        extractionHalo = null
        score = 0
        lives = 3
        distanceTraveled = 0.0
        lastPos = myPos
        isPaused = false
        pauseOverlay.visibility = View.GONE

        updateHud()

        // Traceur
        playerPathLine = Polyline()
        playerPathLine?.outlinePaint?.color = Color.CYAN
        playerPathLine?.outlinePaint?.strokeWidth = 8f
        map.overlays.add(0, playerPathLine)

        // 1. Spawn Objectifs
        for (i in 1..OBJECTIVE_TOTAL) spawnSmartObjective(i, myPos)

        // 2. Spawn Zombies
        for (i in 1..ZOMBIE_COUNT) {
            val angle = Random.nextDouble() * 2 * Math.PI
            val dist = 0.0005 + Random.nextDouble() * (GAME_RADIUS / 111319.0)
            val zLat = myPos.latitude + dist * cos(angle)
            val zLon = myPos.longitude + dist * sin(angle)
            createZombie(GeoPoint(zLat, zLon))
        }

        // 3. Spawn Extraction (VERROUILLÉE AU DÉBUT)
        spawnExtraction(myPos, locked = true)

        map.invalidate()
        startTime = System.currentTimeMillis()
        gameRunning = true
        handler.removeCallbacks(gameLoop)
        handler.post(gameLoop)

        Toast.makeText(this, "RÉCUPÈRE LES $OBJECTIVE_TOTAL SACS !", Toast.LENGTH_LONG).show()
    }

    // --- ICI EST LA CORRECTION : spawnExtraction est sortie de spawnGame ---
    private fun spawnExtraction(center: GeoPoint, locked: Boolean) {
        // Si l'extraction existe déjà (on la met juste à jour), on garde la même position
        val pos: GeoPoint
        if (extractionMarker != null) {
            pos = extractionMarker!!.position
            map.overlays.remove(extractionMarker) // On enlève l'ancien marker
            if (extractionHalo != null) map.overlays.remove(extractionHalo) // On enlève l'ancien halo
        } else {
            // Sinon on calcule une nouvelle position loin
            val angle = Random.nextDouble() * 2 * Math.PI
            val dist = 0.004 // Environ 400m
            val lat = center.latitude + dist * cos(angle)
            val lon = center.longitude + dist * sin(angle)
            pos = GeoPoint(lat, lon)
        }

        // 1. Création du Marker
        extractionMarker = Marker(map)
        extractionMarker?.position = pos
        extractionMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        if (locked) {
            // ÉTAT VERROUILLÉ (Rouge)
            extractionMarker?.title = "EXTRACTION (VERROUILLÉE)"
            // On utilise ton image, mais on la teinte en ROUGE
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_extraction_pin)?.mutate()
            icon?.setTint(Color.RED)
            extractionMarker?.icon = icon
        } else {
            // ÉTAT OUVERT (Vert + Halo)
            extractionMarker?.title = "EXTRACTION (OUVERTE)"
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_extraction_pin)?.mutate()
            icon?.setTint(Color.GREEN)
            extractionMarker?.icon = icon

            // Ajout du Halo Vert pour dire "C'est ici !"
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

    private fun updateHud() {
        txtObjectives.text = "$score/$OBJECTIVE_TOTAL"
        heart1.visibility = if (lives >= 1) View.VISIBLE else View.INVISIBLE
        heart2.visibility = if (lives >= 2) View.VISIBLE else View.INVISIBLE
        heart3.visibility = if (lives >= 3) View.VISIBLE else View.INVISIBLE
    }

    private val gameLoop = object : Runnable {
        override fun run() {
            if (!gameRunning) return
            if (isPaused) return // On arrête la boucle si pause

            val now = System.currentTimeMillis()
            val millis = now - startTime
            val seconds = (millis / 1000).toInt()
            txtTimer.text = String.format("%02d:%02d", seconds / 60, seconds % 60)

            val myPos = locationOverlay.myLocation ?: return

            // Traceur
            if (lastPos != null && distance(lastPos!!, myPos) > 0.000005) {
                playerPathLine?.addPoint(myPos)
                lastPos = myPos
            }

            // Zombies
            for (zombie in zombies) {
                val distToPlayer = distance(zombie.marker.position, myPos)
                if (zombie.state == ZombieState.WANDERING && distToPlayer < DETECTION_RADIUS) {
                    zombie.state = ZombieState.CHASING
                    zombie.path = null
                }

                if (zombie.state == ZombieState.CHASING) {
                    moveChasing(zombie, myPos)
                    if (distToPlayer < 0.00005) {
                        playerHit(zombie)
                        if (lives <= 0) return
                    }
                } else {
                    moveWandering(zombie)
                }
            }

            // GESTION DES OBJECTIFS
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

                // SI ON A TOUT : DÉVERROUILLER L'EXTRACTION (Passer en vert)
                if (score == OBJECTIVE_TOTAL) {
                    // On ne crée pas une nouvelle, on met à jour l'existante
                    spawnExtraction(myPos, locked = false)
                }
            }

            // GESTION DE LA FIN (EXTRACTION)
            if (extractionMarker != null && distance(myPos, extractionMarker!!.position) < 0.00015) {
                if (score >= OBJECTIVE_TOTAL) {
                    // C'EST GAGNÉ
                    gameOver(true)
                    return
                } else {
                    // ON EST ARRIVÉ MAIS PAS FINI
                    val missing = OBJECTIVE_TOTAL - score
                    // On peut ajouter un Toast ici, mais attention à ne pas spammer
                    // Toast.makeText(applicationContext, "Verrouillé ! Il manque $missing objectifs.", Toast.LENGTH_SHORT).show()
                }
            }

            map.invalidate()
            handler.postDelayed(this, 100)
        }
    }

    private fun spawnSmartObjective(index: Int, center: GeoPoint) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val dist = 0.001 + Random.nextDouble() * 0.003
        val lat = center.latitude + dist * cos(angle)
        val lon = center.longitude + dist * sin(angle)

        val pos = GeoPoint(lat, lon)

        // 1. Créer le Halo Bleu
        val haloPoints = ArrayList<GeoPoint>()
        val radiusMeters = 20.0 // 20m de rayon pour le visuel
        for (i in 0..360 step 10) {
            haloPoints.add(GeoPoint(
                lat + (radiusMeters / 111319f) * cos(Math.toRadians(i.toDouble())),
                lon + (radiusMeters / (111319f * cos(Math.toRadians(lat)))) * sin(Math.toRadians(i.toDouble()))
            ))
        }
        val halo = Polygon()
        halo.points = haloPoints
        halo.fillPaint.color = Color.argb(60, 0, 100, 255) // Bleu transparent
        halo.outlinePaint.color = Color.BLUE
        halo.outlinePaint.strokeWidth = 2f

        // 2. Créer le Marker
        val checkpoint = Marker(map)
        checkpoint.position = pos
        checkpoint.icon = ContextCompat.getDrawable(this, R.drawable.ic_bag)
        checkpoint.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        // 3. Ajouter dans l'ordre (Halo SOUS le sac)
        map.overlays.add(0, halo)
        map.overlays.add(checkpoint)

        objectives.add(Objective(checkpoint, halo))
    }

    private fun playerHit(zombie: Zombie) {
        val angle = Random.nextDouble() * 2 * Math.PI
        val pushBackDist = 0.0003
        zombie.marker.position = GeoPoint(zombie.marker.position.latitude + pushBackDist * cos(angle), zombie.marker.position.longitude + pushBackDist * sin(angle))
        zombie.path = null
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

    private fun moveChasing(zombie: Zombie, target: GeoPoint) {
        if (zombie.path == null || zombie.pathIndex >= zombie.path!!.size) {
            if (!zombie.isCalculating) {
                val now = System.currentTimeMillis()
                if (now - zombie.lastCalcTime > 2000) {
                    zombie.lastCalcTime = now
                    getRouteForZombie(zombie, target)
                } else {
                    moveLinearly(zombie, target, ZOMBIE_SPEED_CHASE)
                }
            }
        } else {
            val next = zombie.path!![zombie.pathIndex]
            if (distance(zombie.marker.position, next) < ZOMBIE_SPEED_CHASE) {
                zombie.marker.position = next
                zombie.pathIndex++
            } else {
                moveLinearly(zombie, next, ZOMBIE_SPEED_CHASE)
            }
        }
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

    private fun gameOver(victory: Boolean) {
        gameRunning = false
        gameUIContainer.visibility = View.GONE
        pauseOverlay.visibility = View.GONE // Sécurité
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