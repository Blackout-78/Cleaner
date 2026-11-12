package com.blackout.cleaner

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

/**
 * Deep Clean through Shizuku (a separate open-source app, not Google). Shizuku lets Cleaner run one
 * shell command with the same rights as "adb shell". Everything stays on the phone: Cleaner talks to
 * Shizuku through Android's local binder, so Cleaner needs no internet permission.
 */
object Deep {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ = 4711
    private val main = Handler(Looper.getMainLooper())

    /** missing, stopped, needs_permission, denied, ready */
    var state by mutableStateOf("missing"); private set
    var busy by mutableStateOf(false); private set
    var result by mutableStateOf(""); private set

    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        try {
            Shizuku.addBinderReceivedListenerSticky { main.post { refresh(ctx) } }
            Shizuku.addBinderDeadListener { main.post { refresh(ctx) } }
            Shizuku.addRequestPermissionResultListener { _, _ -> main.post { refresh(ctx) } }
        } catch (e: Throwable) { }
        refresh(ctx)
    }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false } catch (e: Throwable) { false }

    fun refresh(ctx: Context) {
        state = try {
            when {
                !installed(ctx) -> "missing"
                !Shizuku.pingBinder() -> "stopped"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "ready"
                Shizuku.shouldShowRequestPermissionRationale() -> "denied"
                else -> "needs_permission"
            }
        } catch (e: Throwable) { "stopped" }
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQ) } catch (e: Throwable) { result = "Could not ask Shizuku: ${e.message}" }
    }

    fun openShizuku(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (i != null) ctx.startActivity(i)
    }

    private fun freeBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    /** Clears the cache of every app (the same thing as "Clear cache" for each one). Nothing else is touched. */
    fun run(ctx: Context) {
        if (busy || state != "ready") return
        busy = true
        result = "Working..."
        val before = freeBytes()
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .processNameSuffix("shell").debuggable(false).daemon(false).version(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IUserService.Stub.asInterface(binder)
                Thread {
                    val out = try { svc.exec("pm trim-caches 1000000000000") } catch (e: Throwable) { "[error ${e.message}]" }
                    val freed = freeBytes() - before
                    main.post {
                        result = "Done. Freed ${fmtSize(if (freed > 0) freed else 0)}.\n" +
                            (if (out.contains("exit 0")) "" else "Shell said: $out")
                        busy = false
                    }
                    try { Shizuku.unbindUserService(args, this, true) } catch (e: Throwable) { }
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            result = "Could not start Deep Clean: ${e.message}"
            busy = false
        }
    }
}
