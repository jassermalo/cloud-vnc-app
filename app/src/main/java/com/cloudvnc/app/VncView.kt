package com.cloudvnc.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.*
import kotlin.math.*

class VncView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    SurfaceView(ctx, attrs), SurfaceHolder.Callback {

    var client: RfbClient? = null
    var onTouchTranslated: ((x: Int, y: Int, mask: Int) -> Unit)? = null

    // Viewport transform
    private var scaleX = 1f; private var scaleY = 1f
    private var panX = 0f; private var panY = 0f
    private var vncW = 0; private var vncH = 0

    // Touch state
    private var lastPointerX = 0f; private var lastPointerY = 0f
    private var btnMask = 0
    private var lastTouchCount = 0
    private val scaleDetector = ScaleGestureDetector(ctx, ScaleListener())
    private val gestureDetector = GestureDetector(ctx, TapListener())

    // Rendering
    private val paint = Paint().apply { isFilterBitmap = true }
    private val dirtyRect = RectF()
    @Volatile private var pendingDraw = false

    init { holder.addCallback(this); isFocusable = true; isFocusableInTouchMode = true }

    fun setVncSize(w: Int, h: Int) {
        vncW = w; vncH = h
        post { fitToScreen() }
    }

    private fun fitToScreen() {
        if (vncW == 0 || vncH == 0 || width == 0 || height == 0) return
        val sx = width.toFloat() / vncW
        val sy = height.toFloat() / vncH
        scaleX = min(sx, sy); scaleY = scaleX
        panX = (width - vncW * scaleX) / 2f
        panY = (height - vncH * scaleY) / 2f
    }

    fun notifyBitmapUpdated() {
        if (!pendingDraw) { pendingDraw = true; post { drawFrame() } }
    }

    private fun drawFrame() {
        pendingDraw = false
        val bm = client?.bitmap ?: return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val m = Matrix().apply { setScale(scaleX, scaleY); postTranslate(panX, panY) }
            canvas.drawBitmap(bm, m, paint)
        } finally { holder.unlockCanvasAndPost(canvas) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPointerX = event.x; lastPointerY = event.y
                lastTouchCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastTouchCount = pointerCount
                if (pointerCount == 2) {
                    // two-finger tap prep: use centroid
                    lastPointerX = (event.getX(0) + event.getX(1)) / 2f
                    lastPointerY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val cx = if (pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else event.x
                    val cy = if (pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else event.y
                    if (pointerCount == 2) {
                        // two-finger pan
                        panX += cx - lastPointerX; panY += cy - lastPointerY
                        clampPan(); post { drawFrame() }
                    } else if (pointerCount == 1 && btnMask and 1 != 0) {
                        // drag with left button held
                        val vx = screenToVncX(cx); val vy = screenToVncY(cy)
                        sendPointer(vx, vy, 1)
                    }
                    lastPointerX = cx; lastPointerY = cy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val vx = screenToVncX(event.x); val vy = screenToVncY(event.y)
                sendPointer(vx, vy, 0); btnMask = 0
                lastTouchCount = 0
            }
        }
        return true
    }

    private fun sendPointer(x: Int, y: Int, mask: Int) {
        val cx = x.coerceIn(0, vncW - 1); val cy = y.coerceIn(0, vncH - 1)
        btnMask = mask; onTouchTranslated?.invoke(cx, cy, mask)
    }

    private fun screenToVncX(sx: Float) = ((sx - panX) / scaleX).toInt()
    private fun screenToVncY(sy: Float) = ((sy - panY) / scaleY).toInt()

    private fun clampPan() {
        val maxPanX = 0f; val minPanX = width - vncW * scaleX
        val maxPanY = 0f; val minPanY = height - vncH * scaleY
        panX = panX.coerceIn(min(minPanX, 0f), max(maxPanX, 0f))
        panY = panY.coerceIn(min(minPanY, 0f), max(maxPanY, 0f))
    }

    override fun surfaceCreated(h: SurfaceHolder) { post { drawFrame() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { fitToScreen(); post { drawFrame() } }
    override fun surfaceDestroyed(h: SurfaceHolder) {}

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            scaleX *= f; scaleY *= f
            scaleX = scaleX.coerceIn(0.3f, 5f); scaleY = scaleY.coerceIn(0.3f, 5f)
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            clampPan(); post { drawFrame() }
            return true
        }
    }

    inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val vx = screenToVncX(e.x); val vy = screenToVncY(e.y)
            sendPointer(vx, vy, 1)   // press
            postDelayed({ sendPointer(vx, vy, 0) }, 80)  // release
            performClick(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            // Long press = right click
            val vx = screenToVncX(e.x); val vy = screenToVncY(e.y)
            sendPointer(vx, vy, 4)   // right button
            postDelayed({ sendPointer(vx, vy, 0) }, 80)
        }
        override fun onDown(e: MotionEvent) = true
    }
}
