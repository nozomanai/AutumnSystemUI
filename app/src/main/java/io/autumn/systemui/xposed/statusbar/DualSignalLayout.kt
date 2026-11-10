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
 * 动画开始前先改掉这个目标，两张卡才会一起滑到叠放位置。
 *
 * 灵动岛按并排坐标裁图标。叠放后两张卡视觉上在同一处，却仍会只把左边那张收进岛里。
 * 靠右的那张还显示时，左边那张也保持显示，避免展开时错位、收起后只剩一张或只剩下排。
 */
object DualSignalLayout {
    private const val MOBILE = "ModernStatusBarMobileView"
    private const val CONTAINER = "MiuiStatusIconContainer"
    private const val SPEED = "NetworkSpeedView"
    private const val SCALE_Y = 0.42f
    private const val SCALE_X = 0.60f
    private val containers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val anchors = WeakHashMap<View, Slot>()
    private val islandActive = WeakHashMap<ViewGroup, Boolean>()
    private val subIds = WeakHashMap<View, Int>()
    private val fields = HashMap<String, Field?>()
    private val methods = HashMap<String, java.lang.reflect.Method?>()
    private val properties = HashMap<String, Any>()
    private val resIds = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private var idsReady = false
    private var stateTag = View.NO_ID
    private var islandMethodReady = false
    private var islandMethod: java.lang.reflect.Method? = null
    private var subIdMethod: java.lang.reflect.Method? = null
    private var subIdLookup = false
    private var passContainer: ViewGroup? = null
    private var passMobiles: List<View>? = null
    private var passPlacement: Placement? = null
    private var inkView: View? = null
    private var inkTop = Int.MIN_VALUE
    private var inkHeight = Int.MIN_VALUE
    private var inkCached: Band? = null

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
            val mobiles = if (passContainer === container && passMobiles != null) {
                passMobiles!!
            } else {
                mobileViews(container)
            }
            if (!enabled || mobiles.size < 2) {
                mobiles.forEach(::reset)
                return
            }
            val placed = if (passContainer === container && passPlacement != null) {
                passPlacement!!
            } else {
                placement(mobiles) ?: return
            }
            val ref = referenceBand(container, mobiles) ?: return
            keepPair(container, placed)
            if (placed.shift > 1f) {
                for (index in 0 until container.childCount) {
                    val child = container.getChildAt(index)
                    if (child === placed.top || child === placed.bottom) continue
                    if (systemX(child) + child.width <= placed.leftEdge + 1f) {
                        snapX(child, systemX(child), systemX(child) + placed.shift)
                    }
                }
            }
            val lowerBottom = ref.bottom + placed.bottom.height * 0.08f
            val topX = stackedX(placed.top, placed.targetRight)
            val bottomX = stackedX(placed.bottom, placed.targetRight)
            val snap = kotlin.math.abs((topX - placed.top.translationX) - (bottomX - placed.bottom.translationX)) > 4f
            place(placed.bottom, bottomX, lowerBottom, snap)
            place(placed.top, topX, lowerBottom - placed.bottom.height * SCALE_Y, snap)
        } finally {
            passContainer = null
            passMobiles = null
            passPlacement = null
        }
    }

    /**
     * 系统算完并排坐标之后、Folme 开动画之前调用。
     * 把目标 translationX / 缩放改成叠放结果，移动过程就沿着最终位置走。
     */
    fun beforeApply(state: Any, view: View) {
        if (!enabled) return
        val container = view.parent as? ViewGroup ?: return
        if (!container.javaClass.name.endsWith(CONTAINER)) return
        val placed = prepare(container) ?: return
        if (!placed.fromLayout) return
        val stacked = view === placed.top || view === placed.bottom
        if (stacked) releaseFalseIslandHide(state, view, placed)
        if (stacked && !shown(state)) return
        val tighten = !stacked && placed.shift > 1f && systemX(view) + view.width <= placed.leftEdge + 1f
        if (!stacked && !tighten) return
        if (stacked) {
            if (visuallyClear(container, placed)) writeInt(state, "blur", 0)
            val raw = systemX(view)
            val x = stackedX(view, placed.targetRight)
            writeFloat(state, "translationX", x)
            writeFloat(state, "scaleX", SCALE_X)
            writeFloat(state, "scaleY", SCALE_Y)
            val partner = if (view === placed.top) placed.bottom else placed.top
            val partnerX = stackedX(partner, placed.targetRight)
            val split = kotlin.math.abs((x - view.translationX) - (partnerX - partner.translationX)) > 4f
            if (split) {
                stopFolme(view, "folme_translation_x_animator_tag", "TRANSLATION_X")
                view.translationX = x
            }
            anchors[view] = Slot(raw, x)
        } else {
            val raw = systemX(view)
            val x = raw + placed.shift
            if (view.translationX != x) {
                stopFolme(view, "folme_translation_x_animator_tag", "TRANSLATION_X")
                view.translationX = x
            }
            writeFloat(state, "translationX", x)
            anchors[view] = Slot(raw, x)
        }
    }

    /**
     * 叠放后的位置如果在岛的右边，这张卡不该因为并排坐标被收进岛里。
     */
    private fun prepare(container: ViewGroup): Placement? {
        if (passContainer === container) return passPlacement
        val mobiles = mobileViews(container)
        passContainer = container
        passMobiles = mobiles
        passPlacement = if (!enabled || mobiles.size < 2) null else placement(mobiles)
        return passPlacement
    }

    private fun releaseFalseIslandHide(state: Any, view: View, placed: Placement) {
        val container = view.parent as? ViewGroup ?: return
        if (islandCut(container) <= 0) return
        if (!visuallyClear(container, placed)) return
        val blur = readInt(state, "blur") ?: 0
        if (!suppressed(view, state) && blur == 0) return
        writeInt(state, "visibleState", 0)
        writeInt(state, "inIslandState", 20)
        writeInt(state, "blur", 0)
        writeFloat(state, "alpha", 1f)
        showIcon(view)
    }

    /** 岛已经把模糊和缩小动画发出去时，在布局结束后清掉。 */
    private fun keepPair(container: ViewGroup, placed: Placement) {
        val cut = islandCut(container)
        val was = islandActive[container] == true
        if (cut <= 0) {
            if (!was) return
            islandActive[container] = false
        } else {
            islandActive[container] = true
            if (placed.targetRight - maxOf(placed.top.width, placed.bottom.width) * SCALE_X < cut) return
        }
        restore(placed.top)
        restore(placed.bottom)
        clearIslandClip(placed.top)
        clearIslandClip(placed.bottom)
    }

    private fun restore(view: View) {
        val state = iconState(view)
        if (state != null) {
            writeInt(state, "visibleState", 0)
            writeInt(state, "inIslandState", 20)
            writeInt(state, "blur", 0)
            writeFloat(state, "alpha", 1f)
            writeFloat(state, "scaleX", SCALE_X)
            writeFloat(state, "scaleY", SCALE_Y)
        }
        showIcon(view)
        stopFolme(view, "folme_alpha_animator_tag", "ALPHA")
        view.alpha = 1f
        clearBlur(view)
    }

    /** 叠放后的左边缘在岛右边缘的外侧，说明这列信号没有被岛挡住。 */
    private fun visuallyClear(container: ViewGroup, placed: Placement): Boolean {
        val cut = islandCut(container)
        if (cut <= 0) return true
        val width = maxOf(placed.top.width, placed.bottom.width)
        return placed.targetRight - width * SCALE_X >= cut
    }

    private fun islandCut(container: ViewGroup): Int {
        if (!islandMethodReady) {
            islandMethodReady = true
            islandMethod = container.javaClass.declaredMethods.firstOrNull {
                it.name == "getIslandTranslationX" && it.parameterTypes.isEmpty()
            }?.also { it.isAccessible = true }
        }
        val method = islandMethod ?: return -1
        return try {
            (method.invoke(container) as? Number)?.toInt() ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** 高度用 [SCALE_Y]，宽度用 [SCALE_X]。底边放到 [bottomEdge]，右边缘对齐 [targetRight]。 */
    private fun place(view: View, applied: Float, bottomEdge: Float, snap: Boolean) {
        val pivotX = view.width / 2f
        val pivotY = view.height / 2f
        if (view.pivotX != pivotX) view.pivotX = pivotX
        if (view.pivotY != pivotY) view.pivotY = pivotY
        if (view.scaleX != SCALE_X) view.scaleX = SCALE_X
        if (view.scaleY != SCALE_Y) view.scaleY = SCALE_Y
        ensureX(view, systemX(view), applied, snap)
        val translationY = bottomEdge - view.top - view.height * (1f + SCALE_Y) / 2f
        if (view.translationY != translationY) view.translationY = translationY
    }

    /** 两张卡还叠在一起时，允许整组跟着岛平移。已经拆开再往右收的动画直接停掉。 */
    private fun ensureX(view: View, raw: Float, applied: Float, snap: Boolean) {
        anchors[view] = Slot(raw, applied)
        val id = identifier(view, "folme_translation_x_animator_tag")
        val info = if (id != 0) view.getTag(id) else null
        if (!snap && info != null) {
            val end = findField(info.javaClass, "endValue")?.get(info) as? Float
            if (end != null && kotlin.math.abs(end - applied) <= 1f) return
        }
        if (info != null) stopFolme(view, "folme_translation_x_animator_tag", "TRANSLATION_X")
        val state = iconState(view)
        if (state != null) writeFloat(state, "translationX", applied)
        view.translationX = applied
    }

    private fun snapX(view: View, raw: Float, applied: Float) {
        ensureX(view, raw, applied, snap = true)
    }

    private fun mobileViews(container: ViewGroup): List<View> {
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.javaClass.name.endsWith(MOBILE) && it.width > 0 && it.visibility != View.GONE }
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

    /** 系统排版坐标。如果系统把我们改过的 translation 抄回 layoutTranslationX，仍用上一次的原始位置，避免再往右收一截。 */
    private fun systemX(view: View): Float {
        val reported = layoutX(view)
        val saved = anchors[view]
        if (reported == null) return saved?.raw ?: view.translationX
        if (saved != null &&
            kotlin.math.abs(saved.applied - saved.raw) > 1.5f &&
            kotlin.math.abs(reported - saved.applied) <= 1.5f
        ) {
            return saved.raw
        }
        return reported
    }

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

    private fun suppressed(view: View, state: Any? = iconState(view)): Boolean {
        if (state != null) {
            if (readInt(state, "inIslandState") == 10) return true
            if (readInt(state, "visibleState") == 2) return true
            val alpha = readFloat(state, "alpha")
            if (alpha != null && alpha <= 0f) return true
        }
        return view.alpha <= 0.01f
    }

    private fun showIcon(view: View) {
        val method = method(view.javaClass, "setVisibleState", 2) ?: return
        try {
            method.invoke(view, 0, false)
        } catch (_: Throwable) {
        }
    }

    /** 岛按这张卡自己的并排坐标藏过内部图标时，把它们收回来。 */
    private fun clearIslandClip(view: View) {
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (islandState(child) == 10 || blurred(child)) {
                setIslandState(child, 20)
                cancelChildAnimator(child)
                if (child.visibility != View.GONE) child.visibility = View.VISIBLE
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationX = 0f
                clearBlur(child)
            }
            clearIslandClip(child)
        }
    }

    private fun islandState(view: View): Int? {
        val method = method(view.javaClass, "getIslandState", 0) ?: return null
        return try {
            method.invoke(view) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearBlur(view: View) {
        val method = method(view.javaClass, "setBlurRadius", 1) ?: return
        try {
            method.invoke(view, 0)
        } catch (_: Throwable) {
        }
    }

    private fun blurred(view: View): Boolean {
        val method = method(view.javaClass, "getBlurRadius", 0) ?: return false
        return try {
            (method.invoke(view) as? Int ?: 0) > 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun setIslandState(view: View, value: Int) {
        val method = method(view.javaClass, "setIslandState", 1) ?: return
        try {
            method.invoke(view, value)
        } catch (_: Throwable) {
        }
    }

    private fun cancelChildAnimator(view: View) {
        val state = iconState(view) ?: return
        val cancel = method(state.javaClass, "cancelAnimator", 0) ?: return
        try {
            cancel.invoke(state)
        } catch (_: Throwable) {
        }
    }

    private fun method(type: Class<*>, name: String, count: Int): java.lang.reflect.Method? {
        val key = type.name + "#" + name + "#" + count
        if (methods.containsKey(key)) return methods[key]
        val found = type.methods.firstOrNull { it.name == name && it.parameterTypes.size == count }
        methods[key] = found
        return found
    }

    private fun stopFolme(view: View, tagName: String, propertyName: String) {
        val id = identifier(view, tagName)
        if (id == 0) return
        val info = view.getTag(id) ?: return
        try {
            val folme = findField(info.javaClass, "folme")?.get(info)
            if (folme != null) {
                val stateMethod = method(folme.javaClass, "state", 0) ?: return
                val style = stateMethod.invoke(folme)
                val property = viewProperty(view, propertyName) ?: return
                val cancel = cancelMethod(style.javaClass) ?: return
                if (cancel.parameterTypes[0].isArray) {
                    val component = cancel.parameterTypes[0].componentType ?: return
                    val args = java.lang.reflect.Array.newInstance(component, 1)
                    java.lang.reflect.Array.set(args, 0, property)
                    cancel.invoke(style, args)
                } else {
                    cancel.invoke(style, property)
                }
            }
        } catch (_: Throwable) {
        }
        view.setTag(id, null)
    }

    private fun viewProperty(view: View, name: String): Any? {
        properties[name]?.let { return it }
        val value = try {
            Class.forName(
                "miuix.animation.property.ViewProperty",
                false,
                view.javaClass.classLoader,
            ).getField(name).get(null)
        } catch (_: Throwable) {
            null
        } ?: return null
        properties[name] = value
        return value
    }

    private fun cancelMethod(type: Class<*>): java.lang.reflect.Method? {
        return method(type, "cancel", 1)
    }

    private fun identifier(view: View, name: String): Int {
        resIds[name]?.let { return it }
        var id = 0
        val resources = view.resources
        for (pkg in listOf(view.context.packageName, "com.android.systemui")) {
            id = resources.getIdentifier(name, "id", pkg)
            if (id != 0) break
        }
        resIds[name] = id
        return id
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
        }
    }

    private fun writeFloat(target: Any, name: String, value: Float) {
        val field = findField(target.javaClass, name) ?: return
        try {
            field.setFloat(target, value)
        } catch (_: Throwable) {
        }
    }

    private fun writeInt(target: Any, name: String, value: Int) {
        val field = findField(target.javaClass, name) ?: return
        try {
            field.setInt(target, value)
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

    private data class Slot(val raw: Float, val applied: Float)

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
        val speed = firstSpeed(container)
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

    private fun firstSpeed(container: ViewGroup): View? {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child.javaClass.name.endsWith(SPEED) && child.height > 0 && child.height < container.height * 0.92f) {
                return child
            }
        }
        return null
    }

    /**
     * 网速控件比两行字高。字形框又偏紧，直接用会把信号缩得比系统「合并」图标小。
     * 以字形为中心，把高度放到旁边图标那一档。
     */
    private fun iconSlot(view: View): Band {
        val cached = inkCached
        if (inkView === view && inkTop == view.top && inkHeight == view.height && cached != null) {
            return cached
        }
        val ink = inkBand(view)
        val span = maxOf(ink.span, view.height * 0.66f)
        val band = Band(ink.mid - span / 2f, ink.mid + span / 2f)
        inkView = view
        inkTop = view.top
        inkHeight = view.height
        inkCached = band
        return band
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
        subIds[view]?.let { return it }
        if (!subIdLookup) {
            subIdLookup = true
            subIdMethod = view.javaClass.methods.firstOrNull {
                it.name == "getSubId" && it.parameterTypes.isEmpty()
            }
        }
        val id = try {
            subIdMethod?.invoke(view) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }
        subIds[view] = id
        return id
    }

    private data class Band(val top: Float, val bottom: Float) {
        val span: Float get() = bottom - top
        val mid: Float get() = (top + bottom) / 2f
    }
}
