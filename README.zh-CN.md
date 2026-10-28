# Live Background

[English](README.md) | 简体中文

把视频当作 JetBrains IDE 的背景播放，也可以轮播一个文件夹里的视频和图片。

支持基于 IntelliJ 平台 **2025.1（build 251）及以上**的所有 IDE：IntelliJ IDEA、PhpStorm、WebStorm、PyCharm、GoLand 等。

## 功能

- **视频背景**：mp4、webm、mov、mkv、avi、gif、apng 等，只要 ffmpeg 能解码就行；jpg、png、webp 等图片也可以
- **整个窗口或只在编辑器里**：项目树、工具窗口、工具栏、状态栏都可以铺上，也可以只铺编辑器；分屏和拖出去的编辑器窗口画面连成一整幅
- **文件夹轮播**：文件夹里的视频和图片轮着放，顺序或随机，定时切换或手动切换
- **不影响看代码**：不透明度、模糊、压暗都可以调，还能让选区和搜索高亮的颜色保持清楚
- **占用低**：解码交给本机的 `ffmpeg`，帧只在内存里，不写磁盘；每帧只往显卡传一次；IDE 失去焦点或进入省电模式时自动暂停
- 界面有中文和英文两种，跟着 IDE 的语言走

## 环境要求

- JetBrains IDE 2025.1 或更新版本
- [ffmpeg](https://ffmpeg.org/)：用来解码视频和图片

  ```bash
  brew install ffmpeg          # macOS
  winget install ffmpeg        # Windows
  sudo apt install ffmpeg      # Debian / Ubuntu
  ```

  插件会在 `PATH` 和常见安装位置（Homebrew、MacPorts、WinGet、Scoop）里找 ffmpeg，也可以在设置里手动指定路径。

> 在 macOS 上开发和测试。代码支持 Windows 和 Linux，但还没有实测过。

## 安装

插件还没有上架 JetBrains Marketplace，需要从源码构建：

```bash
git clone https://github.com/chen12311/intellijLiveBackground.git
cd intellijLiveBackground
./gradlew buildPlugin
```

然后在 IDE 里：**设置 → 插件 → ⚙ → 从磁盘安装插件…**，选择 `build/distributions/live-background-0.1.0.zip`。

注意：

- 运行 Gradle 需要一个 JDK。没装的话可以借用 IDE 自带的运行时，比如 macOS 上：
  `export JAVA_HOME=/Applications/PhpStorm.app/Contents/jbr/Contents/Home`
- 第一次构建会下载 JDK 21 和编译用的 IDE（约 1 GB）
- 如果下载时报 `Remote host terminated the handshake` 之类的 TLS 错误，先执行下面的命令再重新构建（已下载的部分会保留）：

  ```bash
  ./gradlew --stop
  export JAVA_TOOL_OPTIONS="-Djdk.tls.client.protocols=TLSv1.2 -Dorg.gradle.internal.repository.max.retries=10"
  ```

## 使用

刚装好时什么都不显示，需要先选一个来源。菜单在 **工具 → Live Background**：

| 菜单项 | 作用 |
|---|---|
| 启用动态背景 | 打开或关闭背景 |
| 暂停 / 继续 | 暂停或继续播放 |
| 选择视频… | 播放一个视频或一张图片 |
| 选择文件夹（轮播）… | 轮播一个文件夹 |
| 下一个 / 上一个 | 轮播时切到下一个 / 上一个 |
| 性能统计 | 实际帧率、帧尺寸、绘制耗时、轮播状态 |
| Live Background 设置… | 打开设置页 |

文件夹轮播时的快捷键：

| | macOS | Windows / Linux |
|---|---|---|
| 下一个 | `⌘⌥⇧.` | `Ctrl+Alt+Shift+.` |
| 上一个 | `⌘⌥⇧,` | `Ctrl+Alt+Shift+,` |

和 IDE 自带的各套键位都不冲突。想换的话，在 **设置 → 键盘映射** 里搜索「Live Background」。

IDE 语言是中文时显示中文，其他语言都显示英文。换语言后需要重启 IDE。

### 设置

**设置 → 外观与行为 → Live Background**

| 设置项 | 默认值 | 说明 |
|---|---|---|
| 画面来源 | 视频文件 | 视频文件或文件夹（轮播） |
| 铺到哪里 | 整个窗口 | 「整个窗口」或「只在编辑器里」 |
| 不透明度 | 20% | 越大背景越明显 |
| 铺满方式 | 铺满 | 铺满（裁掉多出来的部分）、完整显示（可能留边）或拉伸 |
| 帧率上限 | 24 fps | 5–60 fps |
| 模糊半径 | 2 | 0 = 不模糊 |
| 压暗 | 15% | 把背景调暗，文字更好读 |
| 解码分辨率 | 1280 | 长边的上限，960 到 4K，或者原画；比上限小的视频不会被放大 |
| 自动暂停 | 开 | IDE 失去焦点或进入省电模式时暂停 |
| 保护选区和高亮颜色 | 关 | 让选区和搜索高亮保持清楚（实验） |
| ffmpeg 路径 | 自动 | 留空就自动查找 |
| 硬件解码 | 开 | macOS VideoToolbox |

**画面不够清楚？** Retina 屏上铺满整个窗口时，窗口实际有 3000 多像素宽。把解码分辨率调到 2K 或 4K（视频本身得有这么大），模糊半径设成 0。

### 文件夹轮播

画面来源选「文件夹（轮播）」，或者用菜单里的「选择文件夹（轮播）…」。

- 文件夹里的视频（mp4、m4v、mov、webm、mkv、avi、wmv、flv、ts、gif、apng）和图片（jpg、png、webp、bmp、tiff）都会放，按文件名自然排序（`2` 排在 `10` 前面）。隐藏文件会跳过，包括 macOS 拷到移动硬盘时多出来的 `._xxx`
- **每隔 N 分钟换一个**（默认 10，0 = 只手动换）。只算播放时间：暂停、IDE 失去焦点、省电模式时停止计时，回来后接着数
- **随机顺序**：每一轮里每个文件只放一次，新一轮的第一个不会和刚放完的重复；「上一个」可以倒着走回去
- **包含子文件夹**：最多往下找 3 层。Wallpaper Engine 的壁纸目录里还有 `preview.jpg` / `preview.gif` 预览图，也会被算进去，所以一般建议把 `.mp4` 拷到同一个文件夹里
- 每次切换前都会重新扫描文件夹，新放进去的文件自动加入；重启 IDE 后从上次那个接着放
- 播不了的文件会跳过（原因写在 `idea.log` 里，性能统计里能看到跳过了几个），只有全部都播不了时才会提示
- 切换时先保留上一个的画面，等下一个的第一帧出来再换，不会闪白
- 图片只解码一次（同样会缩放、模糊、压暗），之后不占 CPU

Wallpaper Engine 的视频类壁纸在 Windows 的 `Steam\steamapps\workshop\content\431960\<ID>\` 里，把 `.mp4` 拷过来就能直接用。

## 从源码构建和运行

用 IntelliJ IDEA 打开项目，等 Gradle 同步完成；也可以直接在命令行里运行：

| 任务 | 说明 |
|---|---|
| `./gradlew buildPlugin` | 构建安装包，输出到 `build/distributions/` |
| `./gradlew runIde` | 用 PhpStorm 2025.1.7（最低支持版本）启动一个装好插件的沙箱 |
| `./gradlew runPhpStorm` | 有 `/Applications/PhpStorm.app` 就用它启动沙箱，没有就下载 PhpStorm 2026.2.3 |
| `./gradlew test` | 运行单元测试（装了 ffmpeg 才会跑真实解码的测试） |
| `./gradlew verifyPlugin` | 检查和最低版本、本机 IDE 版本的兼容性 |

上面用到的版本号在 `gradle.properties` 里配置。

### 代码结构

```
src/main/kotlin/io/github/livebg/
├─ LiveBgService.kt   总调度：设置 → 帧来源 → 播放器 → 画笔；暂停逻辑
├─ player/            ffmpeg 解码、帧缓冲、文件夹轮播
├─ render/            把帧画进 IDE 的窗口和编辑器
├─ settings/          设置项和设置页
└─ actions/           菜单动作
```

界面文字在 `src/main/resources/messages/LiveBgBundle*.properties`（默认英文，`_zh` 是中文）。
