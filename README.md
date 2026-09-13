# AudioCut

一款专注于「听、转、剪」的 Android 本地音频处理应用。支持音频库管理、离线语音识别转写（带逐字时间戳）、片段剪辑与多格式导出，全程离线运行，不上传任何数据。

## 功能特性

### 🎵 音乐库
- 从文件选择器导入音频/视频，或扫描本地设备中的音频
- 按名称、艺术家、文件夹搜索
- 按时长、文件大小筛选，支持多字段排序
- 收藏管理与多选批量操作
- 底部常驻浮动播放条，随时控制播放/暂停/上一首/下一首/拖动进度

### 📝 文稿（离线语音转写）
- 基于本地离线 ASR 引擎（SenseVoice + Silero VAD），无需联网、无隐私泄露风险
- 逐字时间戳对齐，播放时高亮当前字（卡拉OK式跟读）
- 智能分句与段落排版，自动补充标点
- 长文本转写支持分段加载与进度反馈
- 选中文本即可快速创建剪辑片段或裁剪区间，与剪辑页联动

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

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| 播放 | Media3 / ExoPlayer |
| 语音识别 | sherpa-onnx (ONNX Runtime) + SenseVoice int8 + Silero VAD |
| 音频编解码 | Android MediaCodec / MediaMuxer（流拷贝 + PCM 重采样转码） |
| 架构 | 单模块 MVVM（ViewModel + Kotlin Flow + StateFlow） |
| 数据持久化 | SharedPreferences（KTX）+ org.json |
| 构建工具 | Gradle (Kotlin DSL) + Version Catalog |

## 项目结构

```
app/src/main/java/com/example/mp3player/
├── asr/                 # 离线 ASR 引擎与波形提取
│   ├── OfflineAsrEngine.kt
│   └── WaveformExtractor.kt
├── data/
│   ├── model/           # 数据模型（AudioItem、AudioSegment、Transcript 等）
│   └── repository/      # 数据仓库（音频库、偏好设置）
├── ffmpeg/              # 音频切片与多片段拼接导出器
├── player/              # 播放器管理（ExoPlayer 封装）
├── ui/
│   ├── components/      # 通用组件（浮动播放条、转写视图、筛选面板等）
│   ├── screens/         # 页面（音乐库、文稿、剪辑、裁剪、设置）
│   └── theme/           # 主题与配色
├── utils/               # 工具类（崩溃处理等）
└── viewmodel/           # MainViewModel（全局状态管理）
```

## 环境要求

- Android Studio（建议最新稳定版）
- JDK 11+
- Android SDK：compileSdk 37，minSdk 24（Android 7.0+）
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

## 语音模型说明



| 文件 | 说明 |
| --- | --- |
| `model.int8.onnx` | SenseVoice 语音识别模型（int8 量化） |
| `silero_vad.int8.onnx` | Silero 语音活动检测模型 |
| `tokens.txt` | 词表文件 |

模型在首次使用时会自动复制到应用私有目录，识别全程在本地完成。

## 许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎自由使用、修改与分发。

## 贡献

欢迎提交 Issue 与 Pull Request！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m "feat: add your feature"`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request
