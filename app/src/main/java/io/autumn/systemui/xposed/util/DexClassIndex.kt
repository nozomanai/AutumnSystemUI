package io.autumn.systemui.xposed.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class DexMethodRef(
    val className: String,
    val methodName: String,
)

data class DexIndex(
    val panelClasses: List<String>,
    val iconClasses: List<String>,
    val volteMethods: List<DexMethodRef>,
)

object DexClassIndex {
    fun readApk(path: String): DexIndex {
        val panels = LinkedHashSet<String>()
        val icons = LinkedHashSet<String>()
        val methods = ArrayList<DexMethodRef>()
        ZipFile(path).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
                val bytes = zip.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toByteArray()
                }
                parse(bytes, panels, icons, methods)
            }
        }
        return DexIndex(panels.toList(), icons.toList(), methods)
    }

    private fun parse(
        dex: ByteArray,
        panels: MutableSet<String>,
        icons: MutableSet<String>,
        methods: MutableList<DexMethodRef>,
    ) {
        if (dex.size < 0x70) return
        val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0x28) != 0x12345678) return
        val stringIdsSize = buffer.getInt(0x38)
        val stringIdsOff = buffer.getInt(0x3C)
        val typeIdsSize = buffer.getInt(0x40)
        val typeIdsOff = buffer.getInt(0x44)
        val methodIdsSize = buffer.getInt(0x58)
        val methodIdsOff = buffer.getInt(0x5C)
        if (stringIdsSize <= 0 || typeIdsSize <= 0) return
        if (stringIdsOff < 0 || typeIdsOff < 0 || methodIdsOff < 0) return

        val stringOffsets = IntArray(stringIdsSize)
        for (index in 0 until stringIdsSize) {
            val pos = stringIdsOff + index * 4
            if (pos + 4 > dex.size) return
            stringOffsets[index] = buffer.getInt(pos)
        }
        val typeNames = arrayOfNulls<String>(typeIdsSize)
        for (index in 0 until typeIdsSize) {
            val pos = typeIdsOff + index * 4
            if (pos + 4 > dex.size) return
            val stringIndex = buffer.getInt(pos)
            val descriptor = readString(dex, stringOffsets, stringIndex) ?: continue
            val name = descriptorToClass(descriptor) ?: continue
            typeNames[index] = name
            val lower = name.lowercase()
            if (isPanelClass(lower)) panels += name
            if (isIconClass(lower)) icons += name
        }
        if (methodIdsSize <= 0) return
        for (index in 0 until methodIdsSize) {
            val pos = methodIdsOff + index * 8
            if (pos + 8 > dex.size) return
            val classIndex = buffer.getShort(pos).toInt() and 0xFFFF
            val nameIndex = buffer.getInt(pos + 4)
            if (classIndex !in typeNames.indices) continue
            val className = typeNames[classIndex] ?: continue
            val methodName = readString(dex, stringOffsets, nameIndex) ?: continue
            if (VolteNames.isVolteMethod(methodName)) {
                methods += DexMethodRef(className, methodName)
            }
        }
    }

    private fun readString(dex: ByteArray, offsets: IntArray, index: Int): String? {
        if (index !in offsets.indices) return null
        val start = offsets[index]
        if (start < 0 || start >= dex.size) return null
        var cursor = start
        var shift = 0
        var value = 0
        while (cursor < dex.size && shift <= 28) {
            val bite = dex[cursor].toInt() and 0xFF
            cursor++
            value = value or ((bite and 0x7F) shl shift)
            if (bite and 0x80 == 0) break
            shift += 7
        }
        val end = dex.indexOfZero(cursor)
        if (end < 0) return null
        return String(dex, cursor, end - cursor, Charsets.UTF_8)
    }

    private fun ByteArray.indexOfZero(start: Int): Int {
        var index = start
        while (index < size) {
            if (this[index].toInt() == 0) return index
            index++
        }
        return -1
    }

    private fun descriptorToClass(descriptor: String): String? {
        if (descriptor.length < 3 || descriptor[0] != 'L' || !descriptor.endsWith(";")) return null
        return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }

    fun isPanelClass(lowerName: String): Boolean {
        return lowerName.contains("controlcenter") ||
            lowerName.contains("controlpanel") ||
            lowerName.contains("notificationpanelviewcontroller") ||
            lowerName.contains("shadeexpansionstatemanager")
    }

    fun isIconClass(lowerName: String): Boolean {
        return lowerName.endsWith("statusbariconview") ||
            lowerName.contains(".statusbariconview$") ||
            lowerName.contains("volte") ||
            lowerName.contains("vonr")
    }
}

object VolteNames {
    fun isVolteSlot(slot: String): Boolean {
        val name = slot.lowercase()
        if (name.contains("vowifi") || name.contains("vo_wifi") || name.contains("wifi_call")) return false
        return name.contains("volte") || name.contains("vonr") || name.contains("hdvoice") || name.contains("hd_voice")
    }

    fun isVolteResource(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("vowifi") || lower.contains("vo_wifi")) return false
        return lower.contains("volte") || lower.contains("vonr")
    }

    fun isVolteText(text: String): Boolean {
        if (text.length > 16) return false
        val lower = text.lowercase()
        if (lower.contains("vowifi") || lower.contains("wifi")) return false
        return lower.contains("volte") ||
            lower.contains("vonr") ||
            text.contains("高清语音") ||
            text.contains("高清通话")
    }

    fun isVolteMethod(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("vowifi")) return false
        if (!lower.contains("volte") && !lower.contains("vonr")) return false
        if (lower.contains("enable") ||
            lower.contains("capab") ||
            lower.contains("support") ||
            lower.contains("config") ||
            lower.contains("setting") ||
            lower.contains("register") ||
            lower.contains("listener")
        ) {
            return false
        }
        return lower.contains("visible") ||
            lower.contains("show") ||
            lower.contains("icon") ||
            lower.contains("hide") ||
            lower.contains("display") ||
            lower.startsWith("set") ||
            lower.startsWith("update") ||
            lower.startsWith("is") ||
            lower.startsWith("get") ||
            lower.startsWith("has")
    }
}
