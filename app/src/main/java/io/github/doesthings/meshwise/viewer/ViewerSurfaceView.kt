package io.github.doesthings.meshwise.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The 3D view. Renders on demand (only while something changes) and translates touch
 * gestures into camera moves: one finger orbits, two fingers pan and pinch-zoom towards the
 * point under the fingers, a fling keeps spinning with inertia.
 */
@SuppressLint("ViewConstructor")
class ViewerSurfaceView(context: Context, private val vm: ViewerViewModel) : GLSurfaceView(context), GlHost {

    private val engine get() = vm.engine

    private val renderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = engine.onSurfaceCreated()
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = engine.onSurfaceChanged(width, height)
        override fun onDrawFrame(gl: GL10?) {
            vm.runGlTasks()
            val more = engine.drawFrame()
            vm.onFrameRendered()
            if (more) requestRender()
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(ConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun queueOnGl(block: () -> Unit) = queueEvent(block)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vm.attachGl(this)
    }

    override fun onDetachedFromWindow() {
        vm.detachGl(this)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ touch

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var velocity: VelocityTracker? = null
    private var mode = Mode.Idle
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    private enum class Mode { Idle, Pending, Orbit, Multi, AfterMulti }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // Tools that place points react instantly; elsewhere wait to tell taps from double taps.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (vm.instantTaps) vm.onTap(e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!vm.instantTaps) vm.onTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!vm.instantTaps) vm.onDoubleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode == Mode.Pending) vm.onLongPress(e.x, e.y)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                engine.stopMotion()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                mode = Mode.Pending
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mode = Mode.Multi
                    lastCx = (event.getX(0) + event.getX(1)) / 2f
                    lastCy = (event.getY(0) + event.getY(1)) / 2f
                    lastSpan = span(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                when {
                    event.pointerCount >= 2 && (mode == Mode.Multi) -> {
                        val cx = (event.getX(0) + event.getX(1)) / 2f
                        val cy = (event.getY(0) + event.getY(1)) / 2f
                        val s = span(event)
                        engine.pan(cx - lastCx, cy - lastCy)
                        if (lastSpan > 10f && s > 10f) engine.zoom(s / lastSpan, cx, cy)
                        lastCx = cx; lastCy = cy; lastSpan = s
                        requestRender()
                    }
                    event.pointerCount == 1 -> {
                        val x = event.x
                        val y = event.y
                        if (mode == Mode.Pending && hypot(x - downX, y - downY) > touchSlop) {
                            mode = Mode.Orbit
                            lastX = x; lastY = y
                        }
                        if (mode == Mode.Orbit) {
                            engine.orbit(x - lastX, y - lastY)
                            requestRender()
                        }
                        if (mode == Mode.AfterMulti) {
                            // Continue as orbit with the remaining finger without a jump.
                            mode = Mode.Orbit
                        }
                        lastX = x; lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    mode = Mode.AfterMulti
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                } else {
                    lastSpan = span(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.let { vt ->
                    vt.addMovement(event)
                    vt.computeCurrentVelocity(1000)
                    if (mode == Mode.Orbit) {
                        val vx = vt.xVelocity
                        val vy = vt.yVelocity
                        if (abs(vx) > 250f || abs(vy) > 250f) engine.fling(vx, vy)
                    }
                    vt.recycle()
                }
                velocity = null
                mode = Mode.Idle
                requestRender()
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
                mode = Mode.Idle
            }
        }
        return true
    }

    private fun span(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /** Picks the best available config: MSAA 4x with depth/stencil, degrading gracefully. */
    private class ConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val EGL_OPENGL_ES3_BIT = 0x40
            val attempts = listOf(
                intArrayOf(8, 24, 8, 4),
                intArrayOf(8, 24, 8, 2),
                intArrayOf(8, 24, 8, 0),
                intArrayOf(8, 16, 8, 0),
                intArrayOf(8, 16, 0, 0),
            )
            for (a in attempts) {
                val attrs = mutableListOf(
                    EGL10.EGL_RED_SIZE, a[0], EGL10.EGL_GREEN_SIZE, a[0], EGL10.EGL_BLUE_SIZE, a[0], EGL10.EGL_ALPHA_SIZE, 0,
                    EGL10.EGL_DEPTH_SIZE, a[1], EGL10.EGL_STENCIL_SIZE, a[2],
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                )
                if (a[3] > 0) attrs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, a[3])
                attrs += EGL10.EGL_NONE
                val arr = attrs.toIntArray()
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, arr, null, 0, num) && num[0] > 0) {
                    val configs = arrayOfNulls<EGLConfig>(num[0])
                    egl.eglChooseConfig(display, arr, configs, num[0], num)
                    // Prefer an exact RGB888 match.
                    val exact = configs.firstOrNull { c ->
                        c != null && value(egl, display, c, EGL10.EGL_RED_SIZE) == 8 && value(egl, display, c, EGL10.EGL_GREEN_SIZE) == 8
                    }
                    (exact ?: configs.firstOrNull())?.let { return it }
                }
            }
            throw IllegalStateException("No OpenGL ES 3 config available")
        }

        private fun value(egl: EGL10, d: EGLDisplay, c: EGLConfig, attr: Int): Int {
            val v = IntArray(1)
            return if (egl.eglGetConfigAttrib(d, c, attr, v)) v[0] else 0
        }
    }
}
