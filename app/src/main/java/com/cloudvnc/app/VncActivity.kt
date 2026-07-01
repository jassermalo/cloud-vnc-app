package com.cloudvnc.app

import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class VncActivity : AppCompatActivity(), RfbListener {
    private lateinit var vncView: VncView
    private lateinit var toolbar: View
    private lateinit var tvHost: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnKeyboard: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private var rfb: RfbClient? = null
    private var toolbarVisible = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        setContentView(R.layout.activity_vnc)

        vncView = findViewById(R.id.vncView)
        toolbar = findViewById(R.id.toolbar)
        tvHost = findViewById(R.id.tvHost)
        btnBack = findViewById(R.id.btnBack)
        btnKeyboard = findViewById(R.id.btnKeyboard)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        val host = intent.getStringExtra("host") ?: return finish()
        val port = intent.getIntExtra("port", 5900)
        val pass = intent.getStringExtra("pass") ?: ""

        tvHost.text = "$host:$port"
        btnBack.setOnClickListener { disconnect(); finish() }
        btnKeyboard.setOnClickListener { toggleKeyboard() }

        vncView.setOnLongClickListener { toggleToolbar(); true }

        vncView.onTouchTranslated = { x, y, mask ->
            try { rfb?.sendPointerEvent(x, y, mask) } catch (_: Exception) {}
        }

        connect(host, port, pass)
    }

    private fun connect(host: String, port: Int, pass: String) {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = RfbClient(RfbConfig(host, port, pass), this@VncActivity)
                rfb = client
                withContext(Dispatchers.Main) { vncView.client = client }
                client.connect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError(e.message ?: "Connection failed") }
            }
        }
    }

    override fun onConnected(width: Int, height: Int) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            vncView.setVncSize(width, height)
        }
    }

    override fun onBitmapUpdated(x: Int, y: Int, w: Int, h: Int) {
        vncView.notifyBitmapUpdated()
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread { showError(reason) }
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = "❌ $msg"
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        toolbar.visibility = if (toolbarVisible) View.VISIBLE else View.GONE
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        vncView.requestFocus()
        imm.showSoftInput(vncView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun disconnect() { job?.cancel(); rfb?.disconnect() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { disconnect(); super.onBackPressed() }
}