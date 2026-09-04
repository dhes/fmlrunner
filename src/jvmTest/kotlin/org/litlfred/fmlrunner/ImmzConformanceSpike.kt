package org.litlfred.fmlrunner

import java.io.File
import kotlin.test.Test

/**
 * Conformance spike: feed fmlrunner WHO smart-immunizations content verbatim
 * and report how far it gets. Fixtures from github.com/dhes/fmlrunner-conformance
 * (reference-engine oracles); FML sources from the IG's input/maps.
 *
 * Diagnostic, not pass/fail: prints a stage-by-stage ledger.
 */
class ImmzConformanceSpike {

    private val mapsDir = File(System.getProperty("user.home"), "projects/smart-immunizations-fresher/input/maps")
    private val fixturesDir = File(System.getProperty("user.home"), "projects/fmlrunner-conformance")

    @Test
    fun c4EndToEnd() {
        val runner = FmlRunner()

        // Stage 1: compile every IMMZ FML source
        println("=== Stage 1: compile all IMMZ FML maps ===")
        var compiled = 0
        mapsDir.listFiles { f -> f.extension == "fml" }?.sortedBy { it.name }?.forEach { f ->
            val r = runner.compileFml(f.readText())
            if (r.success && r.structureMap != null) {
                runner.registerStructureMap(r.structureMap!!)
                compiled++
                println("COMPILE OK   ${f.name} -> ${r.structureMap!!.url}")
            } else {
                println("COMPILE FAIL ${f.name}: ${r.errors.take(2)}")
            }
        }
        println("compiled $compiled maps")

        // Stage 2: execute C4 QRToPatient on the published QR example
        println("=== Stage 2: execute IMMZ.C4.QRToPatient on published QR ===")
        val qr = File(fixturesDir, "package/example/QuestionnaireResponse-Example.IMMZ.C.QuestionnaireResponse.1.json").readText()
        val exec = runner.executeStructureMap(
            "http://smart.who.int/immunizations/StructureMap/IMMZ.C4.QRToPatient", qr
        )
        println("execute success=${exec.success}")
        exec.errors.take(5).forEach { println("  error: $it") }
        exec.warnings.take(5).forEach { println("  warning: $it") }
        println("--- output (first 1500 chars) ---")
        println(exec.result?.take(1500) ?: "(null result)")
        println("--- oracle (first 600 chars, for eyeball) ---")
        println(File(fixturesDir, "oracle/IMMZ.C4.QRToPatient__C.QuestionnaireResponse.1.json").readText().take(600))
    }
}
