package coverage

import kotlin.test.Test
import kotlin.test.assertTrue

class CoverageTests {
    @Test
    fun testHello() {
        assertTrue("Hello, Test coverage" in hello())
    }
}