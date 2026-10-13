package io.github.doesthings.meshwise.ui.viewer

import android.content.res.Resources
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.MeasureResult
import io.github.doesthings.meshwise.viewer.MeasureValue
import io.github.doesthings.meshwise.viewer.ValueKind
import java.util.Locale

/** Text for a measured value in the user's units and precision. */
fun formatMeasure(v: MeasureValue, units: Units, res: Resources): String = when (v.kind) {
    ValueKind.Length -> Fmt.length(v.value, units)
    ValueKind.Angle -> "%.${Fmt.decimals.coerceIn(1, 3)}f°".format(Locale.US, v.value)
    ValueKind.Area -> Fmt.area(v.value, units)
    ValueKind.Coordinate -> Fmt.number(v.value * units.factor, Fmt.decimals)
    ValueKind.Position -> v.position?.let { p ->
        listOf(p.x, p.y, p.z).joinToString(", ") { Fmt.number(it * units.factor, Fmt.decimals) }
    } ?: ""
    ValueKind.Text -> if (v.text != 0) res.getString(v.text) else ""
}

/** One line per value, for copying: "Hole: Diameter Ø 8.00 mm, …". */
fun measureSummary(r: MeasureResult, units: Units, res: Resources): String =
    res.getString(r.title) + ": " + r.values.joinToString(", ") { res.getString(it.label) + " " + formatMeasure(it, units, res) }
