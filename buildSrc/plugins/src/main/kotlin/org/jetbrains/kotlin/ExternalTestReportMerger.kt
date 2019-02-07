package org.jetbrains.kotlin

import kotlinx.serialization.*
import kotlinx.serialization.internal.SerialClassDescImpl
import kotlinx.serialization.internal.StringDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonInput

enum class TestStatus {
    PASSED,
    FAILED,
    ERROR
}

@Serializable
data class TestResult(val comment: String = "", val status: TestStatus)

//@Serializable
data class TestSuiteResult(val name:String, val testsResults:Map<String, TestResult>)

@Serializable
data class Statistics(val error: Int, val total:Int, val skipped: Int, val failed: Int, val passed:Int)

@Serializable
data class ExternalTestReport(val statistics: Statistics, val tests: TestSuiteResult)

@Serializer(forClass = TestSuiteResult::class)
object TestSuiteResultSerializer:KSerializer<TestSuiteResult> {
    override fun deserialize(decoder: Decoder): TestSuiteResult {
        val value = decoder.decodeString()
        return TestSuiteResult(value, emptyMap())
    }
}

@Serializer(forClass = ExternalTestReport::class)
object ExternalTestReportSerializer:KSerializer<ExternalTestReport> {
    @ImplicitReflectionSerializer
    override fun deserialize(decoder: Decoder):ExternalTestReport {
        val input = decoder as? JsonInput ?: TODO("non json format unsupported")
        decoder.beginStructure(SerialClassDescImpl(){})
        println(decoder.context)
        println(decoder.decode<Statistics>())
        return ExternalTestReport(Statistics(0,0,0,0, 0), TestSuiteResult("name", emptyMap()))
    }
    //ExternalTestReport(decoder.decode(), Json.parse(TestSuiteResultSerializer, decoder.decodeString()))
}



@ImplicitReflectionSerializer
fun main(args: Array<String>) {
    val results = Json.parse(ExternalTestReportSerializer, """
        |{
        |  "statistics" : {
        |     "error" : 42,
        |     "total" : 5677,
        |     "skipped" : 1275,
        |     "failed" : 0,
        |     "passed" : 4302
        |  },
        |  "tests" :{
        |      "build_external_compiler_codegen_boxInline_anonymousObject": {
        |        "kt17972.kt": {
        |           "comment": "",
        |           "status": "PASSED"
        |        }
        |      }
        |  }
        |}
    """.trimMargin())
    println (results)
}