# Live Background

把视频当作 JetBrains IDE 编辑器的背景。PhpStorm / IDEA / WebStorm / GoLand 等 2025.1（build 251）及以上都能装。

现在的进度：**P0 验证 + P1 最小可用版**的代码都在，外加**文件夹轮播**。选了视频就播视频，选了文件夹就轮着放里面的视频和图片；刚装好还没选的时候什么都不放。

## 怎么跑

前提：mac 上装好 ffmpeg

```bash
brew install ffmpeg
```

### 方式一：直接装进你的 PhpStorm（最快）

```bash
export JAVA_HOME=/Applications/PhpStorm.app/Contents/jbr/Contents/Home   # 没装 JDK 时借用 PhpStorm 自带的
./gradlew buildPlugin
```

然后 PhpStorm → 设置 → 插件 → 齿轮 → 从磁盘安装插件 → 选 `build/distributions/live-background-0.1.0.zip`。

> 第一次构建要下载 JDK 21 和 PhpStorm 2025.1.7（约 1GB）。如果报 `Remote host terminated the handshake`
> 之类的 TLS 握手错误，先执行下面这行再重跑（已下载的部分会保留）：
>
> ```bash
> ./gradlew --stop; export JAVA_TOOL_OPTIONS="-Djdk.tls.client.protocols=TLSv1.2 -Dorg.gradle.internal.repository.max.retries=10"
> ```

### 方式二：开发调试

用 IntelliJ IDEA 打开这个文件夹（PhpStorm 不能写 Kotlin 插件），等 Gradle 同步完，运行：

- `runPhpStorm`：用本机的 `/Applications/PhpStorm.app`（2026.2）启动一个装好插件的 PhpStorm 沙箱
- `runIde`：用编译基线 PhpStorm 2025.1.7 启动，确认最低版本也能用
- `verifyPlugin`：用插件兼容性检查工具检查 2025.1.7 和本机 PhpStorm

> 你的 IDEA 是 2024.1.7，比较老，打开 Gradle 9 + Kotlin 2.4 的工程可能有提示。能用，但建议升级到最新版。
> 不想升级也可以全在终端里跑 `./gradlew runPhpStorm`。

## 用法

- 设置 → 外观与行为 → **Live Background**：来源、铺到哪里（整个窗口 / 只在编辑器里）、透明度、铺满方式、帧率、模糊、压暗、解码分辨率（960 到 4K，或原画不缩小）、ffmpeg 路径
- 嫌糊：Retina 屏铺满整个窗口时，窗口实际有 3000 多像素宽，解码分辨率选 4K（视频本身是 4K 的话），模糊半径设 0
- 菜单 工具 → Live Background：开关、暂停/继续、选择视频、选择文件夹（轮播）、下一个 / 上一个、性能统计
- 界面语言跟着 IDE 走：IDE 是中文（设置 → 外观与行为 → 系统设置 → 语言和区域）就显示中文，其他语言都显示英文。换语言后要重启 IDE
- 快捷键：文件夹轮播时 下一个 `⌘⌥⇧.`、上一个 `⌘⌥⇧,`（Windows / Linux 是 `Ctrl+Alt+Shift+.` / `Ctrl+Alt+Shift+,`）。
  在 PhpStorm 自带的各套键位里都没被占用；想换的话在 设置 → 键盘映射 里搜「Live Background」

### 文件夹轮播

来源选「文件夹（轮播）」，或者菜单里「选择文件夹（轮播）…」。

- 文件夹里的视频（mp4、webm、mov、mkv、gif…）和图片（jpg、png、webp…）都算，按文件名自然排序（2 排在 10 前面），隐藏文件（包括 mac 拷到移动硬盘多出来的 `._xxx`）跳过
- 每隔 N 分钟换一个（默认 10，0 = 不自动换）。**只算播放时间**：暂停、IDE 失去焦点、省电模式时停表，回来接着数
- 随机顺序：洗一遍放完再洗，一轮里不重复，新一轮的第一个不和刚放完的撞；「上一个」能倒着走回去
- 包含子文件夹：最多往下找 3 层子文件夹。注意 Wallpaper Engine 的壁纸目录里还有 `preview.jpg / .gif` 预览图，也会被算进去，建议还是把 `.mp4` 拷到一个文件夹里
- 每次换之前重新扫一遍文件夹，新放进去的文件不用重新设置就能轮到；重启 IDE 后接着放上次那个
- 某个文件播不了就跳过它接着轮（原因写进 idea.log，性能统计里能看到跳过了几个），全都播不了才弹提示
- 换的时候先留着上一个的画面，等下一个的第一帧解出来再换上，不会闪一下空白
- 图片只让 ffmpeg 解一帧（同样会缩放、模糊、压暗），之后不占 CPU

### 网页壁纸（实验）

来源选「网页壁纸」，或者菜单里「选择网页壁纸…」，选 Wallpaper Engine 里类型是 web 的壁纸文件夹（有 `project.json`），
也可以直接选一个 html。用 IDE 自带的 Chromium（JCEF）在后台渲染，不用 ffmpeg。

- 页面按 1920×1080 的 CSS 像素排版，输出宽度用「解码分辨率」（1280 → 1280×720；原画按 1920）
- `project.json` 里用户设置的默认值会在页面加载完后交给壁纸（`applyUserProperties`），帧率交给 `applyGeneralProperties`
- 模糊、压暗用页面最上面一层 `backdrop-filter` 做；壁纸里的视频、音频一律静音
- 暂停（失去焦点、省电）时浏览器帧率降到 1，并调壁纸的 `setPaused(true)`
- 做不到的：音频可视化不会动（拿不到系统声音，只喂静音）；跟鼠标互动的效果没反应；读正在播放的歌、随机挑文件这些接口是空的
- 页面的报错、找不到的文件写在 idea.log 里（搜「网页壁纸」）
- 测试用的壁纸在 `samples/web-wallpaper-demo/`，左下角会显示每一环通没通

## P0 验证清单

装好后先选一个视频（工具 → Live Background → 选择视频…），打开任意项目和文件，整个窗口（编辑器、项目树、工具栏、状态栏）背景上应该在播这个视频。

| 要看的 | 怎么看 | 合格线（参考） |
|---|---|---|
| Retina 上流不流畅 | 选一段有匀速运动的视频，看画面是否顺、不跳 | 肉眼不卡 |
| CPU 占多少 | 活动监视器里 PhpStorm 加上 ffmpeg；整个窗口和只在编辑器里各看一次 | 闲着不动时 PhpStorm 个位数到十几 % |
| EDT 开销 | 工具 → Live Background → 性能统计，看「实际帧率」和「画笔耗时」 | 实际帧率接近目标；画笔耗时每秒几十 ms 以内 |
| 打字、滚动会不会卡 | 在大文件里快速打字、按住方向键、拖滚动条 | 和关掉插件时差不多 |
| 坑 3：选区保护 | 选中一段代码、搜索一个词，然后在设置里勾上「保护选区和高亮颜色」再对比 | 勾上后选区和高亮明显更清楚 |
| 铺满整个窗口 | 看项目树、底部工具窗口、顶部工具栏、状态栏 | 都有画面，和编辑器拼起来是同一幅；补全列表、提示气泡里没有画面 |
| 空编辑器区 | 关掉所有文件，看「随处搜索 / 转到文件」那块 | 有视频；设了自带背景图的话，那张图在这里也被视频盖住 |
| 只在编辑器里 | 设置里「铺到哪里」改成「只在编辑器里」 | 只剩编辑器有画面，其他地方恢复原样 |
| 吸顶行 | 在长函数里往下滚，看顶部吸住的那几行 | 吸顶行是纯色背景，不会出现错位的画面 |
| 分屏 / 拖出窗口 | 右键标签页 → 向右拆分；把标签页拖出成独立窗口 | 都能播，分屏的画面是连续的一整幅 |
| 失去焦点暂停 | 切到别的应用 | 画面停住；切回来继续 |

这一步不过（明显卡顿、CPU 很高），就要换方案，先别往下做。

文件夹轮播要实测的：

| 要看的 | 怎么看 | 合格线 |
|---|---|---|
| 到点换 | 间隔设 1 分钟，放一个有几个视频和图片的文件夹 | 一分钟左右换一次，换的时候不闪空白 |
| 只算播放时间 | 播 30 秒后切到别的应用待两分钟再切回来 | 回来后大约再过 30 秒才换 |
| 手动换 | 按 `⌘⌥⇧.` / `⌘⌥⇧,`，或者 工具 → Live Background → 下一个 / 上一个 | 马上换；随机模式下「上一个」能退回刚才那几个 |
| 图片不占 CPU | 换到一张图片时看活动监视器 | 没有常驻的 ffmpeg 进程，PhpStorm 回到平时水平 |
| 坏文件 | 文件夹里放一个改了扩展名的 txt（比如 `bad.mp4`） | 轮到它时直接跳过，不弹窗 |

Wallpaper Engine 的视频类壁纸在 Windows 的 `Steam\steamapps\workshop\content\431960\<ID>\` 里，拷 `.mp4` 过来就能用。

网页壁纸要实测的（先用 `samples/web-wallpaper-demo`，再拿两三个真壁纸：时钟、Canvas 粒子、WebGL 各一个，整个文件夹拷过来）：

| 要看的 | 怎么看 | 合格线 |
|---|---|---|
| 能出画面 | 选 `samples/web-wallpaper-demo` | 有画面、在动；没画面看 idea.log 里有没有「网页壁纸」的报错 |
| 设置传进去了 | demo 左下角第一行 | 「是」，颜色是橙色（蓝色说明没传进去） |
| 读得到壁纸里的文件 | demo 左下角第二行 | OK |
| 输出尺寸 | demo 的「视口」一行 + 性能统计里的帧尺寸 | 1920×1080 CSS px；解码分辨率 1280 时 devicePixelRatio 0.67、帧 1280×720 |
| CPU | 活动监视器里 PhpStorm + 「jcef Helper (Renderer)」「jcef Helper (GPU)」 | 闲着不动时合计和放视频差不多；WebGL 壁纸记下实际数字 |
| 流畅 | 看 demo 的渐变和粒子 | 肉眼不卡；性能统计实际帧率接近目标 |
| 失去焦点暂停 | 切到别的应用再切回来 | 画面停住；回来后 demo 的「暂停」变回「否」 |
| 换回视频 | 来源改回视频文件 | 正常放视频，活动监视器里 jcef Helper 的 CPU 降下去 |
| 不是网页壁纸 | 选一个视频类壁纸的文件夹 | 提示「这是 video 类型的壁纸」 |

## 代码结构

```
src/main/kotlin/io/github/livebg/
├─ LiveBgService.kt        总调度：设置 → 帧来源 → 播放器 → 各窗口、编辑器的画笔；暂停逻辑
├─ LiveBgStartup.kt        项目打开时启动，给项目窗口登记
├─ Notifier.kt             气泡通知
├─ LiveBgBundle.kt         界面文字（中英文），文字本身在 resources/messages/LiveBgBundle*.properties
├─ player/
│  ├─ FrameBuffers.kt      三缓冲 + 背压（不读管道 ffmpeg 就停）
│  ├─ FrameSource.kt       后台解码线程基类
│  ├─ FfmpegFrameSource.kt ffmpeg 子进程 → bgr0 原始像素 → TYPE_INT_RGB；图片只解一帧
│  ├─ VideoProbe.kt        用 ffmpeg -i 拿尺寸（含竖屏旋转），算解码分辨率
│  ├─ FfmpegLocator.kt     设置路径 → PATH → 常见安装位置
│  ├─ FramePlayer.kt       EDT 定时器按帧率切换当前帧；换来源时留着上一帧，等新来源第一帧到了再换
│  ├─ MediaFiles.kt        哪些扩展名算视频 / 图片，扫文件夹
│  ├─ Playlist.kt          轮播顺序：顺序 / 随机洗牌、上一个、文件被删后接着往下
│  ├─ Carousel.kt          文件夹轮播：只算播放时间的倒计时、到点换、跳过播不了的
│  └─ Stats.kt             性能统计
├─ render/
│  ├─ VideoPainter.kt      画笔：按窗口对齐、铺满方式、透明度、选区保护
│  ├─ FrameTexture.kt      当前帧的显卡副本（坑 4），所有画笔共用，每帧只上传一次
│  ├─ BackgroundInstaller.kt 每个项目窗口的根面板、每个主编辑器各登记一次背景变换；吸顶行、弹出层不画
│  ├─ IdePainterSlots.kt   把画笔挂进窗口自带的背景画笔组（空编辑器区、Islands 圆角）
│  ├─ EditorHook.kt        编辑器创建 / 释放
│  └─ SelectionProtection.kt  坑 3：1×1 透明图
├─ web/                    网页壁纸
│  ├─ WebWallpaper.kt      读 project.json：入口网页、用户设置默认值；网址 ↔ 壁纸文件夹里的文件（不许 ../ 跑出去）
│  ├─ WebShim.kt           往入口网页 <head> 里插 Wallpaper Engine 接口的模拟（resources/web/livebg-shim.js）
│  ├─ WallpaperRequestHandler.kt  拦下 http://wallpaper.livebg/ 的请求，从文件夹里读文件返回
│  └─ WebFrameSource.kt    JCEF 离屏渲染 → onPaint 拷进三缓冲（不等，顶掉没显示的帧）；暂停时帧率降到 1
├─ settings/               设置项和设置页
└─ actions/                菜单动作
```

## 和原规划不一样的地方

- **作用范围（坑 2）靠登记在哪个根组件上来定。** 读 2026.2 源码发现，`createTemporaryBackgroundTransform` 会把我们的画笔包一层，
  包装只转发两个参数的 `paint`，画笔拿不到“正在画哪个组件”，没法在画笔里挑组件。
  - 每个主编辑器单独登记一次，根组件是 `editor.component`，拖出去的独立编辑器窗口也能播。
  - 「整个窗口」（默认）再给每个项目窗口的根面板登记一次：平台的 `JBPanel`、树、列表、表格、工具栏、状态栏、
    标题栏在取画笔时都会走全局变换，所以项目树、工具窗口、状态栏都会画上视频。项目打开 / 关闭时增减登记。
  - 窗口里的轻量弹窗（补全列表、提示）也在根面板下面，自带背景图不画弹出层，这里照做，紧跟着窗口的变换再登记一个变换把它剥掉。
  - 吸顶行的保护必须排在窗口的变换后面，所以新登记一个窗口时，把已有的编辑器按顺序重新登记一遍。
  - 没打开文件时的编辑器空白区（`EditorsSplitters`）、Islands 主题的圆角，是画的时候直接调
    `withFrameBackground` / `withEditorBackground` 向窗口要自带画笔的，会把我们那层换掉：设了自带背景图就画那张图，
    没设就什么都不画。所以再把一个画笔挂进窗口玻璃层的那两组自带画笔里（类型和增删方法包内可见，反射；2025.1 和 2026.2 一样）。
    自带背景图的画笔排在前面、先画，我们的画笔先用平台刚填的颜色把这块重填一遍盖掉，再叠视频，和别处一致。
- **每个编辑器一个画笔实例。** 平台的辅助对象会挂在画笔的监听列表上，登记撤掉时不会摘下来，共用一个画笔会泄漏已关闭的编辑器。
- **吸顶行（sticky lines）不画视频。** 吸顶行是把编辑器内容画到另一块面板上，视频会错位；自带背景也是这么处理的。
  新版平台有个 `NO_BACKGROUND_PREDICATE` 可以干这事，但 2025.1 里没有，所以改成紧跟着每个编辑器的背景变换再登记一个变换，
  吸顶绘制时把背景剥掉。判断“正在画吸顶行”的方法标着 `@Internal`，用反射调。
- **坑 4：每帧先传到显卡再缩放。** 0.1.0 第一版把刚写完的 `BufferedImage` 带双线性缩放直接画上去，Metal 会退回软件路径：
  每次约 250 ms、分配约 135 MB。装上后 EDT 每分钟忙 50～60 秒、每分钟分配 21 GB（平时 1 GB），表现就是卡死、内存狂涨。
  现在每帧先 1:1 拷到同尺寸的 `VolatileImage`（约 1 ms），缩放和透明度都在显卡上做；同样一帧 20 块背景，
  从 272 ms / 135 MB 降到 2.6 ms / 0.02 MB。原帧只在目标不是显卡表面时直接画，而且不开双线性。
- **选区保护的透明度系数用 0.3**（自带背景是 0.65），视频会动，压低一点更好读。那个标记字段在 2025.1 里是包内可见、
  2026.2 里标着 `@Internal`，所以也用反射读，读不到就当没有。
- **像素格式用 `bgr0` + `TYPE_INT_RGB`（原来是 `bgr24` + `TYPE_3BYTE_BGR`）。** Metal 没有 3 字节的纹理格式，
  3 字节的帧每次传显卡都要先在 EDT 上转一遍。实测（M2 Max，PhpStorm 自带的 JBR）每帧上传：

  | 解码分辨率 | 3 字节 | 4 字节 |
  |---|---|---|
  | 1280×720 | 2.1 ms | 0.6 ms |
  | 1920×1080 | 3.5 ms | 1.2 ms |
  | 2560×1440 | 5.7 ms | 2.1 ms |
  | 3840×2160 | 10.8 ms | 4.5 ms |

  4K、24 fps 时 EDT 每秒花在上传上的时间从约 260 ms 降到约 110 ms。管道里的字节一块一块（1 MB）读出来直接写进图片的像素数组，
  不再多占一整帧的字节数组。
- **模糊用 `avgblur` 代替 `boxblur`。** 两者都是方框模糊，输出 PSNR 53 dB，看不出差别；4K 视频、模糊半径 2 时
  ffmpeg 从约 0.8 核降到 0.5 核（`boxblur` 在 4K 下只比实时快 1.4 倍，机器一忙就掉帧）。模糊半径是按解码后的像素算的，
  同样的半径，分辨率越高看起来越不糊。
- **输出尺寸和视频一样时不加 `scale`**（原画、或视频本身比上限小），省一次缩放。

- **网页壁纸用 IDE 自带的 JCEF 离屏渲染，自己接 onPaint。** `JBCefBrowserBuilder.setOSRHandlerFactory` 换成我们的 `CefRenderHandler`，
  视口固定 1920×1080、按输出宽度设 device scale factor；平台只把 `createComponent` 的结果强转成它自己的组件，不碰我们的 handler。
  2025.1.7 和 2026.2 这几个接口一模一样，都不是内部 / 实验性 API；两边默认都开着离屏渲染和 GPU。
  - onPaint 在浏览器的线程上，不能像 ffmpeg 那样卡在三缓冲上等，所以加了不等的 `FrameBuffers.offer`：上一帧没显示就顶掉。
  - 页面从假网址 `http://wallpaper.livebg/` 加载、请求拦下来读文件（`file://` 下页面 fetch 自己的文件会被拦）。
    文件处理继承 `CefResourceHandlerAdapter` 而不是直接实现接口：2026.2 的接口多了 `open / read / skip` 三个抽象方法，
    Adapter 的默认实现会退回老的 `processRequest / readResponse`。
  - 2026.2 起 JCEF 是单独的内置插件（`com.intellij.modules.jcef`），2025.1 里在核心、没有这个 id，所以 plugin.xml 里写成可选依赖。
    `verifyPlugin` 去掉这一行也报 Compatible，分辨不出运行时是否真需要，先保留（两边都无害）。
  - 插件被禁用时一加载 `WebFrameSource` 就找不到类，所以「能不能用」的判断单独放在 `JcefSupport` 里。

## 已验证

- 编译基线 PhpStorm 2025.1.7，单元测试 35 个通过（视频信息解析、解码尺寸（含原画）、铺满计算、三缓冲的顺序 / 背压 / 关闭、
  扫文件夹、轮播顺序 / 随机 / 上一个、播放计时、尺寸不变时不缩放、旧设置里的演示动画读出来落回视频、
  中英文的键和参数一一对应、中文 IDE 读中文文件、其他语言退回英文）
- 本机装了 ffmpeg 时，测试会用 ffmpeg 现生成一张图和一段视频，跑通真实解码：图片解一帧后解码线程自己结束，视频能循环，
  原画尺寸不变、红蓝通道没有串；没装 ffmpeg 就跳过这三个
- `verifyPlugin`：2025.1.7（PS-251）和 2026.2.3（PS-262）都是 Compatible，只有 1 处实验性 API（就是上面那个接口），
  没有内部 API；可以不重启启用 / 禁用
- 网页壁纸（分支 `feature/web-wallpaper`）：单元测试再加 11 个（读 project.json、各种不是网页壁纸的情况、网址 ↔ 文件含 `../` 越界、
  插脚本的位置和原编码保留、配置转义、`offer` 不等），共 46 个通过；`verifyPlugin` 两个版本仍是 Compatible，没有新增实验性 / 内部 API。
  插进去的脚本和 demo 页面在普通 Chromium 里跑过：设置传进去、读本地文件、静音回调、暂停 / 继续、静音、模糊压暗层都正常
- **没验证的**：在 IDE 里的实际画面效果、性能、轮播的实际表现——这些就是上面几张清单要你实测的；英文界面在设置页里的排版（英文比中文长）；
  网页壁纸在 JCEF 离屏模式下真的出帧、device scale 小于 1 时的尺寸、CPU 占用

## 接下来（P2 / P3）

- P2：拖出去的独立编辑器窗口里，编辑器以外的部分也播；记住每个视频播到哪了、轮播切换时淡入淡出、解码分辨率按窗口自适应
- P3：Windows / Linux 实测、`verifyPlugin` 改成 `recommended()` 跑全版本、上架 Marketplace、Wallpaper Engine 导入
