package com.example.app_clisa

import com.example.app_clisa.data.ProductSearch
import org.junit.Assert.*
import org.junit.Test

class ProductSearchTest {
    private val name = "Parlante Creative Muvo Flex Auracast 10W / Mic / Bt 5.3"
    @Test fun findsWordsAndPartialTextAnywhereIgnoringCase() {
        listOf("Parlante", "creative", "MUVO", "uvo", "10W", "Creative Muvo").forEach {
            assertTrue(it, ProductSearch.matches(name, "06MF172500001", it))
        }
    }
    @Test fun requiresAllWordsAndDoesNotInventBluetoothSynonyms() {
        assertFalse(ProductSearch.matches(name, "06MF172500001", "Creative Samsung"))
        assertFalse(ProductSearch.matches(name, "06MF172500001", "Bluetooth"))
        assertTrue(ProductSearch.matches("Parlante Bluetooth", "ABC", "bluetooth"))
    }
    @Test fun normalizesAccentsAndExtraWhitespace() {
        assertEquals("audifonos creative", ProductSearch.normalize("  AUDÍFONOS \n  Creative  "))
        assertTrue(ProductSearch.matches(name, "CODE", "  Creative   Muvo "))
    }
    @Test fun codeSearchSupportsInternalFragments() {
        assertTrue(ProductSearch.matches(name, "06MF172500001", "mf1725"))
    }
    @Test fun everyAcceptedMatchHasTheQueriedTrigramInItsIndex() {
        val index = ProductSearch.grams(name) + ProductSearch.grams("06MF172500001")
        listOf("creative muvo", "10W", "Parlante", "MF1725", "creative 10w").forEach {
            assertTrue(it, index.contains(ProductSearch.anchor(it)))
        }
    }
    @Test fun shortWordsCanAccompanyAnIndexableWord() {
        assertFalse(ProductSearch.eligible("bt 2"))
        assertTrue(ProductSearch.eligible("Muvo bt"))
        assertTrue(ProductSearch.matches(name, "CODE", "Muvo bt"))
    }
}
