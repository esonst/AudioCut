package com.example.mp3player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.mp3player.ui.components.BottomNavBar
import com.example.mp3player.ui.screens.ConvertScreen
import com.example.mp3player.viewmodel.AppScreen
import com.example.mp3player.viewmodel.MainViewModel

/**
 * 主容器页面：基于 HorizontalPager 支持 音乐库-文稿-剪辑-裁剪-格式转换-设置 六屏左右滑动平滑切换
 * 同时集成底部导航栏双向实时联动
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsState()
    // 裁剪页（第4页）向左滑动不再进入系统设置，系统设置改为覆盖层展示
    val pagerState = rememberPagerState(
        initialPage = currentScreen.pageIndex.coerceAtMost(4),
        pageCount = { 5 }
    )

    // 监听全局 Toast 事件
    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 监听外部跳转目标页面事件（系统设置不在 Pager 中，需过滤掉其目标页索引）
    LaunchedEffect(Unit) {
        viewModel.targetPage.collect { targetPage ->
            if (targetPage < 5 && pagerState.currentPage != targetPage) {
                pagerState.scrollToPage(targetPage)
            }
        }
    }

    // 监听滑动切换并同步到 ViewModel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.onPageScrolled(page)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> AudioLibraryScreen(viewModel = viewModel)
                1 -> TranscriptScreen(viewModel = viewModel)
                2 -> ClipScreen(viewModel = viewModel)
                3 -> TrimScreen(viewModel = viewModel)
                4 -> ConvertScreen(viewModel = viewModel)
            }
        }

        // 系统设置覆盖层：仅通过右上角齿轮进入，不参与左右滑动
        if (currentScreen == AppScreen.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { viewModel.navigateTo(AppScreen.AUDIO_LIBRARY) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 底部常驻导航栏
        BottomNavBar(
            currentScreen = currentScreen,
            onNavigate = { screen ->
                viewModel.navigateTo(screen)
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
