package com.voiceping.offlinetranscription.util

/** Same rationale as [GrowableFloatArray], for 16-bit PCM decode buffers. */
class GrowableShortArray(initialCapacity: Int = 1024) {
    private var array = ShortArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(value: Short) {
        if (size == array.size) {
            array = array.copyOf(array.size * 2)
        }
        array[size++] = value
    }

    fun toShortArray(): ShortArray = array.copyOf(size)
}
