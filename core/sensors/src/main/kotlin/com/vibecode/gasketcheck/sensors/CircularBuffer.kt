package com.vibecode.gasketcheck.sensors

/**
 * Lightweight circular buffer for fixed-size sample windows.
 */
class CircularBuffer<T>(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val data: Array<Any?> = arrayOfNulls(capacity)
    private var head: Int = 0
    private var size: Int = 0

    fun add(item: T) {
        data[head] = item
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        head = 0
        size = 0
        for (i in data.indices) data[i] = null
    }

    fun isFull(): Boolean = size == capacity

    fun isEmpty(): Boolean = size == 0

    fun toList(): List<T> {
        val result = ArrayList<T>(size)
        val start = (head - size + capacity) % capacity
        for (i in 0 until size) {
            @Suppress("UNCHECKED_CAST")
            result.add(data[(start + i) % capacity] as T)
        }
        return result
    }
}

