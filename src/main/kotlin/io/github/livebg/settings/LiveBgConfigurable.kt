package io.github.livebg.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.livebg.LiveBgBundle.message
import io.github.livebg.LiveBgService
import io.github.livebg.player.FfmpegLocator

class LiveBgConfigurable : BoundConfigurable("Live Background") {

    override fun createPanel(): DialogPanel {
        val st = LiveBgSettings.getInstance().state
        return panel {
            row {
                checkBox(message("settings.enabled")).bindSelected(st::enabled)
            }

            buttonsGroup(message("settings.source")) {
                row {
                    radioButton(message("settings.source.video"), SourceKind.VIDEO)
                }
                indent {
                    row {
                        textFieldWithBrowseButton(
                            FileChooserDescriptorFactory.singleFile().withTitle(message("chooser.video.title"))
                        ).bindText(st::videoPath).align(AlignX.FILL)
                    }
                    row {
                        comment(message("settings.source.video.comment"))
                    }
                }
                row {
                    radioButton(message("settings.source.folder"), SourceKind.FOLDER)
                }
                indent {
                    row {
                        textFieldWithBrowseButton(
                            FileChooserDescriptorFactory.singleDir().withTitle(message("chooser.folder.title"))
                        ).bindText(st::folderPath).align(AlignX.FILL)
                    }
                    row(message("settings.rotate.every")) {
                        spinner(0..1440, 1).bindIntValue(st::rotateMinutes)
                        label(message("settings.rotate.minutes"))
                    }
                    row {
                        checkBox(message("settings.shuffle")).bindSelected(st::shuffle)
                        checkBox(message("settings.recursive")).bindSelected(st::folderRecursive)
                    }
                    row {
                        comment(message("settings.folder.comment", nextShortcut()))
                    }
                }
            }.bind(st::source)

            group(message("settings.display")) {
                row(message("settings.scope")) {
                    comboBox(Scope.entries).bindItem({ st.scope }, { st.scope = it ?: Scope.WINDOW })
                        .comment(message("settings.scope.comment"))
                }
                row(message("settings.opacity")) {
                    spinner(1..100, 5).bindIntValue(st::opacity)
                }
                row(message("settings.fill")) {
                    comboBox(FillMode.entries).bindItem({ st.fill }, { st.fill = it ?: FillMode.COVER })
                }
                row(message("settings.fps")) {
                    spinner(5..60, 1).bindIntValue(st::fps)
                }
                row(message("settings.blur")) {
                    spinner(0..20, 1).bindIntValue(st::blur).comment(message("settings.blur.comment"))
                }
                row(message("settings.dim")) {
                    spinner(0..80, 5).bindIntValue(st::dim)
                }
                row(message("settings.decode")) {
                    comboBox(DECODE_SIZES.keys, textListCellRenderer { it?.let(::decodeSizeName) })
                        .bindItem({ st.decodeWidth }, { st.decodeWidth = it ?: 1280 })
                        .comment(message("settings.decode.comment"))
                }
            }

            group(message("settings.autoPause")) {
                row {
                    checkBox(message("settings.pauseWhenInactive")).bindSelected(st::pauseWhenInactive)
                }
                row {
                    checkBox(message("settings.pauseInPowerSave")).bindSelected(st::pauseInPowerSave)
                }
            }

            group(message("settings.experimental")) {
                row {
                    checkBox(message("settings.protectSelection")).bindSelected(st::protectSelection)
                        .comment(message("settings.protectSelection.comment"))
                }
            }

            group("ffmpeg") {
                row(message("settings.ffmpeg.path")) {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withTitle(message("chooser.ffmpeg.title"))
                    ).bindText(st::ffmpegPath).align(AlignX.FILL)
                }
                row {
                    comment(ffmpegStatus(st.ffmpegPath))
                }
                row {
                    checkBox(message("settings.hwaccel")).bindSelected(st::hwaccel)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        LiveBgService.getInstance().reload()
    }

    /** 「下一个」当前绑的快捷键，没绑就是空。 */
    private fun nextShortcut(): String {
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText("LiveBg.NextBackground")
        return if (shortcut.isEmpty()) "" else message("settings.folder.comment.shortcut", shortcut)
    }

    private fun ffmpegStatus(configured: String): String {
        val found = FfmpegLocator.find(configured)
        return when {
            found != null -> message("settings.ffmpeg.found", found)
            configured.isNotBlank() -> message("settings.ffmpeg.notExecutable", configured)
            else -> message("settings.ffmpeg.notFound")
        }
    }

    private fun decodeSizeName(width: Int): String {
        val tag = DECODE_SIZES[width]
        return when {
            width == 0 -> message("settings.decode.original")
            tag == null -> "$width"
            else -> message("settings.decode.size", "$width", tag)
        }
    }

    private companion object {
        /** 长边上限 → 括号里的说明（没有就是 null）；0 = 原画 */
        val DECODE_SIZES = linkedMapOf(
            960 to null,
            1280 to "720p",
            1600 to null,
            1920 to "1080p",
            2560 to "2K",
            3840 to "4K",
            0 to null,
        )
    }
}
