package com.shizuku.terminal

import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class ShizukuExecutor {

    interface OnExecuteListener {
        fun onOutput(text: String)
        fun onError(text: String)
        fun onExit(exitCode: Int)
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun execute(command: String, listener: OnExecuteListener) {
        Thread {
            try {
                if (isShizukuAvailable() && hasShizukuPermission()) {
                    executeViaShizuku(command, listener)
                } else {
                    executeViaRuntime(command, listener)
                }
            } catch (e: Exception) {
                listener.onError("执行异常: ${e.message}\n")
                listener.onExit(-1)
            }
        }.start()
    }

    private fun executeViaShizuku(command: String, listener: OnExecuteListener) {
        try {
            val cmd = arrayOf("sh", "-c", command)
            val remoteProcess = callNewProcess(cmd)
            if (remoteProcess == null) {
                listener.onError("Shizuku newProcess 调用失败，切换至普通模式\n")
                executeViaRuntime(command, listener)
                return
            }

            val inputStream: InputStream? = getRemoteProcessInputStream(remoteProcess)
            val errorStream: InputStream? = getRemoteProcessErrorStream(remoteProcess)

            val stdoutThread = Thread {
                try {
                    if (inputStream != null) {
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            listener.onOutput(line + "\n")
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            val stderrThread = Thread {
                try {
                    if (errorStream != null) {
                        val reader = BufferedReader(InputStreamReader(errorStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            listener.onError(line + "\n")
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            stdoutThread.start()
            stderrThread.start()

            stdoutThread.join()
            stderrThread.join()

            val exitCode = callRemoteProcessWaitFor(remoteProcess)
            callRemoteProcessDestroy(remoteProcess)
            listener.onExit(exitCode)
        } catch (e: RemoteException) {
            listener.onError("Shizuku 远程异常: ${e.message}\n")
            listener.onExit(-1)
        } catch (e: Exception) {
            listener.onError("Shizuku 执行异常: ${e.message}\n")
            listener.onExit(-1)
        }
    }

    private fun callNewProcess(cmd: Array<String>): Any? {
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
        } catch (e: Exception) {
            try {
                // Try alternative: ShizukuRemoteProcess directly
                val clazz = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                val constructor = clazz.getDeclaredConstructor(
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                constructor.isAccessible = true
                constructor.newInstance(cmd, null, null)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun getRemoteProcessInputStream(process: Any): InputStream? {
        return try {
            val method = process.javaClass.getMethod("getInputStream")
            method.invoke(process) as? InputStream
        } catch (e: Exception) {
            try {
                val field = process.javaClass.getDeclaredField("inputStream")
                field.isAccessible = true
                field.get(process) as? InputStream
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun getRemoteProcessErrorStream(process: Any): InputStream? {
        return try {
            val method = process.javaClass.getMethod("getErrorStream")
            method.invoke(process) as? InputStream
        } catch (e: Exception) {
            try {
                val field = process.javaClass.getDeclaredField("errorStream")
                field.isAccessible = true
                field.get(process) as? InputStream
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun callRemoteProcessWaitFor(process: Any): Int {
        return try {
            val method = process.javaClass.getMethod("waitFor")
            method.invoke(process) as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun callRemoteProcessDestroy(process: Any) {
        try {
            val method = process.javaClass.getMethod("destroy")
            method.invoke(process)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun executeViaRuntime(command: String, listener: OnExecuteListener) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        listener.onOutput(line + "\n")
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        listener.onError(line + "\n")
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            stdoutThread.start()
            stderrThread.start()

            stdoutThread.join()
            stderrThread.join()

            val exitCode = process.waitFor()
            process.destroy()
            listener.onExit(exitCode)
        } catch (e: Exception) {
            listener.onError("普通执行异常: ${e.message}\n")
            listener.onExit(-1)
        }
    }

    companion object {
        private var instance: ShizukuExecutor? = null
        fun getInstance(): ShizukuExecutor {
            if (instance == null) {
                instance = ShizukuExecutor()
            }
            return instance!!
        }
    }
}
