package ir.mycon.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

data class LocationSample(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val speedMps: Float,
    val timeMs: Long
)

class LocationTracker(context: Context) : LocationListener {
    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val latestRef = AtomicReference<LocationSample?>(null)

    fun latest(): LocationSample? = latestRef.get()

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this
            )
        } catch (_: Exception) {
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                this
            )
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            manager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        latestRef.set(
            LocationSample(
                lat = location.latitude,
                lon = location.longitude,
                alt = location.altitude,
                accuracyM = location.accuracy,
                bearingDeg = location.bearing,
                speedMps = location.speed,
                timeMs = location.time
            )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
