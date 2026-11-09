package io.autumn.systemui.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.autumn.systemui.ModuleConfig
import io.github.libxposed.service.XposedService

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun installedScopeCandidates(context: Context): List<String> {
    return ModuleConfig.scopeCandidates.filter { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

fun requestSystemUiScope(context: Context, service: XposedService) {
    val packages = installedScopeCandidates(context).ifEmpty { listOf(ModuleConfig.SYSTEM_UI) }
    service.requestScope(packages, object : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) {
            Handler(Looper.getMainLooper()).post {
                context.toast(if (approved.isEmpty()) "作用域没有变化" else "已加入作用域")
            }
        }

        override fun onScopeRequestFailed(message: String) {
            Handler(Looper.getMainLooper()).post {
                context.toast(message.ifBlank { "申请作用域失败" })
            }
        }
    })
}

fun restartSystemUi(context: Context) {
    Thread {
        val commands = listOf(
            arrayOf("su", "-c", "killall com.android.systemui"),
            arrayOf("su", "0", "killall", "com.android.systemui"),
        )
        var message = "没有 root，无法重启系统界面"
        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                if (process.waitFor() == 0) {
                    message = "正在重启系统界面"
                    break
                }
            } catch (_: Exception) {
            }
        }
        Handler(Looper.getMainLooper()).post { context.toast(message) }
    }.start()
}

fun hotReloadSystemUi(context: Context, service: XposedService) {
    val targets = try {
        service.runningTargets
    } catch (error: Throwable) {
        context.toast(error.message ?: "读取作用域进程失败")
        return
    }
    val systemUi = targets.filter { it.processName.contains("systemui") }
    if (systemUi.isEmpty()) {
        context.toast("系统界面里还没有加载本模块")
        return
    }
    if (service.apiVersion < XposedService.API_102) {
        context.toast("当前框架不支持热重载")
        return
    }
    systemUi.forEach { target ->
        service.hotReloadModule(target, null) { process, result ->
            Handler(Looper.getMainLooper()).post {
                context.toast("已重载 ${process.processName}: $result")
            }
        }
    }
}
