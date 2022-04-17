// Copyright 2022 shadowmoon_waltz
//
// This file is part of RotationSend.
//
// RotationSend is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
//
// RotationSend is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.

package com.sw.rotationsend

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.sw.rotationsend.databinding.MainLayoutBinding
import com.sw.rotationsend.databinding.SettingsLayoutBinding
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.math.PI
import android.Manifest
import android.os.Process
import android.view.SurfaceHolder
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.util.TypedValue
import android.view.SurfaceView
import android.view.Surface
import android.view.View
import android.app.AlertDialog
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.content.SharedPreferences
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.widget.Toast
import java.net.InetAddress
import java.util.concurrent.Executors
import android.os.Build
import android.view.WindowManager

// https://developer.android.com/guide/topics/sensors/sensors_position
// https://developer.android.com/guide/topics/media/camera
class MainActivity: Activity(), SensorEventListener {
  private lateinit var binding: MainLayoutBinding

  private lateinit var sensorManager: SensorManager
  private val accelerometerReading = FloatArray(3)
  private val magnetometerReading = FloatArray(3)
  private val rotationMatrix = FloatArray(9)
  private val orientationAngles = FloatArray(3)

  private var lastUpdateTime = 0L

  private var showPreview = false
  private var minUpdateIntervalMilliseconds = 250

  private val radConvert = (180 / PI).toFloat()

  private var cameraPreview: CameraPreview? = null

  private lateinit var prefs: SharedPreferences

  private var socket: DatagramSocket? = null
  private var ssend = false
  private var sendTapOnly = false
  private var sendHost = ""
  private var sendPort = -1
  private val sendBuffer = ByteArray(24)
  private var sendAddr: InetAddress? = null
  private val sendThread = Executors.newSingleThreadExecutor()

  private fun updateSendVars(b: Boolean, tap: Boolean, host: String?, port: Int?) {
    if (!b) {
      socket?.apply { close() }
      socket = null
    } else if (socket == null) {
      socket = try { DatagramSocket().apply { setBroadcast(false) } } catch (_: Exception) { null }
    }
    ssend = b
    sendTapOnly = tap
    sendHost = host ?: ""
    sendPort = port ?: -1
    if (sendHost != "") {
      sendAddr = try {
        InetAddress.getByName(sendHost)
      } catch (e: Exception) {
        Toast.makeText(this, "Failed to resolve host. Edit and try again.", Toast.LENGTH_LONG).show()
        null
      }
    } else {
      sendAddr = null
    }
  }

  private fun hasCamPerm() =
    (checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)

  // https://stackoverflow.com/questions/12375766/how-to-get-background-color-from-current-theme-programmatically
  private fun setBgColor(v: View) {
    val a = TypedValue()
    getTheme().resolveAttribute(android.R.attr.windowBackground, a, true)
    if (a.type >= TypedValue.TYPE_FIRST_COLOR_INT && a.type <= TypedValue.TYPE_LAST_COLOR_INT) {
      v.setBackgroundColor(a.data)
    } else if (Build.VERSION.SDK_INT >= 16) {
      v.setBackground(getResources().getDrawable(a.resourceId))
    }
  }

  private fun updateShowPreview() {
    showPreview = (hasCamPerm() && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA))
    if (showPreview && cameraPreview == null) {
      cameraPreview = CameraPreview(this).also {
        binding.root.addView(it as View, 0)
      }
    }
  }

  override protected fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = getSharedPreferences("RotationSend", Context.MODE_PRIVATE)
    updateSendVars(prefs.getBoolean(PrefSend, false), prefs.getBoolean(PrefSendTapOnly, false), prefs.getString(PrefSendHost, ""), prefs.getInt(PrefSendPort, -1))
    binding = MainLayoutBinding.inflate(getLayoutInflater())
    setContentView(binding.root)
    if (Build.VERSION.SDK_INT >= 19) {
      binding.root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    setBgColor(binding.tvOverlay)
    updateShowPreview()
    binding.tvOverlay.setOnClickListener { _ ->
      val v = getLayoutInflater().inflate(R.layout.settings_layout, null)
      val b = SettingsLayoutBinding.bind(v)
      b.btnSettingsCamera.setOnClickListener {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { setData(Uri.fromParts("package", getPackageName(), null)) })
      }
      b.btnSettingsSendInfo.setOnClickListener {
        AlertDialog.Builder(this@MainActivity)
          .setTitle("Send UDP Server Info")
          .setMessage("24-byte packets (little-endian) are sent at phone sensor speeds (limited to at most every 250 milliseconds) or when screen is tapped (if \"On Tap Only\" is checked)\nFormat is: 64-bit long java timestamp, 32-bit reserved, 32-bit float roll, 32-bit float pitch, 32-bit azimuth")
          .setPositiveButton("Okay", { _, _ -> Unit })
          .show()
      }
      b.cbSettingsSend.setChecked(ssend)
      b.cbSettingsSendTapOnly.setChecked(sendTapOnly)
      b.etSettingsHost.setText(sendHost)
      b.etSettingsPort.setText(if (sendPort >= 0 && sendPort <= 65535) sendPort.toString() else "")
      AlertDialog.Builder(this@MainActivity)
          .setTitle("Settings")
          .setView(v)
          .setPositiveButton("Save", { _, _ ->
            val ed = prefs.edit()
            val s = b.cbSettingsSend.isChecked()
            val host = b.etSettingsHost.text.toString()
            if (host.isEmpty() && s) {
              AlertDialog.Builder(this@MainActivity)
                .setMessage("Host not set. Settings not saved.")
                .setPositiveButton(android.R.string.ok, { _, _ -> Unit })
                .show()
              return@setPositiveButton
            }
            val portString = b.etSettingsPort.text.toString()
            val port = portString.toIntOrNull()
            if (port == null || port < 0 || port > 65535) {
              if (s) {
                AlertDialog.Builder(this@MainActivity)
                  .setMessage("${if (portString.isEmpty()) "Port not set" else "Invalid port"}. Settings not saved.")
                  .setPositiveButton(android.R.string.ok, { _, _ -> Unit })
                  .show()
                return@setPositiveButton
              }
            } else {
              ed.putInt(PrefSendPort, port)
            }
            val tap = b.cbSettingsSendTapOnly.isChecked()
            ed.putBoolean(PrefSend, s).putBoolean(PrefSendTapOnly, tap).putString(PrefSendHost, host).commit()
            updateSendVars(s, tap, host, port)
          })
          .setNegativeButton("Cancel", { _, _ -> Unit })
          .show()
    }
    binding.root.setOnClickListener {
      if (sendTapOnly) {
        sendMaybe(true)
      }
    }
    binding.root.setOnLongClickListener { _ ->
      binding.tvOverlay.visibility = if (binding.tvOverlay.visibility == View.GONE) View.VISIBLE else View.GONE
      true
    }
    binding.tvOverlay.setOnLongClickListener { _ ->
      binding.tvOverlay.visibility = if (binding.tvOverlay.visibility == View.GONE) View.VISIBLE else View.GONE
      true
    }
  }

  override protected fun onResume() {
    super.onResume()
    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
      sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
    }
    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { magneticField ->
      sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
    }
    updateShowPreview()
    cameraPreview?.let { it.initCamera() }
  }

  override protected fun onDestroy() {
    super.onDestroy()
    socket?.apply { close() }
  }
  
  override protected fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
    cameraPreview?.let { it.releaseCamera() }
  }

  private fun sendMaybe(toast: Boolean) {
    if (sendAddr != null && socket != null && !sendHost.isEmpty() && sendPort >= 0 && sendPort <= 65535) {
      val now = System.currentTimeMillis()
      val roll = orientationAngles[2] * radConvert
      val pitch = orientationAngles[1] * radConvert
      val azimuth = orientationAngles[0] * radConvert
      sendThread.submit {
        socket?.let { s -> 
          sendAddr?.let { addr ->
            try {
              ByteBuffer.wrap(sendBuffer)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .putLong(now)
                  .putInt(0)
                  .putFloat(roll)
                  .putFloat(pitch)
                  .putFloat(azimuth)
              s.send(DatagramPacket(sendBuffer, sendBuffer.size, addr, sendPort))
              if (toast) {
                this@MainActivity.runOnUiThread {
                  Toast.makeText(this@MainActivity, "Tap Handled", Toast.LENGTH_SHORT).show()
                }
              }
            } catch (e: Exception) {
            }
          }
        }
      }
    }
  }
  // https://developer.android.com/reference/android/hardware/Camera#setDisplayOrientation(int)
  private fun updateValues() {
    SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
    SensorManager.getOrientation(rotationMatrix, orientationAngles)
    if (binding.tvOverlay.visibility == View.VISIBLE) {
      binding.tvOverlay.text = String.format("Roll: %7.4f\nPitch: %7.4f\nYaw: %7.4f\n%s\n[Settings]",
          orientationAngles[2] * radConvert, orientationAngles[1] * radConvert, orientationAngles[0] * radConvert,
          if (socket != null) { if (sendAddr != null) { if (sendTapOnly) "Sending (Tap)" else "Sending" } else "Bad Host" } else if (ssend) "Socket Issue" else "Not Sending")
    }
    if (!sendTapOnly) {
      sendMaybe(false)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
      event.values.copyInto(accelerometerReading, endIndex = accelerometerReading.size)
    } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
      event.values.copyInto(magnetometerReading, endIndex = magnetometerReading.size)
    } else {
      return
    }
    val now = System.currentTimeMillis()
    if (now - lastUpdateTime >= minUpdateIntervalMilliseconds) {
      updateValues()
      lastUpdateTime = now
    }
  }

  class CameraPreview(val context: Activity) : SurfaceView(context), SurfaceHolder.Callback {
    private var camera: Camera? = null
    private var cameraId: Int = -1
    private var cameraOrientation: Int = -1
 
    init {
      //setOnClickListener { camera?.autoFocus { _, _ -> Unit } }
      holder.addCallback(this)
      val info = CameraInfo()
      for (i in 0..(Camera.getNumberOfCameras()+1)) {
        Camera.getCameraInfo(i, info)
        if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
          cameraId = i
          cameraOrientation = info.orientation
          break
        }
      }
    }

    fun releaseCamera() {
      camera?.let {
        try { it.stopPreview() } catch (_: Exception) { }
        try { it.release() } catch (_: Exception) { }
      }
      camera = null
    }

    fun initCamera() {
      if (cameraId != -1) {
        releaseCamera()
        camera = try { Camera.open(cameraId) } catch (_: Exception) { null }
        // https://stackoverflow.com/questions/12021884/android-camera-continuous-focus-on-demand
        camera?.let { it.setParameters(it.getParameters().apply { setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) }) }
        setupCamera()
      }
    }

    private fun setCameraOrientation(c: Camera) {
      val rotation = context.getWindowManager().getDefaultDisplay().getRotation()
      var degrees = when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> return
      }
      val result = (cameraOrientation - degrees + 360) % 360;
      c.setDisplayOrientation(result)
    }

    private fun setupCamera() {
      camera?.let {
        if (holder.surface != null) {
          try {
            it.setPreviewDisplay(holder)
            setCameraOrientation(it)
            it.startPreview()
          } catch (_: Exception) {
          }
        }
      }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
      setupCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
      if (holder.surface != null) {
        try { camera?.stopPreview() } catch (_: Exception) { }
        setupCamera()
      }
    }
  }

  private companion object {
    val PrefSend = "PrefSend"
    val PrefSendTapOnly = "PrefSendTapOnly"
    val PrefSendHost = "PrefSendHost"
    val PrefSendPort = "PrefSendPort"
  }
}
