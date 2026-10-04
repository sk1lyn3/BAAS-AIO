package moe.baas.aio

/** Display order only; the upstream scheduler still owns task execution. */
internal object TaskOrder {
    fun compare(tickA: Double, priorityA: Double, tickB: Double, priorityB: Double,
                now: Double, byTime: Boolean): Int {
        if (!byTime) return priorityA.compareTo(priorityB)
        val dueA = tickA <= now
        val dueB = tickB <= now
        if (dueA != dueB) return if (dueA) -1 else 1
        if (dueA) return priorityA.compareTo(priorityB)
        val time = tickA.compareTo(tickB)
        return if (time != 0) time else priorityA.compareTo(priorityB)
    }
}
