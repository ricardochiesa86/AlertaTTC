package com.alertattc.app.ttc

import com.alertattc.app.detector.Detection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Um alvo acompanhado entre frames. O `id` e estavel enquanto o alvo nao for perdido. */
data class TrackedTarget(
    val id: Int,
    val detection: Detection,
    val framesSeen: Int,
    val isStatic: Boolean
)

data class TrackingResult(
    val leader: TrackedTarget?,
    /** Ha quantos frames o lider atual e acompanhado. */
    val leaderFramesTracked: Int,
    /** true quando a serie de larguras precisa ser reiniciada: alvo novo. */
    val leaderChanged: Boolean,
    val leaderChangeReason: String?,
    /** Lider sumiu neste frame mas ainda dentro do periodo de tolerancia — nao zerar a serie. */
    val aguardandoReaparecer: Boolean,
    val semLiderMotivo: String?,
    val targets: List<TrackedTarget>,
    val descartadosPorImobilidade: Int,
    /** Alvos que sobraram depois do descarte por imobilidade. */
    val candidatosMoveis: Int,
    val aposFiltroTercoCentral: Int
)

/**
 * Da identidade as deteccoes entre frames. Existe por dois motivos, os
 * dois medidos em campo:
 *
 * 1. Sem identidade, o "lider" era reeleito a cada frame como a maior
 *    caixa do terco central, e pulava entre veiculos diferentes
 *    (larguras observadas: 37, 37, 23, 38, 119, 96, 47, 37...). A serie
 *    de larguras virava ruido, a derivada nunca se firmava e a
 *    confirmacao temporal jamais era satisfeita — o TTC aparecia na tela
 *    mas o alerta nunca soava.
 *
 * 2. Objetos que fazem parte do proprio carro (painel, capo, suporte do
 *    celular, moldura do parabrisa) sao descartados por IMOBILIDADE, nao
 *    por tamanho. Um veiculo real em rota de colisao muda de tamanho e
 *    posicao conforme a distancia varia; o painel fica parado
 *    indefinidamente. O antigo filtro por largura (>70% do quadro) so
 *    pegava o painel por sorte — 477px de 480 — deixaria passar um
 *    suporte menor, e pior: descartava um veiculo prestes a colidir,
 *    que por definicao ocupa quase todo o quadro. Foi removido.
 *
 * Rastrear NAO pode virar teimosia: o lider e mantido por padrao, mas
 * trocado na hora quando outro alvo representa risco maior (ver
 * [FATOR_RISCO_LARGURA]) — o caso do carro que corta na frente. Nessa
 * troca a serie e reiniciada, porque misturar medicoes de dois veiculos
 * na mesma derivada produz um TTC que nao descreve nenhum dos dois.
 */
class TargetTracker {

    companion object {
        /** Custo maximo para casar uma deteccao com um track existente. */
        private const val CUSTO_MAX_MATCH = 0.45f

        /** Frames sem ver o lider antes de eleger outro. Abaixo disso, espera ele reaparecer. */
        private const val TOLERANCIA_PERDA_FRAMES = 5

        /** Track sem match por tantos frames e removido de vez. */
        private const val DESCARTE_TRACK_FRAMES = 15

        /**
         * Troca de lider por risco: outro alvo precisa estar
         * significativamente mais perto. Largura da caixa e a mesma proxy
         * de distancia que o TTC ja usa.
         */
        private const val FATOR_RISCO_LARGURA = 1.30f

        // --- imobilidade (correcao 2) --------------------------------
        /** Janela de observacao, em segundos, para decidir se um alvo esta parado. */
        private const val JANELA_IMOBILIDADE_S = 2.0

        /** Historico minimo antes de rotular como fixo — evita marcar alvo recem-visto. */
        private const val MIN_HISTORICO_S = 1.5

        /** Deslocamento maximo do centro, em fracao da largura do quadro. */
        private const val DESLOC_MAX_FRAC = 0.02f

        /** Variacao maxima da largura, relativa a media. */
        private const val VAR_LARGURA_MAX = 0.12f
    }

    private class Track(val id: Int) {
        var detection: Detection? = null
        var framesSeen = 0
        var framesMissed = 0
        var isStatic = false
        /** (t, centerX, centerY, width) — podado por tempo, nao por contagem de frames. */
        val historico = ArrayDeque<Quad>()
    }

    private class Quad(val t: Double, val cx: Float, val cy: Float, val w: Float)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    private var leaderId: Int? = null
    private var leaderFrames = 0
    private var leaderMissFrames = 0

    fun reset() {
        tracks.clear()
        leaderId = null
        leaderFrames = 0
        leaderMissFrames = 0
    }

    fun update(t: Double, deteccoes: List<Detection>, frameW: Int): TrackingResult {
        associar(t, deteccoes, frameW)

        val visiveis = tracks.filter { it.framesMissed == 0 && it.detection != null }
        val targets = visiveis.map {
            TrackedTarget(it.id, it.detection!!, it.framesSeen, it.isStatic)
        }

        // --- correcao 2: fora os que fazem parte do proprio carro
        val moveis = targets.filter { !it.isStatic }
        val descartadosPorImobilidade = targets.size - moveis.size

        // NAO ha mais filtro por largura maxima. O antigo "descarta caixa >
        // 70% do quadro" existia para remover o painel, tarefa que agora e
        // da imobilidade — e ele tinha um custo inaceitavel: um veiculo
        // prestes a colidir OCUPA mais de 70% do quadro, entao o filtro
        // descartava justamente o alvo no momento de maior risco.
        val centrais = moveis.filter {
            it.detection.centerX in (0.33f * frameW)..(0.67f * frameW)
        }

        return elegerLider(
            candidatos = centrais,
            targets = targets,
            descartadosPorImobilidade = descartadosPorImobilidade,
            candidatosMoveis = moveis.size,
            aposFiltroTercoCentral = centrais.size,
            houveDeteccao = deteccoes.isNotEmpty()
        )
    }

    private fun elegerLider(
        candidatos: List<TrackedTarget>,
        targets: List<TrackedTarget>,
        descartadosPorImobilidade: Int,
        candidatosMoveis: Int,
        aposFiltroTercoCentral: Int,
        houveDeteccao: Boolean
    ): TrackingResult {
        fun resultado(
            leader: TrackedTarget?, changed: Boolean, reason: String?,
            aguardando: Boolean, semLider: String?
        ) = TrackingResult(
            leader, leaderFrames, changed, reason, aguardando, semLider,
            targets, descartadosPorImobilidade, candidatosMoveis, aposFiltroTercoCentral
        )

        val atual = candidatos.firstOrNull { it.id == leaderId }

        if (atual != null) {
            leaderMissFrames = 0
            // Ressalva: rastrear nao pode virar teimosia. Se outro alvo esta
            // significativamente mais perto (carro cortando na frente), ele e
            // o risco relevante agora.
            val rival = candidatos.filter { it.id != atual.id }.maxByOrNull { it.detection.width }
            if (rival != null && rival.detection.width >= FATOR_RISCO_LARGURA * atual.detection.width) {
                leaderId = rival.id
                leaderFrames = 1
                return resultado(
                    rival, changed = true,
                    reason = "risco maior: #${rival.id} largura=%.0fpx vs #${atual.id} %.0fpx"
                        .format(rival.detection.width, atual.detection.width),
                    aguardando = false, semLider = null
                )
            }
            leaderFrames++
            return resultado(atual, changed = false, reason = null, aguardando = false, semLider = null)
        }

        // lider atual nao esta entre os candidatos deste frame
        if (leaderId != null) {
            leaderMissFrames++
            if (leaderMissFrames < TOLERANCIA_PERDA_FRAMES) {
                // Aguarda reaparecer: nao zera a serie por um sumico de 1-2 frames.
                return resultado(
                    null, changed = false, reason = null, aguardando = true,
                    semLider = "lider #$leaderId sumiu ha $leaderMissFrames frame(s), aguardando"
                )
            }
        }

        val novo = candidatos.maxByOrNull { it.detection.width }
        if (novo == null) {
            val motivo = when {
                !houveDeteccao -> "nenhuma deteccao"
                targets.isNotEmpty() && descartadosPorImobilidade == targets.size ->
                    "todas as deteccoes sao objetos fixos do proprio carro"
                aposFiltroTercoCentral == 0 -> "todas fora do terco central"
                else -> "sem candidato"
            }
            leaderId = null
            leaderFrames = 0
            return resultado(null, changed = false, reason = null, aguardando = false, semLider = motivo)
        }

        val anterior = leaderId
        leaderId = novo.id
        leaderFrames = 1
        leaderMissFrames = 0
        return resultado(
            novo, changed = true,
            reason = if (anterior == null) "primeiro lider: #${novo.id}"
                     else "anterior #$anterior perdido, novo #${novo.id}",
            aguardando = false, semLider = null
        )
    }

    /** Casa deteccoes com tracks existentes por proximidade de centro + semelhanca de tamanho. */
    private fun associar(t: Double, deteccoes: List<Detection>, frameW: Int) {
        tracks.forEach { it.detection = null }

        // Pares candidatos por INDICE da deteccao, nao pela instancia:
        // Detection e data class, entao duas caixas com valores iguais
        // seriam o "mesmo" elemento num Set e uma delas sumiria.
        data class Par(val track: Track, val detIdx: Int, val custo: Float)
        val pares = mutableListOf<Par>()
        for (tr in tracks) {
            val ultimo = tr.historico.lastOrNull() ?: continue
            deteccoes.forEachIndexed { idx, d ->
                val dist = hypot(d.centerX - ultimo.cx, d.centerY - ultimo.cy) / frameW
                val difLarg = abs(d.width - ultimo.w) / max(d.width, ultimo.w).coerceAtLeast(1f)
                val custo = dist + difLarg
                if (custo <= CUSTO_MAX_MATCH) pares.add(Par(tr, idx, custo))
            }
        }

        val tracksUsados = mutableSetOf<Int>()
        val detsUsadas = mutableSetOf<Int>()
        for (p in pares.sortedBy { it.custo }) {
            if (p.track.id in tracksUsados || p.detIdx in detsUsadas) continue
            tracksUsados.add(p.track.id)
            detsUsadas.add(p.detIdx)
            val d = deteccoes[p.detIdx]
            p.track.detection = d
            p.track.framesSeen++
            p.track.framesMissed = 0
            registrarHistorico(p.track, t, d, frameW)
        }

        // deteccoes sem dono viram tracks novos
        deteccoes.forEachIndexed { idx, d ->
            if (idx in detsUsadas) return@forEachIndexed
            val tr = Track(nextId++)
            tr.detection = d
            tr.framesSeen = 1
            registrarHistorico(tr, t, d, frameW)
            tracks.add(tr)
        }

        // tracks sem match envelhecem e somem
        for (tr in tracks) {
            if (tr.detection == null) tr.framesMissed++
        }
        tracks.removeAll { it.framesMissed > DESCARTE_TRACK_FRAMES }
    }

    /**
     * Correcao 2: um alvo que ha [JANELA_IMOBILIDADE_S] segundos nao muda
     * de posicao nem de tamanho e parte do proprio carro, qualquer que
     * seja a largura que ocupe.
     */
    private fun registrarHistorico(tr: Track, t: Double, d: Detection, frameW: Int) {
        tr.historico.addLast(Quad(t, d.centerX, d.centerY, d.width))
        while (tr.historico.isNotEmpty() && t - tr.historico.first().t > JANELA_IMOBILIDADE_S) {
            tr.historico.removeFirst()
        }

        val h = tr.historico
        val span = if (h.size >= 2) h.last().t - h.first().t else 0.0
        if (span < MIN_HISTORICO_S || h.size < 4) return

        val cx0 = h.first().cx
        val cy0 = h.first().cy
        val deslocMax = h.maxOf { hypot(it.cx - cx0, it.cy - cy0) } / frameW
        val larguraMedia = h.sumOf { it.w.toDouble() } / h.size
        val varLargura = if (larguraMedia <= 0.0) 1.0
                         else (h.maxOf { it.w } - h.minOf { it.w }) / larguraMedia

        tr.isStatic = deslocMax < DESLOC_MAX_FRAC && varLargura < VAR_LARGURA_MAX
    }
}
