package io.github.doesthings.meshwise

import io.github.doesthings.meshwise.engine.CameraState
import io.github.doesthings.meshwise.engine.Engine
import io.github.doesthings.meshwise.engine.ExportFormat
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.RenderOptions
import io.github.doesthings.meshwise.engine.StandardView
import io.github.doesthings.meshwise.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI and GL callbacks can still arrive after the engine is released; none of them may crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineReleaseTest {
    @Before
    fun setUp() {
        assumeTrue("JVM build of libmeshwise.so missing", TestSupport.nativeAvailable())
    }

    @Test
    fun everyCallIsSafeAfterRelease() {
        val e = Engine()
        e.release()
        assertEquals(0L, e.handle)
        e.release()

        e.onSurfaceCreated()
        e.onSurfaceChanged(100, 100)
        assertFalse(e.drawFrame())
        assertNull(e.snapshot(16, 16, false))
        assertNull(e.thumbnail(16))

        e.clearModel()
        assertFalse(e.buildAnalysis(null))
        assertNull(e.meshCheck())
        assertNull(e.computeThickness(null))
        e.clearScalars()
        assertNull(e.overhang(45f))

        e.orbit(1f, 1f)
        e.pan(1f, 1f)
        e.zoom(1.1f, 0f, 0f)
        e.fling(1f, 1f)
        e.stopMotion()
        e.setStandardView(StandardView.Top)
        e.setOrientation(0.5f, 0.5f)
        e.fitView()
        e.focusPart(0)
        e.focusPoint(Vec3(0f, 0f, 0f), 1f)
        e.setOrtho(true)
        e.setViewInsets(0f, 0f)
        e.setTurntable(true)
        e.setSensitivity(1f)
        e.setTurntableSpeed(0.6f)
        e.setFov(50f)
        e.camera()
        e.setCamera(CameraState(Vec3(0f, 0f, 0f), 10f, 0f, 0f, false), false)
        e.cameraFrame()
        assertNull(e.pick(1f, 1f))

        e.setPartColor(0, 0)
        e.setPartVisible(0, false)
        e.setPartSelected(0, true)
        e.setPartHighlight(0, true)
        e.setPartGhost(0, true)
        e.setPartMaterial(0, MaterialPreset.entries.first())
        e.setPartVertexColors(0, true)
        e.setPartOffset(0, Vec3(1f, 1f, 1f))
        e.partOffset(0)
        e.setExplode(0.5f)
        e.resetParts()

        e.setOptions(RenderOptions())
        e.sceneBounds()
        assertFalse(e.export(-1, ExportFormat.STL, null, false))
    }
}
