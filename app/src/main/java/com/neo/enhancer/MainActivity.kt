package com.neo.enhancer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val upscaler by lazy { Upscaler(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnhanceScreen(upscaler = { upscaler })
                }
            }
        }
    }
}

private val RESOLUTIONS = listOf(
    320 to "320p",
    480 to "480p",
    720 to "720p",
    1080 to "1080p",
    1440 to "2K",
    2160 to "4K"
)

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun EnhanceScreen(upscaler: () -> Upscaler) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var mode by remember { mutableIntStateOf(0) } // 0 = video, 1 = image
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var imgW by remember { mutableIntStateOf(0) }
    var imgH by remember { mutableIntStateOf(0) }

    var fps by remember { mutableFloatStateOf(30f) }
    var noBlur by remember { mutableStateOf(true) }
    var ai by remember { mutableStateOf(false) }
    var resIndex by remember { mutableIntStateOf(3) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var resultVideo by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<Job?>(null) }

    SideEffect { view.keepScreenOn = busy }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val isVideo = mode == 0
            scope.launch {
                busy = true
                resultUri = null
                progress = 0f
                status = "Loading..."
                try {
                    if (isVideo) {
                        val (file, info, thumb) = withContext(Dispatchers.IO) { Enhancer.loadVideo(context, uri) }
                        videoFile = file
                        videoInfo = info
                        preview = thumb
                        fps = info.fps.roundToInt().coerceIn(1, 120).toFloat()
                    } else {
                        val bmp = withContext(Dispatchers.IO) { Enhancer.decodeImage(context, uri, 4096) }
                        imageUri = uri
                        preview = bmp
                        imgW = bmp.width
                        imgH = bmp.height
                    }
                    status = ""
                } catch (e: Throwable) {
                    status = "Error: ${e} | cause: ${e.cause}"
                }
                busy = false
            }
        }
    }

    val ready = if (mode == 0) videoFile != null else imageUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Neo Enhancer", style = MaterialTheme.typography.headlineMedium)

        // Mode
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Enhance Video", "Enhance Image").forEachIndexed { i, label ->
                val sel = mode == i
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    onClick = {
                        if (mode != i) {
                            mode = i
                            preview = null
                            imageUri = null
                            videoFile = null
                            videoInfo = null
                            resultUri = null
                            status = ""
                            progress = 0f
                        }
                    }
                ) { Text(label) }
            }
        }

        // Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            val p = preview
            if (p != null) {
                Image(
                    bitmap = p.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(if (mode == 0) "Pick a video" else "Pick an image")
            }
        }

        // Info
        val inW = if (mode == 0) videoInfo?.width ?: 0 else imgW
        val inH = if (mode == 0) videoInfo?.height ?: 0 else imgH
        if (ready && inW > 0) {
            val (tw, th) = Enhancer.targetSize(inW, inH, RESOLUTIONS[resIndex].first)
            val vi = videoInfo
            val src = if (mode == 0 && vi != null)
                "Input: ${inW}x$inH, ${"%.1f".format(vi.fps)} fps, ${vi.durationMs / 1000}s"
            else "Input: ${inW}x$inH"
            Text("$src\nOutput: ${tw}x$th", style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(
                        if (mode == 0) ActivityResultContracts.PickVisualMedia.VideoOnly
                        else ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (mode == 0) "Pick video" else "Pick image") }

        // Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Enhancement settings", style = MaterialTheme.typography.titleMedium)

                if (mode == 0) {
                    Text("FPS: ${fps.roundToInt()}")
                    Slider(
                        value = fps,
                        onValueChange = { fps = it },
                        valueRange = 1f..120f,
                        enabled = !busy
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(24, 30, 60, 90, 120).forEach { v ->
                            FilterChip(
                                selected = fps.roundToInt() == v,
                                onClick = { if (!busy) fps = v.toFloat() },
                                label = { Text("$v") }
                            )
                        }
                    }
                }

                SettingSwitch("No blur (sharpen)", noBlur) { if (!busy) noBlur = it }
                SettingSwitch("Enhance every pixel (AI)", ai) { if (!busy) ai = it }
                if (ai) {
                    Text(
                        if (mode == 0)
                            "AI runs on every frame, so it is slow on phones. Use short clips (5-10 s). Only used when output is bigger than the input."
                        else
                            "AI upscaling can take 30-90 s per image. Only used when output is bigger than the input.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text("Resolution")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RESOLUTIONS.forEachIndexed { i, (_, label) ->
                        FilterChip(
                            selected = resIndex == i,
                            onClick = { if (!busy) resIndex = i },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Enhance button
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = ready && !busy,
            onClick = {
                val s = Settings(fps.roundToInt(), noBlur, ai, RESOLUTIONS[resIndex].first)
                val isVideo = mode == 0
                val vFile = videoFile
                val vInfo = videoInfo
                val iUri = imageUri
                job = scope.launch {
                    busy = true
                    resultUri = null
                    progress = 0f
                    status = "Starting..."
                    try {
                        val report: (Float, String) -> Unit = { p, t ->
                            progress = p
                            status = t
                        }
                        val file = withContext(Dispatchers.Default) {
                            if (isVideo) Enhancer.enhanceVideo(context, vFile!!, vInfo!!, s, upscaler, report)
                            else Enhancer.enhanceImage(context, iUri!!, s, upscaler, report)
                        }
                        val uri = withContext(Dispatchers.IO) { Enhancer.saveToGallery(context, file, isVideo) }
                        resultUri = uri
                        resultVideo = isVideo
                        progress = 1f
                        status = if (uri != null)
                            "Done! Saved in ${if (isVideo) "Movies" else "Pictures"}/Enhancer"
                        else "Save failed"
                    } catch (e: CancellationException) {
                        status = "Cancelled"
                        throw e
                    } catch (e: Throwable) {
                        status = "Error: ${e} | cause: ${e.cause}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("Enhance", style = MaterialTheme.typography.titleMedium) }

        if (busy) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(status)
            OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else if (status.isNotEmpty()) {
            Text(status)
        }

        val done = resultUri
        if (done != null && !busy) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = if (resultVideo) "video/mp4" else "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, done)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(i, "Share"))
                }
            ) { Text("Share result") }
        }
    }
}
