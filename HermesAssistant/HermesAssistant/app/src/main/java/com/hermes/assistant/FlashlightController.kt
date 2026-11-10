package com.hermes.assistant

import android.content.Context
import android.hardware.camera2.CameraManager

/**
 * Toggles the rear flash as a torch. No special permission needed on
 * API 23+ for CameraManager.setTorchMode.
 */
object FlashlightController {

    fun set(context: Context, on: Boolean): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            val camId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            manager.setTorchMode(camId, on)
            true
        } catch (e: Exception) {
            false
        }
    }
}
