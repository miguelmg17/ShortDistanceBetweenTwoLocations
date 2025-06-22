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

        btnCalculate = findViewById(R.id.btnCalculateRoute)
        btnAddLocation = findViewById(R.id.btnAddLocation)
        lvPosiciones = findViewById(R.id.lvposiciones)

        infoAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvPosiciones.adapter = infoAdapter

        btnCalculate.setOnClickListener {
            poly?.remove()
            poly = null
            findNearestToUni()
        }

        btnAddLocation.setOnClickListener {
            showCompanionNameDialog()
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
        if (!::Miposicion.isInitialized) {
            Toast.makeText(this, "Tu ubicación no está disponible aún. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
            return
        }
        if (companionLocation == null) {
            Toast.makeText(this, "La ubicación de tu compañero no está disponible aún.", Toast.LENGTH_SHORT).show()
            return
        }

        // Calcular distancia usando fórmula de Haversine (línea recta)
        val myDistanceSimple = calculateDistanceHaversine(
            Miposicion.latitude, Miposicion.longitude,
            uniLocation.latitude, uniLocation.longitude
        )
        
        val companionDistanceSimple = calculateDistanceHaversine(
            companionLocation!!.latitude, companionLocation!!.longitude,
            uniLocation.latitude, uniLocation.longitude
        )

        // Mostrar resultado
        val resultMessage = if (myDistanceSimple < companionDistanceSimple) {
            "¡TÚ estás más cerca de la UNI! (${String.format("%.2f", myDistanceSimple)} km vs ${String.format("%.2f", companionDistanceSimple)} km)"
        } else {
            "$companionName está más cerca de la UNI (${String.format("%.2f", companionDistanceSimple)} km vs ${String.format("%.2f", myDistanceSimple)} km)"
        }
        
        Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()
        
        // Dibujar línea recta al ganador
        drawStraightLineToUni(if (myDistanceSimple < companionDistanceSimple) Miposicion else companionLocation!!)
    }
    
    // Función para dibujar línea recta a la UNI
    private fun drawStraightLineToUni(fromLocation: LatLng) {
        poly?.remove() // Limpiar ruta anterior
        
        val polyLineOptions = PolylineOptions()
            .width(8f)
            .color(Color.RED)
            .jointType(JointType.ROUND)
            .startCap(RoundCap())
            .endCap(RoundCap())
            .add(fromLocation)
            .add(uniLocation)
            
        poly = map.addPolyline(polyLineOptions)
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