package moe.baas.aio

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Repeatable task target: real BAAS shop fixture plus an OCR-visible tap counter. */
class WorkflowTargetActivity : Activity() {
    private var counter = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val fixture = ZipInputStream(assets.open("baas-source.zip")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "fixtures/shop.png") entry = zip.nextEntry
            check(entry != null) { "Missing workflow fixture" }
            checkNotNull(BitmapFactory.decodeStream(zip))
        }
        setContentView(TargetView(fixture))
    }
    private inner class TargetView(private val fixture: Bitmap) : View(this@WorkflowTargetActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawBitmap(fixture, null, Rect(0, 0, width, height), paint)
            canvas.save(); canvas.scale(width / 1280f, height / 720f)
            paint.color = Color.rgb(16, 24, 32); canvas.drawRect(875f, 115f, 1100f, 205f, paint)
            paint.color = Color.WHITE; paint.textSize = 64f; paint.typeface = Typeface.MONOSPACE
            canvas.drawText(counter.toString(), 895f, 183f, paint)
            paint.color = Color.rgb(40, 80, 130); canvas.drawRect(500f, 590f, 800f, 685f, paint)
            paint.color = Color.WHITE; paint.textSize = 30f
            canvas.drawText("ADVANCE", 575f, 648f, paint)
            canvas.restore()
            postInvalidateDelayed(100) // Keep capture freshness representative of a rendering game.
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val x = event.x * 1280 / width; val y = event.y * 720 / height
                if (x in 500f..800f && y in 590f..685f) {
                    counter++; invalidate()
                    File(filesDir, "workflow-target.json").writeText(JSONObject().put("counter", counter)
                        .put("displayId", display.displayId).toString())
                }
            }
            return true
        }
    }
}
