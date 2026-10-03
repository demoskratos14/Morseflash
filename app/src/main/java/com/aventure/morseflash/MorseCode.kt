package com.aventure.morseflash

import java.text.Normalizer

/** Une étape de clignotement : lampe allumée ou éteinte pendant [ms] millisecondes. */
data class Step(val on: Boolean, val ms: Long)

object MorseCode {

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
     * Convertit le texte en suite d'étapes allumé/éteint.
     * Point = 1 unité, trait = 3, pause entre signes = 1, entre lettres = 3, entre mots = 7.
     */
    fun toSteps(text: String, unitMs: Long): List<Step> {
        val steps = mutableListOf<Step>()
        val words = normalize(text).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

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
