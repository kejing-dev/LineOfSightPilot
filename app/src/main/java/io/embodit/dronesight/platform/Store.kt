package io.embodit.dronesight.platform

import android.content.Context
import io.embodit.dronesight.domain.FlightMode
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.StickInvert
import io.embodit.dronesight.domain.Tolerance
import io.embodit.dronesight.domain.WindLevel
import io.embodit.dronesight.ui.HintMode
import io.embodit.dronesight.ui.Lang
import io.embodit.dronesight.ui.Prefs
import io.embodit.dronesight.ui.VStickMode

/** SharedPreferences (design persistence): settings, best scores / passed lessons, waypoint round, welcome seen. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("drone_sight", Context.MODE_PRIVATE)

    var welcomeSeen: Boolean
        get() = p.getBoolean("welcome", false)
        set(v) = p.edit().putBoolean("welcome", v).apply()

    val round: Int
        get() = p.getInt("round", 1)

    fun prefs(): Prefs {
        val d = Prefs()
        fun <T : Enum<T>> en(key: String, def: T, values: Array<T>): T = runCatching { values.first { it.name == p.getString(key, def.name) } }.getOrDefault(def)
        return Prefs(
            volume = p.getInt("volume", d.volume),
            muted = p.getBoolean("muted", d.muted),
            mode = en("mode", d.mode, FlightMode.values()),
            wind = en("wind", d.wind, WindLevel.values()),
            tolerance = en("tolerance", d.tolerance, Tolerance.values()),
            poleDist = p.getFloat("poleDist", d.poleDist),
            hoverH = p.getFloat("hoverH", d.hoverH),
            ceiling = p.getFloat("ceiling", d.ceiling),
            floorOffset = p.getFloat("floorOffset", d.floorOffset),
            seated = p.getBoolean("seated", d.seated),
            invert = StickInvert(p.getBoolean("invLY", false), p.getBoolean("invLX", false), p.getBoolean("invRY", false), p.getBoolean("invRX", false)),
            slow = p.getBoolean("slow", d.slow),
            hints = en("hints", d.hints, HintMode.values()),
            vsticks = en("vsticks", d.vsticks, VStickMode.values()),
            altLine = p.getBoolean("altLine", d.altLine),
            bigTag = p.getBoolean("bigTag", d.bigTag),
            lang = en("lang", d.lang, Lang.values()),
        )
    }

    fun savePrefs(v: Prefs) {
        p.edit()
            .putInt("volume", v.volume).putBoolean("muted", v.muted).putString("mode", v.mode.name).putString("wind", v.wind.name)
            .putString("tolerance", v.tolerance.name).putFloat("poleDist", v.poleDist).putFloat("hoverH", v.hoverH).putFloat("ceiling", v.ceiling)
            .putFloat("floorOffset", v.floorOffset).putBoolean("seated", v.seated)
            .putBoolean("invLY", v.invert.leftY).putBoolean("invLX", v.invert.leftX).putBoolean("invRY", v.invert.rightY).putBoolean("invRX", v.invert.rightX)
            .putBoolean("slow", v.slow).putString("hints", v.hints.name).putString("vsticks", v.vsticks.name).putBoolean("altLine", v.altLine)
            .putBoolean("bigTag", v.bigTag).putString("lang", v.lang.name)
            .apply()
    }

    fun best(): Map<LessonId, Float> = LessonId.values().mapNotNull { id -> if (p.contains("best_$id")) id to p.getFloat("best_$id", 0f) else null }.toMap()

    fun passed(): Set<LessonId> = p.getStringSet("passed", emptySet())!!.mapNotNull { n -> LessonId.values().firstOrNull { it.name == n } }.toSet()

    fun saveBest(best: Map<LessonId, Float>, passed: Set<LessonId>, round: Int) {
        val e = p.edit()
        for (id in LessonId.values()) e.remove("best_$id")
        for ((k, v) in best) e.putFloat("best_$k", v)
        e.putStringSet("passed", passed.map { it.name }.toSet()).putInt("round", round).apply()
    }

    fun clearProgress() {
        val e = p.edit()
        for (id in LessonId.values()) e.remove("best_$id")
        e.remove("passed").remove("round").apply()
    }

    fun clearAll() {
        p.edit().clear().apply()
    }
}
