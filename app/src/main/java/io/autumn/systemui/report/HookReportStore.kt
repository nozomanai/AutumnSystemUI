package io.autumn.systemui.report

import android.content.Context
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import io.autumn.systemui.ModuleConfig

object HookReportStore {
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun prefs(): SharedPreferences {
        val context = appContext ?: error("HookReportStore is not installed")
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ModuleConfig.REPORT_PREFS, Context.MODE_PRIVATE)
    }

    fun read(): String {
        val context = appContext ?: return ""
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ModuleConfig.REPORT_PREFS, Context.MODE_PRIVATE)
            .getString(ModuleConfig.REPORT_KEY, "")
            .orEmpty()
    }

    fun write(context: Context, text: String) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(ModuleConfig.REPORT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ModuleConfig.REPORT_KEY, text)
            .apply()
    }
}

class HookReportProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "report") return null
        val context = context ?: return null
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (packages.none { it.contains("systemui") }) return null
        HookReportStore.write(context, extras?.getString("text").orEmpty())
        return Bundle.EMPTY
    }

    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) = null

    override fun getType(uri: android.net.Uri): String? = null

    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null

    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: android.net.Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
