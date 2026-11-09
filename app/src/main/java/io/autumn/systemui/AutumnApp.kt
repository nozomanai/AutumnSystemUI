package io.autumn.systemui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.autumn.systemui.report.HookReportStore
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class AutumnApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        HookReportStore.install(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        bridge = service
    }

    override fun onServiceDied(service: XposedService) {
        if (bridge === service) {
            bridge = null
        }
    }

    companion object {
        var bridge by mutableStateOf<XposedService?>(null)
            private set
    }
}
