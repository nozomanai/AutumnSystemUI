package io.autumn.systemui.xposed.statusbar

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.autumn.systemui.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

/**
 * 把状态栏里并排的两张卡信号收成一列：一张在上，一张在下。
 * 高度保持不变，水平方向再拉长一截；下排底边对齐旁边图标。
 */
object DualSignalLayout {
    private const val MOBILE = "ModernStatusBarMobileView"
    private const val SPEED = "NetworkSpeedView"
    private const val SCALE_Y = 0.42f
    private const val SCALE_X = 0.60f
    private val containers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var enabled: Boolean = true

    fun installPrefs(module: XposedModule) {
        try {
            val prefs = module.getRemotePreferences(ModuleConfig.PREF_GROUP)
            enabled = prefs.getBoolean(ModuleConfig.KEY_DUAL_SIGNAL, true)
            prefs.registerOnSharedPreferenceChangeListener { shared, key ->
                if (key == null || key == ModuleConfig.KEY_DUAL_SIGNAL) {
                    enabled = shared.getBoolean(ModuleConfig.KEY_DUAL_SIGNAL, true)
                    main.post { containers.forEach { it.requestLayout() } }
                }
            }
        } catch (_: Throwable) {
            enabled = true
        }
    }

    fun apply(container: ViewGroup) {
        containers.add(container)
        val mobiles = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.javaClass.name.endsWith(MOBILE) && it.width > 0 && it.alpha > 0.01f }
        if (!enabled || mobiles.size < 2) {
            mobiles.forEach(::reset)
            return
        }
        val ordered = mobiles.sortedWith(compareBy<View> { subId(it) }.thenBy { it.translationX })
        val top = ordered.first()
        val bottom = ordered.last()
        val ref = referenceBand(container, mobiles) ?: return
        val leftEdge = minOf(top.translationX, bottom.translationX)
        val targetRight = maxOf(top.translationX + top.width, bottom.translationX + bottom.width)
        val visualLeft = targetRight - maxOf(top.width, bottom.width) * SCALE_X
        val shift = visualLeft - leftEdge
        if (shift > 1f) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                if (child === top || child === bottom) continue
                if (child.translationX + child.width <= leftEdge + 1f) {
                    child.translationX += shift
                }
            }
        }
        val lowerBottom = ref.bottom + bottom.height * 0.08f
        place(bottom, targetRight, lowerBottom)
        place(top, targetRight, lowerBottom - bottom.height * SCALE_Y)
    }

    /** 高度用 [SCALE_Y]，宽度用 [SCALE_X]。底边放到 [bottomEdge]，右边缘对齐 [targetRight]。 */
    private fun place(view: View, targetRight: Float, bottomEdge: Float) {
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = SCALE_X
        view.scaleY = SCALE_Y
        view.translationX = targetRight - view.width * (1f + SCALE_X) / 2f
        view.translationY = bottomEdge - view.top - view.height * (1f + SCALE_Y) / 2f
    }

    /** 旁边图标的上下沿。网速是两行字，底部和电池齐，用它做参考。 */
    private fun referenceBand(container: ViewGroup, mobiles: List<View>): Band? {
        val speed = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull {
                it.javaClass.name.endsWith(SPEED) && it.height > 0 && it.height < container.height * 0.92f
            }
        if (speed != null) return iconSlot(speed)
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        var found = false
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (mobiles.any { it === child }) continue
            if (child.visibility != View.VISIBLE || child.alpha < 0.01f || child.height <= 0) continue
            if (child.height >= container.height * 0.92f) continue
            top = minOf(top, child.top.toFloat())
            bottom = maxOf(bottom, child.top + child.height.toFloat())
            found = true
        }
        return if (found) Band(top, bottom) else null
    }

    /**
     * 网速控件比两行字高。字形框又偏紧，直接用会把信号缩得比系统「合并」图标小。
     * 以字形为中心，把高度放到旁边图标那一档。
     */
    private fun iconSlot(view: View): Band {
        val ink = inkBand(view)
        val span = maxOf(ink.span, view.height * 0.66f)
        return Band(ink.mid - span / 2f, ink.mid + span / 2f)
    }

    /** 文字实际占用的上下沿。网速控件比字形高，按控件边缘对齐会把信号排到字的下面。 */
    private fun inkBand(view: View): Band {
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        var found = false
        fun consider(text: TextView, origin: Float) {
            val layout = text.layout ?: return
            if (layout.lineCount <= 0) return
            top = minOf(top, origin + text.paddingTop + layout.getLineTop(0))
            bottom = maxOf(bottom, origin + text.paddingTop + layout.getLineBottom(layout.lineCount - 1))
            found = true
        }
        fun walk(node: View, origin: Float) {
            if (node is TextView && node.visibility == View.VISIBLE) consider(node, origin)
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) {
                    val child = node.getChildAt(index)
                    walk(child, origin + child.top)
                }
            }
        }
        walk(view, view.top.toFloat())
        if (found && bottom - top > 8f) return Band(top, bottom)
        return Band(
            view.top + view.paddingTop.toFloat(),
            view.top + view.height - view.paddingBottom.toFloat(),
        )
    }

    private fun reset(view: View) {
        if (view.scaleX == 1f && view.scaleY == 1f && view.translationY == 0f) return
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }

    private fun subId(view: View): Int {
        return try {
            view.javaClass.getMethod("getSubId").invoke(view) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private data class Band(val top: Float, val bottom: Float) {
        val span: Float get() = bottom - top
        val mid: Float get() = (top + bottom) / 2f
    }
}
