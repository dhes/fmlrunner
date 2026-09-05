package org.litlfred.fmlrunner

import org.litlfred.fmlrunner.types.*
import org.litlfred.fmlrunner.compiler.FmlCompiler
import org.litlfred.fmlrunner.executor.FmlEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.litlfred.fmlrunner.terminology.*

/**
 * Main FmlRunner class providing FML compilation and StructureMap execution
 * Now includes kotlin-fhir terminology services for comprehensive FHIR processing
 */
class FmlRunner {
    private val compiler = FmlCompiler()
    private val structureMapStore = mutableMapOf<String, StructureMap>()
    private val conceptMapStore = mutableMapOf<String, JsonObject>()
    private val typeStore = mutableMapOf<String, Map<String, String>>() // SD url -> element leaf -> type code
    private val displayStore = mutableMapOf<String, String>() // "system|code" -> display
    
    // kotlin-fhir terminology services
    private val conceptMapService = ConceptMapService()
    private val valueSetService = ValueSetService()
    private val codeSystemService = CodeSystemService()
    private val validationService = ValidationService()
    private val bundleService = BundleService(conceptMapService, valueSetService, codeSystemService, validationService)

    /**
     * Compile FML content to StructureMap
     */
    fun compileFml(fmlContent: String): FmlCompilationResult {
        return compiler.compile(fmlContent)
    }

    /**
     * Execute StructureMap on input content
     */
    fun executeStructureMap(structureMapReference: String, inputContent: String, options: ExecutionOptions = ExecutionOptions()): ExecutionResult {
        val structureMap = getStructureMap(structureMapReference)
            ?: return ExecutionResult(success = false, errors = listOf("StructureMap not found: $structureMapReference"))
        val source = try {
            Json.parseToJsonElement(inputContent)
        } catch (e: Exception) {
            return ExecutionResult(success = false, errors = listOf("Invalid source JSON: ${e.message}"))
        }
        val engine = FmlEngine(
            resolveMap = { ref -> getStructureMap(ref) },
            resolveConceptMap = { url -> conceptMapStore[url] },
            resolveElementTypes = { url -> typeStore[url] },
            resolveDisplay = { system, code -> displayStore["$system|$code"] }
        )
        return engine.execute(structureMap, source)
    }

    /**
     * Register a CodeSystem (JSON): concept displays back the c() transform,
     * which resolves display from terminology (matching the reference engine)
     * rather than from its optional third argument.
     */
    fun registerCodeSystem(json: String): Boolean {
        return try {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return false
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return false
            fun walk(concepts: kotlinx.serialization.json.JsonArray?) {
                concepts?.forEach { c ->
                    val co = c as? JsonObject ?: return@forEach
                    val code = (co["code"] as? JsonPrimitive)?.contentOrNull
                    val display = (co["display"] as? JsonPrimitive)?.contentOrNull
                    if (code != null && display != null) displayStore["$url|$code"] = display
                    walk(co["concept"] as? kotlinx.serialization.json.JsonArray)
                }
            }
            walk(o["concept"] as? kotlinx.serialization.json.JsonArray)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Register a StructureDefinition (JSON): declared element types drive
     * choice-element ([x]) naming during execution.
     */
    fun registerStructureDefinition(json: String): Boolean {
        return try {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return false
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return false
            val elements = ((o["snapshot"] ?: o["differential"]) as? JsonObject)
                ?.get("element") as? kotlinx.serialization.json.JsonArray ?: return false
            val types = mutableMapOf<String, String>()
            for (el in elements) {
                val eo = el as? JsonObject ?: continue
                val id = (eo["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val type = ((eo["type"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull() as? JsonObject)?.get("code") as? JsonPrimitive ?: continue
                val code = type.contentOrNull ?: continue
                val max = (eo["max"] as? JsonPrimitive)?.contentOrNull ?: "1"
                types[id.substringAfterLast('.')] = "$code|$max"
            }
            typeStore[url] = types
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Register a ConceptMap (JSON) for use by the translate() transform.
     */
    fun registerConceptMap(json: String): Boolean {
        return try {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return false
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return false
            conceptMapStore[url] = o
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Register a StructureMap for later execution
     */
    fun registerStructureMap(structureMap: StructureMap): Boolean {
        return try {
            val key = structureMap.url ?: structureMap.name ?: structureMap.id 
                ?: return false
            structureMapStore[key] = structureMap
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get StructureMap by reference (URL, name, or ID)
     */
    fun getStructureMap(reference: String): StructureMap? {
        return structureMapStore[reference]
    }

    /**
     * Get all registered StructureMaps
     */
    fun getAllStructureMaps(): List<StructureMap> {
        return structureMapStore.values.toList()
    }

    /**
     * Search StructureMaps by parameters
     */
    fun searchStructureMaps(name: String? = null, status: StructureMapStatus? = null, url: String? = null): List<StructureMap> {
        var results = getAllStructureMaps()

        name?.let { searchName ->
            results = results.filter { 
                it.name?.contains(searchName, ignoreCase = true) == true 
            }
        }

        status?.let { searchStatus ->
            results = results.filter { it.status == searchStatus }
        }

        url?.let { searchUrl ->
            results = results.filter { it.url == searchUrl }
        }

        return results
    }

    /**
     * Remove StructureMap by reference
     */
    fun removeStructureMap(reference: String): Boolean {
        return structureMapStore.remove(reference) != null
    }

    /**
     * Clear all StructureMaps
     */
    fun clear() {
        structureMapStore.clear()
        conceptMapService.clear()
        valueSetService.clear()
        codeSystemService.clear()
        validationService.clear()
        bundleService.clear()
    }

    /**
     * Get count of registered StructureMaps
     */
    fun getCount(): Int {
        return structureMapStore.size
    }

    /**
     * Validate StructureMap structure
     */
    fun validateStructureMap(structureMap: StructureMap): org.litlfred.fmlrunner.executor.ValidationResult {
        val errors = mutableListOf<String>()
        if (structureMap.group.isEmpty()) errors.add("StructureMap must have at least one group")
        structureMap.group.forEach { g ->
            if (g.input.isEmpty()) errors.add("Group '${g.name}' has no inputs")
            if (g.rule.isEmpty()) errors.add("Group '${g.name}' has no rules")
        }
        return org.litlfred.fmlrunner.executor.ValidationResult(valid = errors.isEmpty(), errors = errors)
    }

    /**
     * Compile and register StructureMap in one operation
     */
    fun compileAndRegisterFml(fmlContent: String): FmlCompilationResult {
        val compilationResult = compileFml(fmlContent)
        if (compilationResult.success && compilationResult.structureMap != null) {
            if (!registerStructureMap(compilationResult.structureMap)) {
                return FmlCompilationResult(
                    success = false,
                    errors = listOf("Failed to register compiled StructureMap")
                )
            }
        }
        return compilationResult
    }

    // kotlin-fhir terminology service methods

    /**
     * Register ConceptMap with kotlin-fhir service
     */
    fun registerConceptMap(conceptMap: org.litlfred.fmlrunner.terminology.ConceptMap) {
        conceptMapService.registerConceptMap(conceptMap)
    }

    /**
     * Get ConceptMap by reference
     */
    fun getConceptMap(reference: String): org.litlfred.fmlrunner.terminology.ConceptMap? {
        return conceptMapService.getConceptMap(reference)
    }

    /**
     * Translate code using kotlin-fhir ConceptMap service
     */
    fun translateCode(sourceSystem: String, sourceCode: String, targetSystem: String? = null): List<TranslationResult> {
        return conceptMapService.translate(sourceSystem, sourceCode, targetSystem)
    }

    /**
     * Register ValueSet with kotlin-fhir service
     */
    fun registerValueSet(valueSet: org.litlfred.fmlrunner.terminology.ValueSet) {
        valueSetService.registerValueSet(valueSet)
    }

    /**
     * Get ValueSet by reference
     */
    fun getValueSet(reference: String): org.litlfred.fmlrunner.terminology.ValueSet? {
        return valueSetService.getValueSet(reference)
    }

    /**
     * Validate code in ValueSet using kotlin-fhir service
     */
    fun validateCodeInValueSet(code: String, system: String? = null, valueSetUrl: String? = null): org.litlfred.fmlrunner.terminology.ValidationResult {
        return valueSetService.validateCode(code, system, valueSetUrl)
    }

    /**
     * Expand ValueSet using kotlin-fhir service
     */
    fun expandValueSet(valueSetUrl: String): ValueSetExpansion? {
        return valueSetService.expandValueSet(valueSetUrl)
    }

    /**
     * Register CodeSystem with kotlin-fhir service
     */
    fun registerCodeSystem(codeSystem: org.litlfred.fmlrunner.terminology.CodeSystem) {
        codeSystemService.registerCodeSystem(codeSystem)
    }

    /**
     * Get CodeSystem by reference
     */
    fun getCodeSystem(reference: String): org.litlfred.fmlrunner.terminology.CodeSystem? {
        return codeSystemService.getCodeSystem(reference)
    }

    /**
     * Lookup code in CodeSystem using kotlin-fhir service
     */
    fun lookupCode(system: String, code: String): LookupResult? {
        return codeSystemService.lookupCode(system, code)
    }

    /**
     * Register StructureDefinition with kotlin-fhir validation service
     */
    fun registerStructureDefinition(structureDefinition: org.litlfred.fmlrunner.terminology.StructureDefinition) {
        validationService.registerStructureDefinition(structureDefinition)
    }

    /**
     * Validate resource against StructureDefinition using kotlin-fhir service
     */
    fun validateResource(resource: kotlinx.serialization.json.JsonElement, structureDefinition: org.litlfred.fmlrunner.terminology.StructureDefinition): ResourceValidationResult {
        return validationService.validateResource(resource, structureDefinition)
    }

    /**
     * Process Bundle using kotlin-fhir service
     */
    fun processBundle(bundle: org.litlfred.fmlrunner.terminology.Bundle): BundleProcessingResult {
        return bundleService.processBundle(bundle)
    }

    /**
     * Get Bundle processing statistics
     */
    fun getBundleStats(): BundleStats {
        return bundleService.getStats()
    }
}

/**
 * Validation result for StructureMap validation
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>
)