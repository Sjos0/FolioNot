package com.sjos.folionote

import com.sjos.folionote.engine.context.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun tokenEstimator_scalesWithTextLength() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertTrue(TokenEstimator.estimateTokens("FolioNote local-first knowledge base") >= 5)
    }
}
