package moe.baas.aio

import android.content.Context

/** Each batch owns a fresh OCR process; managed idle keeps no OCR service bound. */
class WorkflowOcr(private val context: Context) : AutoCloseable {
    private var session: OcrSession? = null
    fun start(projectDir: String): String {
        check(session == null)
        val next = OcrSession(context)
        session = next
        return next.start(projectDir)
    }
    fun stop() {
        val current = checkNotNull(session)
        try { current.stop() } finally { session = null }
    }
    override fun close() { session?.close(); session = null }
}
