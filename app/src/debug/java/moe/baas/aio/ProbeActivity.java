package moe.baas.aio;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.PopupWindow;
import android.graphics.drawable.ColorDrawable;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Debug-only deterministic target for proving display-bound input and capture. */
public final class ProbeActivity extends Activity {
    private int downs, ups, moves, keys, lastKey;
    private float x, y;
    private long gestureDurationMs;
    private int pointerDowns, pointerUps, maxPointers, cancels;
    private float firstSeparation, lastSeparation;
    private ProbeView view;
    private int popupDismissals;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new ProbeView();
        setContentView(view);
        if (getIntent().getBooleanExtra("dismiss_popup_on_down", false)) {
            view.post(() -> {
                View content = new View(this);
                PopupWindow popup = new PopupWindow(content, 400, 200, true);
                popup.setBackgroundDrawable(new ColorDrawable(Color.BLUE));
                content.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        popup.dismiss();
                        popupDismissals++;
                        writeState();
                    }
                    return true;
                });
                popup.showAtLocation(view, Gravity.CENTER, 0, 0);
            });
        }
    }

    private void writeState() {
        try {
            JSONObject state = new JSONObject().put("displayId", view.getDisplay().getDisplayId())
                    .put("width", view.getWidth()).put("height", view.getHeight())
                    .put("downs", downs).put("ups", ups).put("moves", moves)
                    .put("keys", keys).put("lastKey", lastKey).put("x", x).put("y", y)
                    .put("gestureDurationMs", gestureDurationMs)
                    .put("pointerDowns", pointerDowns).put("pointerUps", pointerUps)
                    .put("popupDismissals", popupDismissals)
                    .put("maxPointers", maxPointers).put("cancels", cancels)
                    .put("firstSeparation", firstSeparation).put("lastSeparation", lastSeparation);
            AtomicFile file = new AtomicFile(new File(getFilesDir(), "probe_state.json"));
            FileOutputStream out = file.startWrite();
            try {
                out.write(state.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (Exception e) { file.failWrite(out); }
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        keys++;
        lastKey = keyCode;
        writeState();
        view.invalidate();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) { return true; }

    private final class ProbeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tick;
        ProbeView() { super(ProbeActivity.this); }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) { writeState(); }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(24, 40, 64));
            paint.setColor(Color.WHITE);
            paint.setTextSize(32);
            canvas.drawText("BAAS native middleware probe", 40, 65, paint);
            canvas.drawText("touch=" + downs + "/" + ups + " move=" + moves + " key=" + keys, 40, 115, paint);
            paint.setColor(Color.rgb((++tick * 17) % 200 + 40, 150, 210));
            canvas.drawRect(40, 160, 200, 260, paint);
            paint.setColor(Color.YELLOW);
            canvas.drawCircle(x, y, 20, paint);
            postInvalidateDelayed(100);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            x = event.getX(); y = event.getY();
            maxPointers = Math.max(maxPointers, event.getPointerCount());
            if (event.getPointerCount() == 2) {
                lastSeparation = Math.abs(event.getX(1) - event.getX(0));
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) firstSeparation = lastSeparation;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downs++; break;
                case MotionEvent.ACTION_POINTER_DOWN: pointerDowns++; break;
                case MotionEvent.ACTION_POINTER_UP: pointerUps++; break;
                case MotionEvent.ACTION_CANCEL: cancels++; break;
                case MotionEvent.ACTION_MOVE: moves++; break;
                case MotionEvent.ACTION_UP:
                    ups++; gestureDurationMs = event.getEventTime() - event.getDownTime(); break;
            }
            writeState();
            invalidate();
            return true;
        }
    }
}
