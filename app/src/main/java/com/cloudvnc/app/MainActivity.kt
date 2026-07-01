package com.cloudvnc.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudvnc.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val PREFS = "vnc_prefs"
    private val KEY_HISTORY = "history"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        loadLastSession()
        setupHistory()
        b.btnConnect.setOnClickListener {
            val host = b.etHost.text.toString().trim()
            val port = b.etPort.text.toString().toIntOrNull() ?: 5900
            val pass = b.etPassword.text.toString()
            if (host.isEmpty()) { b.tilHost.error = "أدخل العنوان"; return@setOnClickListener }
            b.tilHost.error = null
            saveToHistory(host, port, pass)
            startVnc(host, port, pass)
        }
    }

    private fun startVnc(host: String, port: Int, pass: String) {
        val i = Intent(this, VncActivity::class.java)
        i.putExtra("host", host); i.putExtra("port", port); i.putExtra("pass", pass)
        startActivity(i)
    }

    private fun loadLastSession() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = prefs.getString(KEY_HISTORY, null) ?: return
        val arr = JSONArray(history)
        if (arr.length() == 0) return
        val last = arr.getJSONObject(0)
        b.etHost.setText(last.optString("host"))
        b.etPort.setText(last.optInt("port", 5900).toString())
    }

    private fun setupHistory() {
        b.rvHistory.layoutManager = LinearLayoutManager(this)
        b.rvHistory.adapter = HistoryAdapter(getHistory()) { host, port, pass ->
            b.etHost.setText(host)
            b.etPort.setText(port.toString())
            b.etPassword.setText(pass)
            startVnc(host, port, pass)
        }
    }

    private fun getHistory(): List<Triple<String, Int, String>> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Triple(o.optString("host"), o.optInt("port", 5900), o.optString("pass"))
        }
    }

    private fun saveToHistory(host: String, port: Int, pass: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        val entry = JSONObject().put("host", host).put("port", port).put("pass", pass)
        val newArr = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 9)) {
            val o = arr.getJSONObject(i)
            if (o.optString("host") != host) newArr.put(o)
        }
        prefs.edit().putString(KEY_HISTORY, newArr.toString()).apply()
    }
}

class HistoryAdapter(
    private val items: List<Triple<String, Int, String>>,
    private val onClick: (String, Int, String) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvHost: TextView = v.findViewById(android.R.id.text1)
        val tvPort: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        v.setBackgroundResource(android.R.drawable.list_selector_background)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (host, port, pass) = items[pos]
        h.tvHost.text = host
        h.tvHost.setTextColor(0xFFFFFFFF.toInt())
        h.tvPort.text = "Port: $port"
        h.tvPort.setTextColor(0xFF00D4FF.toInt())
        h.itemView.setOnClickListener { onClick(host, port, pass) }
    }

    override fun getItemCount() = items.size
}
