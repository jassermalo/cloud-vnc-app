package com.cloudvnc.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec

data class RfbConfig(val host: String, val port: Int, val password: String = "")

interface RfbListener {
    fun onConnected(width: Int, height: Int)
    fun onBitmapUpdated(x: Int, y: Int, w: Int, h: Int)
    fun onDisconnected(reason: String)
}

class RfbClient(private val config: RfbConfig, private val listener: RfbListener) {

    var bitmap: Bitmap? = null
        private set

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var running = false
    private var bitsPerPixel = 32
    private var bytesPerPixel = 4
    private var redShift = 16; private var greenShift = 8; private var blueShift = 0

    fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(config.host, config.port), 10_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), 131_072))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65_536))
        handshake()
        running = true
        receiveLoop()
    }

    fun disconnect() {
        running = false
        runCatching { socket.close() }
    }

    private fun handshake() {
        // ── Protocol version ──────────────────────────────────────────────
        val serverVer = ByteArray(12); input.readFully(serverVer)
        output.write("RFB 003.008\n".toByteArray()); output.flush()

        // ── Security types ─────────────────────────────────────────────────
        val numTypes = input.readUnsignedByte()
        if (numTypes == 0) {
            val len = input.readInt(); val msg = ByteArray(len); input.readFully(msg)
            throw IOException("Server error: ${String(msg)}")
        }
        val types = ByteArray(numTypes); input.readFully(types)
        val chosen = when {
            1.toByte() in types -> 1
            2.toByte() in types -> 2
            else -> throw IOException("No supported security type")
        }
        output.write(chosen); output.flush()

        // ── Auth ───────────────────────────────────────────────────────────
        if (chosen == 2) {
            val challenge = ByteArray(16); input.readFully(challenge)
            output.write(desEncrypt(challenge, config.password)); output.flush()
        }

        // ── Security result ────────────────────────────────────────────────
        val result = input.readInt()
        if (result != 0) {
            val len = input.readInt(); val msg = ByteArray(len); input.readFully(msg)
            throw IOException("Auth failed: ${String(msg)}")
        }

        // ── ClientInit (shared) ────────────────────────────────────────────
        output.write(1); output.flush()

        // ── ServerInit ─────────────────────────────────────────────────────
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        val pixFmt = ByteArray(16); input.readFully(pixFmt)
        bitsPerPixel = pixFmt[0].toInt() and 0xFF
        bytesPerPixel = bitsPerPixel / 8
        val nameLen = input.readInt(); val name = ByteArray(nameLen); input.readFully(name)

        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        listener.onConnected(width, height)

        // ── SetPixelFormat: 32-bit BGRA little-endian ──────────────────────
        output.write(byteArrayOf(0, 0, 0, 0))          // type + 3 padding
        output.write(byteArrayOf(32, 24, 0, 1))        // bpp, depth, big-endian=0, true-colour=1
        writeUShort(255); writeUShort(255); writeUShort(255) // RGB maxima
        output.write(byteArrayOf(16, 8, 0))            // red/green/blue shift
        output.write(byteArrayOf(0, 0, 0))             // padding
        output.flush()
        redShift = 16; greenShift = 8; blueShift = 0; bytesPerPixel = 4

        // ── SetEncodings ───────────────────────────────────────────────────
        output.write(2); output.write(0)               // type + padding
        writeUShort(2)                                 // 2 encodings
        writeInt(0)                                    // Raw
        writeInt(1)                                    // CopyRect
        output.flush()

        requestUpdate(false)
    }

    private fun receiveLoop() {
        while (running) {
            val msgType = input.readUnsignedByte()
            when (msgType) {
                0 -> handleFramebufferUpdate()
                2 -> { /* Bell */ }
                3 -> { val len = input.readInt(); input.skipBytes(len + 3) } // ServerCutText
                else -> { /* skip */ }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        input.readUnsignedByte() // padding
        val numRects = input.readUnsignedShort()
        val bm = bitmap ?: return
        repeat(numRects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val enc = input.readInt()
            when (enc) {
                0 -> decodeRaw(bm, x, y, w, h)
                1 -> decodeCopyRect(bm, x, y, w, h)
                else -> { /* unsupported, skip */ }
            }
            listener.onBitmapUpdated(x, y, w, h)
        }
        requestUpdate(true)
    }

    private fun decodeRaw(bm: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val total = w * h
        val raw = ByteArray(total * 4); input.readFully(raw)
        val pixels = IntArray(total)
        for (i in 0 until total) {
            val off = i * 4
            val b = raw[off].toInt() and 0xFF
            val g = raw[off + 1].toInt() and 0xFF
            val r = raw[off + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        synchronized(bm) { bm.setPixels(pixels, 0, w, x, y, w, h) }
    }

    private fun decodeCopyRect(bm: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        val pixels = IntArray(w * h)
        synchronized(bm) {
            bm.getPixels(pixels, 0, w, srcX, srcY, w, h)
            bm.setPixels(pixels, 0, w, x, y, w, h)
        }
    }

    fun requestUpdate(incremental: Boolean) {
        val bm = bitmap ?: return
        output.write(3)                          // FramebufferUpdateRequest
        output.write(if (incremental) 1 else 0)
        writeUShort(0); writeUShort(0)           // x, y
        writeUShort(bm.width); writeUShort(bm.height)
        output.flush()
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        output.write(5)
        output.write(buttonMask)
        writeUShort(x); writeUShort(y)
        output.flush()
    }

    fun sendKeyEvent(key: Long, down: Boolean) {
        output.write(4)
        output.write(if (down) 1 else 0)
        output.write(0); output.write(0)
        output.writeInt(key.toInt())
        output.flush()
    }

    private fun writeUShort(v: Int) { output.write((v shr 8) and 0xFF); output.write(v and 0xFF) }
    private fun writeInt(v: Int) { output.writeInt(v) }

    private fun desEncrypt(challenge: ByteArray, password: String): ByteArray {
        val key = ByteArray(8)
        for (i in 0..7) if (i < password.length) key[i] = reverseBits(password[i].code.toByte())
        val ks = DESKeySpec(key)
        val kf = SecretKeyFactory.getInstance("DES")
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kf.generateSecret(ks))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF; var r = 0
        repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        return r.toByte()
    }
}
