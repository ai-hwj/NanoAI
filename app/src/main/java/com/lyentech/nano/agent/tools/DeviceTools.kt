package com.lyentech.nano.agent.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.tools.FunctionTool
import kotlin.jvm.java

class DeviceTools(context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    @Tool(
        name = "get_battery_level",
        description = "Returns this device's current battery charge, as a percentage.",
    )
    fun getBatteryLevel(): Map<String, Any> {
        // A device that cannot report capacity returns Integer.MIN_VALUE; don't hand the model that.
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (percent in 0..100) mapOf("battery_percent" to percent)
        else mapOf(FunctionTool.ERROR_KEY to "This device does not report its battery level.")
    }

    @Tool(
        name = "get_device_info",
        description = "Returns one hardware or software property of this device.",
    )
    fun getDeviceInfo(
        @Param("Which property to read: 'model', 'manufacturer' or 'android_version'.") property: String
    ): Map<String, String> {
        val value =
            when (property.lowercase()) {
                "model" -> Build.MODEL
                "manufacturer" -> Build.MANUFACTURER
                "android_version" -> Build.VERSION.RELEASE
                else ->
                    return mapOf(
                        FunctionTool.ERROR_KEY to
                                "Unknown property. Use model, manufacturer or android_version."
                    )
            }
        return mapOf("property" to property, "value" to value)
    }
}