package io.github.doesthings.meshwise.util

import io.github.doesthings.meshwise.data.Units
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Fmt {
    fun length(mm: Double, units: Units, digits: Int = 2): String {
        val v = mm * units.factor
        return "%.${digits}f %s".format(Locale.US, v, units.suffix)
    }

    fun lengthShort(mm: Double, units: Units): String {
        val v = abs(mm * units.factor)
        val d = when {
            v >= 1000 -> 0
            v >= 100 -> 1
            v >= 10 -> 1
            v >= 1 -> 2
            else -> 3
        }
        return "%.${d}f %s".format(Locale.US, mm * units.factor, units.suffix)
    }

    fun number(v: Double, digits: Int = 2): String = "%.${digits}f".format(Locale.US, v)

    fun area(mm2: Double, units: Units): String = when (units) {
        Units.IN -> "%.2f in²".format(Locale.US, mm2 / 645.16)
        else -> "%.2f cm²".format(Locale.US, mm2 / 100.0)
    }

    fun volume(mm3: Double, units: Units): String = when (units) {
        Units.IN -> "%.2f in³".format(Locale.US, mm3 / 16387.064)
        else -> "%.2f cm³".format(Locale.US, mm3 / 1000.0)
    }

    fun mass(grams: Double): String = when {
        grams >= 1000 -> "%.2f kg".format(Locale.US, grams / 1000)
        grams >= 100 -> "%.0f g".format(Locale.US, grams)
        else -> "%.1f g".format(Locale.US, grams)
    }

    fun bytes(b: Long): String = when {
        b < 0 -> "—"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(Locale.US, b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.2f MB".format(Locale.US, b / 1048576.0)
        else -> "%.2f GB".format(Locale.US, b / 1073741824.0)
    }

    fun count(n: Long): String = when {
        n >= 1_000_000 -> "%.2fM".format(Locale.US, n / 1e6)
        n >= 10_000 -> "%.1fk".format(Locale.US, n / 1e3)
        else -> n.toString()
    }

    fun time(epochMillis: Long): String {
        val now = System.currentTimeMillis()
        val d = Date(epochMillis)
        return if (now - epochMillis < 20 * 3600 * 1000L) DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
        else DateFormat.getDateInstance(DateFormat.MEDIUM).format(d)
    }

    fun degrees(rad: Double) = "%.2f°".format(Locale.US, Math.toDegrees(rad))
}
