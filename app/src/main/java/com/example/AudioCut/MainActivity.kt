package com.example.AudioCut

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AudioCut.ui.screens.MainScreen
import com.example.AudioCut.ui.theme.AudioCutTheme
import com.example.AudioCut.viewmodel.AudioLibraryViewModel
import com.example.AudioCut.viewmodel.ClipViewModel
import com.example.AudioCut.viewmodel.ConvertViewModel
import com.example.AudioCut.viewmodel.MainViewModel
import com.example.AudioCut.viewmodel.SettingsViewModel
import com.example.AudioCut.viewmodel.TranscriptViewModel
import com.example.AudioCut.viewmodel.TrimViewModel
import com.example.AudioCut.viewmodel.ViewModelFactory

class MainActivity : ComponentActivity() {

    private val factory by lazy {
        ViewModelFactory(application, (application as AudioCutApplication).container)
    }

    private val mainViewModel: MainViewModel by viewModels { factory }
    private val audioLibraryViewModel: AudioLibraryViewModel by viewModels { factory }
    private val transcriptViewModel: TranscriptViewModel by viewModels { factory }
    private val clipViewModel: ClipViewModel by viewModels { factory }
    private val trimViewModel: TrimViewModel by viewModels { factory }
    private val convertViewModel: ConvertViewModel by viewModels { factory }
    private val settingsViewModel: SettingsViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AudioCutTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionHandler(
                        mainViewModel,
                        audioLibraryViewModel,
                        transcriptViewModel,
                        clipViewModel,
                        trimViewModel,
                        convertViewModel,
                        settingsViewModel
                    )
                }
            }
        }

        // 处理外部传入的 Intent（Open With / Share）
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理外部传入的音频/视频 URI
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                if (intent.action == Intent.ACTION_SEND) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } else {
                    intent.data
                }
            }
            else -> null
        }
        uri?.let {
            audioLibraryViewModel.handleExternalUri(it)
        }
    }
}

@Composable
private fun PermissionHandler(
    mainViewModel: MainViewModel,
    audioLibraryViewModel: AudioLibraryViewModel,
    transcriptViewModel: TranscriptViewModel,
    clipViewModel: ClipViewModel,
    trimViewModel: TrimViewModel,
    convertViewModel: ConvertViewModel,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current

    // 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            audioLibraryViewModel.scanAudios(force = true)
        }
    }

    // 应用进入时请求存储权限
    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissions)
        } else {
            audioLibraryViewModel.scanAudios()
        }
    }

    MainScreen(
        mainViewModel = mainViewModel,
        audioLibraryViewModel = audioLibraryViewModel,
        transcriptViewModel = transcriptViewModel,
        clipViewModel = clipViewModel,
        trimViewModel = trimViewModel,
        convertViewModel = convertViewModel,
        settingsViewModel = settingsViewModel
    )
}
