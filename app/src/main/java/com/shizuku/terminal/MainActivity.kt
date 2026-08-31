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

    private val shizukuProviderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuStatus() }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateShizukuStatus() }
    }

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        runOnUiThread {
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

        try {
            Shizuku.addBinderReceivedListener(shizukuProviderListener)
        } catch (e: Exception) {
            // try with the "sticky" pattern via reflective call if normal fails
        }
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionListener)

        updateShizukuStatus()
        appendOutputInfo("Shizuku Terminal v1.0 已启动\n")
        appendOutputInfo("提示: 输入命令后点击执行，或直接回车\n")
        appendOutputInfo("========================================\n")
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuProviderListener)
        } catch (e: Exception) {
            // ignore
        }
        try {
            Shizuku.removeBinderDeadListener(shizukuDeadListener)
        } catch (e: Exception) {
            // ignore
        }
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
        } catch (e: Exception) {
            // ignore
        }
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
        val available = executor.isShizukuAvailable()
        val hasPermission = if (available) executor.hasShizukuPermission() else false

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
                runOnUiThread { appendOutput(text) }
            }

            override fun onError(text: String) {
                runOnUiThread { appendOutputError(text) }
            }

            override fun onExit(exitCode: Int) {
                runOnUiThread {
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
}
