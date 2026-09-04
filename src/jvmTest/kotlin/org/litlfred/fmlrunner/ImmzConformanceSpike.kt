package org.litlfred.fmlrunner

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test

/**
 * Conformance spike: feed fmlrunner WHO smart-immunizations content verbatim
 * and score it against the reference-engine oracles
 * (github.com/dhes/fmlrunner-conformance). Diagnostic, not pass/fail:
 * prints a per-fixture ledger.
 */
class ImmzConformanceSpike {

    private val mapsDir = File(System.getProperty("user.home"), "projects/smart-immunizations-fresher/input/maps")
    private val fixturesDir = File(System.getProperty("user.home"), "projects/fmlrunner-conformance")

    private fun loadRunner(): FmlRunner {
        val runner = FmlRunner()
        mapsDir.listFiles { f -> f.extension == "fml" }?.sortedBy { it.name }?.forEach { f ->
            val r = runner.compileFml(f.readText())
            if (r.success && r.structureMap != null) runner.registerStructureMap(r.structureMap!!)
            else println("COMPILE FAIL ${f.name}: ${r.errors.take(2)}")
        }
        File(fixturesDir, "package").listFiles { f -> f.name.startsWith("ConceptMap-") }?.forEach {
            runner.registerConceptMap(it.readText())
        }
        File(fixturesDir, "package").listFiles { f -> f.name.startsWith("StructureDefinition-") }?.forEach {
            runner.registerStructureDefinition(it.readText())
        }
        return runner
    }

    private fun mapUrlFor(family: String): String {
        val name = if (family == "C") "IMMZ.C4.QRToPatient" else "IMMZ.$family.QRToBundle"
        return "http://smart.who.int/immunizations/StructureMap/$name"
    }

    @Test
    fun scoreAllFixtures() {
        val runner = loadRunner()
        println("=== Conformance score: 33 fixtures vs oracle ===")
        var match = 0; var diff = 0; var error = 0
        File(fixturesDir, "package/example").listFiles { f ->
            f.name.startsWith("QuestionnaireResponse-Example.IMMZ.")
        }?.sortedBy { it.name }?.forEach { qrFile ->
            val short = qrFile.name.removePrefix("QuestionnaireResponse-Example.IMMZ.").removeSuffix(".json")
            val family = short.substringBefore('.')
            val mapName = if (family == "C") "IMMZ.C4.QRToPatient" else "IMMZ.$family.QRToBundle"
            val oracleFile = File(fixturesDir, "oracle/${mapName}__${short}.json")
            val exec = runner.executeStructureMap(mapUrlFor(family), qrFile.readText())
            if (!exec.success || exec.result == null) {
                error++
                println("ERROR $short: ${exec.errors.firstOrNull()}")
                return@forEach
            }
            val ours = normalize(Json.parseToJsonElement(exec.result!!))
            val oracle = normalize(Json.parseToJsonElement(oracleFile.readText()))
            val divergence = firstDiff(oracle, ours, "$")
            if (divergence == null) { match++; println("MATCH $short") }
            else { diff++; println("DIFF  $short at $divergence") }
        }
        println("== score: $match match, $diff diff, $error error / 33")
    }

    // --- normalization: sequence-number UUIDs, unwrap singleton arrays ---

    private val uuidRe = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun normalize(e: JsonElement): JsonElement {
        val compact = Json.encodeToString(JsonElement.serializer(), e)
        val seen = LinkedHashMap<String, String>()
        val renumbered = uuidRe.replace(compact) { m ->
            seen.getOrPut(m.value.lowercase()) { "uuid-${seen.size + 1}" }
        }
        return unwrap(Json.parseToJsonElement(renumbered))
    }

    private fun unwrap(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> if (e.size == 1) unwrap(e[0]) else JsonArray(e.map { unwrap(it) })
        is JsonObject -> JsonObject(e.mapValues { unwrap(it.value) })
        else -> e
    }

    private fun firstDiff(a: JsonElement, b: JsonElement, path: String): String? {
        if (a::class != b::class) return "$path (kind: oracle=${a::class.simpleName} ours=${b::class.simpleName})"
        when (a) {
            is JsonObject -> {
                b as JsonObject
                for (k in a.keys + b.keys) {
                    val av = a[k]; val bv = b[k]
                    if (av == null) return "$path.$k (extra in ours: ${short(bv)})"
                    if (bv == null) return "$path.$k (missing in ours; oracle=${short(av)})"
                    firstDiff(av, bv, "$path.$k")?.let { return it }
                }
            }
            is JsonArray -> {
                b as JsonArray
                if (a.size != b.size) return "$path (size: oracle=${a.size} ours=${b.size})"
                a.indices.forEach { i -> firstDiff(a[i], b[i], "$path[$i]")?.let { return it } }
            }
            else -> if (a != b) return "$path (oracle=${short(a)} ours=${short(b)})"
        }
        return null
    }

    private fun short(e: JsonElement?): String =
        (e?.toString() ?: "null").let { if (it.length > 60) it.take(60) + "…" else it }
}
