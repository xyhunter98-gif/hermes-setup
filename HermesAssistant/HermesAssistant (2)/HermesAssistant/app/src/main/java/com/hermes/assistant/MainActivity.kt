package com.hermes.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermes.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.permissionButton.setOnClickListener { requestMissingPermissions() }

        binding.micButton.setOnClickListener {
            if (allPermissionsGranted() && overlayPermissionGranted()) {
                startListening()
            } else {
                requestMissingPermissions()
            }
        }

        binding.backgroundSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !(allPermissionsGranted() && overlayPermissionGranted())) {
                binding.backgroundSwitch.isChecked = false
                requestMissingPermissions()
                return@setOnCheckedChangeListener
            }
            HermesWakeService.setEnabled(this, checked)
            if (checked) maybeRequestBatteryExemption()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        binding.backgroundSwitch.isChecked = HermesWakeService.isEnabled(this)
    }

    @SuppressLint("BatteryLife")
    private fun maybeRequestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {
                // Some OEM ROMs block this intent; background listening still
                // works, it's just more likely to get killed after a while.
            }
        }
    }

    private fun refreshPermissionUi() {
        val allGood = allPermissionsGranted() && overlayPermissionGranted()
        binding.permissionButton.visibility = if (allGood) android.view.View.GONE else android.view.View.VISIBLE
        binding.statusText.text = if (allGood) {
            getString(R.string.tap_to_speak)
        } else {
            "Hermes needs phone, contacts, SMS, mic and \"display over other apps\" permissions to work like a real assistant."
        }
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun overlayPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return
        }
        if (!overlayPermissionGranted()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startListening() {
        val intent = Intent(this, HermesListenerService::class.java).apply {
            action = HermesListenerService.ACTION_START_LISTENING
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
