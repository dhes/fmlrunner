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
        val sources = mutableListOf<StructureMapGroupRuleSource>()
        
        // Parse source
        sources.add(parseRuleSource())

        // Expect arrow
        if (!match(TokenType.ARROW)) {
            throw IllegalArgumentException(err("Expected '->' in rule"))
        }

        // Parse targets
        val targets = mutableListOf<StructureMapGroupRuleTarget>()
        targets.add(parseRuleTarget())

        // Expect semicolon
        if (!match(TokenType.SEMICOLON)) {
            throw IllegalArgumentException(err("Expected ';' to end rule"))
        }

        return StructureMapGroupRule(
            source = sources,
            target = targets
        )
    }

    private fun parseRuleSource(): StructureMapGroupRuleSource {
        val context = if (peek().type == TokenType.IDENTIFIER) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected source context"))
        }

        var element: String? = null
        if (match(TokenType.DOT)) {
            element = if (peek().type == TokenType.IDENTIFIER) {
                advance().value
            } else {
                throw IllegalArgumentException(err("Expected element name after '.'"))
            }
        }

        return StructureMapGroupRuleSource(
            context = context,
            element = element
        )
    }

    private fun parseRuleTarget(): StructureMapGroupRuleTarget {
        val context = if (peek().type == TokenType.IDENTIFIER) {
            advance().value
        } else {
            throw IllegalArgumentException(err("Expected target context"))
        }

        var element: String? = null
        if (match(TokenType.DOT)) {
            element = if (peek().type == TokenType.IDENTIFIER) {
                advance().value
            } else {
                throw IllegalArgumentException(err("Expected element name after '.'"))
            }
        }

        return StructureMapGroupRuleTarget(
            context = context,
            contextType = ContextType.VARIABLE,
            element = element
        )
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