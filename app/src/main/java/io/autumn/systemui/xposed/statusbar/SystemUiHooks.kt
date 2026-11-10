package io.autumn.systemui.xposed.statusbar

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.autumn.systemui.ModuleConfig
import io.autumn.systemui.xposed.ModuleMain
import io.autumn.systemui.xposed.installApplicationHook
import io.autumn.systemui.xposed.util.DexClassIndex
import io.autumn.systemui.xposed.util.VolteNames
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 始终隐藏状态栏 VoLTE / VoNR，下拉通知栏或控制中心也不显示。
 * 类名来自 SystemUI dex，具体机型的反编译结果放在 decompiled/。
 */
object SystemUiHooks {
    private const val TAG = ModuleMain.TAG
    private lateinit var module: XposedModule
    private val hooked = ConcurrentHashMap.newKeySet<Executable>()
    private val seenClasses = ConcurrentHashMap.newKeySet<String>()
    private val pending = ArrayDeque<Class<*>>()
    private val draining = AtomicBoolean(false)
    private val volteMethods = HashMap<String, MutableSet<String>>()
    private val indexedClasses = LinkedHashSet<String>()
    private val notes = ArrayDeque<String>()
    private val resourceNames = HashMap<Int, String?>()
    private var frameworkReady = false
    private var reportScheduled = false
    private var appContext: Application? = null
    private val main = Handler(Looper.getMainLooper())

    private val booleanExpand = setOf(
        "setExpanded",
        "setQsExpanded",
        "setPanelExpanded",
        "setControlCenterExpanded",
        "onExpandChange",
        "setExpandedState",
    )
    private val floatExpand = setOf(
        "setExpandedFraction",
        "setExpandFraction",
        "setExpandProgress",
    )

    fun install(xposed: XposedModule, loader: ClassLoader, info: ApplicationInfo) {
        module = xposed
        if (!frameworkReady) {
            frameworkReady = true
            VolteVisibility.installPrefs(xposed)
            DualSignalLayout.installPrefs(xposed)
            installFrameworkHooks()
            xposed.installApplicationHook()
        }
        indexApk(info)
        hookLoadClass()
        loadIndexedClasses(loader)
        installHeaderExpansion(loader)
        installDualSignal(loader)
        scheduleReport()
    }

    private fun installDualSignal(loader: ClassLoader) {
        try {
            val container = Class.forName(
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer",
                false,
                loader,
            )
            val onLayout = container.getDeclaredMethod(
                "onLayout",
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            )
            hook(onLayout) { chain ->
                chain.proceed()
                val view = chain.thisObject as? android.view.ViewGroup ?: return@hook null
                DualSignalLayout.apply(view)
                null
            }
            note("dual signal stack hooked")
        } catch (error: Throwable) {
            note("dual signal: ${error.javaClass.simpleName}")
        }
    }

    private fun installHeaderExpansion(loader: ClassLoader) {
        try {
            val property = Class.forName("com.miui.systemui.util.FloatFlowProperty", false, loader)
            val setValue = property.getDeclaredMethod("setValue", Object::class.java, java.lang.Float.TYPE)
            hook(setValue) { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject
                if (owner?.javaClass?.name == "com.android.systemui.shade.NotificationPanelExpansionAnimator") {
                    val value = (chain.getArg(1) as? Number)?.toFloat()
                    if (value != null) PanelState.onHeaderExpansion(value)
                }
                result
            }
            note("header expansion hooked")
        } catch (error: Throwable) {
            note("header expansion: ${error.javaClass.simpleName}")
        }
    }

    fun attachApplication(app: Application) {
        appContext = app
        scheduleReport()
    }

    private fun installFrameworkHooks() {
        hook(View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType), XposedInterface.PRIORITY_LOWEST) { chain ->
            val view = chain.thisObject as? View
            if (view == null || !VolteVisibility.isTracked(view)) {
                chain.proceed()
            } else {
                val incoming = (chain.getArg(0) as Number).toInt()
                val target = VolteVisibility.onSystemVisibility(view, incoming)
                if (target == incoming) chain.proceed() else chain.proceed(arrayOf<Any>(target))
                VolteVisibility.sync(view)
            }
        }
        hook(View::class.java.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType), XposedInterface.PRIORITY_LOWEST) { chain ->
            val view = chain.thisObject as? View
            val incoming = (chain.getArg(0) as? Number)?.toFloat() ?: return@hook chain.proceed()
            if (view == null || !VolteVisibility.isTracked(view)) {
                chain.proceed()
            } else {
                val target = VolteVisibility.onSystemAlpha(view, incoming)
                if (target == incoming) chain.proceed() else chain.proceed(arrayOf<Any>(target))
            }
        }
        hook(ImageView::class.java.getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? ImageView
            val resId = (chain.getArg(0) as? Number)?.toInt() ?: 0
            if (view != null && resId != 0) {
                val name = resourceName(view, resId)
                if (name != null && VolteNames.isVolteResource(name)) {
                    VolteVisibility.track(view, TrackReason.RESOURCE)
                } else if (VolteVisibility.reason(view) == TrackReason.RESOURCE) {
                    VolteVisibility.untrack(view)
                }
            }
            result
        }
        TextView::class.java.declaredMethods
            .filter { method ->
                method.name == "setText" &&
                    method.parameterTypes.firstOrNull()?.let { CharSequence::class.java.isAssignableFrom(it) } == true
            }
            .forEach { method ->
                hook(method) { chain ->
                    val result = chain.proceed()
                    val text = chain.getArg(0)?.toString().orEmpty()
                    val view = chain.thisObject as? TextView
                    if (view != null && VolteNames.isVolteText(text)) {
                        VolteVisibility.track(view, TrackReason.TEXT)
                    }
                    result
                }
            }
        hook(View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? View
            val text = chain.getArg(0)?.toString().orEmpty()
            if (view != null && VolteNames.isVolteText(text)) {
                VolteVisibility.track(view, TrackReason.TEXT)
            }
            result
        }
    }

    private fun hookLoadClass() {
        val signatures = listOf(
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java),
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType),
        )
        signatures.forEach { method ->
            hook(method) { chain ->
                val loaded = chain.proceed()
                val name = chain.getArg(0) as? String
                if (loaded is Class<*> && name != null && interesting(name)) enqueue(loaded)
                loaded
            }
        }
    }

    private fun indexApk(info: ApplicationInfo) {
        val paths = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        paths.forEach { path ->
            try {
                val index = DexClassIndex.readApk(path)
                index.volteMethods.forEach { ref ->
                    volteMethods.getOrPut(ref.className) { LinkedHashSet() }.add(ref.methodName)
                }
                indexedClasses += index.panelClasses
                indexedClasses += index.iconClasses
                indexedClasses += volteMethods.keys
                note("dex ${path.substringAfterLast('/')}: panel=${index.panelClasses.size} icon=${index.iconClasses.size} volteMethods=${index.volteMethods.size}")
            } catch (error: Throwable) {
                note("dex fail $path: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun loadIndexedClasses(loader: ClassLoader) {
        val names = indexedClasses.filter { name ->
            '$' !in name || name.lowercase().contains("volte") || name.lowercase().contains("vonr")
        }.take(240)
        names.forEach { name -> loadQuietly(loader, name) }
    }

    private fun loadQuietly(loader: ClassLoader, name: String) {
        try {
            val cls = Class.forName(name, false, loader)
            enqueue(cls)
        } catch (_: Throwable) {
        }
    }

    private fun interesting(name: String): Boolean {
        if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("kotlin")) {
            return false
        }
        val lower = name.lowercase()
        return DexClassIndex.isPanelClass(lower) || DexClassIndex.isIconClass(lower) || name in volteMethods
    }

    private fun enqueue(cls: Class<*>) {
        synchronized(pending) { pending.add(cls) }
        drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val cls = synchronized(pending) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: break
                if (!seenClasses.add(cls.name)) continue
                try {
                    installClass(cls)
                } catch (error: Throwable) {
                    note("install ${cls.name}: ${error.javaClass.simpleName}")
                }
            }
        } finally {
            draining.set(false)
        }
        val more = synchronized(pending) { pending.isNotEmpty() }
        if (more) drain()
        scheduleReport()
    }

    private fun installClass(cls: Class<*>) {
        val lower = cls.name.lowercase()
        if (DexClassIndex.isPanelClass(lower)) installPanel(cls)
        if (DexClassIndex.isIconClass(lower)) installIconClass(cls)
        val methodNames = volteMethods[cls.name].orEmpty()
        if (methodNames.isNotEmpty()) installVolteMethods(cls, methodNames)
    }

    private fun installPanel(cls: Class<*>) {
        val controlCenter = cls.name.lowercase().let { it.contains("controlcenter") || it.contains("controlpanel") }
        val hookedNames = ArrayList<String>()
        cls.declaredMethods.forEach { method ->
            if (Modifier.isAbstract(method.modifiers) || method.parameterCount == 0) return@forEach
            val reader = expansionReader(method) ?: return@forEach
            hook(method) { chain ->
                val result = chain.proceed()
                val fraction = reader(chain.getArgs())
                if (fraction != null) PanelState.update(controlCenter, fraction)
                result
            }
            hookedNames += method.name
        }
        if (hookedNames.isNotEmpty()) {
            note("panel ${cls.name} [${hookedNames.distinct().joinToString()}]")
        }
    }

    private fun expansionReader(method: Method): ((List<Any?>) -> Float?)? {
        val types = method.parameterTypes
        val first = types.firstOrNull() ?: return null
        if (method.name in booleanExpand && isBoolean(first)) {
            return { args -> if (args.firstOrNull() as? Boolean == true) 1f else 0f }
        }
        if (method.name in floatExpand && isFloat(first)) {
            return { args -> (args.firstOrNull() as? Number)?.toFloat() }
        }
        if (method.name == "onPanelExpansionChanged" && isFloat(first)) {
            return { args -> (args.firstOrNull() as? Number)?.toFloat() }
        }
        return null
    }

    private fun installIconClass(cls: Class<*>) {
        val lower = cls.name.lowercase()
        val volteType = (lower.contains("volte") || lower.contains("vonr")) && View::class.java.isAssignableFrom(cls)
        val slotType = lower.contains("statusbariconview")
        if (!volteType && !slotType) return
        cls.declaredConstructors.forEach { constructor ->
            hook(constructor) { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View
                if (view != null && volteType) {
                    VolteVisibility.track(view, TrackReason.CLASS)
                }
                if (view != null && slotType) {
                    val slot = chain.getArgs().firstOrNull { it is String } as? String
                    if (slot != null && VolteNames.isVolteSlot(slot)) {
                        VolteVisibility.track(view, TrackReason.SLOT)
                    }
                }
                result
            }
        }
        note("icon ${cls.name}")
    }

    private fun installVolteMethods(cls: Class<*>, names: Set<String>) {
        cls.declaredMethods
            .filter { it.name in names && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                val params = method.parameterTypes
                val returnsBoolean = isBoolean(method.returnType)
                when {
                    params.isEmpty() && returnsBoolean -> hook(method, XposedInterface.PRIORITY_LOWEST) { chain ->
                        val shown = chain.proceed() as? Boolean == true
                        if (shown && VolteVisibility.hideCollapsed) false else shown
                    }
                    params.size == 1 && isBoolean(params[0]) -> hook(method, XposedInterface.PRIORITY_LOWEST) { chain ->
                        val show = chain.getArg(0) as? Boolean == true
                        chain.thisObject?.let { VolteVisibility.rememberSetter(it, method, show) }
                        val hide = VolteVisibility.hideCollapsed
                        if (hide && show) chain.proceed(arrayOf<Any>(false)) else chain.proceed()
                    }
                    else -> note("skip ${cls.name}#${method.name}")
                }
            }
        note("volte ${cls.name} [${names.joinToString()}]")
    }

    private fun hook(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        if (!hooked.add(executable)) return
        try {
            module.hook(executable).setPriority(priority).intercept { chain ->
                try {
                    block(chain)
                } catch (error: Throwable) {
                    module.log(Log.ERROR, TAG, "hook ${executable.name}", error)
                    chain.proceed()
                }
            }
        } catch (error: Throwable) {
            hooked.remove(executable)
            note("hook fail ${executable.name}: ${error.javaClass.simpleName}")
        }
    }

    private fun resourceName(view: View, resId: Int): String? {
        resourceNames[resId]?.let { return it }
        val name = try {
            view.resources.getResourceEntryName(resId)
        } catch (_: Throwable) {
            null
        }
        if (resourceNames.size > 512) resourceNames.clear()
        resourceNames[resId] = name
        return name
    }

    private fun scheduleReport() {
        if (reportScheduled) return
        reportScheduled = true
        main.postDelayed({
            reportScheduled = false
            publishReport()
        }, 900)
    }

    private fun publishReport() {
        val lines = synchronized(notes) { notes.toList() }
        val text = buildString {
            appendLine("AutumnSystemUI")
            appendLine("hideCollapsed=${VolteVisibility.hideCollapsed}")
            lines.take(120).forEach { appendLine(it) }
        }
        try {
            module.log(Log.INFO, TAG, text)
        } catch (_: Throwable) {
        }
        val context = appContext ?: return
        try {
            context.contentResolver.call(
                Uri.parse("content://${ModuleConfig.REPORT_AUTHORITY}"),
                "report",
                null,
                Bundle().apply { putString("text", text) },
            )
        } catch (error: Throwable) {
            module.log(Log.WARN, TAG, "report provider failed", error)
        }
    }

    private fun note(line: String) {
        synchronized(notes) {
            notes.addLast(line)
            while (notes.size > 160) notes.removeFirst()
        }
    }

    private fun isBoolean(type: Class<*>): Boolean {
        return type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java
    }

    private fun isFloat(type: Class<*>): Boolean {
        return type == java.lang.Float.TYPE || type == java.lang.Float::class.java
    }
}
