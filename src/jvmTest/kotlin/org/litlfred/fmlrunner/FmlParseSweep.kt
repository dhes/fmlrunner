package org.litlfred.fmlrunner

import org.litlfred.fmlrunner.compiler.FmlCompiler
import java.io.File
import kotlin.test.Test

/**
 * Parse-only sweep over every .fml file WHO publishes (org-wide census).
 * Diagnostic, not pass/fail: prints a per-file ledger plus a failure tally
 * grouped by error signature — the grammar backlog, prioritized by frequency.
 *
 * Point FML_SWEEP_DIR at a directory of <repo>/<file>.fml subdirectories.
 */
class FmlParseSweep {

    @Test
    fun sweep() {
        val root = File(
            System.getenv("FML_SWEEP_DIR")
                ?: return println("SWEEP SKIPPED: FML_SWEEP_DIR not set")
        )
        val files = root.walkTopDown()
            .filter { it.extension == "fml" }
            .sortedBy { it.relativeTo(root).path }
            .toList()
        val compiler = FmlCompiler()
        var ok = 0
        val failures = mutableListOf<Pair<String, String>>()
        for (f in files) {
            val rel = f.relativeTo(root).path
            val r = try {
                compiler.compile(f.readText())
            } catch (e: Exception) {
                failures += rel to "EXCEPTION ${e.message}"
                println("SWEEP FAIL $rel :: EXCEPTION ${e.message}")
                continue
            }
            if (r.success && r.structureMap != null) {
                ok++
                println("SWEEP OK   $rel (${r.structureMap!!.group.size} groups)")
            } else {
                val err = r.errors.firstOrNull() ?: "no error message"
                failures += rel to err
                println("SWEEP FAIL $rel :: $err")
            }
        }
        println("SWEEP score: $ok/${files.size}")
        // Tally by error signature: strip file-specific line/token detail so
        // identical grammar gaps group together.
        val signature = { e: String ->
            e.replace(Regex("\\(line \\d+, near '[^']*'\\)"), "")
                .replace(Regex("'[^']*'"), "'…'")
                .trim()
        }
        failures.groupBy { signature(it.second) }
            .entries.sortedByDescending { it.value.size }
            .forEach { (sig, hits) ->
                println("SWEEP TALLY ${hits.size}x :: $sig")
                hits.forEach { println("SWEEP       - ${it.first}") }
            }
    }
}
