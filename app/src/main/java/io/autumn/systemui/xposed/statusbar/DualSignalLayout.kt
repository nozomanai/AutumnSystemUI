package io.autumn.systemui.xposed.statusbar

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.autumn.systemui.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.Collections
import java.util.HashMap
import java.util.WeakHashMap

/**
 * 把状态栏里并排的两张卡信号收成一列：一张在上，一张在下。
 * 高度保持不变，水平方向再拉长一截；下排底边对齐旁边图标。
 *
 * 图标位置变化时，系统会把 translationX 动画到并排坐标，并把缩放收回 1。
 * 动画开始前先改掉这个目标，两张卡才会一起滑到叠放位置，而不是先被拉开再弹回来。
 */
object DualSignalLayout {
    private const val MOBILE = "ModernStatusBarMobileView"
    private const val CONTAINER = "MiuiStatusIconContainer"
    private const val SPEED = "NetworkSpeedView"
    private const val SCALE_Y = 0.42f
    private const val SCALE_X = 0.60f
    private val containers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val patched = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val fields = HashMap<String, Field?>()
    private val main = Handler(Looper.getMainLooper())
    private var idsReady = false
    private var stateTag = View.NO_ID
    private var translationTag = View.NO_ID

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
        try {
            val mobiles = visibleMobiles(container)
            if (!enabled || mobiles.size < 2) {
                mobiles.forEach(::reset)
                return
            }
            val placed = placement(mobiles) ?: return
            val ref = referenceBand(container, mobiles) ?: return
            if (placed.shift > 1f) {
                for (index in 0 until container.childCount) {
                    val child = container.getChildAt(index)
                    if (child === placed.top || child === placed.bottom) continue
                    if (systemX(child) + child.width <= placed.leftEdge + 1f) {
                        commitX(child, systemX(child) + placed.shift)
                    }
                }
            }
            val lowerBottom = ref.bottom + placed.bottom.height * 0.08f
            place(placed.bottom, placed.targetRight, lowerBottom)
            place(placed.top, placed.targetRight, lowerBottom - placed.bottom.height * SCALE_Y)
        } finally {
            patched.clear()
        }
    }

    /**
     * 系统算完并排坐标之后、Folme 开动画之前调用。
     * 把目标 translationX / 缩放改成叠放结果，移动过程就沿着最终位置走。
     */
    fun beforeApply(state: Any, view: View) {
        if (!enabled || !shown(state)) return
        val container = view.parent as? ViewGroup ?: return
        if (!container.javaClass.name.endsWith(CONTAINER)) return
        val mobiles = visibleMobiles(container)
        if (mobiles.size < 2) return
        val placed = placement(mobiles) ?: return
        if (!placed.fromLayout) return
        val stacked = view === placed.top || view === placed.bottom
        val tighten = !stacked && placed.shift > 1f && systemX(view) + view.width <= placed.leftEdge + 1f
        if (!stacked && !tighten) return
        if (stacked) {
            writeFloat(state, "translationX", stackedX(view, placed.targetRight))
            writeFloat(state, "scaleX", SCALE_X)
            writeFloat(state, "scaleY", SCALE_Y)
        } else {
            writeFloat(state, "translationX", systemX(view) + placed.shift)
        }
        patched.add(view)
    }

    /** 高度用 [SCALE_Y]，宽度用 [SCALE_X]。底边放到 [bottomEdge]，右边缘对齐 [targetRight]。 */
    private fun place(view: View, targetRight: Float, bottomEdge: Float) {
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = SCALE_X
        view.scaleY = SCALE_Y
        commitX(view, stackedX(view, targetRight))
        view.translationY = bottomEdge - view.top - view.height * (1f + SCALE_Y) / 2f
    }

    /** 动画已经朝叠放目标走时，不要再把当前位置拽到终点。 */
    private fun commitX(view: View, x: Float) {
        if (view in patched && translationAnimating(view)) return
        view.translationX = x
    }

    private fun visibleMobiles(container: ViewGroup): List<View> {
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.javaClass.name.endsWith(MOBILE) && it.width > 0 && it.alpha > 0.01f }
    }

    private fun placement(mobiles: List<View>): Placement? {
        if (mobiles.size < 2) return null
        val ordered = mobiles.sortedWith(compareBy<View> { subId(it) }.thenBy { systemX(it) })
        val top = ordered.first()
        val bottom = ordered.last()
        val topX = systemX(top)
        val bottomX = systemX(bottom)
        val leftEdge = minOf(topX, bottomX)
        val targetRight = maxOf(topX + top.width, bottomX + bottom.width)
        val visualLeft = targetRight - maxOf(top.width, bottom.width) * SCALE_X
        val fromLayout = layoutX(top) != null && layoutX(bottom) != null
        return Placement(top, bottom, targetRight, leftEdge, visualLeft - leftEdge, fromLayout)
    }

    /** 系统排版坐标。视图自己的 translationX 在位移动画过程中是中间值，不能拿来当目标。 */
    private fun systemX(view: View): Float = layoutX(view) ?: view.translationX

    private fun layoutX(view: View): Float? {
        val state = iconState(view) ?: return null
        return readFloat(state, "layoutTranslationX")
    }

    private fun stackedX(view: View, targetRight: Float): Float {
        return targetRight - view.width * (1f + SCALE_X) / 2f
    }

    private fun shown(state: Any): Boolean {
        val alpha = readFloat(state, "alpha")
        if (alpha != null && alpha <= 0f) return false
        val visible = readInt(state, "visibleState")
        return visible == null || visible != 2
    }

    private fun translationAnimating(view: View): Boolean {
        ensureIds(view)
        if (translationTag == View.NO_ID) return false
        return view.getTag(translationTag) != null
    }

    private fun iconState(view: View): Any? {
        ensureIds(view)
        if (stateTag == View.NO_ID) return null
        return view.getTag(stateTag)
    }

    private fun ensureIds(view: View) {
        if (idsReady) return
        idsReady = true
        val resources = view.resources
        val packages = listOf(view.context.packageName, "com.android.systemui")
        for (pkg in packages) {
            if (stateTag == View.NO_ID) {
                val id = resources.getIdentifier("status_bar_view_state_tag", "id", pkg)
                if (id != 0) stateTag = id
            }
            if (translationTag == View.NO_ID) {
                val id = resources.getIdentifier("folme_translation_x_animator_tag", "id", pkg)
                if (id != 0) translationTag = id
            }
        }
    }

    private fun writeFloat(target: Any, name: String, value: Float) {
        val field = findField(target.javaClass, name) ?: return
        try {
            field.setFloat(target, value)
        } catch (_: Throwable) {
        }
    }

    private fun readFloat(target: Any, name: String): Float? {
        val field = findField(target.javaClass, name) ?: return null
        return try {
            field.getFloat(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readInt(target: Any, name: String): Int? {
        val field = findField(target.javaClass, name) ?: return null
        return try {
            field.getInt(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        val key = type.name + "#" + name
        if (fields.containsKey(key)) return fields[key]
        var cursor: Class<*>? = type
        var found: Field? = null
        while (cursor != null && found == null) {
            try {
                found = cursor.getDeclaredField(name)
                found.isAccessible = true
            } catch (_: NoSuchFieldException) {
                cursor = cursor.superclass
            }
        }
        fields[key] = found
        return found
    }

    private data class Placement(
        val top: View,
        val bottom: View,
        val targetRight: Float,
        val leftEdge: Float,
        val shift: Float,
        val fromLayout: Boolean,
    )

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
