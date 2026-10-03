package moe.baas.aio;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.MotionEvent;

interface ISocketBroker {
    ParcelFileDescriptor openCompanionSocket();
    String ping();
    String readProbeState();
    void setPreviewSurface(in Surface surface, IBinder owner);
    boolean touch(in MotionEvent event);
    void attachClient(IBinder owner);
    String preparePreview(boolean createIfMissing);
    String launchPreviewGame(String packageName);
}
