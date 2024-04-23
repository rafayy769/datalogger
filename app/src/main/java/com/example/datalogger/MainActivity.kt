package com.example.datalogger

// This file is the MainActivity for the frontend app. It collects sensor data from the device and sends it to the backend server.

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.text.SimpleDateFormat
import java.util.*


// Retrofit interface for sending data
interface SensorDataService {
    @POST("sensorData")
    suspend fun sendSensorData(@Header("X-API-Key") apiKey: String, @Body sensorData: SensorData): retrofit2.Response<Void>
}

data class SensorData(
    val accelerometer: Map<String, List<String>>,
    val gyroscope: Map<String, List<String>>,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val metadata: Map<String, String> // For additional debugging or analysis information
)

class MainActivity : AppCompatActivity() {

    private val DURATION = 2500
    private val API_KEY="yaxL1nRhZ1g36V0Xi8x_ow4Oe4ckRe_oVKjOUAm4lro"

    private lateinit var progressBar: ProgressBar
    private lateinit var sensorManager: SensorManager

//    listeners
    private lateinit var accelerometer: Sensor
    private lateinit var gyroscope: Sensor
    private lateinit var magnetometer: Sensor

    //    arrays that will store the data in memory, received from sensors
    private val accelerometerData = mutableMapOf<String, MutableList<String>>()
    private val gyroscopeData = mutableMapOf<String, MutableList<String>>()

    private var currentFrequency: Int = 0
    private var currentMagState: Boolean = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss", Locale.getDefault())

    //    Separate listeners for all three sensors
    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensorType = event.sensor.type
            val values = event.values
            val timestamp = event.timestamp
            val formattedDate = dateFormat.format(Date())
            val data = when (sensorType) {
                Sensor.TYPE_ACCELEROMETER -> "ACC,$formattedDate,$timestamp,${values[0]},${values[1]},${values[2]}"
                else -> null
            }
            val dataKey = "$currentFrequency-${if (currentMagState) "On" else "Off"}"

            data?.let {
                val dataList = accelerometerData.getOrPut(dataKey) { mutableListOf() }
                dataList.add(data)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val gyroscopeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensorType = event.sensor.type
            val values = event.values
            val timestamp = event.timestamp
            val formattedDate = dateFormat.format(Date())  // Convert nanoseconds to milliseconds
            val data = when (sensorType) {
                Sensor.TYPE_GYROSCOPE -> "GYRO,$formattedDate,$timestamp,${values[0]},${values[1]},${values[2]}"
                else -> null
            }

            val dataKey = "$currentFrequency-${if (currentMagState) "On" else "Off"}"

            data?.let {
                val dataList = gyroscopeData.getOrPut(dataKey) { mutableListOf() }
                dataList.add(data)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val magnetListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {}
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

//    range of frequencies to use for acc and gyroscope
    private val frequencies = arrayOf(200, 173, 139)
    private val magnetoStates = listOf(true, false)

    private fun registerSensors(magneto: Boolean, delay: Int) {
        sensorManager.registerListener(accelerometerListener, accelerometer, delay)
        if (magneto) {
            sensorManager.registerListener(magnetListener, magnetometer, 5001)
        }
        Handler(Looper.getMainLooper()).postDelayed(Runnable {
            sensorManager.registerListener(
                gyroscopeListener,
                gyroscope,
                delay
            )
        }, 2L)
    }
    private fun unregisterSensors(magneto: Boolean) {
        sensorManager.unregisterListener(accelerometerListener)
        sensorManager.unregisterListener(gyroscopeListener)
        if (magneto) {
            sensorManager.unregisterListener(magnetListener)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//      Initialization of member vars
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        try {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        } catch (e: Exception) {
            showSensorUnavailableAlert()
        }

        progressBar = findViewById(R.id.indeterminate)

        val startButton: Button = findViewById(R.id.startButton)
        startButton.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            if (isNetworkAvailable()) {
                startCollectingData()
            } else {
                Toast.makeText(this, "Network is unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSensorUnavailableAlert() {
        val alertDialogBuilder = AlertDialog.Builder(this)
        alertDialogBuilder.setTitle("Sensor Unavailable")
        alertDialogBuilder.setMessage("Unfortunately, one of the sensors is not available, so we can't proceed with the experiment. Feel free to uninstall the app.")
        alertDialogBuilder.setCancelable(false)
        alertDialogBuilder.setPositiveButton("OK") { dialog: DialogInterface, _: Int ->
            // Close the app when OK is clicked
            dialog.dismiss()
            finish()
        }

        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    }

    private fun startCollectingData() {
//        clear all prev stored data for a fresh start
        accelerometerData.clear()
        gyroscopeData.clear()

        Log.d("dataCollection", "Starting data collection process")
        CoroutineScope(Dispatchers.Main).launch {
            val totalExperiments = frequencies.size * magnetoStates.size
            val totalDurationMs = DURATION
            val updateIntervalMs = 50
            val stepsPerExperiment = totalDurationMs / updateIntervalMs
            progressBar.max = totalExperiments * stepsPerExperiment

            var currentProgress = 0
            for (magneto in magnetoStates) {
                currentMagState = magneto
                for (frequency in frequencies) {
                    currentFrequency = frequency
                    Log.d("dataCollection", "Starting with : $frequency-$magneto")
                    val delay = (1.0 / frequency * 1000000).toInt()
                    registerSensors(magneto = magneto, delay = delay)
                    for (step in 1..stepsPerExperiment) {
                        progressBar.progress = ++currentProgress
                        delay(updateIntervalMs.toLong())
                    }
                    unregisterSensors(magneto = magneto)
                }
            }
            progressBar.visibility = View.GONE
            sendDataToServer() // This function might need to be adjusted to handle data collection context.
        }
    }

    private fun showCompletionAlert() {
        Log.d("Alert", "Creating alert box")
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Data Collection Complete")
                .setMessage("The app can be closed, and uninstalled now.")
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .create().show()
        }
    }

    private fun createSensorData(): SensorData {
        val deviceName = Build.DEVICE // A value used by the manufacturer to identify the device, but not always the marketing name
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val metadata = mapOf(
            "SDK_INT" to Build.VERSION.SDK_INT.toString(), // Android OS version
            "RELEASE" to Build.VERSION.RELEASE, // Android version as a readable string
            "BOARD" to Build.BOARD, // The name of the underlying board
            "BRAND" to Build.BRAND, // The brand the software is customized for, if any
            "HARDWARE" to Build.HARDWARE, // The name of the hardware
            "PRODUCT" to Build.PRODUCT, // The name of the overall product
            "MAG_MAX_FREQ" to (1 / magnetometer.minDelay * 1e6).toString(), // minimum allowed delay in magnetometer
            "BOOTLOADER" to Build.BOOTLOADER
        )

        return SensorData(
            accelerometer = accelerometerData,
            gyroscope = gyroscopeData,
            deviceName = deviceName,
            manufacturer = manufacturer,
            model = model,
            metadata = metadata
        )
    }

    private fun sendDataToServer() {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://139.59.65.232")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(SensorDataService::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                launch(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Sending Data", Toast.LENGTH_SHORT).show()
                }
                val response = service.sendSensorData(API_KEY, createSensorData())
                Log.d("Network", response.toString())
                if (response.isSuccessful) {
                    // Handle success, maybe notify the user
                    launch(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Successful", Toast.LENGTH_SHORT).show()
                    }
                    showCompletionAlert()
                } else {
                    // Handle server error
                    launch(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Failed to send the data, Try again later.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle network or other errors here
            }
        }
    }
}
