package com.shizuku.terminal

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvOutput: TextView
    private lateinit var etCommand: EditText
    private lateinit var btnExecute: Button
    private lateinit var btnClear: Button
    private lateinit var btnAuthorize: Button

    private val executor = ShizukuExecutor.getInstance()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    /** Activity 是否已销毁，用于守护后台线程回调不操作死 View */
    @Volatile
    private var activityDestroyed = false

    private val shizukuProviderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThreadSafely { updateShizukuStatus() }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThreadSafely { updateShizukuStatus() }
    }

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThreadSafely {
            updateShizukuStatus()
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                appendOutputInfo("✓ Shizuku 权限已授予\n")
            } else {
                appendOutputError("✗ Shizuku 权限被拒绝\n")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()

        // [修复 Bug 1] 全部 Shizuku 监听器注册都必须包裹 try-catch。
        // Shizuku 未安装/未启动时，addBinderDeadListener 和
        // addRequestPermissionResultListener 会抛 NPE 导致启动闪退。
        try {
            Shizuku.addBinderReceivedListener(shizukuProviderListener)
        } catch (e: Throwable) {
            // Shizuku 不可用时静默忽略
        }
        try {
            Shizuku.addBinderDeadListener(shizukuDeadListener)
        } catch (e: Throwable) {
            // ignore
        }
        try {
            Shizuku.addRequestPermissionResultListener(requestPermissionListener)
        } catch (e: Throwable) {
            // ignore
        }

        runOnUiThreadSafely {
            updateShizukuStatus()
            appendOutputInfo("Shizuku Terminal v1.0.1 已启动\n")
            appendOutputInfo("提示: 输入命令后点击执行，或直接回车\n")
            appendOutputInfo("========================================\n")
        }
    }

    override fun onResume() {
        super.onResume()
        runOnUiThreadSafely { updateShizukuStatus() }
    }

    override fun onDestroy() {
        activityDestroyed = true
        // 取消正在执行的命令，避免后台线程回调死 Activity
        executor.cancelAll()
        try {
            Shizuku.removeBinderReceivedListener(shizukuProviderListener)
        } catch (e: Throwable) { /* ignore */ }
        try {
            Shizuku.removeBinderDeadListener(shizukuDeadListener)
        } catch (e: Throwable) { /* ignore */ }
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
        } catch (e: Throwable) { /* ignore */ }
        super.onDestroy()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvMode = findViewById(R.id.tvMode)
        tvOutput = findViewById(R.id.tvOutput)
        etCommand = findViewById(R.id.etCommand)
        btnExecute = findViewById(R.id.btnExecute)
        btnClear = findViewById(R.id.btnClear)
        btnAuthorize = findViewById(R.id.btnAuthorize)
    }

    private fun setupListeners() {
        btnExecute.setOnClickListener {
            val cmd = etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                executeCommand(cmd)
            }
        }

        btnClear.setOnClickListener {
            tvOutput.text = ""
        }

        btnAuthorize.setOnClickListener {
            if (!executor.isShizukuAvailable()) {
                appendOutputError(getString(R.string.error_no_shizuku) + "\n")
            } else {
                executor.requestPermission(1001)
            }
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_NULL) {
                val cmd = etCommand.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    executeCommand(cmd)
                }
                true
            } else {
                false
            }
        }

        etCommand.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                historyIndex = -1
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateShizukuStatus() {
        val available = try { executor.isShizukuAvailable() } catch (e: Throwable) { false }
        val hasPermission = if (available) {
            try { executor.hasShizukuPermission() } catch (e: Throwable) { false }
        } else false

        when {
            !available -> {
                tvStatus.text = getString(R.string.status_unavailable)
                tvStatus.setTextColor(Color.parseColor("#FFF44336"))
                tvMode.text = getString(R.string.execute_mode_normal)
            }
            !hasPermission -> {
                tvStatus.text = getString(R.string.status_unauthorized)
                tvStatus.setTextColor(Color.parseColor("#FFFFC107"))
                tvMode.text = getString(R.string.execute_mode_normal)
            }
            else -> {
                tvStatus.text = getString(R.string.status_available)
                tvStatus.setTextColor(Color.parseColor("#FF4CAF50"))
                tvMode.text = getString(R.string.execute_mode_shizuku)
            }
        }
    }

    private fun executeCommand(command: String) {
        if (commandHistory.isEmpty() || commandHistory.last() != command) {
            commandHistory.add(command)
        }
        historyIndex = commandHistory.size

        appendOutputCommand("$ $command\n")
        etCommand.setText("")

        if (!executor.isShizukuAvailable()) {
            appendOutputWarn("⚠ Shizuku 未连接，将使用普通应用模式执行（权限受限）\n")
        } else if (!executor.hasShizukuPermission()) {
            appendOutputWarn("⚠ Shizuku 未授权，将使用普通应用模式执行（权限受限）\n")
        }

        executor.execute(command, object : ShizukuExecutor.OnExecuteListener {
            override fun onOutput(text: String) {
                runOnUiThreadSafely { appendOutput(text) }
            }

            override fun onError(text: String) {
                runOnUiThreadSafely { appendOutputError(text) }
            }

            override fun onExit(exitCode: Int) {
                runOnUiThreadSafely {
                    if (exitCode == 0) {
                        appendOutputInfo("[进程退出，exit code: 0]\n")
                    } else {
                        appendOutputError("[进程退出，exit code: $exitCode]\n")
                    }
                    appendOutputInfo("----------------------------------------\n")
                }
            }
        })
    }

    private fun appendOutput(text: String) {
        tvOutput.append(text)
        scrollToBottom()
    }

    private fun appendOutputCommand(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#FF4CAF50")),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(spannable)
        scrollToBottom()
    }

    private fun appendOutputInfo(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#FF81D4FA")),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(spannable)
        scrollToBottom()
    }

    private fun appendOutputWarn(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#FFFFC107")),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(spannable)
        scrollToBottom()
    }

    private fun appendOutputError(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#FFFF5252")),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(spannable)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val scrollView = tvOutput.parent as? android.widget.ScrollView
        scrollView?.post {
            scrollView.scrollTo(0, tvOutput.bottom)
        }
    }

    /**
     * 安全地在主线程执行 UI 更新。
     * Activity 已销毁或正在 finishing 时直接丢弃，防止操作死 View 导致崩溃。
     */
    private fun runOnUiThreadSafely(block: () -> Unit) {
        if (activityDestroyed || isFinishing) return
        runOnUiThread {
            if (activityDestroyed || isFinishing) return@runOnUiThread
            try {
                block()
            } catch (e: Throwable) {
                // UI 更新异常不应该让 App 崩溃
            }
        }
    }
}
