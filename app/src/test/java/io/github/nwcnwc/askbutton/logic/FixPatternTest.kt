package io.github.nwcnwc.askbutton.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPatternTest {
    interface Source {
        val urls: List<String>
    }

    class Host {
        private val urls = arrayListOf("https://example.com/a", "https://example.com/b")

        fun source(): Source = object : Source {
            override val urls: List<String> get() = this@Host.urls.toList()
        }
    }

    @Test
    fun urlsGetterReadsTheOuterList() {
        val source = Host().source()
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), source.urls)
        source.urls.toMutableList().add("https://other.example")
        assertEquals(2, source.urls.size)
    }

    @Test(expected = StackOverflowError::class)
    fun selfReferencingGetterOverflows() {
        val source = object : Source {
            override val urls: List<String> get() = urls.toList()
        }
        source.urls
    }

    @Test
    fun listenCallbacksHandleResultsOnceUntilReset() {
        val once = Once()
        var handled = 0
        fun onResults() {
            if (!once.take()) return
            handled++
        }
        fun onError() {
            if (!once.take()) return
            handled++
        }
        onResults()
        onError()
        onResults()
        assertEquals(1, handled)
        assertTrue(once.taken())
        once.reset()
        assertFalse(once.taken())
        onError()
        assertEquals(2, handled)
    }
}
