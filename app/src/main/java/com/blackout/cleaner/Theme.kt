package com.blackout.cleaner

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Night palette, same look as the other Blackout apps. The four accent colors can be changed in Settings. */
object C {
    val bg = Color(0xFF0B0D12)
    val surface = Color(0xFF151922)
    val bar = Color(0xFF11141B)
    val line = Color(0xFF1E2330)
    val text = Color(0xFFE8EAF0)
    val muted = Color(0xFF9AA3B5)
    val red = Color(0xFFFCA5A5)

    val defaults = mapOf(
        "teal" to Color(0xFF2DD4BF),
        "amber" to Color(0xFFFBBF24),
        "blue" to Color(0xFF60A5FA),
        "violet" to Color(0xFFA78BFA)
    )

    var teal by mutableStateOf(defaults.getValue("teal")); private set
    var amber by mutableStateOf(defaults.getValue("amber")); private set
    var blue by mutableStateOf(defaults.getValue("blue")); private set
    var violet by mutableStateOf(defaults.getValue("violet")); private set

    fun get(key: String): Color = when (key) {
        "teal" -> teal
        "amber" -> amber
        "blue" -> blue
        else -> violet
    }

    private fun apply(key: String, c: Color) {
        when (key) {
            "teal" -> teal = c
            "amber" -> amber = c
            "blue" -> blue = c
            else -> violet = c
        }
    }

    fun load(p: SharedPreferences) {
        defaults.keys.forEach { k -> if (p.contains("c_$k")) apply(k, Color(p.getInt("c_$k", 0))) }
    }

    fun set(key: String, c: Color, p: SharedPreferences) {
        apply(key, c)
        p.edit().putInt("c_$key", c.toArgb()).apply()
    }

    fun reset(p: SharedPreferences) {
        defaults.forEach { (k, v) -> apply(k, v) }
        val e = p.edit()
        defaults.keys.forEach { e.remove("c_$it") }
        e.apply()
    }

    /** Readable text color to put on top of a given accent color. */
    fun on(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF0B0D12) else Color(0xFFFFFFFF)
}
