package ir.mycon.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.sqrt

class CaptureQualityMonitor(context: Context) : SensorEventListener {
    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    var angularSpeedDegPerSec: Float = 0f
        private set

    @Volatile
    var lightLux: Float? = null
        private set

    fun start() {
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        manager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values.getOrElse(0) { 0f }
                val y = event.values.getOrElse(1) { 0f }
                val z = event.values.getOrElse(2) { 0f }
                val rad = sqrt(x * x + y * y + z * z)
                angularSpeedDegPerSec = (rad * 180.0 / PI).toFloat()
            }
            Sensor.TYPE_LIGHT -> {
                lightLux = event.values.getOrNull(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
