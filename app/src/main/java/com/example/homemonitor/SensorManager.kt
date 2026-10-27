package com.example.homemonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.util.Locale

/**
 * Sammelt Werte lokaler Gerätesensoren (außer der Kamera) und stellt sie als
 * JSON zur Verfügung, damit sie über den lokalen Webserver / Hidden Service
 * dargestellt werden können.
 *
 * Unterstützte Quellen:
 *   - Akku (Ladestand, Temperatur, Spannung, Ladezustand)
 *   - Beschleunigungssensor (m/s²)
 *   - Helligkeitssensor (lux)
 *   - Näherungssensor (cm)
 *   - Magnetfeld (µT)
 *   - Gyroskop (rad/s)
 *   - Luftdruck (hPa)
 *   - Relative Luftfeuchte (%)
 *   - Umgebungstemperatur (°C)
 *   - Geräteinformationen (Modell, Hersteller, Android-Version, Laufzeit)
 */
class SensorManager private constructor(private val context: Context) : SensorEventListener {

    private val androidSensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager

    // Zuletzt gemessene Werte, gesichert durch [lock].
    private val latest = mutableMapOf<String, Any>()
    private val lock = Any()

    companion object {
        @Volatile private var instance: SensorManager? = null

        fun init(context: Context): SensorManager {
            return instance ?: synchronized(this) {
                instance ?: SensorManager(context.applicationContext).also { instance = it }
            }
        }

        fun get(): SensorManager? = instance
    }

    /** Registriert Listener für alle verfügbaren Sensoren. */
    fun start() {
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_LIGHT)
        register(Sensor.TYPE_PROXIMITY)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_PRESSURE)
        register(Sensor.TYPE_RELATIVE_HUMIDITY)
        register(Sensor.TYPE_AMBIENT_TEMPERATURE)
    }

    /** Meldet alle Sensoren ab. */
    fun stop() {
        androidSensorManager.unregisterListener(this)
    }

    private fun register(type: Int) {
        val sensor = androidSensorManager.getDefaultSensor(type) ?: return
        androidSensorManager.registerListener(
            this, sensor, AndroidSensorManager.SENSOR_DELAY_NORMAL
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val values = event.values.toList()
        synchronized(lock) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    latest["accelerometer"] = listOf(values[0], values[1], values[2])
                Sensor.TYPE_LIGHT ->
                    latest["light_lux"] = values[0]
                Sensor.TYPE_PROXIMITY ->
                    latest["proximity_cm"] = values[0]
                Sensor.TYPE_MAGNETIC_FIELD ->
                    latest["magnetic_uT"] = listOf(values[0], values[1], values[2])
                Sensor.TYPE_GYROSCOPE ->
                    latest["gyroscope_rad_s"] = listOf(values[0], values[1], values[2])
                Sensor.TYPE_PRESSURE ->
                    latest["pressure_hPa"] = values[0]
                Sensor.TYPE_RELATIVE_HUMIDITY ->
                    latest["humidity_percent"] = values[0]
                Sensor.TYPE_AMBIENT_TEMPERATURE ->
                    latest["ambient_temp_c"] = values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Genauigkeitsänderungen werden nicht separat erfasst.
    }

    /** Liest den aktuellen Akku-Status aus (wird bei jeder Abfrage frisch ermittelt). */
    private fun readBattery(): Map<String, Any> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100f / scale else -1f
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return mapOf(
            "level_percent" to round(pct),
            "temperature_c" to if (temp >= 0) round(temp / 10f) else -1f,
            "voltage_mv" to voltage,
            "charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING),
            "plugged" to (plugged != 0)
        )
    }

    private fun round(v: Float): Number =
        if (v == v.toInt().toFloat()) v.toInt() else String.format(Locale.US, "%.2f", v).toDouble()

    /**
     * Sammelt alle bekannten Sensorwerte und gibt sie als JSON-String zurück.
     * Liefert `null`, falls noch keine Werte vorliegen.
     */
    fun collectJson(): String? {
        val data = synchronized(lock) {
            if (latest.isEmpty()) return@synchronized null
            mutableMapOf<String, Any>().apply {
                putAll(latest)
                put("battery", readBattery())
                put(
                    "device", mapOf(
                        "model" to Build.MODEL,
                        "manufacturer" to Build.MANUFACTURER,
                        "android_version" to Build.VERSION.RELEASE,
                        "sdk" to Build.VERSION.SDK_INT,
                        "uptime_ms" to SystemClock.elapsedRealtime()
                    )
                )
                put("timestamp" to System.currentTimeMillis())
            }
        } ?: return null
        return toJson(data)
    }

    // --- Minimaler JSON-Serializer (keine externe Bibliothek nötig) ---

    private fun toJson(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append("\"${escape(value)}\"")
            is Boolean -> append(if (value) "true" else "false")
            is Number -> append(value.toString())
            is Map<*, *> -> {
                append("{")
                var first = true
                for ((k, v) in value) {
                    if (!first) append(",")
                    first = false
                    append("\"${escape(k.toString())}\":")
                    appendJson(v)
                }
                append("}")
            }
            is List<*> -> {
                append("[")
                var first = true
                for (v in value) {
                    if (!first) append(",")
                    first = false
                    appendJson(v)
                }
                append("]")
            }
            is FloatArray -> appendJson(value.toList())
            is DoubleArray -> appendJson(value.toList())
            else -> append("\"${escape(value.toString())}\"")
        }
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
