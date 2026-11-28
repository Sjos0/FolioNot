package com.sjos.folionote.core.device

import android.app.ActivityManager
import android.content.Context

/**
 * Perfil de recursos pelo tamanho da RAM do aparelho.
 * Aparelhos com menos de 3 GB (ex.: 2 GB) usam limites menores para não lotar a memória.
 */
object DevicePolicy {
    const val LOW_RAM_THRESHOLD_MB = 3072L
    const val OCR_MAX_DIMENSION_STANDARD_PX = 1500
    const val OCR_MAX_DIMENSION_LOW_RAM_PX = 1100

    @Volatile
    var ocrMaxDimensionPx: Int = OCR_MAX_DIMENSION_STANDARD_PX
        private set

    fun isLowRam(totalRamMb: Long): Boolean = totalRamMb < LOW_RAM_THRESHOLD_MB

    fun ocrMaxDimensionPx(totalRamMb: Long): Int =
        if (isLowRam(totalRamMb)) OCR_MAX_DIMENSION_LOW_RAM_PX else OCR_MAX_DIMENSION_STANDARD_PX

    /** Lê a RAM total uma vez e ajusta os limites do app. Chamar no início do processo. */
    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        ocrMaxDimensionPx = ocrMaxDimensionPx(info.totalMem / (1024L * 1024L))
    }
}
