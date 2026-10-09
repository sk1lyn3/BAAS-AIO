package moe.baas.aio

import java.io.File

/** Release builds keep the shared hooks without diagnostic logging or disk writes. */
@Suppress("UNUSED_PARAMETER")
object DebugTrace {
    fun attach(directory: File) = Unit
    fun log(message: String, level: Int = 0) = Unit
    fun persist(level: Int, message: String) = Unit
}
