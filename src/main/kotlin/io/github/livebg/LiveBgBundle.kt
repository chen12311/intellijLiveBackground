package io.github.livebg

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.LiveBgBundle"

/**
 * 界面文字。跟着 IDE 的语言走（设置 → 外观与行为 → 系统设置 → 语言和区域）：中文读 LiveBgBundle_zh，其余都是英文。
 * 平台第一次读到哪个语言就缓存哪个，换了 IDE 语言要重启才生效，和 IDE 自己一样。
 *
 * 带参数的文字会过一遍 MessageFormat：单引号要写成两个，数字参数会按语言加千分位，所以数字都先转成字符串再传。
 */
object LiveBgBundle : DynamicBundle(LiveBgBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
