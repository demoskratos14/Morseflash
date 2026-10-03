package com.aventure.morseflash

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Pilote la lampe torche (flash) sans ouvrir la caméra. */
class TorchController(context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraId: String? = try {
        val withFlash = manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    } catch (e: Exception) {
        null
    }

    val isAvailable: Boolean get() = cameraId != null

    fun set(on: Boolean) {
        val id = cameraId ?: return
        try {
            manager.setTorchMode(id, on)
        } catch (e: Exception) {
            // Torche indisponible (utilisée par une autre appli, surchauffe, etc.)
        }
    }
}
