package moe.baas.aio.companion;
import android.view.Surface;
import android.view.MotionEvent;

interface ICompanionService {
    String ping();
    int startVirtualDisplay(int width, int height, int dpi);
    void stopVirtualDisplay();
    int getDisplayId();
    String getStatus();
    boolean launchApp(String packageName, String activityName);
    boolean click(int x, int y);
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);
    boolean keyEvent(int keyCode);
    String capturePng(String path);
    int cleanupStaleServices();
    String getStateJson();
    boolean wakeDevice();
    boolean setPhysicalScreenOff(boolean off);
    boolean pinch(int cx, int cy, int spreadStart, int spreadEnd, int durationMs);
    void setPreviewSurface(in Surface surface, IBinder owner);
    boolean touch(in MotionEvent event);
}
