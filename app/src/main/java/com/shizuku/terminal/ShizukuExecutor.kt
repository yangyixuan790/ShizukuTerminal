package com.shizuku.terminal

import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Collections

class ShizukuExecutor {

    interface OnExecuteListener {
        fun onOutput(text: String)
        fun onError(text: String)
        fun onExit(exitCode: Int)
    }

    /** 当前正在运行的进程（用于 cancelAll 销毁） */
    private val runningProcesses = Collections.synchronizedList(mutableListOf<Process>())

    /** 当前正在运行的工作线程（用于 cancelAll 中断） */
    private val runningThreads = Collections.synchronizedList(mutableListOf<Thread>())

    @Volatile
    private var cancelled = false

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun execute(command: String, listener: OnExecuteListener) {
        cancelled = false
        val worker = Thread {
            try {
                if (isShizukuAvailable() && hasShizukuPermission()) {
                    executeViaShizuku(command, listener)
                } else {
                    executeViaRuntime(command, listener)
                }
            } catch (e: Throwable) {
                safeOnError(listener, "执行异常: ${e.message ?: e.javaClass.simpleName}\n")
                safeOnExit(listener, -1)
            }
        }
        runningThreads.add(worker)
        worker.start()
    }

    /**
     * 取消所有正在执行的命令。
     * 销毁子进程并中断工作线程，listener 不会再收到回调。
     */
    fun cancelAll() {
        cancelled = true
        synchronized(runningProcesses) {
            for (p in runningProcesses) {
                try { p.destroy() } catch (e: Throwable) { /* ignore */ }
            }
            runningProcesses.clear()
        }
        synchronized(runningThreads) {
            for (t in runningThreads) {
                try { t.interrupt() } catch (e: Throwable) { /* ignore */ }
            }
            runningThreads.clear()
        }
    }

    private fun executeViaShizuku(command: String, listener: OnExecuteListener) {
        try {
            val cmd = arrayOf("sh", "-c", command)
            val remoteProcess = callNewProcess(cmd)
            if (remoteProcess == null) {
                safeOnError(listener, "Shizuku newProcess 调用失败，切换至普通模式\n")
                executeViaRuntime(command, listener)
                return
            }

            val inputStream: InputStream? = getRemoteProcessInputStream(remoteProcess)
            val errorStream: InputStream? = getRemoteProcessErrorStream(remoteProcess)

            val stdoutThread = Thread {
                try {
                    inputStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (cancelled) return@use
                                safeOnOutput(listener, line + "\n")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 流读取异常（如进程被杀）静默忽略
                }
            }

            val stderrThread = Thread {
                try {
                    errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (cancelled) return@use
                                safeOnError(listener, line + "\n")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // ignore
                }
            }

            stdoutThread.start()
            stderrThread.start()

            try {
                stdoutThread.join()
                stderrThread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val exitCode = try { callRemoteProcessWaitFor(remoteProcess) } catch (e: Throwable) { -1 }
            callRemoteProcessDestroy(remoteProcess)
            if (!cancelled) safeOnExit(listener, exitCode)
        } catch (e: RemoteException) {
            safeOnError(listener, "Shizuku 远程异常: ${e.message ?: "unknown"}\n")
            safeOnExit(listener, -1)
        } catch (e: Throwable) {
            safeOnError(listener, "Shizuku 执行异常: ${e.message ?: e.javaClass.simpleName}\n")
            safeOnExit(listener, -1)
        }
    }

    /**
     * Shizuku.newProcess() 是私有 API，必须通过反射调用。
     * 先尝试 Shizuku 类的静态方法，失败再尝试 ShizukuRemoteProcess 构造函数。
     */
    private fun callNewProcess(cmd: Array<String>): Any? {
        // 方式 1: 反射调用 Shizuku.newProcess(cmd, env, dir)
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null)
        } catch (e: Throwable) {
            // 方式 2: 反射构造 ShizukuRemoteProcess
            try {
                val clazz = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                val constructor = clazz.getDeclaredConstructor(
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                constructor.isAccessible = true
                constructor.newInstance(cmd, null, null)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun getRemoteProcessInputStream(process: Any): InputStream? {
        return try {
            val method = process.javaClass.getMethod("getInputStream")
            method.invoke(process) as? InputStream
        } catch (e: Throwable) {
            try {
                val field = process.javaClass.getDeclaredField("inputStream")
                field.isAccessible = true
                field.get(process) as? InputStream
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun getRemoteProcessErrorStream(process: Any): InputStream? {
        return try {
            val method = process.javaClass.getMethod("getErrorStream")
            method.invoke(process) as? InputStream
        } catch (e: Throwable) {
            try {
                val field = process.javaClass.getDeclaredField("errorStream")
                field.isAccessible = true
                field.get(process) as? InputStream
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun callRemoteProcessWaitFor(process: Any): Int {
        return try {
            val method = process.javaClass.getMethod("waitFor")
            method.invoke(process) as? Int ?: -1
        } catch (e: Throwable) {
            -1
        }
    }

    private fun callRemoteProcessDestroy(process: Any) {
        try {
            val method = process.javaClass.getMethod("destroy")
            method.invoke(process)
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun executeViaRuntime(command: String, listener: OnExecuteListener) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            runningProcesses.add(process)

            val stdoutThread = Thread {
                try {
                    process.inputStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (cancelled) return@use
                                safeOnOutput(listener, line + "\n")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // ignore
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (cancelled) return@use
                                safeOnError(listener, line + "\n")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // ignore
                }
            }

            stdoutThread.start()
            stderrThread.start()

            try {
                stdoutThread.join()
                stderrThread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val exitCode = try { process.waitFor() } catch (e: Throwable) { -1 }
            try { process.destroy() } catch (e: Throwable) { /* ignore */ }
            runningProcesses.remove(process)
            if (!cancelled) safeOnExit(listener, exitCode)
        } catch (e: Throwable) {
            process?.let {
                try { it.destroy() } catch (_: Throwable) {}
                runningProcesses.remove(it)
            }
            safeOnError(listener, "普通执行异常: ${e.message ?: e.javaClass.simpleName}\n")
            safeOnExit(listener, -1)
        }
    }

    // ---- 安全的 listener 回调封装，防止 listener 内部异常导致工作线程崩溃 ----

    private fun safeOnOutput(listener: OnExecuteListener, text: String) {
        if (cancelled) return
        try { listener.onOutput(text) } catch (e: Throwable) { /* ignore */ }
    }

    private fun safeOnError(listener: OnExecuteListener, text: String) {
        if (cancelled) return
        try { listener.onError(text) } catch (e: Throwable) { /* ignore */ }
    }

    private fun safeOnExit(listener: OnExecuteListener, exitCode: Int) {
        if (cancelled) return
        try { listener.onExit(exitCode) } catch (e: Throwable) { /* ignore */ }
    }

    companion object {
        @Volatile
        private var instance: ShizukuExecutor? = null

        fun getInstance(): ShizukuExecutor {
            return instance ?: synchronized(this) {
                instance ?: ShizukuExecutor().also { instance = it }
            }
        }
    }
}
