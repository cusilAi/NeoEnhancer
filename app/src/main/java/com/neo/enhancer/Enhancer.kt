package com.neo.enhancer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Settings(
    val fps: Int,
    val noBlur: Boolean,
    val ai: Boolean,
    val shortSide: Int
)

data class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationMs: Long
)

object Enhancer {

    // ---------- helpers ----------

    /** Resolution is the SHORT side (1080 -> 1920x1080 or 1080x1920). 2160 = 4K. */
    fun targetSize(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val short = min(w, h).coerceAtLeast(1)
        var scale = shortSide.toDouble() / short
        val longTarget = max(w, h) * scale
        if (longTarget > 7680) scale *= 7680 / longTarget
        var tw = (w * scale).roundToInt()
        var th = (h * scale).roundToInt()
        tw = (tw - tw % 2).coerceAtLeast(2)
        th = (th - th % 2).coerceAtLeast(2)
        return tw to th
    }

    private fun sharpen(noBlur: Boolean) =
        if (noBlur) "unsharp=7:7:1.5:7:7:0.6" else "unsharp=5:5:0.8:5:5:0.0"

    private fun fpsFilter(srcFps: Double, target: Int): String? = when {
        target > srcFps + 0.5 ->
            "minterpolate=fps=$target:mi_mode=mci:mc_mode=obmc:me_mode=bidir:me=hexbs"
        target < srcFps - 0.5 -> "fps=$target"
        else -> null
    }

    private fun parseRate(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val p = s.split("/")
        val n = p[0].toDoubleOrNull() ?: return null
        val d = p.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        if (d == 0.0) return null
        return n / d
    }

    private fun freshWork(context: Context): File {
        val dir = File(context.cacheDir, "work")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private suspend fun ffmpeg(args: List<String>, totalMs: Long, onProgress: (Float) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s ->
                    if (cont.isActive) {
                        if (ReturnCode.isSuccess(s.returnCode)) {
                            cont.resume(Unit)
                        } else if (ReturnCode.isCancel(s.returnCode)) {
                            cont.cancel()
                        } else {
                            cont.resumeWithException(
                                RuntimeException("FFmpeg failed: " + s.allLogsAsString.takeLast(700))
                            )
                        }
                    }
                },
                null,
                { st ->
                    if (totalMs > 0) {
                        onProgress((st.time.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f))
                    }
                }
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    // ---------- loading ----------

    fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > maxSide) {
                val f = maxSide.toFloat() / big
                decoder.setTargetSize(
                    (info.size.width * f).toInt().coerceAtLeast(1),
                    (info.size.height * f).toInt().coerceAtLeast(1)
                )
            }
        }
    }

    fun loadVideo(context: Context, uri: Uri): Triple<File, VideoInfo, Bitmap?> {
        val dir = File(context.cacheDir, "inputs")
        dir.deleteRecursively()
        dir.mkdirs()
        val f = File(dir, "input_video.mp4")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open video")
        stream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }

        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(f.path)
        var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val capFps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
        val thumb = mmr.getFrameAtTime(0)
        mmr.release()
        if (rot == 90 || rot == 270) {
            val t = w; w = h; h = t
        }
        if (w <= 0 || h <= 0) throw IOException("Not a valid video")

        var fps: Double? = null
        try {
            val info = FFprobeKit.getMediaInformation(f.path).mediaInformation
            val v = info?.streams?.firstOrNull { it.type == "video" }
            fps = parseRate(v?.averageFrameRate) ?: parseRate(v?.realFrameRate)
        } catch (_: Throwable) {
        }
        val finalFps = (fps ?: capFps ?: 30.0).coerceIn(1.0, 240.0)
        return Triple(f, VideoInfo(w, h, finalFps, dur), thumb)
    }

    // ---------- IMAGE ----------

    suspend fun enhanceImage(
        context: Context,
        uri: Uri,
        s: Settings,
        upscaler: () -> Upscaler,
        report: (Float, String) -> Unit
    ): File {
        val work = freshWork(context)
        report(0f, "Loading image...")
        val bmp = decodeImage(context, uri, 4096)
        val (tw, th) = targetSize(bmp.width, bmp.height, s.shortSide)
        val upscaling = min(tw, th) > min(bmp.width, bmp.height)
        val useAi = s.ai && upscaling

        val ctx = currentCoroutineContext()
        val inPng = File(work, "in.png")
        val out = File(work, "out.jpg")

        val toSave: Bitmap = if (useAi) {
            upscaler().upscale(bmp, tw, th) { done, total ->
                ctx.ensureActive()
                report(0.85f * done / total.coerceAtLeast(1), "AI enhancing... $done/$total")
            }
        } else bmp
        FileOutputStream(inPng).use { toSave.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val parts = mutableListOf<String>()
        if (!useAi) parts += "scale=$tw:$th:flags=lanczos"
        if (!useAi || s.noBlur) parts += sharpen(s.noBlur)
        val vf = if (parts.isEmpty()) "null" else parts.joinToString(",")

        report(0.85f, "Finishing...")
        ffmpeg(listOf("-y", "-i", inPng.path, "-vf", vf, "-q:v", "2", out.path), 0L) { }
        inPng.delete()
        report(1f, "Done")
        return out
    }

    // ---------- VIDEO ----------

    suspend fun enhanceVideo(
        context: Context,
        input: File,
        info: VideoInfo,
        s: Settings,
        upscaler: () -> Upscaler,
        report: (Float, String) -> Unit
    ): File {
        val work = freshWork(context)
        val out = File(work, "out.mp4")
        val (tw, th) = targetSize(info.width, info.height, s.shortSide)
        val upscaling = min(tw, th) > min(info.width, info.height)
        val useAi = s.ai && upscaling

        // ---- FAST MODE: one FFmpeg pass ----
        if (!useAi) {
            val fpsF = fpsFilter(info.fps, s.fps)
            val parts = mutableListOf<String>()
            if (upscaling && fpsF != null) parts += fpsF
            parts += "scale=$tw:$th:flags=lanczos"
            if (!upscaling && fpsF != null) parts += fpsF
            parts += sharpen(s.noBlur)
            parts += "format=yuv420p"
            report(0f, "Enhancing video...")
            ffmpeg(
                listOf(
                    "-y", "-i", input.path,
                    "-map", "0:v:0", "-map", "0:a:0?",
                    "-vf", parts.joinToString(","),
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                    "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart",
                    out.path
                ),
                info.durationMs
            ) { report(it, "Enhancing video...") }
            report(1f, "Done")
            return out
        }

        // ---- AI MODE: frames -> AI -> encode ----
        val ctx = currentCoroutineContext()
        val extractFps = if (s.fps < info.fps - 0.5) s.fps.toDouble() else info.fps
        val fpsStr = String.format(Locale.US, "%.3f", extractFps)
        val inDir = File(work, "in").apply { mkdirs() }
        val outDir = File(work, "out").apply { mkdirs() }

        report(0f, "Extracting frames...")
        ffmpeg(
            listOf("-y", "-i", input.path, "-vf", "fps=$fpsStr", "-q:v", "2", "${inDir.path}/%06d.jpg"),
            info.durationMs
        ) { report(it * 0.05f, "Extracting frames...") }

        val frames = (inDir.listFiles() ?: emptyArray()).sortedBy { it.name }
        if (frames.isEmpty()) throw IOException("No frames extracted")

        // target size from the real frame size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames[0].path, bounds)
        val (ftw, fth) = targetSize(bounds.outWidth, bounds.outHeight, s.shortSide)

        val up = upscaler()
        frames.forEachIndexed { i, f ->
            ctx.ensureActive()
            val bmp = BitmapFactory.decodeFile(f.path) ?: throw IOException("Bad frame ${f.name}")
            val res = up.upscale(bmp, ftw, fth) { _, _ -> ctx.ensureActive() }
            FileOutputStream(File(outDir, f.name)).use { res.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            res.recycle()
            f.delete()
            report(0.05f + 0.8f * (i + 1) / frames.size, "AI enhancing frame ${i + 1}/${frames.size}")
        }

        val fpsF2 = fpsFilter(extractFps, s.fps)
        val parts = mutableListOf<String>()
        if (fpsF2 != null) parts += fpsF2
        if (s.noBlur) parts += sharpen(true)
        parts += "format=yuv420p"

        report(0.85f, "Encoding video...")
        ffmpeg(
            listOf(
                "-y",
                "-framerate", fpsStr, "-i", "${outDir.path}/%06d.jpg",
                "-i", input.path,
                "-map", "0:v:0", "-map", "1:a:0?",
                "-vf", parts.joinToString(","),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "17",
                "-c:a", "aac", "-b:a", "192k",
                "-shortest", "-movflags", "+faststart",
                out.path
            ),
            info.durationMs
        ) { report(0.85f + 0.15f * it, "Encoding video...") }

        inDir.deleteRecursively()
        outDir.deleteRecursively()
        report(1f, "Done")
        return out
    }

    // ---------- saving ----------

    fun saveToGallery(context: Context, file: File, isVideo: Boolean): Uri? {
        val ts = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, if (isVideo) "Enhanced_$ts.mp4" else "Enhanced_$ts.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Enhancer"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
