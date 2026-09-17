package com.example.audiocut

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiocut.ui.screens.MainScreen
import com.example.audiocut.ui.theme.AudioCutTheme
import com.example.audiocut.viewmodel.*

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
    val lifecycleOwner = LocalLifecycleOwner.current

    // 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            audioLibraryViewModel.scanAudios(force = true)
        }
    }

    // 每次应用回到前台（onResume）都检查存储权限：
    // 覆盖「首次打开请求」与「从系统设置授权/关闭权限后返回」两种场景
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
