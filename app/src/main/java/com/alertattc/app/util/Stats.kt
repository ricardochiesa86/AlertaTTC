package com.alertattc.app.util

/** Mediana simples, igual a funcao `mediana` do script Python original. */
fun median(values: List<Float>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2].toDouble()
    else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * Regressao linear simples sobre pontos (x, y): retorna d(y)/d(x).
 * Porta direta de `inclinacao` do script Python. x aqui e sempre tempo em
 * segundos, entao o resultado ja sai em unidade/segundo sem conversao.
 */
fun slope(pts: List<Pair<Double, Double>>): Double? {
    val n = pts.size
    if (n < 8) return null
    val mx = pts.sumOf { it.first } / n
    val my = pts.sumOf { it.second } / n
    val den = pts.sumOf { (it.first - mx) * (it.first - mx) }
    if (den == 0.0) return null
    return pts.sumOf { (it.first - mx) * (it.second - my) } / den
}

/**
 * Deque com tamanho maximo mutavel em tempo real. Usado para as janelas de
 * suavizacao e derivada, cujo comprimento em frames muda conforme o fps
 * medido ao vivo (ver TtcEngine.updateWindowSizes).
 */
class FixedWindowDeque<T>(initialMaxSize: Int) {
    var maxSize: Int = initialMaxSize
        set(value) {
            field = value.coerceAtLeast(1)
            trim()
        }
    private val data = ArrayDeque<T>()

    fun add(item: T) {
        data.addLast(item)
        trim()
    }

    private fun trim() {
        while (data.size > maxSize) data.removeFirst()
    }

    fun clear() = data.clear()
    fun toList(): List<T> = data.toList()
    val size: Int get() = data.size
}
