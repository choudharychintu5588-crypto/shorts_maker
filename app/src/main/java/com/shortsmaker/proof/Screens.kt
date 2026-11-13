package com.shortsmaker.proof

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun ShortsMakerApp(sharedText: String?) {
    ShortsTheme {
        val ctx = LocalContext.current
        val state by AppController.state.collectAsState()
        LaunchedEffect(Unit) { AppController.restoreLast(ctx) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val s = state) {
                is AppState.Idle -> HomeScreen(sharedText)
                is AppState.Running -> RunningScreen(s)
                is AppState.Finished -> ResultsScreen(s.result)
                is AppState.Failed -> FailedScreen(s)
            }
        }
    }
}

@UnstableApi
@Composable
fun HomeScreen(sharedText: String?) {
    val ctx = LocalContext.current
    var url by remember(sharedText) { mutableStateOf(sharedText ?: "") }
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var showFallback by remember { mutableStateOf(false) }
    val valid = YouTubeUrl.normalize(url) != null
    val showError = url.isNotBlank() && !valid

    fun go(input: String) {
        SettingsStore.save(ctx, settings)
        AppController.start(ctx, input, settings)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { go(url) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) go(uri.toString())
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Shorts Maker", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Paste a YouTube link. Get ready-to-post vertical Shorts with Hinglish captions.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("YouTube video link") }, singleLine = true,
            isError = showError, modifier = Modifier.fillMaxWidth(),
            supportingText = { if (showError) Text("This is not a link to one YouTube video.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (!t.isNullOrBlank()) url = t.trim()
            }) { Text("Paste") }
            if (url.isNotEmpty()) TextButton(onClick = { url = "" }) { Text("Clear") }
        }

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else go(url)
            },
            enabled = valid, modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Generate Shorts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

        Text("Only use videos you own or have permission to edit and publish. " +
            "Keep the app open or in the background while it works; a long video can take a while.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsCard(settings) { settings = it.sanitized() }

        TextButton(onClick = { showFallback = !showFallback }) { Text("Link not working? Other options") }
        if (showFallback) {
            Text("If YouTube blocks a link, you can download the video yourself (YouTube Studio lets owners download their own videos) " +
                "and choose the file here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { picker.launch("video/*") }) { Text("Choose a video file instead") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(s: Settings, onChange: (Settings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Settings", fontWeight = FontWeight.SemiBold)
                Text(if (open) "Hide" else "Show", color = MaterialTheme.colorScheme.secondary)
            }
            if (!open) {
                Text("${s.clipCount} Shorts, ${s.minSec}-${s.maxSec} s, ${langName(s.language)}, ${s.model} model",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LabeledSlider("Number of Shorts: ${s.clipCount}", s.clipCount.toFloat(), 3f, 12f, 8) { onChange(s.copy(clipCount = it.toInt())) }
                LabeledSlider("Shortest Short: ${s.minSec} s", s.minSec.toFloat(), 15f, 60f, 44) { onChange(s.copy(minSec = it.toInt())) }
                LabeledSlider("Longest Short: ${s.maxSec} s", s.maxSec.toFloat(), 20f, 60f, 39) { onChange(s.copy(maxSec = it.toInt())) }
                Text("Spoken language")
                Chips(listOf("hi" to "Hindi / Hinglish", "en" to "English", "auto" to "Auto"), s.language) { onChange(s.copy(language = it)) }
                Text("Speech model (bigger = more accurate but slower)")
                Chips(listOf("tiny" to "Tiny (fastest)", "base" to "Base", "small" to "Small (slowest)"), s.model) { onChange(s.copy(model = it)) }
                Text("Vertical layout")
                Chips(listOf("BLUR" to "Blurred background", "FILL" to "Fill screen (crop)"), s.layout.name) { onChange(s.copy(layout = Layout.valueOf(it))) }
                Text("Video quality to download")
                Chips(listOf("720" to "720p (faster)", "1080" to "1080p (sharper)"), s.maxVideoHeight.toString()) { onChange(s.copy(maxVideoHeight = it.toInt())) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Write Hindi in Roman letters (Hinglish)")
                    Switch(checked = s.hinglish, onCheckedChange = { onChange(s.copy(hinglish = it)) })
                }
                LabeledSlider("Caption size: ${s.captionSizePx}", s.captionSizePx.toFloat(), 48f, 110f, 61) { onChange(s.copy(captionSizePx = it.toInt())) }
                Text("Caption highlight colour")
                val colors = listOf(
                    android.graphics.Color.rgb(255, 230, 0) to "Yellow", android.graphics.Color.rgb(80, 255, 120) to "Green",
                    android.graphics.Color.rgb(0, 229, 255) to "Cyan", android.graphics.Color.rgb(255, 140, 0) to "Orange",
                    android.graphics.Color.WHITE to "White")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((c, name) in colors) {
                        Box(Modifier.size(if (s.captionColor == c) 40.dp else 32.dp).clip(CircleShape).background(Color(c))
                            .clickable { onChange(s.copy(captionColor = c)) })
                    }
                }
            }
        }
    }
}

private fun langName(l: String) = when (l) { "hi" -> "Hindi"; "en" -> "English"; else -> "auto language" }

@Composable
private fun LabeledSlider(label: String, value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Text(label)
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = steps)
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in options) FilterChip(selected = selected == k, onClick = { onPick(k) }, label = { Text(label, fontSize = 12.sp) })
    }
}

@UnstableApi
@Composable
fun RunningScreen(s: AppState.Running) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Making your Shorts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(progress = { s.overall }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
        Spacer(Modifier.height(8.dp))
        Text("${(s.overall * 100).toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.etaSec?.let { Text("About ${etaText(it)} left", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (st in Stage.values()) {
                val mark = when {
                    st.ordinal < s.stage.ordinal -> "✓"
                    st == s.stage -> "●"
                    else -> "○"
                }
                Text("$mark  ${st.label}", color = if (st == s.stage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (st == s.stage) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("You can leave the app; processing continues. Keep the phone charged for long videos.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { AppController.cancel() }) { Text("Cancel") }
    }
}

private fun etaText(sec: Long): String = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else if (sec >= 90) "${sec / 60} min" else "$sec s"

@UnstableApi
@Composable
fun FailedScreen(s: AppState.Failed) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("That did not work", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(s.message)
        s.detail?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { AppController.reset() }) { Text("Try again") }
    }
}

@UnstableApi
@Composable
fun ResultsScreen(r: JobResult) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<ShortResult?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("${r.shorts.size} Shorts ready", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(r.videoTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (n in r.notes) Text("Note: $n", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            toast(ctx) {
                Exporter.saveToDownloads(ctx, r.zip, "shorts_${System.currentTimeMillis() / 1000}.zip")
                "ZIP saved in Downloads/ShortsMaker"
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Download all as ZIP", fontWeight = FontWeight.SemiBold) }
        OutlinedButton(onClick = {
            toast(ctx) {
                for (s in r.shorts) Exporter.saveToMovies(ctx, s.file, "short_%02d_${System.currentTimeMillis() / 1000}.mp4".format(s.index))
                "All ${r.shorts.size} Shorts saved in Gallery > Movies/ShortsMaker"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save all videos to Gallery") }

        for (s in r.shorts) ShortCard(s, onPlay = { playing = s })

        TextButton(onClick = { AppController.reset() }) { Text("Make Shorts from another video") }
        Spacer(Modifier.height(24.dp))
    }
    playing?.let { PlayerDialog(it) { playing = null } }
}

private fun toast(ctx: Context, work: () -> String) {
    val msg = try { work() } catch (e: Exception) { e.message ?: "Could not save." }
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun ShortCard(s: ShortResult, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceThumb(s)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(110.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable { onPlay() },
                contentAlignment = Alignment.Center) {
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Text("▶", fontSize = 30.sp, color = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${s.index}  ${s.title}", fontWeight = FontWeight.SemiBold)
                Text("${formatTime(s.startMs)} - ${formatTime(s.endMs)}  (${s.durationSec} s)   Score ${s.score}/100",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.hashtags, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                Text(s.description, fontSize = 12.sp, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        toast(ctx) { Exporter.saveToMovies(ctx, s.file, "short_%02d_${System.currentTimeMillis() / 1000}.mp4".format(s.index)); "Saved in Gallery > Movies/ShortsMaker" }
                    }) { Text("Save MP4", fontSize = 12.sp) }
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Short", "${s.title}\n\n${s.description}\n\n${s.hashtags}"))
                        Toast.makeText(ctx, "Title, description and hashtags copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy text", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun produceThumb(s: ShortResult) = androidx.compose.runtime.produceState<Bitmap?>(null, s.file.path) {
    value = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(s.file.absolutePath)
            r.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }
}

@Composable
private fun PlayerDialog(s: ShortResult, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Card {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoURI(Uri.fromFile(s.file))
                            val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                            setOnPreparedListener { it.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
