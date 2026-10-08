package ir.mycon.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject

data class MyConSensorSpec(
    val type: Int,
    val code: String,
    val samplePeriodUs: Int,
    val unit: String
)

data class MyConActiveSensor(
    val spec: MyConSensorSpec,
    val sensor: Sensor
)

object AndroidSensorSuite {
    val specs: List<MyConSensorSpec> =
        listOf(
            MyConSensorSpec(
                Sensor.TYPE_ACCELEROMETER,
                "ACCEL",
                20_000,
                "m/s^2"
            ),
            MyConSensorSpec(
                Sensor.TYPE_GYROSCOPE,
                "GYRO",
                20_000,
                "rad/s"
            ),
            MyConSensorSpec(
                Sensor.TYPE_ROTATION_VECTOR,
                "ROT_VEC",
                20_000,
                "unit_quaternion_vector"
            ),
            MyConSensorSpec(
                Sensor.TYPE_GRAVITY,
                "GRAVITY",
                33_333,
                "m/s^2"
            ),
            MyConSensorSpec(
                Sensor.TYPE_LINEAR_ACCELERATION,
                "LINEAR_ACCEL",
                33_333,
                "m/s^2"
            ),
            MyConSensorSpec(
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                "GAME_ROT_VEC",
                33_333,
                "unit_quaternion_vector"
            ),
            MyConSensorSpec(
                Sensor.TYPE_MAGNETIC_FIELD,
                "MAG_FIELD",
                50_000,
                "uT"
            ),
            MyConSensorSpec(
                Sensor.TYPE_PRESSURE,
                "PRESSURE",
                200_000,
                "hPa"
            )
        )

    fun discover(
        context: Context
    ): List<MyConActiveSensor> {
        val manager =
            context.getSystemService(
                Context.SENSOR_SERVICE
            ) as SensorManager

        return specs.mapNotNull { spec ->
            manager.getDefaultSensor(spec.type)
                ?.let { sensor ->
                    MyConActiveSensor(
                        spec = spec,
                        sensor = sensor
                    )
                }
        }
    }

    fun register(
        manager: SensorManager,
        listener: SensorEventListener
    ): List<MyConActiveSensor> {
        return specs.mapNotNull { spec ->
            manager
                .getDefaultSensor(spec.type)
                ?.let { sensor ->
                    val ok =
                        manager.registerListener(
                            listener,
                            sensor,
                            spec.samplePeriodUs,
                            0
                        )
                    if (ok) {
                        MyConActiveSensor(
                            spec = spec,
                            sensor = sensor
                        )
                    } else {
                        null
                    }
                }
        }
    }

    fun eventCode(
        sensorType: Int
    ): String? =
        specs.firstOrNull {
            it.type == sensorType
        }?.code

    fun compactSummary(
        context: Context
    ): String {
        val active = discover(context)
        val codes = active.map { it.spec.code }.toSet()

        val parts =
            mutableListOf<String>()

        parts +=
            "IMU " +
                active.count {
                    it.spec.code != "PRESSURE"
                } +
                "/7"

        if ("PRESSURE" in codes) {
            parts += "Baro ✓"
        }

        if ("MAG_FIELD" in codes) {
            parts += "Compass ✓"
        }

        return parts.joinToString(" • ")
    }

    fun userSummary(
        context: Context
    ): String {
        val active = discover(context)
        val codes = active.map { it.spec.code }.toSet()

        val rows =
            listOf(
                "شتاب و ژیروسکوپ" to
                    (
                        "ACCEL" in codes &&
                            "GYRO" in codes
                    ),
                "جهت‌گیری" to
                    (
                        "ROT_VEC" in codes ||
                            "GAME_ROT_VEC" in codes
                    ),
                "Gravity / Linear" to
                    (
                        "GRAVITY" in codes &&
                            "LINEAR_ACCEL" in codes
                    ),
                "قطب‌نما" to
                    ("MAG_FIELD" in codes),
                "فشارسنج" to
                    ("PRESSURE" in codes)
            )

        return rows.joinToString("\n") {
            (if (it.second) "✓" else "—") +
                "  " +
                it.first
        }
    }

    fun writeManifest(
        sessionDir: java.io.File,
        activeSensors: List<MyConActiveSensor>,
        eventCounts: Map<String, Long>
    ) {
        val rows =
            JSONArray()

        activeSensors.forEach {
                active ->
            val sensor =
                active.sensor
            val spec =
                active.spec

            rows.put(
                JSONObject()
                    .put("code", spec.code)
                    .put("android_type", sensor.type)
                    .put("string_type", sensor.stringType)
                    .put("name", sensor.name)
                    .put("vendor", sensor.vendor)
                    .put("version", sensor.version)
                    .put("unit", spec.unit)
                    .put("sample_period_us", spec.samplePeriodUs)
                    .put("resolution", sensor.resolution)
                    .put("maximum_range", sensor.maximumRange)
                    .put("power_ma", sensor.power)
                    .put("min_delay_us", sensor.minDelay)
                    .put("max_delay_us", sensor.maxDelay)
                    .put("reporting_mode", sensor.reportingMode)
                    .put("wake_up", sensor.isWakeUpSensor)
                    .put(
                        "event_count",
                        eventCounts[spec.code] ?: 0L
                    )
            )
        }

        java.io.File(
            sessionDir,
            "sensor_manifest.json"
        ).writeText(
            JSONObject()
                .put(
                    "schema",
                    "mycon.android.sensors.v1"
                )
                .put(
                    "policy",
                    "AUTO_IMPORTANT_ONLY"
                )
                .put(
                    "sensor_count",
                    activeSensors.size
                )
                .put(
                    "sensors",
                    rows
                )
                .toString(2)
        )
    }
}
