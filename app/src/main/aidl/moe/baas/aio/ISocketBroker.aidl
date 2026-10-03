package moe.baas.aio;
import android.os.ParcelFileDescriptor;

interface ISocketBroker {
    ParcelFileDescriptor openCompanionSocket();
    String ping();
    String readProbeState();
}
