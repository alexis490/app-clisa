package com.example.app_clisa.data

import java.text.Normalizer
import java.util.Locale

/** Same normalization and trigrams as tools/index-product-search.mjs. */
object ProductSearch {
    const val VERSION = 1L
    const val MIN_LENGTH = 3
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    fun words(query: String) = normalize(query).split(' ').filter { it.isNotEmpty() }
    fun eligible(query: String) = words(query).any { it.length >= MIN_LENGTH }
    fun grams(text: String): Set<String> = normalize(text).windowed(MIN_LENGTH).toSet()
    fun anchor(query: String): String {
        val longest = words(query).filter { it.length >= MIN_LENGTH }
            .maxWithOrNull(compareBy<String> { word -> word.any { it.isDigit() } }.thenBy { it.length })
            ?: error("Escribe al menos una palabra de 3 caracteres.")
        return longest.take(MIN_LENGTH)
    }
    fun matches(name: String, code: String, query: String): Boolean {
        val terms = words(query)
        if (terms.isEmpty()) return false
        val normalizedName = normalize(name)
        val normalizedCode = normalize(code)
        return terms.all { normalizedName.contains(it) } || terms.all { normalizedCode.contains(it) }
    }
}
