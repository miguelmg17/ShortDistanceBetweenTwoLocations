package com.example.mykgps_

import com.google.android.gms.maps.model.LatLng
import com.google.firebase.database.Exclude // Importar para Firebase

data class Posiciones(
    var latitud: Double = 0.0,
    var longitud: Double = 0.0,
    var direccion: String = "",
    var distancia: Double = 0.0,
    var yo: Boolean = false
)


{

    constructor() : this(0.0, 0.0, "", 0.0, false)

    @get:Exclude
    val posicion: LatLng
        get() = LatLng(latitud, longitud)

    fun setLatLng(latLng: LatLng) {
        this.latitud = latLng.latitude
        this.longitud = latLng.longitude
    }
}