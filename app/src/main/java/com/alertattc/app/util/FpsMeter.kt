package com.alertattc.app.util

/** Fps medido por janela deslizante de tempo real (nao media desde o inicio). */
class FpsMeter(private val windowMs: Long = 1000L) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tick(): Double {
        val now = System.currentTimeMillis()
        timestamps.addLast(now)
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
        if (timestamps.size < 2) return 0.0
        val span = (timestamps.last() - timestamps.first()).coerceAtLeast(1)
        return (timestamps.size - 1) * 1000.0 / span
    }
}
