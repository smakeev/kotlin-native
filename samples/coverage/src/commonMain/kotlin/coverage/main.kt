package coverage

import coverage.foo.baz

fun hello(): String = "Hello, Test coverage"

fun main() {
    baz(arrayOf("abc"))
}