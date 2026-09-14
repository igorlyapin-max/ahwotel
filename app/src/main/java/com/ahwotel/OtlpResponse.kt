package com.ahwotel

/** Bounded ExportMetricsServiceResponse decoder. Unknown proto3 fields are skipped.
 * No server error text is logged. Codes 299 and 461 are internal delivery outcomes.
 */
object OtlpResponse {
    const val MAX_BYTES = 65536L
    fun code(bytes: ByteArray): Int = runCatching {
        require(bytes.size <= MAX_BYTES)
        val response = Cursor(bytes)
        var rejected = false
        while (!response.done()) {
            val tag = response.tag()
            if (tag.first == 1) {
                require(tag.second == 2)
                val partial = Cursor(response.bytes())
                while (!partial.done()) {
                    val field = partial.tag()
                    when (field.first) {
                        1 -> { require(field.second == 0); rejected = (partial.uint().also { require(it >= 0) } > 0) || rejected }
                        2 -> { require(field.second == 2); partial.bytes() }
                        else -> partial.skip(field.second)
                    }
                }
            } else response.skip(tag.second)
        }
        if (rejected) 299 else 200
    }.getOrDefault(461)

    private class Cursor(val data: ByteArray) {
        private var position = 0
        fun done() = position == data.size
        fun uint(): Long {
            var result = 0L
            for (i in 0..9) {
                require(position < data.size)
                val b = data[position++].toInt() and 255
                if (i == 9) require(b <= 1)
                result = result or ((b and 127).toLong() shl (i * 7))
                if (b < 128) return result
            }
            error("invalid_varint")
        }
        fun tag(): Pair<Int, Int> {
            val raw = uint()
            require(raw in 8..0xffffffffL)
            return (raw ushr 3).toInt() to (raw and 7).toInt()
        }
        fun bytes(): ByteArray {
            val n = uint()
            require(n >= 0 && n <= data.size - position)
            return data.copyOfRange(position, position + n.toInt()).also { position += n.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> uint()
                1 -> advance(8)
                2 -> bytes()
                5 -> advance(4)
                else -> error("invalid_wire_type")
            }
        }
        private fun advance(n: Int) { require(n <= data.size - position); position += n }
    }
}
