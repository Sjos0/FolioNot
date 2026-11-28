package com.sjos.folionote

import com.sjos.folionote.core.device.DevicePolicy
import com.sjos.folionote.engine.indexing.EmbeddingRequestPolicy
import com.sjos.folionote.engine.indexing.EmbeddingTaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowResourcePolicyTests {

    @Test
    fun twoGigabyteDeviceIsLowRamAndUsesSmallOcrPages() {
        assertTrue(DevicePolicy.isLowRam(2048))
        assertEquals(1100, DevicePolicy.ocrMaxDimensionPx(2048))
    }

    @Test
    fun highRamDeviceKeepsStandardOcrPages() {
        assertFalse(DevicePolicy.isLowRam(8192))
        assertEquals(1500, DevicePolicy.ocrMaxDimensionPx(8192))
    }

    @Test
    fun nimQueryUsesQueryInputType() {
        assertEquals(
            "query",
            EmbeddingRequestPolicy.inputTypeFor("https://integrate.api.nvidia.com/v1", "nvidia/nv-embedqa-e5-v5", EmbeddingTaskType.QUERY)
        )
    }

    @Test
    fun nimDocumentUsesPassageInputType() {
        assertEquals(
            "passage",
            EmbeddingRequestPolicy.inputTypeFor("https://integrate.api.nvidia.com/v1", "nvidia/nv-embedqa-e5-v5", EmbeddingTaskType.DOCUMENT)
        )
    }

    @Test
    fun openAiDoesNotReceiveInputType() {
        assertNull(EmbeddingRequestPolicy.inputTypeFor("https://api.openai.com/v1", "text-embedding-3-small", EmbeddingTaskType.DOCUMENT))
    }

    @Test
    fun geminiOpenAiCompatDoesNotReceiveInputType() {
        assertNull(
            EmbeddingRequestPolicy.inputTypeFor("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-embedding-2", EmbeddingTaskType.QUERY)
        )
    }
}
