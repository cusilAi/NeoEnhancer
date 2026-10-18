package com.neo.enhancer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI upscaler (Real-ESRGAN general x4v3, ONNX). Works tile by tile so big
 * images do not run out of memory, and writes each tile straight into the
 * final target-size bitmap.
 */
class Upscaler(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.iterator().next()
    }

    @Synchronized
    fun upscale(
        src: Bitmap,
        dstW: Int,
        dstH: Int,
        onTile: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Bitmap {
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val fx = dstW.toDouble() / sw
        val fy = dstH.toDouble() / sh

        val total = ((sw + TILE - 1) / TILE) * ((sh + TILE - 1) / TILE)
        var done = 0

        var y0 = 0
        while (y0 < sh) {
            val y1 = min(y0 + TILE, sh)
            val py0 = max(y0 - PAD, 0)
            val py1 = min(y1 + PAD, sh)
            val dt = (y0 * fy).roundToInt()
            val db = if (y1 == sh) dstH else (y1 * fy).roundToInt()

            var x0 = 0
            while (x0 < sw) {
                val x1 = min(x0 + TILE, sw)
                val px0 = max(x0 - PAD, 0)
                val px1 = min(x1 + PAD, sw)
                val dl = (x0 * fx).roundToInt()
                val dr = if (x1 == sw) dstW else (x1 * fx).roundToInt()

                onTile(done, total)

                val inW = px1 - px0
                val inH = py1 - py0
                val plane = inW * inH
                val data = FloatArray(3 * plane)
                var k = 0
                for (yy in py0 until py1) {
                    val row = yy * sw
                    for (xx in px0 until px1) {
                        val p = srcPx[row + xx]
                        data[k] = ((p shr 16) and 0xFF) / 255f
                        data[plane + k] = ((p shr 8) and 0xFF) / 255f
                        data[2 * plane + k] = (p and 0xFF) / 255f
                        k++
                    }
                }

                val ow = inW * SCALE
                val oh = inH * SCALE
                val cx0 = (x0 - px0) * SCALE
                val cy0 = (y0 - py0) * SCALE
                val cw = (x1 - x0) * SCALE
                val ch = (y1 - y0) * SCALE
                val tilePx = IntArray(cw * ch)

                val shape = longArrayOf(1, 3, inH.toLong(), inW.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { res ->
                        val fb = (res[0] as OnnxTensor).floatBuffer
                        val oplane = ow * oh
                        for (yy in 0 until ch) {
                            val orow = (cy0 + yy) * ow + cx0
                            for (xx in 0 until cw) {
                                val i = orow + xx
                                val r = (fb.get(i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                val g = (fb.get(oplane + i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                val b = (fb.get(2 * oplane + i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                                tilePx[yy * cw + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }

                val dw = dr - dl
                val dh = db - dt
                if (dw > 0 && dh > 0) {
                    val tileBmp = Bitmap.createBitmap(tilePx, cw, ch, Bitmap.Config.ARGB_8888)
                    if (dw == cw && dh == ch) {
                        canvas.drawBitmap(tileBmp, dl.toFloat(), dt.toFloat(), null)
                    } else {
                        val scaled = Bitmap.createScaledBitmap(tileBmp, dw, dh, true)
                        canvas.drawBitmap(scaled, dl.toFloat(), dt.toFloat(), null)
                        if (scaled !== tileBmp) scaled.recycle()
                    }
                    tileBmp.recycle()
                }

                done++
                x0 = x1
            }
            y0 = y1
        }
        onTile(total, total)
        return out
    }

    companion object {
        const val MODEL_FILE = "upscaler.onnx"
        private const val SCALE = 4
        private const val TILE = 128
        private const val PAD = 10
    }
}
