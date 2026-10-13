package io.github.doesthings.meshwise.viewer

import io.github.doesthings.meshwise.data.LoadStage
import io.github.doesthings.meshwise.engine.MeasureFeature
import io.github.doesthings.meshwise.engine.PickHit
import io.github.doesthings.meshwise.engine.ThicknessResult
import io.github.doesthings.meshwise.engine.Vec3

enum class Screen { Home, Viewer, Settings, Library, About, Toolbar }

enum class Tool { None, Lighting, Material, Section, Explode, Parts, Save, Color, Weight, Measure, Analyze, Info }

data class LoadingState(val name: String, val stage: LoadStage, val progress: Float)

data class SectionState(
    val axis: Int = 2,
    val value: Float = 0f,
    val flip: Boolean = false,
    val hatch: Boolean = true,
    val showPlane: Boolean = true,
)

enum class MeasureMode(val points: Int) { Smart(2), Distance(2), Angle(3), Circle(3) }

/** A measurement the user chose to keep on screen. */
data class KeptMeasure(val result: MeasureResult, val features: List<MeasureFeature>)

data class MeasureState(
    val mode: MeasureMode = MeasureMode.Smart,
    val points: List<PickHit> = emptyList(),
    val features: List<MeasureFeature> = emptyList(),  // Smart mode, at most two
    val kept: List<KeptMeasure> = emptyList(),
    val history: List<Pair<MeasureMode, List<PickHit>>> = emptyList(),
) {
    val complete: Boolean get() = if (mode == MeasureMode.Smart) features.size >= 2 else points.size >= mode.points
    val empty: Boolean get() = points.isEmpty() && features.isEmpty()
    val smartResult: MeasureResult? get() = SmartMeasure.of(features)
}

/** The precision cursor while the finger is held down in the measure tool. */
data class MeasureHover(val x: Float, val y: Float, val feature: MeasureFeature?)

enum class AnalyzeTab { Thickness, Overhang, MeshCheck }

data class ThicknessUi(
    val computing: Boolean = false,
    val progress: Float = 0f,
    val result: ThicknessResult? = null,
    val threshold: Boolean = false,
    val thresholdValue: Float = 1f,
)

data class OverhangUi(val angle: Float = 45f, val overhangArea: Double = 0.0, val totalArea: Double = 0.0)

/** One manual explode move for undo. */
data class OffsetEdit(val part: Int, val before: Vec3)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Share(val uri: android.net.Uri, val mime: String) : UiEvent
}
