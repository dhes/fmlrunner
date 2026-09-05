package org.litlfred.fmlrunner

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test

/**
 * Conformance spike for the CC-BY-NC-SA WHO corpus (ddcc, smart-ot):
 * 14 reference-engine oracles in ~/projects/fmlrunner-conformance-ncsa.
 * Diagnostic, not pass/fail: prints a per-fixture ledger.
 */
class NcsaConformanceSpike {

    private val home = File(System.getProperty("user.home"))
    private val corpus = File(home, "projects/fmlrunner-conformance-ncsa")

    private fun registerPackage(runner: FmlRunner, dir: File) {
        dir.listFiles { f -> f.name.startsWith("ConceptMap-") }?.forEach {
            runner.registerConceptMap(it.readText())
        }
        dir.listFiles { f -> f.name.startsWith("StructureDefinition-") }?.forEach {
            runner.registerStructureDefinition(it.readText())
        }
        dir.listFiles { f -> f.name.startsWith("CodeSystem-") }?.forEach {
            runner.registerCodeSystem(it.readText())
        }
    }

    private fun loadRunner(): FmlRunner {
        val runner = FmlRunner()
        for (repo in listOf("ddcc", "smart-ot")) {
            File(corpus, "$repo/maps").listFiles { f -> f.extension == "fml" }
                ?.sortedBy { it.name }?.forEach { f ->
                    val r = runner.compileFml(f.readText())
                    if (r.success && r.structureMap != null) runner.registerStructureMap(r.structureMap!!)
                    else println("NCSA COMPILE FAIL ${f.name}: ${r.errors.take(1)}")
                }
            registerPackage(runner, File(corpus, "$repo/package"))
        }
        registerPackage(runner, File(home, ".fhir/packages/hl7.fhir.uv.shc-vaccination#1.0.0/package"))
        File(home, ".fhir/packages/hl7.terminology.r4#6.2.0/package")
            .listFiles { f -> f.name.startsWith("CodeSystem-") }?.forEach {
                runner.registerCodeSystem(it.readText())
            }
        return runner
    }

    private data class Fixture(val label: String, val mapUrl: String, val input: File, val oracle: File)

    private fun fixtures(): List<Fixture> {
        val list = mutableListOf<Fixture>()
        val otBase = "http://worldhealthorganization.github.io/smart-ot/StructureMap"
        val ddBase = "http://smart.who.int/ddcc/StructureMap"
        val otQr = File(corpus, "smart-ot/package/example/QuestionnaireResponse-Response1.json")
        for (m in listOf("MeaslesQuestionnaireToLogicalModel", "MeaslesQuestionnaireToResources")) {
            list.add(Fixture("ot/$m", "$otBase/$m", otQr, File(corpus, "oracle/smart-ot/${m}__Response1.json")))
        }
        File(corpus, "ddcc/package/example").listFiles { f ->
            f.name.startsWith("QuestionnaireResponse-DDCCVSQuestionnaireResponse")
        }?.sortedBy { it.name }?.forEach { f ->
            val short = f.name.removePrefix("QuestionnaireResponse-DDCCVSQuestionnaireResponse").removeSuffix(".json")
            list.add(Fixture("ddcc/QResp-$short", "$ddBase/QRespToVSCoreDataSet", f,
                File(corpus, "oracle/ddcc/QRespToVSCoreDataSet__${short}.json")))
        }
        File(corpus, "ddcc/package/example").listFiles { f ->
            f.name.startsWith("Bundle-Example")
        }?.sortedBy { it.name }?.forEach { f ->
            val short = f.name.removePrefix("Bundle-Example").removePrefix("-").removeSuffix(".json")
            list.add(Fixture("ddcc/toSHC-$short", "$ddBase/DDCCtoSHC", f,
                File(corpus, "oracle/ddcc/DDCCtoSHC__${short}.json")))
        }
        return list
    }

    @Test
    fun scoreAllFixtures() {
        val runner = loadRunner()
        val all = fixtures()
        println("NCSA === Conformance score: ${all.size} fixtures vs oracle ===")
        var match = 0; var diff = 0; var error = 0
        for (fx in all) {
            val exec = try {
                runner.executeStructureMap(fx.mapUrl, fx.input.readText())
            } catch (e: Exception) {
                error++; println("NCSA ERROR ${fx.label}: EXCEPTION ${e.message?.take(160)}"); continue
            }
            if (!exec.success || exec.result == null) {
                error++
                println("NCSA ERROR ${fx.label}: ${exec.errors.firstOrNull()?.take(160)}")
                continue
            }
            val ours = normalize(Json.parseToJsonElement(exec.result!!))
            val oracle = normalize(Json.parseToJsonElement(fx.oracle.readText()))
            val divergence = firstDiff(oracle, ours, "$")
            if (divergence == null) { match++; println("NCSA MATCH ${fx.label}") }
            else { diff++; println("NCSA DIFF  ${fx.label} at $divergence") }
        }
        println("NCSA == score: $match match, $diff diff, $error error / ${all.size}")
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
