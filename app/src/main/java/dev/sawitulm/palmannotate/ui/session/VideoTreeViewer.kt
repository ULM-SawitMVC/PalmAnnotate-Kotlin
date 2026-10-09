package dev.sawitulm.palmannotate.ui.session

import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberAsyncImagePainter
import dev.sawitulm.palmannotate.R
import java.io.File

/**
 * Read-only review of a saved multiside-video tree: its photos and its recording. These trees
 * have no annotation editor, so without this a saved tree could not be looked at again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTreeViewer(
    treeName: String,
    sideCount: Int,
    imageUri: (Int) -> Uri,
    videoFile: File,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var showVideo by rememberSaveable { mutableStateOf(false) }
    val pageCount = sideCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(treeName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (showVideo) {
                    VideoPlayer(videoFile)
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        Image(
                            painter = rememberAsyncImagePainter(imageUri(page)),
                            contentDescription = stringResource(R.string.capture_side_of, page + 1, pageCount),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showVideo) "" else stringResource(R.string.capture_side_of, pagerState.currentPage + 1, pageCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { showVideo = !showVideo }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(if (showVideo) R.string.video_view_photos else R.string.video_view_play))
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(file: File) {
    var failed by remember(file) { mutableStateOf(!file.isFile || file.length() == 0L) }
    if (failed) {
        Text(stringResource(R.string.video_view_failed), color = Color.White)
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // VideoView keeps the recording's aspect ratio only when it may size itself, so it
            // sits centred inside a frame that takes the full area.
            FrameLayout(context).apply {
                val video = VideoView(context)
                addView(video, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
                video.keepScreenOn = true
                video.setMediaController(MediaController(context).also { it.setAnchorView(video) })
                video.setOnErrorListener { _, _, _ -> failed = true; true }
                video.setVideoPath(file.absolutePath)
                video.start()
            }
        },
        onRelease = { frame -> (frame.getChildAt(0) as? VideoView)?.stopPlayback() },
    )
}
