package com.cloudvnc.app

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cloudvnc.app.databinding.ActivityVncBinding
import kotlinx.coroutines.*

class VncActivity : AppCompatActivity(), RfbListener {
    private lateinit var b: ActivityVncBinding
    private var rfb: RfbClient? = null
    private var toolbarVisible = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        b = ActivityVncBinding.inflate(layoutInflater)
        setContentView(b.root)

        val host = intent.getStringExtra("host") ?: return finish()
        val port = intent.getIntExtra("port", 5900)
        val pass = intent.getStringExtra("pass") ?: ""

        b.tvHost.text = "$host:$port"
        b.btnBack.setOnClickListener { disconnect(); finish() }
        b.btnKeyboard.setOnClickListener { toggleKeyboard() }

        // Toggle toolbar on tap
        b.vncView.setOnClickListener { /* handled by VncView tap */ }

        // Show toolbar on first touch → swipe down
        b.root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.y < 80f) toggleToolbar()
            false
        }

        b.vncView.onTouchTranslated = { x, y, mask ->
            try { rfb?.sendPointerEvent(x, y, mask) } catch (_: Exception) {}
        }

        connect(host, port, pass)
    }

    private fun connect(host: String, port: Int, pass: String) {
        b.progressBar.visibility = View.VISIBLE
        b.tvError.visibility = View.GONE
        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = RfbClient(RfbConfig(host, port, pass), this@VncActivity)
                rfb = client
                b.vncView.client = client
                client.connect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError(e.message ?: "Connection failed") }
            }
        }
    }

    override fun onConnected(width: Int, height: Int) {
        runOnUiThread {
            b.progressBar.visibility = View.GONE
            b.vncView.setVncSize(width, height)
        }
    }

    override fun onBitmapUpdated(x: Int, y: Int, w: Int, h: Int) {
        b.vncView.notifyBitmapUpdated()
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread { showError(reason) }
    }

    private fun showError(msg: String) {
        b.progressBar.visibility = View.GONE
        b.tvError.visibility = View.VISIBLE
        b.tvError.text = "❌ $msg\n\nاسحب للأسفل للرجوع"
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        b.toolbar.visibility = if (toolbarVisible) View.VISIBLE else View.GONE
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) imm.hideSoftInputFromWindow(b.vncView.windowToken, 0)
        else { b.vncView.requestFocus(); imm.showSoftInput(b.vncView, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun disconnect() { job?.cancel(); rfb?.disconnect() }

    override fun onDestroy() { disconnect(); super.onDestroy() }
    override fun onBackPressed() { disconnect(); super.onBackPressed() }
}
