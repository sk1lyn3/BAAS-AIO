package moe.baas.aio

import android.content.Context

/** Release builds never start display observers or capture diagnostic frames. */
@Suppress("UNUSED_PARAMETER")
class DisplayDiagnostics(context: Context, source: String, broker: () -> ISocketBroker?, details: () -> String = { "" }) {
    fun start() = Unit
    fun stop() = Unit
}
