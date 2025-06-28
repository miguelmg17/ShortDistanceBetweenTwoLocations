package com.example.mykgps_

import android.Manifest
import android.app.AlertDialog // Para el diálogo de añadir ubicación
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter // Importar para ListView
import android.widget.Button
import android.widget.EditText // Para el diálogo de añadir ubicación
import android.widget.ListView // Importar para ListView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.RoundCap
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.*

// Clases de datos para Google Directions API
data class GoogleDirectionsResponse(
    val routes: List<Route>?,
    val status: String?
)

data class Route(
    val legs: List<Leg>?,
    val overview_polyline: OverviewPolyline?
)

data class Leg(
    val distance: Distance?,
    val duration: Duration?,
    val steps: List<Step>?
)

data class Distance(
    val text: String?,
    val value: Int?
)

data class Duration(
    val text: String?,
    val value: Int?
)

data class Step(
    val polyline: PolylineData?
)

data class PolylineData(
    val points: String?
)

data class OverviewPolyline(
    val points: String?
)

// Interfaz para Google Directions API
interface GoogleDirectionsService {
    @retrofit2.http.GET("directions/json")
    suspend fun getDirections(
        @retrofit2.http.Query("origin") origin: String,
        @retrofit2.http.Query("destination") destination: String,
        @retrofit2.http.Query("key") apiKey: String
    ): GoogleDirectionsResponse
}

// Función para decodificar polyline de Google
fun decodePolyline(encoded: String): List<LatLng> {
    val poly = mutableListOf<LatLng>()
    var index = 0
    var len = encoded.length
    var lat = 0
    var lng = 0

    while (index < len) {
        var b: Int
        var shift = 0
        var result = 0
        do {
            b = encoded[index++].code - 63
            result = result or (b and 0x1f shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lat += dlat

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or (b and 0x1f shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lng += dlng

        val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
        poly.add(p)
    }
    return poly
}

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap
    private lateinit var Miposicion: LatLng
    private var Pi: Double = 3.14159265375
    private var r: Double = 6371.0
    private lateinit var btnCalculate: Button
    private lateinit var lvPosiciones: ListView

    private var poly: Polyline? = null

    // Firebase - Para sincronización en tiempo real
    private lateinit var database: DatabaseReference
    private lateinit var myLocationRef: DatabaseReference
    private lateinit var companionLocationRef: DatabaseReference

    // Ubicaciones dinámicas en tiempo real
    private var companionLocation: LatLng? = null
    private var companionName: String = "Compañero"

    // Ubicación estática de la UNI,
    private val uniLocation = LatLng(-12.01766673864619, -77.04899669186698) // Coordenadas de la UNI
    private val uniPosition = Posiciones(-12.01766673864619, -77.04899669186698, "Universidad Nacional de Ingeniería (UNI)", 0.0, false)

    // Lista para mostrar información (ya no necesitamos lista de múltiples compañeros)
    private lateinit var infoAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        btnCalculate = findViewById(R.id.btnCalculateRoute)
        lvPosiciones = findViewById(R.id.lvposiciones)

        infoAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvPosiciones.adapter = infoAdapter

        btnCalculate.setOnClickListener {
            poly?.remove()
            poly = null
            findNearestToUni()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Mostrar diálogo para seleccionar usuario
        showUserSelectionDialog()
    }

    private fun showUserSelectionDialog() {
        Log.d("MainActivity", "Mostrando diálogo de selección de usuario")

        val users = arrayOf("Usuario 1", "Usuario 2")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("¿Quién eres?")
        builder.setMessage("Selecciona tu identidad para compartir ubicación:")

        builder.setPositiveButton("Usuario 1") { dialog, _ ->
            Log.d("MainActivity", "Usuario seleccionado: user1")
            setupUser("user1")
            dialog.dismiss()
        }

        builder.setNegativeButton("Usuario 2") { dialog, _ ->
            Log.d("MainActivity", "Usuario seleccionado: user2")
            setupUser("user2")
            dialog.dismiss()
        }

        builder.setCancelable(false)
        val dialog = builder.create()
        dialog.show()
    }

    private fun setupUser(selectedUser: String) {
        val companionUser = if (selectedUser == "user1") "user2" else "user1"

        // Configurar Firebase con el usuario seleccionado
        database = FirebaseDatabase.getInstance().reference
        myLocationRef = database.child("locations").child(selectedUser)
        companionLocationRef = database.child("locations").child(companionUser)

        Toast.makeText(this, "Eres $selectedUser", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Configurando Firebase para $selectedUser")

        // Inicializar la app
        getCurrentLocation()
        createMapFragment()
        initializeRealTimeSync()
    }

    private fun loadInitialData() {
        // Ya no necesitamos cargar datos iniciales, todo se maneja en tiempo real
        updateInfoList()
    }

    private fun createMapFragment() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.map = googleMap
        // El ListView ahora solo muestra información, no necesita listener de clic
        createMarkers() // Crea los marcadores en el mapa
    }

    private fun createMarkers() {
        // Verificar que el mapa esté inicializado antes de crear marcadores
        if (!::map.isInitialized) {
            return
        }

        // Limpiar marcadores existentes para evitar duplicados al actualizar
        map.clear()

        // Añadir marcador para "Yo" (ubicación actual)
        if (::Miposicion.isInitialized) {
            map.addMarker(MarkerOptions().position(Miposicion).title("Mi ubicación"))
        }

        // Añadir marcador para mi compañero (ubicación dinámica)
        if (companionLocation != null) {
            map.addMarker(MarkerOptions().position(companionLocation!!).title(companionName))
        }

        // Añadir marcador para la UNI (ubicación estática)
        map.addMarker(MarkerOptions().position(uniLocation).title("UNI").snippet("Universidad Nacional de Ingeniería"))

        // Centrar el mapa para mostrar todas las ubicaciones
        val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()

        if (::Miposicion.isInitialized) {
            bounds.include(Miposicion)
        }
        if (companionLocation != null) {
            bounds.include(companionLocation!!)
        }
        bounds.include(uniLocation)

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds.build(), 100),
            4000,
            null
        )
    }

    // Función para calcular distancia en línea recta usando fórmula de Haversine
    private fun calculateDistanceHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Radio de la Tierra en kilómetros
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // Nueva función para encontrar quién está más cerca de la UNI
    private fun findNearestToUni() {
        if (!::Miposicion.isInitialized || companionLocation == null) {
            Toast.makeText(this, "Las ubicaciones no están listas.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Calculando rutas con OpenRouteService...", Toast.LENGTH_SHORT).show()
        poly?.remove()

        CoroutineScope(Dispatchers.IO).launch {
            val myStart = "${Miposicion.longitude},${Miposicion.latitude}"
            val companionStart = "${companionLocation!!.longitude},${companionLocation!!.latitude}"
            val uniEnd = "${uniLocation.longitude},${uniLocation.latitude}"
            val apiKey = "5b3ce3597851110001cf624826138ef006c74296adca414c449bc21e" // Clave para OpenRouteService
            val apiService = getRetrofit().create(ApiService::class.java)

            try {
                val myCall = apiService.getRoute(apiKey, myStart, uniEnd)
                val companionCall = apiService.getRoute(apiKey, companionStart, uniEnd)

                if (myCall.isSuccessful && companionCall.isSuccessful) {
                    val myDistance = myCall.body()?.features?.firstOrNull()?.properties?.summary?.distance ?: Double.MAX_VALUE
                    val companionDistance = companionCall.body()?.features?.firstOrNull()?.properties?.summary?.distance ?: Double.MAX_VALUE

                    runOnUiThread {
                        if (myDistance < companionDistance) {
                            Toast.makeText(this@MainActivity, "TÚ estás más cerca de la UNI.", Toast.LENGTH_LONG).show()
                            drawRoute(myCall.body()?.features?.firstOrNull()?.geometry?.coordinates)
                        } else {
                            Toast.makeText(this@MainActivity, "Tu compañero está más cerca de la UNI.", Toast.LENGTH_LONG).show()
                            drawRoute(companionCall.body()?.features?.firstOrNull()?.geometry?.coordinates)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error al obtener rutas de OpenRouteService. Código: ${myCall.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun drawRoute(coordinates: List<List<Double>>?) {
        val polyLineOptions = PolylineOptions()
            .width(10f)
            .color(Color.BLUE)
            .jointType(JointType.ROUND)
            .startCap(RoundCap())
            .endCap(RoundCap())

        coordinates?.forEach {
            polyLineOptions.add(LatLng(it[1], it[0]))
        }
        runOnUiThread {
            poly = map.addPolyline(polyLineOptions)
        }
    }

    private fun getRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openrouteservice.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                this.Miposicion = LatLng(location.latitude, location.longitude)
                // Sincronizar mi ubicación con Firebase en tiempo real
                syncMyLocationToFirebase()
                updateInfoList()
                // Verificar que el mapa esté inicializado antes de crear marcadores
                if (::map.isInitialized) {
                    createMarkers() // Llamar a createMarkers aquí para añadir el marcador "Yo"
                }
            } else {
                Toast.makeText(applicationContext, "No se pudo obtener la ubicación", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncMyLocationToFirebase() {
        val myLocation = Posiciones(
            latitud = Miposicion.latitude,
            longitud = Miposicion.longitude,
            direccion = "Mi ubicación",
            distancia = 0.0,
            yo = true
        )
        myLocationRef.setValue(myLocation)
            .addOnSuccessListener {
                Log.d("Firebase", "Mi ubicación sincronizada exitosamente")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error al sincronizar mi ubicación: ${e.message}")
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation()
        }
    }

    private fun initializeRealTimeSync() {
        // Escuchar cambios en la ubicación de mi compañero en tiempo real
        companionLocationRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pos = snapshot.getValue(Posiciones::class.java)
                pos?.let {
                    companionLocation = LatLng(it.latitud, it.longitud)
                    updateInfoList()
                    // Verificar que el mapa esté inicializado antes de crear marcadores
                    if (::map.isInitialized) {
                        createMarkers()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Error al leer ubicación del compañero: ${error.message}")
            }
        })
    }

    private fun updateInfoList() {
        infoAdapter.clear()

        // Mostrar información de las ubicaciones
        if (::Miposicion.isInitialized) {
            infoAdapter.add("Mi ubicación: ${Miposicion.latitude}, ${Miposicion.longitude}")
        } else {
            infoAdapter.add("Mi ubicación: No disponible")
        }

        if (companionLocation != null) {
            infoAdapter.add("$companionName: ${companionLocation!!.latitude}, ${companionLocation!!.longitude}")
        } else {
            infoAdapter.add("$companionName: No disponible")
        }

        infoAdapter.add("UNI: ${uniLocation.latitude}, ${uniLocation.longitude}")
        infoAdapter.notifyDataSetChanged()
    }
}