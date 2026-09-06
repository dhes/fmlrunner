package org.litlfred.fmlrunner.executor

import kotlinx.serialization.json.*
import org.litlfred.fmlrunner.types.*
import kotlin.random.Random

/**
 * StructureMap execution over generic JSON trees.
 *
 * Sources are read from immutable JsonElements (or from target nodes built
 * earlier, e.g. a logical-model instance created by `create(...)` and then
 * used as the source input of a dependent group). Targets are built in a
 * mutable tree and serialized at the end.
 *
 * Unknown transforms and unresolvable groups/variables are hard errors —
 * never silent no-ops.
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

class FmlEngine(
    private val resolveMap: (String) -> StructureMap?,
    private val resolveConceptMap: (String) -> JsonObject? = { null },
    private val resolveElementTypes: (String) -> Map<String, String>? = { null },
    private val resolveLogicalTypeName: (String) -> String? = { null },
    private val resolveDisplay: (String, String) -> String? = { _, _ -> null }
) {

    // ---- mutable target tree ----

    sealed class MNode
    class MObj : MNode() {
        /** StructureDefinition url of the type this node instantiates, when known. */
        var typeUrl: String? = null
        /** Simple complex-type name (e.g. HumanName), when created or declared as one. */
        var typeName: String? = null
        val fields = LinkedHashMap<String, MutableList<MNode>>()
        fun append(name: String, node: MNode, replaceIfSingular: Boolean = false) {
            val list = fields.getOrPut(name) { mutableListOf() }
            // A computed re-write of a singular element replaces (last write wins,
            // e.g. a default status then a translated one). Creation writes and
            // known repeating elements always append.
            if (replaceIfSingular && list.isNotEmpty() && name !in REPEATING_ELEMENTS) list.clear()
            list.add(node)
        }
    }
    class MPrim(val value: JsonPrimitive, val srcChoice: String? = null) : MNode()

    companion object {
        /** FHIR repeating elements the corpus writes more than once per node. */
        val RESOURCE_TYPES = setOf(
            "Patient", "RelatedPerson", "Observation", "Immunization", "AdverseEvent",
            "Bundle", "Practitioner", "Encounter", "Condition", "Organization",
            "Composition", "DiagnosticReport", "Specimen"
        )
        val REPEATING_ELEMENTS = setOf(
            "entry", "extension", "identifier", "name", "telecom", "address",
            "given", "coding", "item", "answer", "contact", "performer",
            "note", "reasonCode", "protocolApplied", "target", "contained"
        )
    }

    private fun jsonToM(e: JsonElement): MNode = when (e) {
        is JsonPrimitive -> MPrim(e)
        is JsonObject -> MObj().also { o -> e.forEach { (k, v) ->
            if (v is JsonArray) v.forEach { o.append(k, jsonToM(it)) } else o.append(k, jsonToM(v))
        } }
        is JsonArray -> throw EngineError("Cannot bind a bare JSON array to a single node")
        else -> throw EngineError("Unsupported JSON element")
    }

    private fun mToJson(n: MNode): JsonElement? = when (n) {
        is MPrim -> n.value
        is MObj -> {
            val o = buildMap<String, JsonElement> {
                for ((k, list) in n.fields) {
                    val items = list.mapNotNull { mToJson(it) }
                    if (items.isEmpty()) continue
                    // FHIR JSON requires arrays for repeating elements even
                    // when a single value is present
                    val repeating = k in REPEATING_ELEMENTS ||
                        n.typeUrl?.let { resolveElementTypes(it)?.get(k) }
                            ?.substringAfter('|', "1") == "*"
                    put(k, if (items.size == 1 && !repeating) items[0] else JsonArray(items))
                }
            }
            if (o.isEmpty()) null else JsonObject(o)
        }
    }

    // ---- values & scopes ----

    class EngineError(message: String) : Exception(message)

    private class Scope(val parent: Scope?) {
        val vars = mutableMapOf<String, Any>() // JsonElement | MNode
        fun lookup(name: String): Any? = vars[name] ?: parent?.lookup(name)
        fun bind(name: String, v: Any) { vars[name] = v }
    }

    /** Read child elements by name from a source-side value (choice-aware). */
    private fun readElement(v: Any, name: String): List<Any> { return when (v) {
        // `.value` on a primitive is the primitive itself (FHIRPath's view of
        // primitive types, e.g. `linkId.value`)
        is JsonPrimitive -> if (name == "value") listOf(v) else emptyList()
        is MPrim -> if (name == "value") listOf(v) else emptyList()
        is JsonObject -> {
            val exact = v[name]
            val hits = mutableListOf<JsonElement>()
            if (exact != null) {
                if (exact is JsonArray) hits.addAll(exact) else hits.add(exact)
            } else {
                // choice type: value[x]
                val tagged = mutableListOf<Any>()
                v.forEach { (k, e) ->
                    if (k.length > name.length && k.startsWith(name) && k[name.length].isUpperCase()) {
                        val suffix = k.substring(name.length)
                        val elems = if (e is JsonArray) e.toList() else listOf(e)
                        elems.forEach { el ->
                            tagged.add(if (el is JsonPrimitive) MPrim(el, suffix) else el)
                        }
                    }
                }
                return tagged
            }
            hits
        }
        is MObj -> {
            val exact = v.fields[name]
            if (exact != null) {
                val declared = v.typeUrl?.let { resolveElementTypes(it)?.get(name) }?.substringBefore('|')
                if (declared != null && declared.first().isLowerCase()) {
                    return exact.map { hit ->
                        val prim = when {
                            hit is MPrim -> hit.value
                            hit is MObj && hit.fields.size == 1 ->
                                (hit.fields["value"]?.singleOrNull() as? MPrim)?.value
                            else -> null
                        }
                        if (prim != null) MPrim(prim, declared.replaceFirstChar { c -> c.uppercase() }) else hit
                    }
                }
                if (declared != null) {
                    val childUrl = childTypeUrl(declared, v.typeUrl)
                    exact.forEach { hit -> if (hit is MObj && hit.typeUrl == null) hit.typeUrl = childUrl }
                }
                exact.toList()
            } else v.fields.entries
                .filter { it.key.length > name.length && it.key.startsWith(name) && it.key[name.length].isUpperCase() }
                .flatMap { it.value }
        }
        else -> emptyList()
    } }

    /**
     * SD a declared child type resolves its own elements from: a URL names a
     * nested model directly; a named datatype maps to its core SD; inline
     * backbones (and primitives) stay on the parent's leaf-flattened map.
     */
    private fun childTypeUrl(declared: String, parentUrl: String?): String? = when {
        declared.startsWith("http") -> declared
        declared == "BackboneElement" || declared == "Element" -> parentUrl
        declared.first().isUpperCase() -> "http://hl7.org/fhir/StructureDefinition/$declared"
        else -> parentUrl
    }

    private fun typeNameOf(item: Any?): String? = when (item) {
        is JsonObject -> (item["resourceType"] as? JsonPrimitive)?.contentOrNull
        is MObj -> (item.fields["resourceType"]?.firstOrNull() as? MPrim)?.value?.contentOrNull
        else -> null
    }

    private fun primString(v: Any?): String? = when (v) {
        is JsonPrimitive -> v.contentOrNull
        is MPrim -> v.value.contentOrNull
        is MObj -> (v.fields["value"]?.firstOrNull() as? MPrim)?.value?.contentOrNull
        is JsonObject -> (v["value"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    // ---- entry ----

    fun execute(map: StructureMap, source: JsonElement): ExecutionResult {
        return try {
            val group = map.group.firstOrNull() ?: throw EngineError("Map has no groups")
            val scope = Scope(null)
            var targetRoot: MObj? = null
            var sourceBound = false
            for (input in group.input) {
                when (input.mode) {
                    InputMode.SOURCE -> {
                        if (!sourceBound) { scope.bind(input.name, source); sourceBound = true }
                        else throw EngineError("Multiple source inputs on entry group '${group.name}'")
                    }
                    InputMode.TARGET -> {
                        val root = MObj()
                        // The input's declared type names either a uses alias or
                        // the last segment of a uses URL (both occur in WHO maps)
                        input.type?.let { alias ->
                            map.structure?.firstOrNull {
                                it.alias == alias || it.url.substringAfterLast('/') == alias
                            }?.url?.let { root.typeUrl = it }
                        }
                        resourceTypeForInput(map, input)?.let { root.append("resourceType", MPrim(JsonPrimitive(it))) }
                            ?: root.typeUrl?.let { u ->
                                resolveLogicalTypeName(u)?.let { root.append("resourceType", MPrim(JsonPrimitive(it))) }
                            }
                        if (targetRoot == null) targetRoot = root
                        scope.bind(input.name, root)
                    }
                }
            }
            val root = targetRoot ?: throw EngineError("Entry group '${group.name}' has no target input")
            executeRules(group.rule, map, scope)
            val json = mToJson(root) ?: JsonObject(emptyMap())
            ExecutionResult(success = true, result = Json.encodeToString(JsonElement.serializer(), json))
        } catch (e: EngineError) {
            ExecutionResult(success = false, errors = listOf(e.message ?: "engine error"))
        }
    }

    /** Entry target typed as a core FHIR resource gets its resourceType. */
    private fun resourceTypeForInput(map: StructureMap, input: StructureMapGroupInput): String? {
        val type = input.type ?: return null
        val url = map.structure?.firstOrNull { it.alias == type }?.url ?: return null
        return if (url.startsWith("http://hl7.org/fhir/StructureDefinition/")) url.substringAfterLast('/') else null
    }

    // ---- rules ----

    private fun executeRules(rules: List<StructureMapGroupRule>, map: StructureMap, scope: Scope) {
        for (rule in rules) executeRule(rule, map, scope)
    }

    private fun executeRule(rule: StructureMapGroupRule, map: StructureMap, scope: Scope) {
        val src = rule.source.firstOrNull() ?: throw EngineError("Rule '${rule.name}' has no source")
        if (rule.source.size > 1) throw EngineError("Multi-source rules not supported (rule '${rule.name}')")

        val ctx = scope.lookup(src.context) ?: throw EngineError("Unknown source context '${src.context}'")
        var items: List<Any> = if (src.element != null) readElement(ctx, src.element!!) else listOf(ctx)

        // Type cast (`entry.resource : Patient`) filters by resourceType
        src.type?.let { t -> items = items.filter { typeNameOf(it) == t } }

        when (src.listMode) {
            null -> {}
            "first" -> items = items.take(1)
            "last" -> items = items.takeLast(1)
            "only_one" -> if (items.size > 1) throw EngineError("only_one matched ${items.size} (rule '${rule.name}')")
            else -> throw EngineError("Unsupported listMode '${src.listMode}'")
        }
        src.condition?.let { cond ->
            items = items.filter { candidate ->
                val condScope = Scope(scope)
                src.variable?.let { condScope.bind(it, candidate) }
                evalCondition(cond, candidate, condScope)
            }
        }

        for (item in items) {
            val ruleScope = Scope(scope)
            src.variable?.let { ruleScope.bind(it, item) }
            rule.target?.forEach { executeTarget(it, ruleScope) }
            rule.rule?.let { executeRules(it, map, ruleScope) }
            rule.dependent?.forEach { dep -> executeDependent(dep, map, ruleScope) }
        }
    }

    private fun executeDependent(dep: StructureMapGroupRuleDependent, map: StructureMap, scope: Scope) {
        val found = resolveGroup(dep.name, map, mutableSetOf())
            ?: throw EngineError("Group '${dep.name}' not found in map or imports")
        val (group, owningMap) = found
        if (group.input.size != dep.variable.size) {
            throw EngineError("Group '${dep.name}' expects ${group.input.size} args, got ${dep.variable.size}")
        }
        val callScope = Scope(null)
        group.input.forEachIndexed { i, input ->
            val arg = scope.lookup(dep.variable[i])
                ?: throw EngineError("Unknown variable '${dep.variable[i]}' passed to '${dep.name}'")
            callScope.bind(input.name, arg)
        }
        executeRules(group.rule, owningMap, callScope)
    }

    private fun resolveGroup(
        name: String, map: StructureMap, visited: MutableSet<String>
    ): Pair<StructureMapGroup, StructureMap>? {
        map.url?.let { if (!visited.add(it)) return null }
        map.group.firstOrNull { it.name == name }?.let { return it to map }
        map.import?.forEach { url ->
            val imported = resolveMap(url) ?: throw EngineError("Imported map not registered: $url")
            resolveGroup(name, imported, visited)?.let { return it }
        }
        return null
    }

    // ---- targets ----

    private fun executeTarget(t: StructureMapGroupRuleTarget, scope: Scope) {
        val computed: MNode? = t.transform?.let { applyTransform(it, t.parameter ?: emptyList(), scope) }

        if (t.context != null) {
            val node = scope.lookup(t.context!!) as? MObj
                ?: throw EngineError("Target context '${t.context}' is not a target node")
            if (t.element == null && t.transform == null) {
                // Bare context target (`-> tgt` / `-> tgt as t`): names an
                // existing node as this rule's target without mutating it
                t.variable?.let { scope.bind(it, node) }
                return
            }
            var element = t.element ?: throw EngineError("Target context '${t.context}' without element")
            var placed = computed ?: MObj()
            if (computed != null && isChoiceWrite(element, node)) {
                val (suffix, unwrapped) = choiceSuffix(computed)
                element += suffix
                placed = unwrapped
            }
            val declaredEntry = node.typeUrl?.let { resolveElementTypes(it)?.get(t.element!!) }
            val declaredCode = declaredEntry?.substringBefore('|')
            val declaredMax = declaredEntry?.substringAfter('|', "?")
            // A primitive cannot be coerced into a complex-declared element
            // (e.g. integer into Quantity); the reference drops such writes
            if (computed is MPrim && declaredCode != null && declaredCode.first().isUpperCase()) return
            // Carry the declared type so the child's own elements resolve from
            // the right SD (nested logical model, named datatype, or inline
            // backbone falling back to the parent's leaf-flattened map)
            if (placed is MObj && placed.typeUrl == null && declaredCode != null) {
                placed.typeUrl = childTypeUrl(declaredCode, node.typeUrl)
            }
            // Declared max governs replace-vs-append even for computed writes
            val replace = if (declaredMax == "*") false else (computed != null || declaredMax == "1")
            node.append(element, placed, replaceIfSingular = replace)
            t.variable?.let { scope.bind(it, placed) }
        } else {
            val v = computed ?: throw EngineError("Target with neither context nor transform")
            t.variable?.let { scope.bind(it, v) }
        }
    }

    private fun applyTransform(name: String, params: List<TransformParameter>, scope: Scope): MNode {
        fun arg(i: Int): TransformParameter =
            params.getOrNull(i) ?: throw EngineError("Transform '$name' missing parameter $i")
        fun argValue(i: Int): Any {
            val p = arg(i)
            return p.valueId?.let { scope.lookup(it) ?: throw EngineError("Unknown variable '$it' in '$name'") }
                ?: p.valueString?.let { JsonPrimitive(it) }
                ?: p.valueInteger?.let { JsonPrimitive(it) }
                ?: p.valueDecimal?.let { JsonPrimitive(it) }
                ?: p.valueBoolean?.let { JsonPrimitive(it) }
                ?: throw EngineError("Empty parameter $i for '$name'")
        }

        return when (name) {
            "copy" -> when (val v = argValue(0)) {
                is JsonElement -> jsonToM(v)
                is MNode -> v // share the node: later writes through the variable alias the target
                else -> throw EngineError("copy: unsupported value")
            }
            "create" -> MObj().also { o ->
                val type = primString(argValue(0)) ?: throw EngineError("create: missing type")
                if (type in RESOURCE_TYPES) {
                    o.append("resourceType", MPrim(JsonPrimitive(type)))
                    // core SD (when registered) supplies element cardinalities
                    o.typeUrl = "http://hl7.org/fhir/StructureDefinition/$type"
                }
                if (type.contains('/')) o.typeUrl = type
                else if (type !in RESOURCE_TYPES && type.first().isUpperCase()) o.typeName = type
            }
            "append" -> MPrim(JsonPrimitive(params.indices.joinToString("") { i ->
                primString(argValue(i)) ?: throw EngineError("append: parameter $i has no primitive value")
            }))
            "uuid" -> MPrim(JsonPrimitive(randomUuid()))
            "c" -> MObj().also { o ->
                val system = primString(argValue(0)) ?: ""
                val code = primString(argValue(1)) ?: ""
                o.append("system", MPrim(JsonPrimitive(system)))
                o.append("code", MPrim(JsonPrimitive(code)))
                // Display comes from terminology, matching the reference engine —
                // the optional third argument is ignored (the reference drops
                // displays it cannot resolve, e.g. unlicensed code systems).
                resolveDisplay(system, code)?.let { o.append("display", MPrim(JsonPrimitive(it))) }
            }
            "translate" -> {
                val src0 = argValue(0)
                val code = primString(src0)
                    ?: readElement(src0, "code").firstOrNull()?.let { primString(it) }
                    ?: throw EngineError("translate: source has no code")
                val cmUrl = primString(argValue(1)) ?: throw EngineError("translate: missing ConceptMap url")
                val cm = resolveConceptMap(cmUrl) ?: throw EngineError("translate: ConceptMap not registered: $cmUrl")
                val mode = if (params.size > 2) primString(argValue(2)) ?: "code" else "code"
                val hit = translateCode(cm, code)
                    ?: throw EngineError("translate: no mapping for '$code' in $cmUrl")
                when (mode) {
                    "code" -> MPrim(JsonPrimitive(hit.code))
                    "display" -> MPrim(JsonPrimitive(hit.display ?: hit.code))
                    "coding", "Coding" -> MObj().also { o ->
                        hit.system?.let { o.append("system", MPrim(JsonPrimitive(it))) }
                        o.append("code", MPrim(JsonPrimitive(hit.code)))
                    }
                    else -> throw EngineError("translate: unsupported output mode '$mode'")
                }
            }
            "evaluate" -> {
                val expr = primString(argValue(0)) ?: throw EngineError("evaluate: missing expression")
                val v = evalPath(expr, null, scope).firstOrNull()
                    ?: throw EngineError("evaluate: '$expr' yielded nothing")
                when (v) { is JsonElement -> jsonToM(v); is MNode -> v; else -> throw EngineError("evaluate: bad value") }
            }
            else -> throw EngineError("Unsupported transform '$name'")
        }
    }

    /**
     * A write to one of these elements is a FHIR choice type ([x]) in the
     * corpus's target resources: occurrence[x] on Immunization; value[x] on
     * Extension (discriminated by the sibling url set in the same rule) and
     * on resources like Observation (discriminated by resourceType).
     */
    private fun isChoiceWrite(element: String, node: MObj): Boolean = when (element) {
        "occurrence", "effective", "onset", "doseNumber", "seriesDoses" -> true
        "value" -> node.fields.containsKey("url") || node.fields.containsKey("resourceType")
        else -> false
    }

    private val dateRe = Regex("\\d{4}(-\\d{2}(-\\d{2})?)?")
    private val dateTimeRe = Regex("\\d{4}-\\d{2}-\\d{2}T.*")

    private fun choiceSuffix(v: MNode): Pair<String, MNode> {
        // provenance from the source's own value[x] key wins
        (v as? MPrim)?.srcChoice?.let { return it to v }
        if (v is MObj && v.fields.size == 1) {
            (v.fields["value"]?.singleOrNull() as? MPrim)?.srcChoice?.let { tag ->
                return tag to (v.fields["value"]!!.single())
            }
        }
        // created/declared complex-type name wins over shape heuristics
        if (v is MObj && v.typeName != null) return v.typeName!! to v
        // Quantity shape (value + unit)
        if (v is MObj && v.fields.containsKey("value") && v.fields.containsKey("unit")) return "Quantity" to v
        // Coding shape
        if (v is MObj && v.fields.containsKey("code") && v.fields.containsKey("system")) return "Coding" to v
        // primitive (possibly wrapped as {value: prim})
        val prim: JsonPrimitive? = when {
            v is MPrim -> v.value
            v is MObj && v.fields.size == 1 -> (v.fields["value"]?.singleOrNull() as? MPrim)?.value
            else -> null
        }
        if (prim != null) {
            val content = prim.contentOrNull ?: ""
            val suffix = when {
                prim.booleanOrNull != null && !prim.isString -> "Boolean"
                prim.intOrNull != null && !prim.isString -> "Integer"
                prim.doubleOrNull != null && !prim.isString -> "Decimal"
                dateTimeRe.matches(content) -> "DateTime"
                dateRe.matches(content) -> "Date"
                else -> "String"
            }
            return suffix to MPrim(prim)
        }
        return "String" to v
    }

    private data class TranslateHit(val code: String, val system: String?, val display: String?)

    private fun translateCode(cm: JsonObject, code: String): TranslateHit? {
        val groups = cm["group"] as? JsonArray ?: return null
        for (g in groups) {
            val go = g as? JsonObject ?: continue
            val elements = go["element"] as? JsonArray ?: continue
            for (el in elements) {
                val o = el as? JsonObject ?: continue
                if ((o["code"] as? JsonPrimitive)?.contentOrNull == code) {
                    val tgt = (o["target"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                    val tCode = (tgt["code"] as? JsonPrimitive)?.contentOrNull ?: continue
                    return TranslateHit(
                        code = tCode,
                        system = (go["target"] as? JsonPrimitive)?.contentOrNull,
                        display = (tgt["display"] as? JsonPrimitive)?.contentOrNull
                    )
                }
            }
        }
        return null
    }

    // ---- condition evaluation (corpus-scoped FHIRPath subset) ----

    /** Handles `path = 'literal'` and bare `path` (existence). */
    private fun evalCondition(expr: String, focus: Any, scope: Scope): Boolean {
        // membership: path in ('a', 'b')
        Regex("^(.*?)\\s+in\\s*\\((.*)\\)\\s*$").find(expr)?.let { m ->
            val values = Regex("'([^']*)'").findAll(m.groupValues[2]).map { it.groupValues[1] }.toSet()
            return evalPath(m.groupValues[1].trim(), focus, scope).any { primString(it) in values }
        }
        // conjunction: A and B (top-level; corpus conditions have no nesting)
        if (expr.contains(" and ")) {
            return expr.split(" and ").all { evalCondition(it.trim(), focus, scope) }
        }
        // existence: path.exists() / path.exists().not()
        val stripped = expr.replace(" ", "")
        if (stripped.endsWith(".exists().not()")) {
            return evalPath(stripped.removeSuffix(".exists().not()"), focus, scope).isEmpty()
        }
        if (stripped.endsWith(".exists()")) {
            return evalPath(stripped.removeSuffix(".exists()"), focus, scope).isNotEmpty()
        }
        val eq = expr.split('=', limit = 2)
        return if (eq.size == 2) {
            val expected = eq[1].trim().removeSurrounding("'")
            evalPath(eq[0].trim(), focus, scope).any { primString(it) == expected }
        } else {
            evalPath(expr.trim(), focus, scope).isNotEmpty()
        }
    }

    private fun evalPath(path: String, focus: Any?, scope: Scope): List<Any> {
        val segments = path.split('.').map { it.trim() }
        if (segments.isEmpty()) return emptyList()
        var current: List<Any> =
            scope.lookup(segments[0])?.let { listOf(it) }
                ?: focus?.let { f ->
                    val first = readElement(f, segments[0])
                    if (first.isEmpty() && segments.size == 1) return emptyList() else return@let first
                }
                ?: return emptyList()
        val startIdx = if (scope.lookup(segments[0]) != null) 1 else 1
        for (i in startIdx until segments.size) {
            current = current.flatMap { readElement(it, segments[i]) }
        }
        return current
    }

    private fun randomUuid(): String {
        val b = Random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
    }
}
