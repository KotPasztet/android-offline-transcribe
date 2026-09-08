package com.voiceping.offlinetranscription.util

/**
 * A growable buffer of primitive floats, functionally similar to
 * `ArrayList<Float>` but WITHOUT boxing every element as a `java.lang.Float`
 * object.
 *
 * Why this exists: `ArrayList<Float>` looked convenient but is a serious
 * memory bug for anything beyond short clips — each element becomes its own
 * heap object (~16 bytes) plus an 8-byte reference in the backing array, so
 * a 30-minute 16kHz recording (≈29 million samples) needs ~700MB just for
 * boxing overhead, which reliably crashes the app with an OutOfMemoryError.
 * A raw `FloatArray` needs exactly 4 bytes/sample (≈115MB for the same
 * recording) — large but survivable, and the actual number this class uses.
 */
class GrowableFloatArray(initialCapacity: Int = 1024) {
    private var array = FloatArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(value: Float) {
        ensureCapacity(size + 1)
        array[size++] = value
    }

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= array.size) return
        var newCapacity = array.size
        while (newCapacity < minCapacity) newCapacity *= 2
        array = array.copyOf(newCapacity)
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)

    /** Equivalent to `list.subList(from, to).toFloatArray()`. */
    fun copyOfRange(from: Int, to: Int): FloatArray {
        val f = from.coerceIn(0, size)
        val t = to.coerceIn(f, size)
        return array.copyOfRange(f, t)
    }

    /** Drops the first [count] elements, shifting the rest down. */
    fun removeFirst(count: Int) {
        val n = count.coerceIn(0, size)
        if (n == 0) return
        System.arraycopy(array, n, array, 0, size - n)
        size -= n
    }

    fun clear() {
        size = 0
    }
}
