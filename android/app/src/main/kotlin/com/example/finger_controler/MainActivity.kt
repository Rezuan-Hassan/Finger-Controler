package com.example.finger_controler

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.fingercontroler/engine"
    private val CAMERA_PERMISSION_CODE = 2001

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getPermissionsStatus" -> {
                    val status = mapOf(
                        "camera" to isCameraPermissionGranted(),
                        "overlay" to isOverlayPermissionGranted(),
                        "accessibility" to isAccessibilityServiceEnabled()
                    )
                    result.success(status)
                }

                "requestCameraPermission" -> {
                    val permissions = mutableListOf(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    ActivityCompat.requestPermissions(this, permissions.toTypedArray(), CAMERA_PERMISSION_CODE)
                    result.success(true)
                }

                "openOverlaySettings" -> {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(fallback)
                        result.success(true)
                    }
                }

                "openAccessibilitySettings" -> {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", "Could not open accessibility settings: ${e.message}", null)
                    }
                }

                "isEngineRunning" -> {
                    result.success(HandTrackingService.isRunning)
                }

                "startEngine" -> {
                    try {
                        // Immediately pop up cursor on activation
                        CursorOverlayManager.getInstance(this).showCursor()
                        val intent = Intent(this, HandTrackingService::class.java)
                        ContextCompat.startForegroundService(this, intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("START_FAILED", "Failed to start engine: ${e.message}", null)
                    }
                }

                "stopEngine" -> {
                    try {
                        CursorOverlayManager.getInstance(this).hideCursor()
                        val intent = Intent(this, HandTrackingService::class.java)
                        stopService(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("STOP_FAILED", "Failed to stop engine: ${e.message}", null)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (GestureAccessibilityService.isServiceRunning) return true

        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (service in enabledServices) {
                val serviceInfo = service.resolveInfo.serviceInfo
                if (serviceInfo.packageName == packageName) {
                    return true
                }
            }
        }

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(packageName)
    }
}
