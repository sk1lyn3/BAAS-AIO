package moe.baas.aio

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import java.util.zip.ZipInputStream

/** Deterministic real-game fixture rendered on Companion's display for capture tests. */
class RecognitionProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val bitmap = ZipInputStream(assets.open("baas-source.zip")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "fixtures/shop.png") entry = zip.nextEntry
            check(entry != null) { "Missing recognition fixture" }
            checkNotNull(BitmapFactory.decodeStream(zip))
        }
        setContentView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        })
    }
}
