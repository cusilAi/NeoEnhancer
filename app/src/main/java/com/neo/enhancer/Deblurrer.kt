package com.neo.enhancer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Blur Destroyer: AI motion-deblur (NAFNet GoPro, ONNX) run tile by tile.
 * Some exported NAFNet models only accept tiles of a minimum size, so the
 * right tile size is found automatically when the model is loaded.
 */
class Deblurrer(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val tile: Int

    init {
        val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.iterator().next()
        tile = findTileSize()
    }

    /** Try tile sizes from small to large until the model runs. */
    private fun findTileSize(): Int {
        var lastError: Throwable? = null
        for (size in CANDIDATES) {
            try {
                val data = FloatArray(3 * size * size) { 0.5f }
                val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
                    session.run(mapOf(inputName to t)).use { }
                }
                return size
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException("Deblur model rejects every tile size: " + lastError?.message)
    }

    @Synchronized
    fun deblur(src: Bitmap, onTile: (done: Int, total: Int) -> Unit = { _, _ -> }): Bitmap {
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)
        val outPx = IntArray(sw * sh)

        val core = tile - 2 * PAD
        val total = ((sw + core - 1) / core) * ((sh + core - 1) / core)
        var done = 0
        val plane = tile * tile
        val shape = longArrayOf(1, 3, tile.toLong(), tile.toLong())

        var cy = 0
        while (cy < sh) {
            val ch = min(core, sh - cy)
            var cx = 0
            while (cx < sw) {
                val cw = min(core, sw - cx)
                onTile(done, total)

                val ox = cx - PAD
                val oy = cy - PAD
                val data = FloatArray(3 * plane)
                for (yy in 0 until tile) {
                    val sy = (oy + yy).coerceIn(0, sh - 1)
                    val row = sy * sw
                    for (xx in 0 until tile) {
                        val sx = (ox + xx).coerceIn(0, sw - 1)
                        val p = srcPx[row + sx]
                        val i = yy * tile + xx
                        data[i] = ((p shr 16) and 0xFF) / 255f
                        data[plane + i] = ((p shr 8) and 0xFF) / 255f
                        data[2 * plane + i] = (p and 0xFF) / 255f
                    }
                }

                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { res ->
                        val fb = (res[0] as OnnxTensor).floatBuffer
                        for (yy in 0 until ch) {
                            val orow = (PAD + yy) * tile + PAD
                            val drow = (cy + yy) * sw + cx
                            for (xx in 0 until cw) {
                                val i = orow + xx
                                val r = (fb.get(i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                val g = (fb.get(plane + i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                val b = (fb.get(2 * plane + i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                outPx[drow + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }

                done++
                cx += cw
            }
            cy += ch
        }
        onTile(total, total)

        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, sw, 0, 0, sw, sh)
        return out
    }

    companion object {
        const val MODEL_FILE = "deblur.onnx"
        private const val PAD = 16
        private val CANDIDATES = intArrayOf(256, 320, 384, 448, 512, 640, 768, 1024)

        /** True when the deblur model was bundled into this build. */
        fun isAvailable(context: Context): Boolean = try {
            context.assets.open(MODEL_FILE).close()
            true
        } catch (e: Exception) {
            false
        }

        /** True when the model is bundled AND loads and runs on this phone. */
        fun isUsable(context: Context, provider: () -> Deblurrer): Boolean {
            if (!isAvailable(context)) return false
            return try {
                provider()
                true
            } catch (e: Throwable) {
                false
            }
        }
    }
}
