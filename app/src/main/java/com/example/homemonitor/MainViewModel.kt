package com.example.homemonitor

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel für die Haupt-Activity.
 *
 * Hält den Zustand (aktuellstes Kamera-JPEG und Sensor-JSON) getrennt von der
 * UI und überlebt Konfigurationsänderungen (z. B. Rotation). Aktuell wird es
 * noch nicht durchgängig von [MainActivity] verwendet; die Felder sind für
 * die weitere Entkopplung von UI und Daten vorbereitet.
 */
class MainViewModel : ViewModel() {
    // Aktuellstes Kamerabild (JPEG-Bytes)
    private val _latestJpeg = MutableStateFlow<ByteArray?>(null)
    val latestJpeg: StateFlow<ByteArray?> = _latestJpeg

    // Sensor-Daten (JSON-String)
    private val _sensorJson = MutableStateFlow<String?>(null)
    val sensorJson: StateFlow<String?> = _sensorJson

    /**
     * Aktualisiert das aktuellste Kamerabild.
     *
     * @param bytes JPEG-Bytes des neuen Bildes oder `null`, falls keines verfügbar.
     */
    fun updateLatestJpeg(bytes: ByteArray?) {
        _latestJpeg.value = bytes
    }

    /**
     * Aktualisiert den Sensor-JSON-String.
     *
     * @param json JSON-String mit Sensorwerten oder `null`.
     */
    fun updateSensorJson(json: String?) {
        _sensorJson.value = json
    }
}
