package io.autumn.systemui.xposed.statusbar

import android.os.Handler
import android.os.Looper
import android.view.View
import io.autumn.systemui.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

enum class TrackReason {
    SLOT,
    CLASS,
    RESOURCE,
    TEXT,
}

object PanelState {
    private const val EDGE = 0.001f

    private var shade = 0f
    private var control = 0f
    private var followControl = false
    private var headerOwnsShade = false

    /** 正在动的那块面板的展开进度。图标透明度跟着它，从完全下拉的 1 收到 0。 */
    @Volatile
    var fraction: Float = 0f
        private set

    val expanded: Boolean
        get() = fraction > EDGE

    fun update(controlCenter: Boolean, value: Float) {
        if (!controlCenter && headerOwnsShade) return
        publish(controlCenter, value)
    }

    /** 小米通知栏收起动画的逐帧进度，0 到 1。 */
    fun onHeaderExpansion(value: Float) {
        headerOwnsShade = true
        publish(controlCenter = false, value)
    }

    private fun publish(controlCenter: Boolean, value: Float) {
        val next = value.coerceIn(0f, 1f)
        val shown = synchronized(this) {
            if (controlCenter) {
                if (next != control) followControl = true
                control = next
            } else {
                if (next != shade) followControl = false
                shade = next
            }
            if (followControl) control else shade
        }
        if (shown == fraction) return
        val crossed = (fraction <= EDGE) != (shown <= EDGE)
        fraction = shown
        VolteVisibility.refresh(setters = crossed)
    }
}

object VolteVisibility {
    private val desired = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val reasons = Collections.synchronizedMap(WeakHashMap<View, TrackReason>())
    private val setters = ArrayList<SetterCall>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var hideCollapsed: Boolean = true

    private val applying = ThreadLocal.withInitial { 0 }

    fun installPrefs(module: XposedModule) {
        try {
            val prefs = module.getRemotePreferences(ModuleConfig.PREF_GROUP)
            hideCollapsed = prefs.getBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, true)
            prefs.registerOnSharedPreferenceChangeListener { shared, key ->
                if (key == null || key == ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED) {
                    hideCollapsed = shared.getBoolean(ModuleConfig.KEY_HIDE_VOLTE_COLLAPSED, true)
                    main.post { refresh() }
                }
            }
        } catch (_: Throwable) {
            hideCollapsed = true
        }
    }

    fun track(view: View, reason: TrackReason) {
        synchronized(desired) {
            if (!desired.containsKey(view)) desired[view] = view.visibility
            reasons[view] = reason
        }
        enforce(view)
        view.post {
            if (reason == TrackReason.TEXT && !looksLikeStatusIcon(view)) {
                untrack(view)
            } else if (isHuge(view)) {
                untrack(view)
            }
        }
    }

    fun untrack(view: View) {
        val previous = synchronized(desired) {
            reasons.remove(view)
            desired.remove(view)
        } ?: return
        runApplying {
            if (view.alpha != 1f) view.alpha = 1f
            if (view.visibility != previous) view.visibility = previous
        }
    }

    fun isTracked(view: View): Boolean = synchronized(desired) { desired.containsKey(view) }

    fun reason(view: View): TrackReason? = synchronized(reasons) { reasons[view] }

    fun onSystemVisibility(view: View, incoming: Int): Int {
        if ((applying.get() ?: 0) > 0) return incoming
        synchronized(desired) {
            if (!desired.containsKey(view)) return incoming
            desired[view] = incoming
        }
        return effective(view, incoming)
    }

    fun onSystemAlpha(view: View, incoming: Float): Float {
        if ((applying.get() ?: 0) > 0 || !isTracked(view)) return incoming
        return visualAlpha(view, incoming)
    }

    fun sync(view: View) {
        if ((applying.get() ?: 0) > 0 || !isTracked(view)) return
        enforce(view)
    }

    fun rememberSetter(target: Any, method: Method, show: Boolean) {
        synchronized(setters) {
            setters.removeAll { it.method == method && it.target.get() == null }
            val existing = setters.indexOfFirst { it.method == method && it.target.get() === target }
            val call = SetterCall(WeakReference(target), method, show)
            if (existing >= 0) setters[existing] = call else setters += call
        }
    }

    fun refresh(setters: Boolean = true) {
        val views = synchronized(desired) { desired.keys.toList() }
        views.forEach { view ->
            if (Looper.myLooper() == Looper.getMainLooper()) enforce(view) else view.post { enforce(view) }
        }
        if (!setters) return
        val calls = synchronized(this.setters) { this.setters.toList() }
        calls.forEach { call ->
            val target = call.target.get() ?: return@forEach
            try {
                call.method.isAccessible = true
                call.method.invoke(target, call.show)
            } catch (_: Throwable) {
            }
        }
    }

    private fun enforce(view: View) {
        val wanted = synchronized(desired) { desired[view] } ?: return
        val visibility = effective(view, wanted)
        val alpha = if (visibility == View.GONE) 0f else visualAlpha(view, 1f)
        runApplying {
            if (view.visibility != visibility) view.visibility = visibility
            if (view.alpha != alpha) view.alpha = alpha
        }
    }

    private fun effective(view: View, wanted: Int): Int {
        return if (hideCollapsed) View.GONE else wanted
    }

    private fun visualAlpha(view: View, incoming: Float): Float {
        return if (hideCollapsed) 0f else incoming
    }

    private fun looksLikeStatusIcon(view: View): Boolean {
        if (isHuge(view)) return false
        var parent = view.parent
        var inNotification = false
        var inStatus = false
        while (parent is View) {
            val name = parent.javaClass.name.lowercase()
            if (name.contains("notificationrow") || name.contains("notificationcontent") || name.contains("hybridnotification")) {
                inNotification = true
            }
            if (name.contains("statusbar") ||
                name.contains("systemicon") ||
                name.contains("mobileicon") ||
                name.contains("signal") ||
                name.contains("controlcenter")
            ) {
                inStatus = true
            }
            parent = parent.parent
        }
        return inStatus || !inNotification
    }

    private fun isHuge(view: View): Boolean {
        if (view.width == 0 || view.height == 0) return false
        val max = (view.resources.displayMetrics.density * 48f).toInt()
        return view.width > max || view.height > max
    }

    private inline fun runApplying(block: () -> Unit) {
        val depth = applying.get() ?: 0
        applying.set(depth + 1)
        try {
            block()
        } finally {
            applying.set(depth)
        }
    }

    private class SetterCall(
        val target: WeakReference<Any>,
        val method: Method,
        val show: Boolean,
    )
}
