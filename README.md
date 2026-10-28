# Live Background

English | [简体中文](README.zh-CN.md)

Play a video as the background of your JetBrains IDE — or rotate through a folder of videos and images, or run a
Wallpaper Engine web wallpaper.

Works with any IDE built on IntelliJ Platform **2025.1 (build 251) or newer**: IntelliJ IDEA, PhpStorm, WebStorm,
PyCharm, GoLand, and so on.

## Features

- **Video background** — mp4, webm, mov, mkv, avi, gif, apng and anything else ffmpeg can decode. Still images
  (jpg, png, webp, …) work too.
- **Whole window or editors only** — cover the project tree, tool windows, toolbars and status bar as well, or just
  the editors. Split editors and detached editor windows show one continuous picture.
- **Folder rotation** — play the videos and images in a folder in turn, in order or shuffled, on a timer or manually.
- **Web wallpapers (experimental)** — render Wallpaper Engine wallpapers of type *web* with the IDE's built-in
  Chromium.
- **Readable code** — opacity, blur and dim controls, plus an option to keep selection and search-highlight colors
  clear.
- **Light on resources** — frames are decoded by a local `ffmpeg` process and kept in memory only (nothing is written
  to disk), uploaded to the GPU once per frame, and playback pauses when the IDE loses focus or enters power save mode.
- English and Chinese UI, following the IDE language.

## Requirements

- A JetBrains IDE, version 2025.1 or newer
- [ffmpeg](https://ffmpeg.org/) for video and image backgrounds (web wallpapers don't need it)

  ```bash
  brew install ffmpeg          # macOS
  winget install ffmpeg        # Windows
  sudo apt install ffmpeg      # Debian / Ubuntu
  ```

  The plugin finds ffmpeg on `PATH` and in common install locations (Homebrew, MacPorts, WinGet, Scoop). You can also
  set the path in the settings.

> Developed and tested on macOS. Windows and Linux are supported by the code but not tested yet.

## Installation

The plugin is not on JetBrains Marketplace yet, so build it from source:

```bash
git clone https://github.com/chen12311/intellijLiveBackground.git
cd intellijLiveBackground
./gradlew buildPlugin
```

Then in the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and choose
`build/distributions/live-background-0.1.0.zip`.

Notes:

- Gradle needs a JDK to start. If you don't have one, use the runtime bundled with your IDE, e.g. on macOS:
  `export JAVA_HOME=/Applications/PhpStorm.app/Contents/jbr/Contents/Home`
- The first build downloads JDK 21 and the IDE used for compilation (about 1 GB).
- If the download fails with a TLS error such as `Remote host terminated the handshake`, run this and build again
  (finished downloads are kept):

  ```bash
  ./gradlew --stop
  export JAVA_TOOL_OPTIONS="-Djdk.tls.client.protocols=TLSv1.2 -Dorg.gradle.internal.repository.max.retries=10"
  ```

## Usage

After installation nothing is shown until you pick a source. Use the **Tools → Live Background** menu:

| Menu item | What it does |
|---|---|
| Enable Live Background | Turn the background on or off |
| Pause / Resume | Pause or resume playback |
| Choose Video… | Play a single video or image |
| Choose Folder to Rotate… | Rotate through a folder |
| Choose Web Wallpaper… | Play a web wallpaper (folder or HTML file) |
| Next / Previous | Switch to the next / previous item while rotating |
| Performance Stats | Actual frame rate, frame size, paint time, rotation status |
| Live Background Settings… | Open the settings page |

Shortcuts while rotating a folder:

| | macOS | Windows / Linux |
|---|---|---|
| Next | `⌘⌥⇧.` | `Ctrl+Alt+Shift+.` |
| Previous | `⌘⌥⇧,` | `Ctrl+Alt+Shift+,` |

They don't clash with any of the IDE's built-in keymaps. To change them, search for "Live Background" in
**Settings → Keymap**.

The UI is shown in Chinese when the IDE language is Chinese, and in English otherwise. Restart the IDE after changing
the language.

### Settings

**Settings → Appearance & Behavior → Live Background**

| Setting | Default | Description |
|---|---|---|
| Source | Video file | Video file, folder (rotation) or web wallpaper |
| Show in | Whole window | *Whole window* or *Editors only* |
| Opacity | 20% | How strongly the background shows through |
| Fill mode | Cover | *Cover* (crop to fill), *Contain* (may leave borders) or *Stretch* |
| Frame rate limit | 24 fps | 5–60 fps |
| Blur radius | 2 | 0 = no blur |
| Dim | 15% | Darkens the background to keep text readable |
| Decode resolution | 1280 | Limit for the longer side, from 960 up to 4K, or *Original*. Smaller videos are never upscaled |
| Auto pause | On | Pause when the IDE loses focus and in power save mode |
| Protect selection and highlight colors | Off | Keeps selections and search highlights clearly visible (experimental) |
| ffmpeg path | Auto | Leave empty to detect automatically |
| Hardware decoding | On | macOS VideoToolbox |

**Tip — sharper picture:** on a Retina display covering the whole window, the window is over 3000 pixels wide. Set
the decode resolution to 2K or 4K (if the video itself is that large) and the blur radius to 0.

### Folder rotation

Choose **Folder (rotation)** as the source, or use **Choose Folder to Rotate…** in the menu.

- Videos (mp4, m4v, mov, webm, mkv, avi, wmv, flv, ts, gif, apng) and images (jpg, png, webp, bmp, tiff) are
  included, sorted by file name in natural order (`2` before `10`). Hidden files, including macOS `._*` files, are
  skipped.
- **Switch every N minutes** (default 10, 0 = manual only). Only playing time counts: the timer stops while
  paused, when the IDE loses focus or in power save mode.
- **Shuffle** plays every file once per round with no repeats, and a new round never starts with the file that just
  played. *Previous* walks back through what was shown.
- **Include subfolders** searches up to 3 levels deep. Wallpaper Engine folders also contain `preview.jpg` /
  `preview.gif`, which would be included too, so it's usually better to copy the `.mp4` files into one folder.
- The folder is rescanned before every switch, so new files are picked up automatically. After an IDE restart,
  rotation continues from the last item.
- Files that can't be played are skipped (the reason is written to `idea.log`, and the skip count appears in
  Performance Stats). You only get an error if nothing in the folder can be played.
- The previous picture stays until the first frame of the next one is ready, so there's no blank flash between
  items.
- An image is decoded once (with the same scaling, blur and dim) and then uses no CPU.

Wallpaper Engine video wallpapers live in `Steam\steamapps\workshop\content\431960\<ID>\` on Windows. Copy the `.mp4`
files over and they work as is.

### Web wallpapers (experimental)

Choose **Web wallpaper** as the source, or use **Choose Web Wallpaper…** in the menu, then pick a Wallpaper Engine
wallpaper folder of type *web* (one that contains `project.json`) or any HTML file. The page is rendered off-screen by
the IDE's built-in Chromium (JCEF). ffmpeg is not needed.

- The page is laid out at 1920×1080 CSS pixels. The output size follows the decode resolution (1280 → 1280×720;
  *Original* → 1920×1080).
- The default values of the wallpaper's user properties in `project.json` are passed to the page
  (`applyUserProperties`), and the frame rate is passed via `applyGeneralProperties`.
- Blur and dim are applied with a `backdrop-filter` layer on top of the page. Audio and video in the wallpaper are
  muted.
- While paused, the browser drops to 1 fps and the wallpaper's `setPaused(true)` is called.
- Not supported: audio visualizers stay still (system audio isn't available), mouse interaction has no effect, and
  the media-playback and random-file APIs return nothing.
- Page errors and missing files are logged to `idea.log`.
- On 2026.2 and later, the **Web Browser (JCEF)** plugin must be enabled.

`samples/web-wallpaper-demo/` is a test wallpaper. The lines in its lower-left corner show whether each part of the
integration (user properties, file access, pause state, viewport) is working.

## Building and running from source

Open the project in IntelliJ IDEA and wait for the Gradle sync, or use the command line:

| Task | Description |
|---|---|
| `./gradlew buildPlugin` | Build the installable zip into `build/distributions/` |
| `./gradlew runIde` | Start a sandbox PhpStorm 2025.1.7 (the minimum supported version) with the plugin |
| `./gradlew runPhpStorm` | Start a sandbox using `/Applications/PhpStorm.app` if present, otherwise PhpStorm 2026.2.3 |
| `./gradlew test` | Run the unit tests (the decoding tests run only when ffmpeg is installed) |
| `./gradlew verifyPlugin` | Check compatibility with the minimum and the local IDE version |

The versions used above are set in `gradle.properties`.

### Project layout

```
src/main/kotlin/io/github/livebg/
├─ LiveBgService.kt   Wires settings → frame source → player → painters; pause logic
├─ player/            ffmpeg decoding, frame buffers, folder playlist and rotation
├─ render/            Painting the frames into IDE windows and editors
├─ web/               Web wallpapers: project.json, Wallpaper Engine API shim, JCEF off-screen rendering
├─ settings/          Settings state and settings page
└─ actions/           Menu actions
```

UI strings are in `src/main/resources/messages/LiveBgBundle*.properties` (English is the default, `_zh` is Chinese).
