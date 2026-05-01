package com.ufo.galaxy.runtime

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NativeInferenceLoader].
 *
 * These tests run on the JVM where `libllama.so` and `libncnn.so` are not present,
 * so [NativeInferenceLoader.loadAll] is expected to complete with both libraries
 * marked unavailable. The tests verify:
 *
 * 1. The loader is callable without crashing even when native libraries are absent.
 * 2. Availability flags are false in the JVM test environment.
 * 3. Calling [loadAll] multiple times returns the cached result.
 * 4. The [LoadResult] computed properties behave correctly.
 * 5. The loader correctly indicates when at least one or both runtimes are available.
 */
class NativeInferenceLoaderTest {

    @Before
    fun setUp() {
        NativeInferenceLoader.resetForTesting()
    }

    @After
    fun tearDown() {
        NativeInferenceLoader.resetForTesting()
    }

    // ── 1. No crash on missing libraries ─────────────────────────────────────

    @Test
    fun `loadAll completes without throwing when native libraries are absent`() {
        // In the JVM test environment, libllama.so and libncnn.so are absent.
        // loadAll() must catch UnsatisfiedLinkError and not propagate it.
        val result = NativeInferenceLoader.loadAll()
        assertNotNull("loadAll must return a non-null LoadResult", result)
    }

    // ── 2. Availability flags ─────────────────────────────────────────────────

    @Test
    fun `isLlamaCppAvailable returns false in JVM test environment`() {
        NativeInferenceLoader.loadAll()
        assertFalse(
            "libllama.so is absent in JVM tests — isLlamaCppAvailable must be false",
            NativeInferenceLoader.isLlamaCppAvailable()
        )
    }

    @Test
    fun `isNcnnAvailable returns false in JVM test environment`() {
        NativeInferenceLoader.loadAll()
        assertFalse(
            "libncnn.so is absent in JVM tests — isNcnnAvailable must be false",
            NativeInferenceLoader.isNcnnAvailable()
        )
    }

    // ── 3. Idempotency ────────────────────────────────────────────────────────

    @Test
    fun `loadAll is idempotent — second call returns same result without re-loading`() {
        val first = NativeInferenceLoader.loadAll()
        val second = NativeInferenceLoader.loadAll()
        assertEquals("llamaCppAvailable must be stable across multiple loadAll calls",
            first.llamaCppAvailable, second.llamaCppAvailable)
        assertEquals("ncnnAvailable must be stable across multiple loadAll calls",
            first.ncnnAvailable, second.ncnnAvailable)
    }

    @Test
    fun `isLlamaCppAvailable is consistent before and after second loadAll`() {
        NativeInferenceLoader.loadAll()
        val before = NativeInferenceLoader.isLlamaCppAvailable()
        NativeInferenceLoader.loadAll()
        val after = NativeInferenceLoader.isLlamaCppAvailable()
        assertEquals("isLlamaCppAvailable must not change between loadAll calls", before, after)
    }

    // ── 4. LoadResult computed properties ────────────────────────────────────

    @Test
    fun `LoadResult anyAvailable is false when both libraries are absent`() {
        val result = NativeInferenceLoader.loadAll()
        assertFalse(
            "anyAvailable must be false when both llamaCpp and ncnn are unavailable",
            result.anyAvailable
        )
    }

    @Test
    fun `LoadResult fullyAvailable is false when both libraries are absent`() {
        val result = NativeInferenceLoader.loadAll()
        assertFalse(
            "fullyAvailable must be false when both libraries are unavailable",
            result.fullyAvailable
        )
    }

    @Test
    fun `LoadResult anyAvailable is true when llamaCpp is available`() {
        val result = NativeInferenceLoader.LoadResult(llamaCppAvailable = true, ncnnAvailable = false)
        assertTrue("anyAvailable must be true when llamaCpp is available", result.anyAvailable)
    }

    @Test
    fun `LoadResult anyAvailable is true when ncnn is available`() {
        val result = NativeInferenceLoader.LoadResult(llamaCppAvailable = false, ncnnAvailable = true)
        assertTrue("anyAvailable must be true when ncnn is available", result.anyAvailable)
    }

    @Test
    fun `LoadResult fullyAvailable is true only when both libraries are available`() {
        val both = NativeInferenceLoader.LoadResult(llamaCppAvailable = true, ncnnAvailable = true)
        assertTrue("fullyAvailable must be true when both are available", both.fullyAvailable)

        val onlyLlama = NativeInferenceLoader.LoadResult(llamaCppAvailable = true, ncnnAvailable = false)
        assertFalse("fullyAvailable must be false with only llama.cpp", onlyLlama.fullyAvailable)

        val onlyNcnn = NativeInferenceLoader.LoadResult(llamaCppAvailable = false, ncnnAvailable = true)
        assertFalse("fullyAvailable must be false with only ncnn", onlyNcnn.fullyAvailable)
    }

    // ── 5. Library name constants ─────────────────────────────────────────────

    @Test
    fun `LIB_LLAMA constant is the expected library name`() {
        assertEquals("llama", NativeInferenceLoader.LIB_LLAMA)
    }

    @Test
    fun `LIB_NCNN constant is the expected library name`() {
        assertEquals("ncnn", NativeInferenceLoader.LIB_NCNN)
    }
}
