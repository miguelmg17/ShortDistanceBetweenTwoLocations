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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap
    private lateinit var Miposicion: LatLng
    private var Pi: Double = 3.14159265375
    private var r: Double = 6371.0
    private lateinit var btnCalculate: Button
    private lateinit var btnAddLocation: Button
    private lateinit var lvPosiciones: ListView

    private var poly: Polyline? = null

    // Firebase - Para sincronización en tiempo real
    private lateinit var database: DatabaseReference
    private lateinit var myLocationRef: DatabaseReference
    private lateinit var companionLocationRef: DatabaseReference
    
    // Ubicaciones dinámicas en tiempo real
    private var companionLocation: LatLng? = null
    private var companionName: String = "Compañero"
    
    // Ubicación estática de la UNI
    private val uniLocation = LatLng(-12.0183, -77.0428) // Coordenadas de la UNI
    private val uniPosition = Posiciones(-12.0183, -77.0428, "Universidad Nacional de Ingeniería (UNI)", 0.0, false)

    // Lista para mostrar información (ya no necesitamos lista de múltiples compañeros)
    private lateinit var infoAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Inicializar Firebase para sincronización en tiempo real
        database = FirebaseDatabase.getInstance().reference
        myLocationRef = database.child("locations").child("user1") // Mi ubicación
        companionLocationRef = database.child("locations").child("user2") // Ubicación de mi compañero

        btnCalculate = findViewById(R.id.btnCalculateRoute)
        btnAddLocation = findViewById(R.id.btnAddLocation) // Inicializar el nuevo botón
        lvPosiciones = findViewById(R.id.lvposiciones) // Inicializar ListView

        // Inicializar adaptador para ListView para mostrar información
        infoAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvPosiciones.adapter = infoAdapter

        btnCalculate.setOnClickListener {
            poly?.remove()
            poly = null
            findNearestToUni() // Nueva función para encontrar quién está más cerca de la UNI
        }

        // Listener para el botón de Añadir Ubicación (ahora para configurar nombre del compañero)
        btnAddLocation.setOnClickListener {
            showCompanionNameDialog()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        getCurrentLocation() // Obtener la ubicación actual al inicio
        createMapFragment()

        // Inicializar sincronización en tiempo real
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

    // Nueva función para encontrar quién está más cerca de la UNI
    private fun findNearestToUni() {
        if (!::Miposicion.isInitialized) {
            Toast.makeText(this, "Tu ubicación no está disponible aún. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
            return
        }
        if (companionLocation == null) {
            Toast.makeText(this, "La ubicación de tu compañero no está disponible aún.", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var myDistanceToUni: Double = Double.MAX_VALUE
            var companionDistanceToUni: Double = Double.MAX_VALUE
            var bestRouteCoordinates: List<List<Double>>? = null

            val apiService = getRetrofit().create(ApiService::class.java)

            // Calcular distancia de mi ubicación a la UNI
            val myStart = "${Miposicion.longitude},${Miposicion.latitude}"
            val uniEnd = "${uniLocation.longitude},${uniLocation.latitude}"

            try {
                val myCall = apiService.getRoute("5b3ce3597851110001cf624826138ef006c74296adca414c449bc21e", myStart, uniEnd)
                if (myCall.isSuccessful) {
                    val myRouteResponse = myCall.body()
                    myDistanceToUni = myRouteResponse?.features?.firstOrNull()?.properties?.summary?.distance ?: Double.MAX_VALUE
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error al calcular mi distancia a la UNI: ${e.message}")
            }

            // Calcular distancia de mi compañero a la UNI
            val companionStart = "${companionLocation!!.longitude},${companionLocation!!.latitude}"

            try {
                val companionCall = apiService.getRoute("5b3ce3597851110001cf624826138ef006c74296adca414c449bc21e", companionStart, uniEnd)
                if (companionCall.isSuccessful) {
                    val companionRouteResponse = companionCall.body()
                    companionDistanceToUni = companionRouteResponse?.features?.firstOrNull()?.properties?.summary?.distance ?: Double.MAX_VALUE
                    bestRouteCoordinates = companionRouteResponse?.features?.firstOrNull()?.geometry?.coordinates
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error al calcular distancia de mi compañero a la UNI: ${e.message}")
            }

            // Comparar y mostrar resultado
            runOnUiThread {
                if (myDistanceToUni < Double.MAX_VALUE && companionDistanceToUni < Double.MAX_VALUE) {
                    val myDistanceKm = myDistanceToUni / 1000
                    val companionDistanceKm = companionDistanceToUni / 1000
                    
                    if (myDistanceKm < companionDistanceKm) {
                        Toast.makeText(this@MainActivity, 
                            "¡TÚ estás más cerca de la UNI! (${String.format("%.2f", myDistanceKm)} km vs ${String.format("%.2f", companionDistanceKm)} km)", 
                            Toast.LENGTH_LONG).show()
                        // Dibujar mi ruta a la UNI
                        drawRouteFromMeToUni()
                    } else {
                        Toast.makeText(this@MainActivity, 
                            "$companionName está más cerca de la UNI (${String.format("%.2f", companionDistanceKm)} km vs ${String.format("%.2f", myDistanceKm)} km)", 
                            Toast.LENGTH_LONG).show()
                        // Dibujar ruta del compañero a la UNI
                        if (bestRouteCoordinates != null) {
                            drawRoute(bestRouteCoordinates)
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, "No se pudo calcular las distancias a la UNI.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Función para dibujar mi ruta a la UNI
    private fun drawRouteFromMeToUni() {
        if (!::Miposicion.isInitialized) {
            Toast.makeText(this, "Tu ubicación no está disponible aún.", Toast.LENGTH_SHORT).show()
            return
        }

        poly?.remove() // Limpiar ruta anterior si existe

        val startPoint = "${Miposicion.longitude},${Miposicion.latitude}"
        val endPoint = "${uniLocation.longitude},${uniLocation.latitude}"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val call = getRetrofit().create(ApiService::class.java).getRoute(
                    "5b3ce3597851110001cf624826138ef006c74296adca414c449bc21e",
                    startPoint,
                    endPoint
                )
                if (call.isSuccessful) {
                    val routeResponse = call.body()
                    val coordinates = routeResponse?.features?.firstOrNull()?.geometry?.coordinates
                    if (coordinates != null) {
                        drawRoute(coordinates)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "No se encontraron coordenadas de ruta.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e("MainActivity", "Error al obtener ruta: ${call.errorBody()?.string()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error al calcular la ruta. Código: ${call.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Excepción al obtener ruta: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error de conexión al calcular la ruta.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Función para dibujar ruta de un compañero a la UNI
    private fun drawRouteFromCompanionToUni(companionLatLng: LatLng) {
        poly?.remove() // Limpiar ruta anterior si existe

        val startPoint = "${companionLatLng.longitude},${companionLatLng.latitude}"
        val endPoint = "${uniLocation.longitude},${uniLocation.latitude}"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val call = getRetrofit().create(ApiService::class.java).getRoute(
                    "5b3ce3597851110001cf624826138ef006c74296adca414c449bc21e",
                    startPoint,
                    endPoint
                )
                if (call.isSuccessful) {
                    val routeResponse = call.body()
                    val coordinates = routeResponse?.features?.firstOrNull()?.geometry?.coordinates
                    if (coordinates != null) {
                        drawRoute(coordinates)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "No se encontraron coordenadas de ruta.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e("MainActivity", "Error al obtener ruta: ${call.errorBody()?.string()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error al calcular la ruta. Código: ${call.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Excepción al obtener ruta: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error de conexión al calcular la ruta.", Toast.LENGTH_SHORT).show()
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
            poly?.remove()
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
                createMarkers() // Llamar a createMarkers aquí para añadir el marcador "Yo"
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

    private fun showCompanionNameDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Añadir Nombre del Compañero")

        val input = EditText(this)
        input.hint = "Nombre del compañero (ej: Juan Pérez)"
        builder.setView(input)

        builder.setPositiveButton("Guardar") { dialog, _ ->
            val companionName = input.text.toString().trim()
            if (companionName.isNotEmpty()) {
                this.companionName = companionName
                Toast.makeText(this, "Nombre del compañero actualizado a '$companionName'.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "El nombre del compañero no puede estar vacío.", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun initializeRealTimeSync() {
        // Escuchar cambios en la ubicación de mi compañero en tiempo real
        companionLocationRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pos = snapshot.getValue(Posiciones::class.java)
                pos?.let {
                    companionLocation = LatLng(it.latitud, it.longitud)
                    updateInfoList()
                    createMarkers()
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