package com.neo.enhancer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * Blur Destroyer: AI motion-deblur (NAFNet GoPro, ONNX) run tile by tile.
 * When the model loads, it is SELF-TESTED on a smooth gradient. A correct deblur
 * model returns almost the same gradient; a broken model returns noise and is
 * rejected, so the app falls back to strong sharpen instead of garbled output.
 */
class Deblurrer(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val tile: Int

    init {
        val file = modelFile(context)
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        session = env.createSession(file.path, opts)
        inputName = session.inputNames.iterator().next()
        tile = findTileSize()
    }

    /** Try tile sizes from small to large; accept the first that runs AND passes the self-test. */
    private fun findTileSize(): Int {
        var lastError: Throwable? = null
        for (size in CANDIDATES) {
            try {
                val plane = size * size
                val data = FloatArray(3 * plane)
                val d = (size - 1).toFloat()
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val i = y * size + x
                        data[i] = x / d
                        data[plane + i] = y / d
                        data[2 * plane + i] = (x + y) / (2f * d)
                    }
                }
                val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
                    session.run(mapOf(inputName to t)).use { res ->
                        val fb = (res[0] as OnnxTensor).floatBuffer
                        if (fb.capacity() < 3 * plane) throw IllegalStateException("unexpected output size")
                        var sum = 0.0
                        var n = 0
                        var i = 0
                        while (i < 3 * plane) {
                            sum += abs(fb.get(i) - data[i])
                            n++
                            i += 7
                        }
                        val mean = sum / n
                        if (mean.isNaN() || mean > 0.12) {
                            throw IllegalStateException("model output looks broken (diff $mean)")
                        }
                    }
                }
                return size
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException("Deblur model failed the self-test: " + lastError?.message)
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

        /** Copy the model out of the APK once (saves memory vs. loading it all into RAM). */
        private fun modelFile(context: Context): File {
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val dir = context.filesDir
            val f = File(dir, "deblur_$stamp.onnx")
            if (!f.exists()) {
                dir.listFiles()?.filter { it.name.startsWith("deblur_") }?.forEach { it.delete() }
                val tmp = File(dir, "deblur.tmp")
                context.assets.open(MODEL_FILE).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
            return f
        }

        /** True when the deblur model was bundled into this build. */
        fun isAvailable(context: Context): Boolean = try {
            context.assets.open(MODEL_FILE).close()
            true
        } catch (e: Exception) {
            false
        }

        /** True when the model is bundled, loads, and passes the self-test on this phone. */
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
