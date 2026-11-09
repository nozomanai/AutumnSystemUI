package io.autumn.systemui.xposed

import android.app.Application
import android.util.Log
import io.autumn.systemui.xposed.statusbar.SystemUiHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class ModuleMain : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName}, ${frameworkName()} api $apiVersion")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        if (!packageName.contains("systemui")) return
        try {
            SystemUiHooks.install(this, param.defaultClassLoader, param.applicationInfo)
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "install failed for $packageName", error)
        }
    }

    private fun frameworkName(): String = try {
        getFrameworkName()
    } catch (_: Throwable) {
        "?"
    }

    companion object {
        const val TAG = "AutumnSystemUI"
    }
}

internal fun XposedModule.installApplicationHook() {
    val onCreate = Application::class.java.getDeclaredMethod("onCreate")
    hook(onCreate).intercept { chain ->
        val result = chain.proceed()
        val app = chain.thisObject as? Application
        if (app != null && app.packageName.contains("systemui")) {
            SystemUiHooks.attachApplication(app)
        }
        result
    }
}
