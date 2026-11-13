package com.shortsmaker.proof

import android.content.Context
import android.graphics.Color

data class Settings(
    val clipCount: Int = 9,
    val minSec: Int = 30,
    val maxSec: Int = 58,
    val language: String = "hi",          // hi, en, auto
    val model: String = "base",           // tiny, base, small
    val layout: Layout = Layout.BLUR,
    val captionSizePx: Int = 76,
    val captionColor: Int = Color.rgb(255, 230, 0),
    val hinglish: Boolean = true,         // write Hindi as Roman letters
    val maxVideoHeight: Int = 720,
) {
    /** Keeps every value inside the allowed range and min < max. */
    fun sanitized(): Settings {
        val mx = maxSec.coerceIn(20, 60)
        val mn = minSec.coerceIn(15, 60).coerceAtMost(mx - 3)
        return copy(
            clipCount = clipCount.coerceIn(3, 12), minSec = mn, maxSec = mx,
            language = if (language in listOf("hi", "en", "auto")) language else "hi",
            model = if (model in ModelStore.names) model else "base",
            captionSizePx = captionSizePx.coerceIn(48, 110),
            maxVideoHeight = if (maxVideoHeight >= 1080) 1080 else 720,
        )
    }
}

object SettingsStore {
    private const val FILE = "settings"
    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Settings()
        return Settings(
            clipCount = p.getInt("clipCount", d.clipCount),
            minSec = p.getInt("minSec", d.minSec),
            maxSec = p.getInt("maxSec", d.maxSec),
            language = p.getString("language", d.language) ?: d.language,
            model = p.getString("model", d.model) ?: d.model,
            layout = try { Layout.valueOf(p.getString("layout", d.layout.name)!!) } catch (e: Exception) { d.layout },
            captionSizePx = p.getInt("captionSizePx", d.captionSizePx),
            captionColor = p.getInt("captionColor", d.captionColor),
            hinglish = p.getBoolean("hinglish", d.hinglish),
            maxVideoHeight = p.getInt("maxVideoHeight", d.maxVideoHeight),
        ).sanitized()
    }

    fun save(context: Context, s: Settings) {
        val v = s.sanitized()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("clipCount", v.clipCount).putInt("minSec", v.minSec).putInt("maxSec", v.maxSec)
            .putString("language", v.language).putString("model", v.model).putString("layout", v.layout.name)
            .putInt("captionSizePx", v.captionSizePx).putInt("captionColor", v.captionColor)
            .putBoolean("hinglish", v.hinglish).putInt("maxVideoHeight", v.maxVideoHeight).apply()
    }
}
