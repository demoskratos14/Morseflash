package com.aventure.morseflash

import kotlin.math.abs
import kotlin.math.ln

/**
 * Transforme un flux de mesures de luminosité (une par image caméra) en texte morse.
 * La vitesse (unité de temps) est estimée automatiquement ; une séquence de calibrage
 * en début d'émission est reconnue si elle est présente, mais n'est jamais obligatoire.
 * Non thread-safe : l'appelant synchronise.
 */
class MorseDecoder(private val nominalUnitMs: Long = MorseCode.TRANSMIT_UNIT_MS) {

    data class Result(
        val morse: String = "",
        val text: String = "",
        val pending: String = "",
        val unitMs: Int = 0,
        val calibrated: Boolean = false
    )

    data class Status(val level: Float, val lampOn: Boolean, val hasSignal: Boolean)

    private class Seg(val on: Boolean, val ms: Long)

    private val segs = ArrayList<Seg>()
    private var initialized = false
    private var lo = 0f
    private var hi = 0f
    private var isOn = false
    private var started = false
    private var lastChangeMs = 0L
    private var lastSampleMs = 0L
    private var level = 0f
    private var hasSignal = false

    fun reset() {
        segs.clear()
        initialized = false
        lo = 0f
        hi = 0f
        isOn = false
        started = false
        lastChangeMs = 0L
        lastSampleMs = 0L
        level = 0f
        hasSignal = false
    }

    fun status() = Status(level, isOn, hasSignal)

    /** Ajoute une mesure de luminosité (0..255) prise à l'instant [timeMs]. */
    fun addSample(timeMs: Long, lum: Float) {
        lastSampleMs = timeMs
        level = lum
        if (!initialized) {
            lo = lum
            hi = lum
            initialized = true
            lastChangeMs = timeMs
        }
        // Bornes basse/haute adaptatives (suivent lentement les changements de lumière ambiante)
        lo = if (lum < lo) lum else lo + (lum - lo) * DECAY
        hi = if (lum > hi) lum else hi - (hi - lum) * DECAY

        val range = hi - lo
        hasSignal = range >= MIN_CONTRAST

        // Seuil avec hystérésis pour éviter les oscillations
        val newOn = when {
            !hasSignal -> false
            lum > lo + range * 0.6f -> true
            lum < lo + range * 0.4f -> false
            else -> isOn
        }

        if (newOn != isOn) {
            if (started) segs.add(Seg(isOn, timeMs - lastChangeMs))
            else if (newOn) started = true
            isOn = newOn
            lastChangeMs = timeMs
        }
    }

    /** Fusionne les parasites (quelques dizaines de ms, moins de 2 images) avec leurs voisins. */
    private fun clean(input: List<Seg>): MutableList<Seg> {
        val out = ArrayList<Seg>()
        for (s in input) {
            if (out.isEmpty()) {
                if (s.on && s.ms >= GLITCH_MS) out.add(s)
                continue
            }
            val last = out[out.size - 1]
            if (s.ms < GLITCH_MS || s.on == last.on) {
                out[out.size - 1] = Seg(last.on, last.ms + s.ms)
            } else {
                out.add(s)
            }
        }
        return out
    }

    private fun segError(on: Boolean, ms: Float, u: Float): Float {
        val opts = if (on) ON_MULTS else OFF_MULTS
        var best = Float.MAX_VALUE
        for (m in opts) {
            val e = abs(ln(ms / (m * u)))
            if (e < best) best = e
        }
        return if (best > 0.7f) 0.7f else best
    }

    private fun bestMultiple(on: Boolean, ms: Float, u: Float): Float {
        val opts = if (on) ON_MULTS else OFF_MULTS
        var best = 1f
        var bestErr = Float.MAX_VALUE
        for (m in opts) {
            val e = abs(ln(ms / (m * u)))
            if (e < bestErr) {
                bestErr = e
                best = m
            }
        }
        return best
    }

    /**
     * Estime l'unité de temps sans calibrage : on teste de nombreuses vitesses et on garde celle
     * pour laquelle toutes les durées observées sont proches de 1, 3 (ou 7) unités.
     * En cas d'ambiguïté (message très court), la vitesse nominale sert d'arbitre.
     */
    private fun estimateUnit(list: List<Seg>): Float {
        val nominal = nominalUnitMs.toFloat()
        if (list.isEmpty()) return nominal

        var bestU = nominal
        var bestScore = Float.MAX_VALUE
        var u = MIN_UNIT_MS
        while (u <= MAX_UNIT_MS) {
            var score = 0f
            for (s in list) score += segError(s.on, s.ms.toFloat(), u)
            score += PRIOR * abs(ln(u / nominal))
            if (score < bestScore) {
                bestScore = score
                bestU = u
            }
            u *= 1.03f
        }

        // Affinage : moyenne sur les segments bien ajustés
        var sum = 0f
        var cnt = 0
        for (s in list) {
            val m = bestMultiple(s.on, s.ms.toFloat(), bestU)
            val ratio = s.ms.toFloat() / (m * bestU)
            if (abs(ln(ratio)) < 0.25f) {
                sum += s.ms.toFloat() / m
                cnt++
            }
        }
        return if (cnt > 0) sum / cnt else bestU
    }

    /**
     * Reconnaît la séquence de calibrage optionnelle "-.-.-" (5 impulsions, pauses d'une unité,
     * suivies d'une longue pause). Renvoie l'unité mesurée, ou 0 si elle n'est pas présente.
     */
    private fun detectPreamble(c: List<Seg>): Float {
        if (c.size < 10) return 0f
        for (i in 0 until 9) {
            if (c[i].on != (i % 2 == 0)) return 0f
        }
        val dashes = floatArrayOf(c[0].ms.toFloat(), c[4].ms.toFloat(), c[8].ms.toFloat())
        val dots = floatArrayOf(c[2].ms.toFloat(), c[6].ms.toFloat())
        val dash = (dashes[0] + dashes[1] + dashes[2]) / 3f
        val dot = (dots[0] + dots[1]) / 2f
        if (dot <= 0f) return 0f

        val ratio = dash / dot
        if (ratio < 2.0f || ratio > 4.5f) return 0f
        for (d in dashes) if (d < dash * 0.65f || d > dash * 1.35f) return 0f
        for (d in dots) if (d < dot * 0.65f || d > dot * 1.35f) return 0f

        var offSum = 0f
        for (i in intArrayOf(1, 3, 5, 7)) {
            val g = c[i].ms.toFloat()
            if (g < dot * 0.5f || g > dot * 1.8f) return 0f
            offSum += g
        }
        if (c[9].ms.toFloat() < dot * 2.0f) return 0f

        val onSum = dashes[0] + dashes[1] + dashes[2] + dots[0] + dots[1]
        val unit = (onSum / 11f + offSum / 4f) / 2f
        return if (unit in MIN_UNIT_MS..MAX_UNIT_MS) unit else 0f
    }

    fun result(): Result {
        if (!started) return Result()

        val cleaned = clean(segs)
        if (cleaned.isEmpty()) return Result()

        // Pause en cours (lampe éteinte depuis la dernière impulsion)
        if (!isOn) {
            val trailing = lastSampleMs - lastChangeMs
            val last = cleaned[cleaned.size - 1]
            if (last.on) cleaned.add(Seg(false, trailing))
            else cleaned[cleaned.size - 1] = Seg(false, last.ms + trailing)
        }

        // 1) Calibrage optionnel ; 2) sinon estimation automatique de la vitesse
        val pre = detectPreamble(cleaned)
        val calibrated = pre > 0f
        val u: Float
        val startIdx: Int
        if (calibrated) {
            u = pre
            startIdx = 9
        } else {
            // La dernière pause (en cours) est incomplète : on ne s'en sert pas pour l'estimation
            val fitList = if (!isOn) cleaned.dropLast(1) else cleaned
            u = estimateUnit(fitList)
            startIdx = 0
        }

        val words = ArrayList<MutableList<String>>()
        val cur = StringBuilder()

        fun flushLetter() {
            if (cur.isNotEmpty()) {
                if (words.isEmpty()) words.add(ArrayList())
                words[words.size - 1].add(cur.toString())
                cur.setLength(0)
            }
        }

        for (i in startIdx until cleaned.size) {
            val s = cleaned[i]
            if (s.on) {
                cur.append(if (s.ms < 2f * u) '.' else '-')
            } else if (s.ms >= 5f * u) {
                flushLetter()
                if (words.isNotEmpty() && words[words.size - 1].isNotEmpty()) {
                    words.add(ArrayList())
                }
            } else if (s.ms >= 2f * u) {
                flushLetter()
            }
        }
        val pending = cur.toString()

        val real = words.filter { it.isNotEmpty() }
        val morse = real.joinToString(" / ") { it.joinToString(" ") }
        val text = real.joinToString(" ") { word ->
            word.map { MorseCode.decodeLetter(it) ?: '?' }.joinToString("")
        }
        return Result(morse, text, pending, u.toInt(), calibrated)
    }

    companion object {
        private const val DECAY = 0.003f
        private const val MIN_CONTRAST = 35f
        private const val GLITCH_MS = 55L
        private const val MIN_UNIT_MS = 80f
        private const val MAX_UNIT_MS = 1500f
        private const val PRIOR = 0.4f
        private val ON_MULTS = floatArrayOf(1f, 3f)
        private val OFF_MULTS = floatArrayOf(1f, 3f, 7f)
    }
}
