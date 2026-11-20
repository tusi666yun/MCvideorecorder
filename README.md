# MC Video Recorder

作者：yun

一个面向 Minecraft Forge 1.20.1 的游戏内录像模组：用摄像机录制画面和游戏音效，生成 MP4 视频；也可以把外部视频导入游戏，在显示屏或录像记录器上播放。

An in-game video recorder for Minecraft Forge 1.20.1. Record gameplay with sound, play MP4/M4A audio, and import or export videos without leaving the game.

## 功能

- **摄像机录制**：普通摄像机与高清摄像机，可配置分辨率、帧率和 JPEG 质量。
- **游戏音效录制**：录制 Minecraft 声音引擎中的静态音效（方块、生物、爆炸、UI 等），混成 48kHz 立体声 WAV。
- **生成带音轨 MP4**：停止录像后自动把 `audio.wav` 合成进 `video.mp4`。
  - 有 FFmpeg：视频轨复制，音频编码为 AAC。
  - 无 FFmpeg：使用 jcodec 复制 H.264 视频轨并封装 PCM 音轨。
- **视频播放**：显示屏和录像记录器支持 MP4/M4A 音轨解码，已支持 AAC、MP3、PCM；播放使用 OpenAL，Android 上回退到 AudioTrack。
- **多人录像库**：服务器保存片段，客户端按需下载；屏幕墙可拼接播放。
- **导入/导出**：支持导入 MP4/MOV/M4V/WebM/MKV（WebM/MKV 需要 FFmpeg），导出 MP4/WebM。

## 环境

- Minecraft **1.20.1**
- Forge **47.x**
- Java **17**
- FFmpeg 可选，用于 AAC 合成、WebM 导出和部分视频导入

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。

如果使用 Android/FCL 等启动器，请使用对应 Java 17 运行时；模组本身按 Forge 客户端/服务端通用方式构建。

## 安装

1. 安装 Forge 1.20.1。
2. 把构建出的 jar 放进 `mods/`。
3. 多人服务器需要把同一版本放到服务端 `mods/`；客户端与服务端协议版本必须一致。

## 使用

- 手持摄像机右键开始/停止录制，需要背包里有空白胶卷。
- 停止后会在 `videorecorder_clips/<clip>/` 生成：
  - `video.mp4`：最终视频（通常已带音轨）
  - `audio.wav`：录制音频源，保留用于回放/重新合成
  - `meta.json`：片段信息
- 录像记录器方块右键打开录像库，可以播放、暂停、删除、导入和导出。
- 显示屏方块可以拼接成屏幕墙；同一面墙共用播放时钟。

## 当前限制

- **录制音频目前只覆盖静态音效**，背景音乐和流式环境声还没有接入；这部分在后续版本补充。
- 没有 FFmpeg 时，合成的 MP4 使用 PCM 音轨：本模组可以播放，但部分外部播放器可能不支持，且文件较大。
- 当前“导出 MP4/WebM”按钮是从 JPG 帧重新编码，尚未把录制音轨带进导出文件。
- 导入片段的音频副本不会上传到服务器，远程玩家听不到；直接录制并合成后的 `video.mp4` 会随视频上传。
- 从 35 版本开始，服务器容量/时长限制已放开，实际只受磁盘空间限制；服主需要自行监控录像目录。

## 目录

```
src/main/java/cn/blockforge/generated/mod38575362/   通用逻辑、方块、网络、服务端存储
src/main/java/.../client/                            客户端录制、播放、音视频处理
src/main/resources/                                  模型、贴图、语言、配方
docs/                                                配置示例
```

## 第三方组件

- [jcodec](https://github.com/jcodec/jcodec) — FreeBSD license
- [JAAD](https://jaadec.sourceforge.net/) — Public Domain

详见 `THIRD_PARTY_NOTICES.md`。

## 许可证

MIT，详见 [LICENSE](LICENSE)。
