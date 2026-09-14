package com.ahwotel

import org.junit.Assert.*
import org.junit.Test

class OtlpResponseTest {
    @Test fun collectorEmptyPartialBlockAndZeroRejectedWarningAreSuccess() {
        assertEquals(200,OtlpResponse.code(byteArrayOf()))
        assertEquals(200,OtlpResponse.code(byteArrayOf(10,0)))
        assertEquals(200,OtlpResponse.code(byteArrayOf(10,5,8,0,18,1,65)))
        assertEquals(299,OtlpResponse.code(byteArrayOf(10,2,8,1)))
    }
    @Test fun invalidAndOversizedResponsesNeverCountAsDelivered() {
        for (bytes in listOf(byteArrayOf(10),byteArrayOf(10,2,8),byteArrayOf(0),byteArrayOf(10,1,8),
            byteArrayOf(10,2,10,0),ByteArray(65537))) assertEquals(461,OtlpResponse.code(bytes))
        // Unknown proto3 fields may be added without changing the known success result.
        assertEquals(200,OtlpResponse.code(byteArrayOf(18,1,65,10,0)))
    }
}
