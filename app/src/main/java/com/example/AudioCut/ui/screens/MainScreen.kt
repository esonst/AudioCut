package com.example.audiocut.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audiocut.navigation.AppScreen
import com.example.audiocut.ui.components.BottomNavBar
import com.example.audiocut.viewmodel.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * 主界面：六屏左右滑动切换 + 底部导航栏 + 浮动播放栏
 *
 * ViewModel 绑定策略：
 * - 所有 ViewModel 由 MainActivity 通过 ViewModelFactory 创建，Activity 级共享
 * - MainViewModel：导航、主播放器、全局状态
 * - 各子 ViewModel：对应界面的专属业务逻辑
 */
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    audioLibraryViewModel: AudioLibraryViewModel,
    transcriptViewModel: TranscriptViewModel,
    clipViewModel: ClipViewModel,
    trimViewModel: TrimViewModel,
    convertViewModel: ConvertViewModel,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = AppScreen.AUDIO_LIBRARY.pageIndex,
        pageCount = { 6 }
    )

    val currentScreen by mainViewModel.currentScreen.collectAsStateWithLifecycle()

    // 全局 Toast
    LaunchedEffect(Unit) {
        mainViewModel.toastEvent.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 页面跳转联动
    LaunchedEffect(Unit) {
        mainViewModel.targetPage.collectLatest { page ->
            if (pagerState.currentPage != page) {
                pagerState.animateScrollToPage(page)
            }
        }
    }

    // 监听滑动切换并同步到 ViewModel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            mainViewModel.onPageScrolled(page)
        }
    }

    // 音频库加载完成后恢复播放状态（在协程内等待列表非空，避免在组合中读取 StateFlow.value）
    LaunchedEffect(Unit) {
        val audios = audioLibraryViewModel.allAudios
            .filter { it.isNotEmpty() }
            .first()
        mainViewModel.loadLastPlaybackState(audios)
    }

    // 各子界面（AudioLibrary/Transcript/Clip/Trim/Convert/Settings）内部已自行
    // 通过 statusBarsPadding() 处理顶部状态栏间距，这里必须关闭 Scaffold 默认的
    // systemBars contentWindowInsets，避免状态栏高度被重复计算导致顶部大空白。
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            BottomNavBar(
                currentScreen = currentScreen,
                onNavigate = { screen -> mainViewModel.navigateTo(screen) }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                when (page) {
                    0 -> AudioLibraryScreen(
                        mainViewModel = mainViewModel,
                        viewModel = audioLibraryViewModel
                    )
                    1 -> TranscriptScreen(
                        mainViewModel = mainViewModel,
                        transcriptViewModel = transcriptViewModel,
                        clipViewModel = clipViewModel,
                        isPageVisible = pagerState.currentPage == page
                    )
                    2 -> ClipScreen(
                        mainViewModel = mainViewModel,
                        clipViewModel = clipViewModel
                    )
                    3 -> TrimScreen(
                        mainViewModel = mainViewModel,
                        trimViewModel = trimViewModel
                    )
                    4 -> ConvertScreen(
                        mainViewModel = mainViewModel,
                        convertViewModel = convertViewModel
                    )
                    5 -> SettingsScreen(
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                        onBack = { mainViewModel.navigateTo(AppScreen.AUDIO_LIBRARY) }
                    )
                }
            }

            // 浮动播放栏
//            FloatingPlayerBar(
//                currentAudio = currentAudio,
//                isPlaying = isPlaying,
//                currentPositionMs = currentPositionMs,
//                durationMs = durationMs,
//                onTogglePlayPause = { mainViewModel.toggleMainPlayPause() },
//                onFastForward5s = { mainViewModel.mainFastForwardOrRewind(5) },
//                onRewind5s = { mainViewModel.mainFastForwardOrRewind(-5) },
//                onPlayPrevious = { mainViewModel.playMainPrevious() },
//                onPlayNext = { mainViewModel.playMainNext() },
//                onClickBar = { mainViewModel.navigateTo(AppScreen.TRANSCRIPT) },
//                onSeekTo = { mainViewModel.mainSeekTo(it) },
//                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
//            )
        }
    }
}
