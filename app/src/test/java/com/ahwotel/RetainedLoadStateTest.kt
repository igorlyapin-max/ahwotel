package com.ahwotel

import org.junit.Assert.*
import org.junit.Test

class RetainedLoadStateTest {
    @Test fun refreshAndFailureRetainTheLastCompletedSnapshot() {
        var state=RetainedLoadState<String,List<Int>>()
        state=loadStarted(state,"first")
        assertTrue(state.loading);assertNull(state.snapshot)
        state=loadSucceeded(state,"first",listOf(1,2))
        assertFalse(state.loading);assertEquals(listOf(1,2),state.snapshot!!.value)

        state=loadStarted(state,"second")
        assertTrue(state.loading);assertEquals("first",state.snapshot!!.request)
        state=loadFailed(state)
        assertTrue(state.failed);assertEquals("second",state.failedRequest)
        assertFalse(state.isCurrent("second"));assertEquals(listOf(1,2),state.snapshot!!.value)

        state=loadStarted(state,"third")
        state=loadSucceeded(state,"third",emptyList())
        assertFalse(state.failed);assertNull(state.failedRequest);assertTrue(state.isCurrent("third"))
        assertTrue(state.snapshot!!.value.isEmpty())
    }

    @Test fun aNewRequestClearsOnlyTheFailureAndKeepsThePreviousSnapshot() {
        var state=loadSucceeded(RetainedLoadState<String,Int>(),"first",1)
        state=loadStarted(loadFailed(loadStarted(state,"second")),"third")
        assertFalse(state.failed)
        assertTrue(state.loading)
        assertEquals("third",state.pending)
        assertEquals(1,state.snapshot?.value)
        assertFalse(state.isCurrent("third"))
    }
}
