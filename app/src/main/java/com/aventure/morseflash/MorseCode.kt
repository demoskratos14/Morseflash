package com.aventure.morseflash

import java.text.Normalizer

/** Une étape de clignotement : lampe allumée ou éteinte pendant [ms] millisecondes. */
data class Step(val on: Boolean, val ms: Long)

object MorseCode {

    /**
     * Vitesse d'émission BLOQUÉE : une unité = 300 ms (point 300 ms, trait 900 ms).
     * Valeur choisie pour qu'une caméra à 30 images/s capte chaque signal avec
     * une marge confortable (≈ 9 images par point).
     */
    const val TRANSMIT_UNIT_MS = 300L

    private val table: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.",
        '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.",
        '$' to "...-..-", '@' to ".--.-."
    )

    private val reverse: Map<String, Char> = table.entries.associate { it.value to it.key }

    /** ".-" -> 'A' (null si la séquence est inconnue). */
    fun decodeLetter(code: String): Char? = reverse[code]

    /** Retire les accents (é -> E) et met en majuscules. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase()

    /** Représentation lisible : "SOS" -> "... --- ...". Les mots sont séparés par " / ". */
    fun toMorseString(text: String): String =
        normalize(text).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" / ") { word ->
                word.mapNotNull { table[it] }.joinToString(" ")
            }

    /**
     * Séquence de calibrage optionnelle : "-.-.-" (3-1-3 avec pauses d'une unité) puis une pause de mot.
     * Elle permet au récepteur de déduire la vitesse dès le début, même pour un message d'une seule lettre.
     */
    fun calibrationSteps(unitMs: Long): List<Step> = listOf(
        Step(true, 3 * unitMs), Step(false, unitMs),
        Step(true, unitMs), Step(false, unitMs),
        Step(true, 3 * unitMs), Step(false, unitMs),
        Step(true, unitMs), Step(false, unitMs),
        Step(true, 3 * unitMs), Step(false, 7 * unitMs)
    )

    /**
     * Convertit le texte en suite d'étapes allumé/éteint.
     * Point = 1 unité, trait = 3, pause entre signes = 1, entre lettres = 3, entre mots = 7.
     */
    fun toSteps(text: String, unitMs: Long, calibration: Boolean = false): List<Step> {
        val steps = mutableListOf<Step>()
        val words = normalize(text).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (calibration && words.isNotEmpty()) steps += calibrationSteps(unitMs)

        words.forEachIndexed { wi, word ->
            if (wi > 0) steps += Step(false, 7 * unitMs)
            val letters = word.mapNotNull { table[it] }
            letters.forEachIndexed { li, code ->
                if (li > 0) steps += Step(false, 3 * unitMs)
                code.forEachIndexed { si, symbol ->
                    if (si > 0) steps += Step(false, unitMs)
                    steps += Step(true, if (symbol == '.') unitMs else 3 * unitMs)
                }
            }
        }
        return steps
    }
}
