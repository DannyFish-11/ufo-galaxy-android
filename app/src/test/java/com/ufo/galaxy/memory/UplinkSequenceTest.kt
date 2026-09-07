package com.ufo.galaxy.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序号分配器的正确性。
 *
 * 这个类只有一条真正要钉的性质：**永不重号**。跳号无害（账本只要求单调且唯一，
 * 不要求连续），重号有害——重号会让服务端把一条真事件当成补传丢掉，而且不报错。
 */
class UplinkSequenceTest {

    /** 记录写入次序的假存储，用来断言"先持久化再返回"。 */
    private class FakeStore(initial: Long = 1L) : UplinkSequence.Store {
        var value: Long = initial
        val writes = mutableListOf<Long>()

        override fun read(): Long = value

        override fun write(value: Long) {
            this.value = value
            writes += value
        }
    }

    @Test
    fun `序号从 1 起连续递增`() {
        val seq = UplinkSequence(FakeStore())

        assertEquals(listOf(1L, 2L, 3L), listOf(seq.next(), seq.next(), seq.next()))
    }

    @Test
    fun `先持久化再返回`() {
        // 顺序反了就会重号:崩溃发生在"返回之后、持久化之前"时,下次会再发同一个号。
        val store = FakeStore()

        val issued = seqOf(store).next()

        assertEquals("返回的号应当是持久化之前的值", 1L, issued)
        assertEquals("返回 1 之前必须已经把 2 写下去", listOf(2L), store.writes)
    }

    @Test
    fun `进程重启后不会重发已经发过的号`() {
        // 模拟:第一个进程发了三个号,然后崩溃;新进程从持久化的值继续。
        val store = FakeStore()
        val first = seqOf(store)
        val issuedBefore = listOf(first.next(), first.next(), first.next())

        val afterRestart = UplinkSequence(store) // 同一份存储，新的分配器
        val issuedAfter = listOf(afterRestart.next(), afterRestart.next())

        assertTrue(
            "重启后重发了已经用过的号 —— 服务端会把真事件当成补传丢掉",
            issuedBefore.intersect(issuedAfter.toSet()).isEmpty(),
        )
        assertEquals(listOf(4L, 5L), issuedAfter)
    }

    @Test
    fun `崩溃导致的跳号是可以接受的`() {
        // 持久化成功、发送前崩溃:那个号被跳过。账本只要求单调唯一,不要求连续。
        val store = FakeStore()
        seqOf(store).next() // 发了 1，假设它没能送出去

        val resumed = UplinkSequence(store).next()

        assertEquals("跳号是预期行为,不是 bug", 2L, resumed)
    }

    @Test
    fun `并发取号不会拿到同一个`() {
        val store = FakeStore()
        val seq = UplinkSequence(store)
        val results = java.util.Collections.synchronizedList(mutableListOf<Long>())

        val threads = (1..8).map {
            Thread {
                repeat(50) { results += seq.next() }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发下出现了重号", 400, results.toSet().size)
    }

    private fun seqOf(store: FakeStore) = UplinkSequence(store)
}
