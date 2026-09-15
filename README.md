# AudioCut

一款专注于「听、转、剪」的 Android 本地音频处理应用。支持音频库管理、离线语音识别转写（带逐字时间戳）、标点排版优化、片段剪辑与多格式导出。**全程离线运行，不上传任何音频数据。**

## 功能特性

### 🎵 音乐库
- 从文件选择器导入音频/视频，或扫描本地设备中的音频
- 按名称、艺术家、文件夹搜索；按时长、文件大小筛选，支持多字段排序
- 收藏管理与多选批量操作
- 底部常驻浮动播放条，随时控制播放/暂停/上一首/下一首/拖动进度

### 📝 文稿（离线语音转写）
- 基于 sherpa-onnx 离线 ASR 引擎（SenseVoice int8 + Silero VAD），无需联网
- **机械分段流程**：VAD 语音检测 + 定时长分块，逐块识别并拼接，支持进度反馈
- 逐字时间戳对齐，播放时高亮当前字（卡拉 OK 式跟读）
- **排版优化**：使用 punct-ct 标点恢复模型，删除原文标点后重新打标点，改善长句可读性
  - 分片处理：单次文本数量可在设置中调整（默认 1000 字），窗口间重叠固定 20 字
- 每段开头自动插入当前播放时间戳行，格式 `[hh:mm:ss]`
- 播放时文稿自动跟随滚动，正在播放的文字保持在屏幕约 3/4 处（到底后停止）
- 长按选择文本：拖动滑块可收缩选择范围，选中后单击空白处取消选择（无选区时单击才跳转播放进度）
- 选中文本即可快速创建剪辑片段或裁剪区间，与剪辑页联动
- 识别模型与标点模型均支持**应用内下载**（可随时停止）或**本地文件导入**

### ✂️ 剪辑
- 标记多个音频片段，支持拖动调节与 0.1s 精确微调
- 单片段试听与多片段合并试听（支持变速）
- 多格式导出：M4A (AAC)、WAV (无损)、MP3
- 优先流拷贝（Stream Copy）实现无损极速导出，失败自动降级转码
- 实时导出进度与结果反馈

### 🔪 裁剪
- 卡片式管理多个裁剪区间，删除所选部分
- 裁剪结果预览试听与导出
- 与文稿页文本选择联动创建裁剪区间

### 🔄 转换
- 音频格式转换，支持常见格式互转，转换进度实时反馈

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.2.20 |
| UI | Jetpack Compose + Material 3（Compose BOM 2024.09.00） |
| 构建 | Android Gradle Plugin 9.1.0，Gradle (Kotlin DSL) + Version Catalog |
| 播放 | Media3 / ExoPlayer 1.3.1 |
| 语音识别 | sherpa-onnx 1.13.8（AAR）+ ONNX Runtime 1.17.0 |
| 识别模型 | SenseVoice int8（多语种 ASR）+ Silero VAD int8（语音活动检测）+ punct-ct int8（标点恢复） |
| 音频编解码 | Android MediaCodec / MediaMuxer（流拷贝 + PCM 重采样转码） |
| 音频处理 | FFmpegKit 8.1.2（`io.github.maitrungduc1410` fork） |
| 解压 | Apache Commons Compress 1.27.1（tar.bz2 / tar.gz） |
| 架构 | 单模块 MVVM（ViewModel + Kotlin Flow + StateFlow） |
| 数据持久化 | SharedPreferences（KTX）+ org.json |
| 原生 ABI | arm64-v8a、x86_64（同时支持模拟器） |

## 项目结构

```
app/src/main/java/com/example/AudioCut/
├── asr/                 # 离线 ASR 引擎、VAD 分段、标点分段、模型下载与安装
│   ├── OfflineAsrEngine.kt
│   ├── SenseVoiceRecognizer.kt
│   ├── VadSegmenter.kt
│   ├── AudioChunker.kt
│   ├── PunctuationSegmenter.kt   # 排版优化：标点重打（1000 字/片，重叠 20 字）
│   ├── ModelManager.kt           # 模型下载/导入/解压安装（支持取消）
│   └── ModelInstallCoordinator.kt
├── core/                # 应用事件总线
├── data/
│   ├── model/           # 数据模型（AudioItem、AudioSegment、Transcript 等）
│   └── repository/      # 数据仓库（音频库、偏好设置）
├── di/                  # 依赖容器
├── ffmpeg/              # FFmpegKit 音频切片与拼接导出
├── navigation/          # 页面路由
├── player/              # 播放器管理（ExoPlayer 封装）
├── ui/
│   ├── components/      # 通用组件（浮动播放条、文稿视图、模型对话框等）
│   ├── screens/         # 页面（音乐库、文稿、剪辑、裁剪、转换、设置）
│   └── theme/           # 主题与配色
└── viewmodel/           # 各页面 ViewModel（+ ViewModelFactory）
```

## 环境要求

- Android Studio（建议最新稳定版）
- JDK 11+
- Android SDK：compileSdk 37 / targetSdk 37，minSdk 24（Android 7.0+）
- Gradle 通过 Wrapper 管理（`gradlew`）

## 快速开始

1. 克隆仓库：

```bash
# github
git clone https://github.com/esonst/AudioCut.git
cd AudioCut
```

```bash
# gitee
git clone https://gitee.com/esonst/AudioCut.git
cd AudioCut
```

2. 使用 Android Studio 打开项目，等待 Gradle 同步完成。
3. 连接设备或启动模拟器，点击 Run 运行。

或使用命令行构建：

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（启用混淆与资源压缩）
./gradlew assembleRelease
```

> 说明：`release` 构建类型目前使用 debug 签名密钥，便于本地测试与分发验证；**正式发布前请替换为正式 keystore 签名配置**（见 `app/build.gradle.kts` 中 `buildTypes.release` 的注释）。

## 语音模型说明

模型首次使用时需先安装（在设置页或文稿页触发），支持**应用内下载**（下载/解压过程中可随时点「停止」中止并清理临时文件）或**本地文件导入**。安装后所有识别均在设备本地完成，不联网。

| 模型 | 说明 | 文件 | 
| --- | --- | --- | 
| SenseVoice 识别模型 | 中文/英文/日文/韩文/粤语多语种语音识别（int8 量化） | `model.int8.onnx` + `tokens.json` | 
| punct-ct 标点恢复模型 | 文稿「排版优化」用，删除标点后重新打标点（int8 量化） | `model.int8.onnx` + `tokens.json` | 
| Silero VAD | 语音活动检测（内置在 assets，无需下载） | `silero_vad.int8.onnx` | 

所有模型均自动下载

## 许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎自由使用、修改与分发。

## 贡献

欢迎提交 Issue 与 Pull Request！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m "feat: add your feature"`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request
