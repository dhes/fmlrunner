package org.litlfred.fmlrunner.compiler

import org.litlfred.fmlrunner.types.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * FML Token types based on FHIR Mapping Language specification
 */
enum class TokenType {
    // Keywords
    MAP, USES, IMPORTS, CONCEPTMAP, PREFIX, GROUP, INPUT, RULE, WHERE, CHECK, LOG, AS, ALIAS, MODE,
    
    // Identifiers and literals
    IDENTIFIER, STRING, NUMBER, CONSTANT,
    
    // Operators and symbols
    ARROW, COLON, SEMICOLON, COMMA, DOT, EQUALS, LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    
    // Special
    NEWLINE, EOF, WHITESPACE, COMMENT
}

/**
 * FML Token
 */
data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val column: Int
)

/**
 * FML Tokenizer for FHIR Mapping Language
 */
class FmlTokenizer(private val input: String) {
    private var position = 0
    private var line = 1
    private var column = 1

    /**
     * Tokenize the input string
     */
    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        
        // Skip initial whitespace and newlines
        while (!isAtEnd() && (isWhitespace(peek()) || peek() == '\n')) {
            advance()
        }
        
        while (!isAtEnd()) {
            val token = nextToken()
            if (token != null && token.type != TokenType.WHITESPACE && 
                token.type != TokenType.COMMENT && token.type != TokenType.NEWLINE) {
                tokens.add(token)
            }
        }
        
        tokens.add(Token(TokenType.EOF, "", line, column))
        return tokens
    }

    private fun nextToken(): Token? {
        if (isAtEnd()) return null

        val start = position
        val startLine = line
        val startColumn = column
        val char = advance()

        // Skip whitespace
        if (isWhitespace(char)) {
            while (!isAtEnd() && isWhitespace(peek())) {
                advance()
            }
            return Token(TokenType.WHITESPACE, input.substring(start, position), startLine, startColumn)
        }

        // Handle newlines
        if (char == '\n') {
            return Token(TokenType.NEWLINE, char.toString(), startLine, startColumn)
        }

        // Handle comments
        if (char == '/') {
            if (peek() == '/') {
                // Single-line comment
                while (!isAtEnd() && peek() != '\n') {
                    advance()
                }
                return Token(TokenType.COMMENT, input.substring(start, position), startLine, startColumn)
            }
            if (peek() == '*') {
                // Block comment
                advance()
                while (!isAtEnd() && !(peek() == '*' && position + 1 < input.length && input[position + 1] == '/')) {
                    advance()
                }
                if (!isAtEnd()) {
                    advance() // '*'
                    advance() // '/'
                }
                return Token(TokenType.COMMENT, input.substring(start, position), startLine, startColumn)
            }
        }

        // Handle strings
        if (char == '"' || char == '\'') {
            return parseString(char, start, startLine, startColumn)
        }

        // Handle numbers
        if (char.isDigit()) {
            return parseNumber(start, startLine, startColumn)
        }

        // Handle arrows and operators
        when (char) {
            '-' -> {
                if (peek() == '>') {
                    advance()
                    return Token(TokenType.ARROW, "->", startLine, startColumn)
                }
            }
            ':' -> return Token(TokenType.COLON, ":", startLine, startColumn)
            ';' -> return Token(TokenType.SEMICOLON, ";", startLine, startColumn)
            ',' -> return Token(TokenType.COMMA, ",", startLine, startColumn)
            '.' -> return Token(TokenType.DOT, ".", startLine, startColumn)
            '=' -> return Token(TokenType.EQUALS, "=", startLine, startColumn)
            '(' -> return Token(TokenType.LPAREN, "(", startLine, startColumn)
            ')' -> return Token(TokenType.RPAREN, ")", startLine, startColumn)
            '{' -> return Token(TokenType.LBRACE, "{", startLine, startColumn)
            '}' -> return Token(TokenType.RBRACE, "}", startLine, startColumn)
            '[' -> return Token(TokenType.LBRACKET, "[", startLine, startColumn)
            ']' -> return Token(TokenType.RBRACKET, "]", startLine, startColumn)
        }

        // Handle identifiers and keywords
        if (char.isLetter() || char == '_') {
            return parseIdentifier(start, startLine, startColumn)
        }

        // Unknown character - return as identifier
        return Token(TokenType.IDENTIFIER, char.toString(), startLine, startColumn)
    }

    private fun parseString(quote: Char, start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\n') line++
            advance()
        }

        if (isAtEnd()) {
            // Unterminated string
            return Token(TokenType.STRING, input.substring(start, position), startLine, startColumn)
        }

        // Consume closing quote
        advance()
        
        // Remove quotes from value
        val value = input.substring(start + 1, position - 1)
        return Token(TokenType.STRING, value, startLine, startColumn)
    }

    private fun parseNumber(start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && (peek().isDigit() || peek() == '.')) {
            advance()
        }
        
        return Token(TokenType.NUMBER, input.substring(start, position), startLine, startColumn)
    }

    private fun parseIdentifier(start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && (peek().isLetterOrDigit() || peek() == '_' || peek() == '-')) {
            advance()
        }

        val value = input.substring(start, position)
        val tokenType = when (value.uppercase()) {
            "MAP" -> TokenType.MAP
            "USES" -> TokenType.USES
            "IMPORTS" -> TokenType.IMPORTS
            "CONCEPTMAP" -> TokenType.CONCEPTMAP
            "PREFIX" -> TokenType.PREFIX
            "GROUP" -> TokenType.GROUP
            "INPUT" -> TokenType.INPUT
            "RULE" -> TokenType.RULE
            "WHERE" -> TokenType.WHERE
            "CHECK" -> TokenType.CHECK
            "LOG" -> TokenType.LOG
            "AS" -> TokenType.AS
            "ALIAS" -> TokenType.ALIAS
            "MODE" -> TokenType.MODE
            else -> TokenType.IDENTIFIER
        }

        return Token(tokenType, value, startLine, startColumn)
    }

    private fun isAtEnd(): Boolean = position >= input.length

    private fun peek(): Char = if (isAtEnd()) '\u0000' else input[position]

    private fun advance(): Char {
        if (!isAtEnd()) {
            val char = input[position]
            position++
            if (char == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return char
        }
        return '\u0000'
    }

    private fun isWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r'
}

/**
 * FML Parser for converting tokens to StructureMap
 */
class FmlParser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): FmlCompilationResult {
        return try {
            val structureMap = parseStructureMap()
            FmlCompilationResult(success = true, structureMap = structureMap)
        } catch (e: Exception) {
            FmlCompilationResult(success = false, errors = listOf(e.message ?: "Unknown parsing error"))
        }
    }

    private fun parseStructureMap(): StructureMap {
        // Expect "map"
        if (!match(TokenType.MAP)) {
            throw IllegalArgumentException(err("Expected 'map' keyword at start of StructureMap"))
        }

        // Parse URL
        val url = if (peek().type == TokenType.STRING) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected URL string after 'map'"))
        }

        // Parse "="
        if (!match(TokenType.EQUALS)) {
            throw IllegalArgumentException(err("Expected '=' after URL"))
        }

        // Parse name
        val name = if (peek().type == TokenType.STRING) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected name string after '='"))
        }

        // Parse header declarations: uses ... / imports ...
        val structures = mutableListOf<StructureMapStructure>()
        val imports = mutableListOf<String>()
        while (true) {
            when (peek().type) {
                TokenType.USES -> structures.add(parseUses())
                TokenType.IMPORTS -> {
                    advance()
                    if (peek().type != TokenType.STRING) {
                        throw IllegalArgumentException(err("Expected URL string after 'imports'"))
                    }
                    imports.add(advance().value)
                }
                else -> break
            }
        }

        // Parse groups
        val groups = mutableListOf<StructureMapGroup>()
        while (!isAtEnd() && peek().type == TokenType.GROUP) {
            groups.add(parseGroup())
        }

        if (groups.isEmpty()) {
            throw IllegalArgumentException(err("StructureMap must have at least one group"))
        }

        return StructureMap(
            url = url,
            name = name,
            status = StructureMapStatus.ACTIVE,
            structure = structures.ifEmpty { null },
            import = imports.ifEmpty { null },
            group = groups
        )
    }

    // uses "url" [alias Name] as source|queried|target|produced
    private fun parseUses(): StructureMapStructure {
        advance() // 'uses'
        if (peek().type != TokenType.STRING) {
            throw IllegalArgumentException(err("Expected URL string after 'uses'"))
        }
        val url = advance().value
        var alias: String? = null
        if (peek().type == TokenType.ALIAS) {
            advance()
            if (peek().type != TokenType.IDENTIFIER) {
                throw IllegalArgumentException(err("Expected alias name after 'alias'"))
            }
            alias = advance().value
        }
        if (peek().type != TokenType.AS) {
            throw IllegalArgumentException(err("Expected 'as' in uses declaration"))
        }
        advance()
        if (peek().type != TokenType.IDENTIFIER) {
            throw IllegalArgumentException(err("Expected model mode after 'as'"))
        }
        val modeStr = advance().value
        val mode = when (modeStr.lowercase()) {
            "source" -> StructureMapModelMode.SOURCE
            "queried" -> StructureMapModelMode.QUERIED
            "target" -> StructureMapModelMode.TARGET
            "produced" -> StructureMapModelMode.PRODUCED
            else -> throw IllegalArgumentException(err("Invalid model mode: $modeStr"))
        }
        return StructureMapStructure(url = url, mode = mode, alias = alias)
    }

    private fun err(message: String): String {
        val t = peek()
        return "$message (line ${t.line}, near '${t.value.take(20)}')"
    }

    private fun parseGroup(): StructureMapGroup {
        if (!match(TokenType.GROUP)) {
            throw IllegalArgumentException(err("Expected 'group' keyword"))
        }

        val name = if (peek().type == TokenType.IDENTIFIER) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected group name"))
        }

        if (!match(TokenType.LPAREN)) {
            throw IllegalArgumentException(err("Expected '(' after group name"))
        }

        // Parse inputs
        val inputs = mutableListOf<StructureMapGroupInput>()
        while (!check(TokenType.RPAREN)) {
            inputs.add(parseInput())
            if (!check(TokenType.RPAREN)) {
                if (!match(TokenType.COMMA)) {
                    throw IllegalArgumentException(err("Expected ',' between inputs"))
                }
            }
        }

        if (!match(TokenType.RPAREN)) {
            throw IllegalArgumentException(err("Expected ')' after inputs"))
        }

        if (!match(TokenType.LBRACE)) {
            throw IllegalArgumentException(err("Expected '{' to start group body"))
        }

        // Parse rules
        val rules = mutableListOf<StructureMapGroupRule>()
        while (!check(TokenType.RBRACE)) {
            rules.add(parseRule())
        }

        if (!match(TokenType.RBRACE)) {
            throw IllegalArgumentException(err("Expected '}' to end group body"))
        }

        return StructureMapGroup(
            name = name,
            input = inputs,
            rule = rules
        )
    }

    private fun parseInput(): StructureMapGroupInput {
        val mode = when (peek().type) {
            TokenType.IDENTIFIER -> {
                val modeStr = advance().value
                when (modeStr.lowercase()) {
                    "source" -> InputMode.SOURCE
                    "target" -> InputMode.TARGET
                    else -> throw IllegalArgumentException(err("Invalid input mode: $modeStr"))
                }
            }
            else -> throw IllegalArgumentException(err("Expected input mode (source/target)"))
        }

        val name = if (peek().type == TokenType.IDENTIFIER) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected input name"))
        }

        // Optional type: `source qr : QResp`
        var type: String? = null
        if (match(TokenType.COLON)) {
            type = if (peek().type == TokenType.IDENTIFIER) {
                advance().value
            } else {
                throw IllegalArgumentException(err("Expected input type after ':'"))
            }
        }

        return StructureMapGroupInput(
            name = name,
            type = type,
            mode = mode
        )
    }

    private fun parseRule(): StructureMapGroupRule {
        val sources = mutableListOf(parseRuleSource())
        while (match(TokenType.COMMA)) sources.add(parseRuleSource())

        var targets: MutableList<StructureMapGroupRuleTarget>? = null
        if (match(TokenType.ARROW)) {
            targets = mutableListOf(parseRuleTarget())
            while (match(TokenType.COMMA)) targets.add(parseRuleTarget())
        }

        var nested: MutableList<StructureMapGroupRule>? = null
        var dependents: MutableList<StructureMapGroupRuleDependent>? = null
        if (peek().type == TokenType.IDENTIFIER && peek().value == "then") {
            advance()
            if (match(TokenType.LBRACE)) {
                nested = mutableListOf()
                while (!check(TokenType.RBRACE) && !isAtEnd()) {
                    nested.add(parseRule())
                }
                if (!match(TokenType.RBRACE)) {
                    throw IllegalArgumentException(err("Expected '}' to end nested rules"))
                }
            } else {
                dependents = mutableListOf(parseDependent())
                while (match(TokenType.COMMA)) dependents.add(parseDependent())
            }
        }

        var name: String? = null
        if (peek().type == TokenType.STRING) {
            name = advance().value
        }

        if (!match(TokenType.SEMICOLON)) {
            throw IllegalArgumentException(err("Expected ';' to end rule"))
        }

        return StructureMapGroupRule(
            name = name,
            source = sources,
            target = targets,
            rule = nested,
            dependent = dependents
        )
    }

    private fun parseDependent(): StructureMapGroupRuleDependent {
        val name = expectIdentifier("dependent group name")
        if (!match(TokenType.LPAREN)) {
            throw IllegalArgumentException(err("Expected '(' after dependent group name"))
        }
        val variables = mutableListOf<String>()
        while (!check(TokenType.RPAREN)) {
            variables.add(expectIdentifier("dependent variable"))
            if (!check(TokenType.RPAREN) && !match(TokenType.COMMA)) {
                throw IllegalArgumentException(err("Expected ',' between dependent variables"))
            }
        }
        advance() // ')'
        return StructureMapGroupRuleDependent(name = name, variable = variables)
    }

    private fun parseRuleSource(): StructureMapGroupRuleSource {
        val context = expectIdentifier("source context")
        var element: String? = null
        if (match(TokenType.DOT)) {
            element = expectIdentifier("element name after '.'")
        }
        var listMode: String? = null
        var variable: String? = null
        var condition: String? = null
        var checkExpr: String? = null
        loop@ while (true) {
            val t = peek()
            when {
                t.type == TokenType.IDENTIFIER && t.value in LIST_MODES && listMode == null ->
                    listMode = advance().value
                t.type == TokenType.AS -> {
                    advance()
                    variable = expectIdentifier("variable name after 'as'")
                }
                t.type == TokenType.WHERE -> {
                    advance()
                    condition = captureExpression()
                }
                t.type == TokenType.CHECK -> {
                    advance()
                    checkExpr = captureExpression()
                }
                else -> break@loop
            }
        }
        return StructureMapGroupRuleSource(
            context = context,
            element = element,
            variable = variable,
            listMode = listMode,
            condition = condition,
            check = checkExpr
        )
    }

    private fun parseRuleTarget(): StructureMapGroupRuleTarget {
        // Invocation form: create("...") as model, uuid() as pid, c(system, code)
        if (peek().type == TokenType.IDENTIFIER && peekNext().type == TokenType.LPAREN) {
            val fn = advance().value
            val params = parseTransformParams()
            var variable: String? = null
            if (peek().type == TokenType.AS) {
                advance()
                variable = expectIdentifier("variable name after 'as'")
            }
            return StructureMapGroupRuleTarget(
                variable = variable,
                transform = fn,
                parameter = params.ifEmpty { null }
            )
        }

        // Context form: ctx('.' element)? ('=' transform)? ('as' var)?
        val context = expectIdentifier("target context")
        var element: String? = null
        if (match(TokenType.DOT)) {
            element = expectIdentifier("element name after '.'")
        }
        var transform: String? = null
        var parameter: List<TransformParameter>? = null
        if (match(TokenType.EQUALS)) {
            val t = peek()
            when {
                t.type == TokenType.IDENTIFIER && peekNext().type == TokenType.LPAREN -> {
                    transform = advance().value
                    parameter = parseTransformParams().ifEmpty { null }
                }
                t.type == TokenType.STRING -> {
                    transform = "copy"
                    parameter = listOf(TransformParameter(valueString = advance().value))
                }
                t.type == TokenType.NUMBER -> {
                    transform = "copy"
                    parameter = listOf(numberParam(advance().value))
                }
                t.type == TokenType.IDENTIFIER -> {
                    var path = advance().value
                    if (check(TokenType.DOT)) {
                        while (match(TokenType.DOT)) {
                            path += "." + expectIdentifier("path segment")
                        }
                        transform = "evaluate"
                        parameter = listOf(TransformParameter(valueString = path))
                    } else {
                        transform = "copy"
                        parameter = listOf(TransformParameter(valueId = path))
                    }
                }
                else -> throw IllegalArgumentException(err("Expected transform after '='"))
            }
        }
        var variable: String? = null
        if (peek().type == TokenType.AS) {
            advance()
            variable = expectIdentifier("variable name after 'as'")
        }
        return StructureMapGroupRuleTarget(
            context = context,
            contextType = ContextType.VARIABLE,
            element = element,
            variable = variable,
            transform = transform,
            parameter = parameter
        )
    }

    private fun parseTransformParams(): List<TransformParameter> {
        if (!match(TokenType.LPAREN)) {
            throw IllegalArgumentException(err("Expected '(' to start parameters"))
        }
        val params = mutableListOf<TransformParameter>()
        while (!check(TokenType.RPAREN)) {
            val t = peek()
            params.add(when (t.type) {
                TokenType.STRING -> TransformParameter(valueString = advance().value)
                TokenType.NUMBER -> numberParam(advance().value)
                TokenType.IDENTIFIER -> when (t.value) {
                    "true" -> { advance(); TransformParameter(valueBoolean = true) }
                    "false" -> { advance(); TransformParameter(valueBoolean = false) }
                    else -> TransformParameter(valueId = advance().value)
                }
                else -> throw IllegalArgumentException(err("Expected parameter"))
            })
            if (!check(TokenType.RPAREN) && !match(TokenType.COMMA)) {
                throw IllegalArgumentException(err("Expected ',' between parameters"))
            }
        }
        advance() // ')'
        return params
    }

    private fun numberParam(raw: String): TransformParameter =
        if (raw.contains('.')) TransformParameter(valueDecimal = raw.toDouble())
        else TransformParameter(valueInteger = raw.toInt())

    /**
     * Capture a FHIRPath expression as raw-ish text: tokens joined with spaces
     * (FHIRPath is whitespace-insensitive), strings re-quoted single. Stops at
     * a rule boundary (->, ;, ',', as, check, then) at paren depth 0.
     */
    private fun captureExpression(): String {
        val sb = StringBuilder()
        var depth = 0
        while (!isAtEnd()) {
            val t = peek()
            if (depth == 0 && (t.type == TokenType.ARROW || t.type == TokenType.SEMICOLON ||
                    t.type == TokenType.COMMA || t.type == TokenType.AS || t.type == TokenType.CHECK ||
                    (t.type == TokenType.IDENTIFIER && t.value == "then"))) break
            if (t.type == TokenType.LPAREN) depth++
            if (t.type == TokenType.RPAREN) {
                if (depth == 0) break
                depth--
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(if (t.type == TokenType.STRING) "'" + t.value + "'" else t.value)
            advance()
        }
        if (sb.isEmpty()) {
            throw IllegalArgumentException(err("Expected expression"))
        }
        return sb.toString()
    }

    private fun expectIdentifier(what: String): String {
        if (peek().type != TokenType.IDENTIFIER) {
            throw IllegalArgumentException(err("Expected $what"))
        }
        return advance().value
    }

    private fun peekNext(): Token =
        if (current + 1 < tokens.size) tokens[current + 1] else tokens[tokens.size - 1]

    companion object {
        private val LIST_MODES = setOf("first", "not_first", "last", "not_last", "only_one")
    }

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        return peek().type == type
    }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): Token = tokens[current]

    private fun previous(): Token = tokens[current - 1]
}

/**
 * Main FML Compiler class
 */
class FmlCompiler {
    
    /**
     * Compile FML content to StructureMap
     */
    fun compile(fmlContent: String): FmlCompilationResult {
        return try {
            // Tokenize
            val tokenizer = FmlTokenizer(fmlContent)
            val tokens = tokenizer.tokenize()
            
            // Parse
            val parser = FmlParser(tokens)
            parser.parse()
        } catch (e: Exception) {
            FmlCompilationResult(
                success = false,
                errors = listOf("Compilation failed: ${e.message}")
            )
        }
    }
}