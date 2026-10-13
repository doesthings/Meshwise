package io.github.doesthings.meshwise.cad

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import io.github.doesthings.meshwise.engine.NativeProgress
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JNI surface of libmeshwise_cad.so (Open CASCADE based STEP/IGES/BREP import). */
object CadNative {
    /** False on ABIs built without the CAD kernel. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("meshwise_cad")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Returns [parts, triangles]. Throws IOException or CancellationException. */
    @JvmStatic external fun importCad(
        input: String, ext: String, output: String, relativeDeflection: Double, angularDeflection: Double, progress: NativeProgress?,
    ): LongArray

    fun deflectionFor(quality: Int): Pair<Double, Double> = when (quality) {
        0 -> 0.0022 to 0.5
        2 -> 0.00030 to 0.20
        else -> 0.0008 to 0.35
    }
}

class CadCrashException : IOException("CAD converter crashed")
class CadUnavailableException : IOException("CAD import unavailable")

data class CadImportResult(val parts: Long, val triangles: Long)

private const val MSG_IMPORT = 1
private const val MSG_CANCEL = 2
private const val MSG_PROGRESS = 10
private const val MSG_DONE = 11
private const val MSG_ERROR = 12
private const val MSG_CANCELLED = 13
private const val MSG_UNAVAILABLE = 14

/**
 * Runs CAD conversion in the isolated ":cad" process. The process is killed after the
 * client unbinds so the kernel's memory is returned immediately.
 */
class CadImportService : Service() {
    @Volatile private var cancelled = false

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_IMPORT -> {
                    val reply = msg.replyTo ?: return
                    val data = Bundle(msg.data)
                    cancelled = false
                    worker.execute { runImport(data, reply) }
                }
                MSG_CANCEL -> cancelled = true
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        // A client may bind again (next file of a ZIP, or a new file right after Cancel)
        // before the previous instance's delayed kill has fired: keep the process alive.
        killer.removeCallbacks(killProcess)
    }

    override fun onBind(intent: Intent?): IBinder {
        killer.removeCallbacks(killProcess)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelled = true
        killer.removeCallbacks(killProcess)
        killer.postDelayed(killProcess, 300)
        return false
    }

    companion object {
        // Process-wide, so imports from successive service instances run one at a time
        // (a cancelled import finishes at its next progress check before the next starts).
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(null, r, "cad-import", 64L shl 20) }
        private val killer = Handler(Looper.getMainLooper())
        private val killProcess = Runnable { Process.killProcess(Process.myPid()) }
    }

    private fun send(reply: Messenger, what: Int, fill: (Bundle) -> Unit = {}) {
        try {
            reply.send(Message.obtain(null, what).apply { data = Bundle().also(fill) })
        } catch (_: RemoteException) {
            cancelled = true
        }
    }

    private fun runImport(data: Bundle, reply: Messenger) {
        if (!CadNative.available) {
            send(reply, MSG_UNAVAILABLE)
            return
        }
        var lastSent = 0L
        val progress = NativeProgress { f ->
            val now = SystemClock.uptimeMillis()
            if (now - lastSent > 80) {
                lastSent = now
                send(reply, MSG_PROGRESS) { it.putFloat("p", f) }
            }
            !cancelled
        }
        try {
            val r = CadNative.importCad(
                data.getString("input")!!, data.getString("ext")!!, data.getString("output")!!,
                data.getDouble("lin"), data.getDouble("ang"), progress,
            )
            send(reply, MSG_DONE) { it.putLong("parts", r[0]); it.putLong("tris", r[1]) }
        } catch (e: CancellationException) {
            send(reply, MSG_CANCELLED)
        } catch (e: Throwable) {
            send(reply, MSG_ERROR) { it.putString("msg", e.message ?: e.toString()) }
        }
    }
}

/** Client side: binds the service, runs one import and unbinds. */
class CadImportClient(private val context: Context) {

    suspend fun import(input: File, ext: String, output: File, quality: Int, onProgress: (Float) -> Unit): CadImportResult =
        suspendCancellableCoroutine { cont ->
            val (lin, ang) = CadNative.deflectionFor(quality)
            var service: Messenger? = null
            var finished = false
            lateinit var connection: ServiceConnection

            fun finish(block: () -> Unit) {
                if (finished) return
                finished = true
                try { context.unbindService(connection) } catch (_: Exception) {}
                block()
            }

            val replyHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_PROGRESS -> onProgress(msg.data.getFloat("p"))
                        MSG_DONE -> finish { cont.resume(CadImportResult(msg.data.getLong("parts"), msg.data.getLong("tris"))) }
                        MSG_ERROR -> finish { cont.resumeWithException(IOException(msg.data.getString("msg") ?: "CAD import failed")) }
                        MSG_CANCELLED -> finish { cont.resumeWithException(kotlinx.coroutines.CancellationException("cancelled")) }
                        MSG_UNAVAILABLE -> finish { cont.resumeWithException(CadUnavailableException()) }
                    }
                }
            }
            val replyTo = Messenger(replyHandler)

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val m = Messenger(binder)
                    service = m
                    val msg = Message.obtain(null, MSG_IMPORT).apply {
                        this.replyTo = replyTo
                        data = Bundle().apply {
                            putString("input", input.absolutePath)
                            putString("ext", ext)
                            putString("output", output.absolutePath)
                            putDouble("lin", lin)
                            putDouble("ang", ang)
                        }
                    }
                    try {
                        m.send(msg)
                    } catch (e: RemoteException) {
                        finish { cont.resumeWithException(CadCrashException()) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The :cad process died while working.
                    finish { cont.resumeWithException(CadCrashException()) }
                }

                override fun onBindingDied(name: ComponentName?) {
                    finish { cont.resumeWithException(CadCrashException()) }
                }
            }

            cont.invokeOnCancellation {
                try { service?.send(Message.obtain(null, MSG_CANCEL)) } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).post { finish {} }
            }

            val ok = context.bindService(Intent(context, CadImportService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!ok) finish { cont.resumeWithException(CadCrashException()) }
        }
}
