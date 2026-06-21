package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
    }
}

// Data holder of audio input lanes
data class AudioTrackState(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val languageCode: String,
    val title: String,
    val isOriginal: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainLayout() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Core States
    var selectedVideo by remember { mutableStateOf<File?>(null) }
    var videoName by remember { mutableStateOf("") }
    var videoSizeText by remember { mutableStateOf("") }
    var videoDurationText by remember { mutableStateOf("") }

    val audioTracks = remember { mutableStateListOf<AudioTrackState>() }
    var includeOriginalAudio by remember { mutableStateOf(true) }
    var originalLanguageCode by remember { mutableStateOf("fil") }

    // Processing & Log states
    var isMuxing by remember { mutableStateOf(false) }
    var progressStage by remember { mutableStateOf("") }
    var progressPercentage by remember { mutableStateOf(0f) }
    val muxLogList = remember { mutableStateListOf<String>() }

    // Final result & Player state
    var renderedOutputFile by remember { mutableStateOf<File?>(null) }
    var showPlayerSection by remember { mutableStateOf(false) }

    // Dialog sheets state
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var showRecordAudioDialog by remember { mutableStateOf(false) }
    var showQuickSettingsDialog by remember { mutableStateOf(false) }

    // Voice record states
    var isRecordingVo by remember { mutableStateOf(false) }
    var activeMediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var lastRecordedVoFile by remember { mutableStateOf<File?>(null) }
    var recordingTimer by remember { mutableStateOf(0) }

    // ExoPlayer controller reference for live track selection
    var activeExoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var availablePlayerTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    // Dynamic log helper
    val addLog = { text: String ->
        muxLogList.add(text)
    }

    // Initialize with professional pre-loaded instructions
    LaunchedEffect(Unit) {
        addLog("Track Master loaded successfully. Bold Typography Theme initialized.")
        addLog("Tip: Click 'Generate Demo Video & Sound' below if your device holds no media.")
    }

    // Media launchers for selection
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                addLog("Copying source video stream...")
                val copiedFile = copyMediaToCache(context, uri, "source_video", ".mp4")
                selectedVideo = copiedFile
                videoName = getFileNameFromUri(context, uri) ?: copiedFile.name
                videoSizeText = formatFileSize(copiedFile.length())
                videoDurationText = queryMediaDurationString(context, copiedFile)
                addLog("Selected Video: $videoName | $videoSizeText")
            }
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                addLog("Copying audio attachment...")
                val copiedFile = copyMediaToCache(context, uri, "audio_attachment", ".mp3")
                val baseFilename = getFileNameFromUri(context, uri) ?: "track_${System.currentTimeMillis()}.mp3"
                // Open language parameter dialog
                audioTracks.add(
                    AudioTrackState(
                        file = copiedFile,
                        languageCode = "eng",
                        title = baseFilename.take(24)
                    )
                )
                addLog("Injected Audio Track: $baseFilename")
            }
        }
    }

    // Permission launcher for Recording Sound
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showRecordAudioDialog = true
        } else {
            Toast.makeText(context, "Microphone permission is required to record speech commentary", Toast.LENGTH_LONG).show()
        }
    }

    // Voice Recording countdown timer
    LaunchedEffect(isRecordingVo) {
        if (isRecordingVo) {
            recordingTimer = 0
            while (isRecordingVo) {
                delay(1000)
                recordingTimer++
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFFEF7FF), // Theme Background Light Lavender-Blush
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "TRACK MASTER",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        letterSpacing = 1.2.sp,
                        color = Color(0xFF1D1B20)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Reset state
                            selectedVideo = null
                            audioTracks.clear()
                            renderedOutputFile = null
                            showPlayerSection = false
                            addLog("Workspace cleared.")
                        },
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Reset Board",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showQuickSettingsDialog = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Preferences",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFFFEF7FF)
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectedVideo == null) {
                        Toast.makeText(context, "Please configure/select a source video file first", Toast.LENGTH_SHORT).show()
                    } else {
                        showAddTrackDialog = true
                    }
                },
                containerColor = Color(0xFFD0BCFF),
                contentColor = Color(0xFF21005D),
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Icon") },
                text = {
                    Text(
                        "ADD TRACK",
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                },
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("fab_add_track")
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // --- SECTION 1: SOURCE VIDEO CONTAINER ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "SOURCE VIDEO",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF6750A4)
                    )
                    Text(
                        text = if (selectedVideo != null) "LOADED" else "PENDING SOURCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF49454F)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (selectedVideo != null) {
                    // Loaded Video Card Design matching proposed styling
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { videoPickerLauncher.launch("video/*") }
                            .testTag("source_video_card"),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
                        border = BorderStroke(1.dp, Color(0xFFD0BCFF))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color(0xFF21005D), shape = RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = "Movie Video icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = videoName,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color(0xFF1D1B20),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$videoSizeText • $videoDurationText",
                                    fontSize = 13.sp,
                                    color = Color(0xFF49454F)
                                )
                            }
                            IconButton(onClick = { videoPickerLauncher.launch("video/*") }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Video source",
                                    tint = Color(0xFF6750A4)
                                )
                            }
                        }
                    }
                } else {
                    // Empty Card State with customizable dotted pattern style
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .background(Color(0xFFFFFBFE), shape = RoundedCornerShape(24.dp))
                            .clickable { videoPickerLauncher.launch("video/*") }
                            .testTag("empty_video_card"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                            drawRoundRect(
                                color = Color(0xFFD0BCFF),
                                style = Stroke(width = 2.5f, pathEffect = pathEffect),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Movie,
                                contentDescription = "Add Video logo",
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "TAP TO CHOOSE VIDEO",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                fontSize = 13.sp,
                                color = Color(0xFF6750A4)
                            )
                        }
                    }
                }
            }

            // --- DEMO ASSETS GENERATION PANEL (Self-Contained testing) ---
            if (selectedVideo == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFFCAC4D0).copy(0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No media files on device?",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = "Generate synthetic demo video & audio assets programmatically to test multi-track switching instantly!",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF49454F),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        Button(
                            onClick = {
                                isMuxing = true
                                progressStage = "Generating demo video..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val sampleVid = File(context.cacheDir, "demo_source_movie.mp4")
                                        val successVid = SyntheticMediaGenerator.generateMp4(sampleVid, 6)

                                        val sampleAud = File(context.cacheDir, "dub_audio_english.wav")
                                        SyntheticMediaGenerator.generateWav(sampleAud, 440.0, 6)

                                        val sampleAudArabic = File(context.cacheDir, "translation_arabic.wav")
                                        SyntheticMediaGenerator.generateWav(sampleAudArabic, 660.0, 6)

                                        withContext(Dispatchers.Main) {
                                            if (successVid) {
                                                selectedVideo = sampleVid
                                                videoName = "demo_source_movie.mp4"
                                                videoSizeText = formatFileSize(sampleVid.length())
                                                videoDurationText = "00:06"

                                                audioTracks.clear()
                                                // Inject synthetic dubs
                                                audioTracks.add(
                                                    AudioTrackState(
                                                        file = sampleAud,
                                                        languageCode = "eng",
                                                        title = "English Commentary Dub"
                                                    )
                                                )
                                                audioTracks.add(
                                                    AudioTrackState(
                                                        file = sampleAudArabic,
                                                        languageCode = "ara",
                                                        title = "Arabic Voice Translation"
                                                    )
                                                )

                                                addLog("Successfully generated custom MP4 video stream (H.264 codec) and 2 WAV audio dub tracks!")
                                                Toast.makeText(context, "Generated complete live tracking assets!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                addLog("AVC video generation issue. Unsupported codec on device.")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            addLog("Generator error: ${e.message}")
                                            Toast.makeText(context, "Asset generator failed", Toast.LENGTH_SHORT).show()
                                        }
                                    } finally {
                                        isMuxing = false
                                        progressStage = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("GENERATE DEMO VIDEO & SOUND", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }

            // --- SECTION 2: AUDIO LANES ROW ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "AUDIO TRACKS LIST",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF6750A4)
                    )
                    Text(
                        text = "${if (includeOriginalAudio) audioTracks.size + 1 else audioTracks.size} Lane(s) Active",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF49454F)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Track lists container
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Lane 1: Original audio track of parent video
                    if (includeOriginalAudio) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                            border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFFE8DEF8), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Original Sound",
                                        tint = Color(0xFF1D192B)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Original Audio Stream",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 14.sp,
                                            color = Color(0xFF1D1B20)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF6750A4), shape = RoundedCornerShape(100.dp))
                                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = originalLanguageCode.uppercase(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Raw video stream channels (Auto-muxed)",
                                        fontSize = 12.sp,
                                        color = Color(0xFF49454F)
                                    )
                                }
                            }
                        }
                    }

                    // Secondary audio lanes
                    if (audioTracks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "None secondary sound lanes loaded. Tap plus or generate to insert translation voiceovers.",
                                style = LocalTextStyle.current.copy(fontStyle = FontStyle.Italic),
                                fontSize = 12.sp,
                                color = Color(0xFF49454F),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        audioTracks.forEach { track ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                                border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color(0xFFE8DEF8), shape = RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = "Language Hub icon",
                                            tint = Color(0xFF1D192B)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = track.title,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 14.sp,
                                                color = Color(0xFF1D1B20),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFEADDFF), shape = RoundedCornerShape(100.dp))
                                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = track.languageCode.uppercase(),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = Color(0xFF21005D)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "External source: ${track.file.name.take(30)}...",
                                            fontSize = 12.sp,
                                            color = Color(0xFF49454F)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            audioTracks.remove(track)
                                            addLog("Removed sound track: ${track.title}")
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete attachment",
                                            tint = Color(0xFFB3261E)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Dashed Add Track Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color.Transparent, shape = RoundedCornerShape(24.dp))
                            .clickable {
                                if (selectedVideo == null) {
                                    Toast
                                        .makeText(
                                            context,
                                            "Please load/generate a source video first!",
                                            Toast.LENGTH_SHORT
                                        )
                                        .show()
                                } else {
                                    showAddTrackDialog = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            drawRoundRect(
                                color = Color(0xFFCAC4D0),
                                style = Stroke(width = 2f, pathEffect = pathEffect),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Track Symbol",
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ADD AUDIO TRACK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF1D1B20),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // --- SECTION 3: MUX LOGS / PROGRESS CONSOLE ---
            if (isMuxing || muxLogList.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "CONSOLE WORKFLOW",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF6750A4)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1B20)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (isMuxing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { progressPercentage },
                                        color = Color(0xFFD0BCFF),
                                        trackColor = Color(0xFF49454F),
                                        strokeWidth = 3.dp,
                                        strokeCap = StrokeCap.Round,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = progressStage,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${(progressPercentage * 100).toInt()}%",
                                        color = Color(0xFFD0BCFF),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { progressPercentage },
                                    color = Color(0xFFD0BCFF),
                                    trackColor = Color(0xFF49454F),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }

                            // Dynamic tailing standard compiler log rows
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                muxLogList.toList().reversed().forEach { log ->
                                    Text(
                                        text = "• $log",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (log.contains("Error", true)) Color(0xFFF2B8B5) else Color(0xFFEADDFF)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- SECTION 4: MULTIPLEX MASTER TRIGGER BUTTON ---
            Button(
                onClick = {
                    val vid = selectedVideo
                    if (vid == null) {
                        Toast.makeText(context, "Please configure/select a source video file!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isMuxing = true
                    progressPercentage = 0.05f
                    progressStage = "Registering stream map pointers..."
                    muxLogList.clear()
                    addLog("Starting Multiplex operations...")

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val outName = "rendered_multitrack_${System.currentTimeMillis()}.mp4"
                            val outPath = File(context.cacheDir, outName)

                            val mappedInputs = audioTracks.map {
                                MuxEngine.AudioTrackInput(
                                    tempFile = it.file,
                                    languageCode = it.languageCode,
                                    title = it.title
                                )
                            }

                            val result = MuxEngine.multiplex(
                                videoFile = vid,
                                audioTracks = mappedInputs,
                                outputFile = outPath,
                                includeOriginalAudio = includeOriginalAudio,
                                origLanguageCode = originalLanguageCode,
                                listener = object : MuxEngine.ProgressListener {
                                    override fun onProgress(stage: String, progress: Float) {
                                        coroutineScope.launch {
                                            progressStage = stage
                                            progressPercentage = progress
                                        }
                                    }

                                    override fun onLog(message: String) {
                                        coroutineScope.launch {
                                            addLog(message)
                                        }
                                    }
                                }
                            )

                            withContext(Dispatchers.Main) {
                                if (result && outPath.exists()) {
                                    renderedOutputFile = outPath
                                    showPlayerSection = true
                                    addLog("Render success. Location: /cache/$outName")
                                    Toast.makeText(context, "Multiplex rendered successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    addLog("Error: Multiplex operation failed.")
                                    Toast.makeText(context, "Muxer compile failed.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                addLog("Error: ${e.message}")
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                isMuxing = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("render_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                shape = RoundedCornerShape(100.dp),
                enabled = !isMuxing && selectedVideo != null
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Transform,
                        contentDescription = "Rebase Edit logo",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RENDER MULTI-TRACK VIDEO",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 0.5.sp,
                        color = Color.White
                    )
                }
            }

            // --- SECTION 5: EXO-PLAYER (LIVE TRACK SWITCHER INTEGRATION) ---
            if (showPlayerSection && renderedOutputFile != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "STREAM TESTING & PLAYER REVIEW",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF6750A4)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1B20)),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Exo player render
                            val outVideo = renderedOutputFile!!
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(Color.Black, shape = RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = true
                                            val exo = ExoPlayer.Builder(ctx).build().apply {
                                                repeatMode = Player.REPEAT_MODE_ONE
                                                setMediaItem(MediaItem.fromUri(Uri.fromFile(outVideo)))
                                                prepare()
                                                playWhenReady = true
                                                addListener(object : Player.Listener {
                                                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                                        // Update list of audio layers
                                                        val list = mutableListOf<Pair<Int, String>>()
                                                        var index = 0
                                                        for (group in tracks.groups) {
                                                            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                                                                for (i in 0 until group.length) {
                                                                    val format = group.getTrackFormat(i)
                                                                    val language = format.language ?: "und"
                                                                    val label = format.label ?: "Track ${index + 1}"
                                                                    val active = group.isTrackSelected(i)
                                                                    list.add(index to "$label [$language]${if (active) " (Active)" else ""}")
                                                                    index++
                                                                }
                                                            }
                                                        }
                                                        availablePlayerTracks = list
                                                    }
                                                })
                                            }
                                            player = exo
                                            activeExoPlayer = exo
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    update = { view ->
                                        // Update video stream url if file target changed
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Select Dynamic Audio Language Stream:",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Interactive selectable flows matching M3 tabs/pills
                            if (availablePlayerTracks.isEmpty()) {
                                Text(
                                    text = "Reading container stream metadata...",
                                    fontSize = 11.sp,
                                    color = Color.LightGray
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    availablePlayerTracks.forEach { (index, description) ->
                                        val isActive = description.contains("(Active)")
                                        Card(
                                            onClick = {
                                                val playerRef = activeExoPlayer
                                                if (playerRef != null) {
                                                    selectAudioTrackIndex(playerRef, index)
                                                }
                                            },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isActive) Color(0xFFD0BCFF) else Color(0xFF49454F)
                                            ),
                                            shape = RoundedCornerShape(100.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isActive) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                                    contentDescription = "Stream Icon",
                                                    tint = if (isActive) Color(0xFF21005D) else Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = description.replace(" (Active)", ""),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isActive) Color(0xFF21005D) else Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }

        // --- DIALOG MODAL 1: ADD TRACK OPTIONS ---
        if (showAddTrackDialog) {
            Dialog(onDismissRequest = { showAddTrackDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7FF)),
                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Add Audio Layer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF1D1B20)
                        )

                        // 1. Pick file from system
                        Button(
                            onClick = {
                                showAddTrackDialog = false
                                audioPickerLauncher.launch("audio/*")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AudioFile, contentDescription = "Audio Icon")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Choose Audio File", fontWeight = FontWeight.Bold)
                            }
                        }

                        // 2. Record Mic Dub Voice-over
                        Button(
                            onClick = {
                                showAddTrackDialog = false
                                val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                    showRecordAudioDialog = true
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = "Mic Icon")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Record Voice Dubbing", fontWeight = FontWeight.Bold)
                            }
                        }

                        // 3. Generate high pitch pad tones
                        Button(
                            onClick = {
                                showAddTrackDialog = false
                                val freq = 750.0
                                val tempFile = File(context.cacheDir, "synth_sound_${System.currentTimeMillis()}.wav")
                                SyntheticMediaGenerator.generateWav(tempFile, freq, 6)
                                audioTracks.add(
                                    AudioTrackState(
                                        file = tempFile,
                                        languageCode = "fra",
                                        title = "High Pitch Beep Tone"
                                    )
                                )
                                addLog("Added Synthetic High Frequency Dub track.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEADDFF), contentColor = Color(0xFF21005D)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SettingsVoice, contentDescription = "Synthesizer Icon")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generate Synth Pad Tone", fontWeight = FontWeight.Bold)
                            }
                        }

                        TextButton(onClick = { showAddTrackDialog = false }) {
                            Text("Discard", fontWeight = FontWeight.Black, color = Color(0xFFB3261E))
                        }
                    }
                }
            }
        }

        // --- DIALOG MODAL 2: VOICE DUB RECORDER ---
        if (showRecordAudioDialog) {
            Dialog(onDismissRequest = {
                if (isRecordingVo) {
                    try {
                        activeMediaRecorder?.stop()
                        activeMediaRecorder?.release()
                    } catch (e: Exception) {}
                    isRecordingVo = false
                }
                showRecordAudioDialog = false
            }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7FF)),
                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                ) {
                    var audioTitle by remember { mutableStateOf("Mic commentary") }
                    var languageCodeInput by remember { mutableStateOf("spa") }

                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Commentary Sound Recorder",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF1D1B20)
                        )

                        // Visual level or blinking record red target
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(
                                    if (isRecordingVo) Color(0xFFF2B8B5) else Color(0xFFEADDFF),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isRecordingVo) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                                contentDescription = "Status mic",
                                tint = if (isRecordingVo) Color.Red else Color(0xFF21005D),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        if (isRecordingVo) {
                            Text(
                                text = "Recording active: $recordingTimer seconds",
                                color = Color.Red,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        } else {
                            Text(
                                text = "Speak directly to dub commentaries onto file.",
                                fontSize = 12.sp,
                                color = Color(0xFF49454F),
                                textAlign = TextAlign.Center
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!isRecordingVo) {
                                Button(
                                    onClick = {
                                        try {
                                            val voiceFile = File(context.cacheDir, "mic_commentary_${System.currentTimeMillis()}.m4a")
                                            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                MediaRecorder(context)
                                            } else {
                                                @Suppress("DEPRECATION")
                                                MediaRecorder()
                                            }.apply {
                                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                setOutputFile(voiceFile.absolutePath)
                                                prepare()
                                                start()
                                            }
                                            activeMediaRecorder = recorder
                                            lastRecordedVoFile = voiceFile
                                            isRecordingVo = true
                                            addLog("Recording commentary voice lane...")
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Recorder prepare error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                                ) {
                                    Text("Start Mic")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        try {
                                            activeMediaRecorder?.stop()
                                            activeMediaRecorder?.release()
                                            activeMediaRecorder = null
                                            isRecordingVo = false
                                            addLog("Commentary track successfully recorded.")
                                            Toast.makeText(context, "Dub Saved", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            isRecordingVo = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                                ) {
                                    Text("Stop & Keep")
                                }
                            }
                        }

                        Divider(color = Color(0xFFCAC4D0))

                        TextField(
                            value = audioTitle,
                            onValueChange = { audioTitle = it },
                            label = { Text("Track Label title") },
                            placeholder = { Text("Director speech Commentary") },
                            modifier = Modifier.fillMaxWidth().testTag("recorded_title_input")
                        )

                        // Quick language flags
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Lang Code:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF49454F))
                            listOf("en", "es", "tl", "ar", "fr").forEach { code ->
                                FilterChip(
                                    selected = languageCodeInput == code,
                                    onClick = { languageCodeInput = code },
                                    label = { Text(code.uppercase()) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                if (isRecordingVo) {
                                    try {
                                        activeMediaRecorder?.stop()
                                        activeMediaRecorder?.release()
                                    } catch (e: Exception) {}
                                    isRecordingVo = false
                                }
                                showRecordAudioDialog = false
                            }) {
                                Text("Discard")
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            TextButton(
                                onClick = {
                                    val f = lastRecordedVoFile
                                    if (f != null && f.exists() && f.length() > 0) {
                                        audioTracks.add(
                                            AudioTrackState(
                                                file = f,
                                                languageCode = languageCodeInput,
                                                title = audioTitle
                                            )
                                        )
                                        showRecordAudioDialog = false
                                    } else {
                                        Toast.makeText(context, "No audio recorded yet! Start microphone first.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("Add Track", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // --- DIALOG MODAL 3: QUICK SETTINGS & MAP CONFIGURATION ---
        if (showQuickSettingsDialog) {
            Dialog(onDismissRequest = { showQuickSettingsDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7FF)),
                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Muxer Map Settings",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF1D1B20)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Include Original audio", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Switch(
                                checked = includeOriginalAudio,
                                onCheckedChange = { includeOriginalAudio = it }
                            )
                        }

                        if (includeOriginalAudio) {
                            TextField(
                                value = originalLanguageCode,
                                onValueChange = { originalLanguageCode = it },
                                label = { Text("Original Audio Language Code") },
                                placeholder = { Text("fil") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = { showQuickSettingsDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                        ) {
                            Text("Apply Changes", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// Helpers
private fun copyMediaToCache(context: Context, uri: Uri, prefix: String, suffix: String): File {
    val tempFile = File.createTempFile(prefix, suffix, context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(tempFile).use { output ->
                input?.copyTo(output)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return tempFile
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun queryMediaDurationString(context: Context, file: File): String {
    return try {
        val mp = MediaPlayer().apply { setDataSource(file.absolutePath) }
        mp.prepare()
        val totalMs = mp.duration
        mp.release()
        val totalSec = totalMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        String.format(Locale.US, "%02d:%02d", min, sec)
    } catch (e: Exception) {
        "00:05" // Fallback fallback duration
    }
}

private fun selectAudioTrackIndex(player: ExoPlayer, trackIndex: Int) {
    val trackGroups = player.currentTracks
    var currentGlobalIdx = 0
    for (group in trackGroups.groups) {
        if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
            for (i in 0 until group.length) {
                if (currentGlobalIdx == trackIndex) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(
                                group.mediaTrackGroup,
                                i
                            )
                        )
                        .build()
                    return
                }
                currentGlobalIdx++
            }
        }
    }
}
