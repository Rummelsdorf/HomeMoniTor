package com.example.homemonitor

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel that holds the state for the main activity.
 * Separates UI logic from data (camera, sensor, network) and survives configuration changes.
 */
class MainViewModel : ViewModel() {
    // Latest camera snapshot (JPEG bytes)
    private val _latestJpeg = MutableStateFlow<ByteArray?>(null)
    val latestJpeg: StateFlow<ByteArray?> = _latestJpeg

    // Sensor data (JSON string)
    private val _sensorJson = MutableStateFlow<String?>(null)
    val sensorJson: StateFlow<String?> = _sensorJson

    /**
     * Update the latest camera snapshot.
     */
    fun updateLatestJpeg(bytes: ByteArray?) {
        _latestJpeg.value = bytes
    }

    /**
     * Update the sensor JSON string.
     */
    fun updateSensorJson(json: String?) {
        _sensorJson.value = json
    }
}
