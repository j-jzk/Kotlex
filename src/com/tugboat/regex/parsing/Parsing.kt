package com.tugboat.regex.parsing

import com.tugboat.regex.Regexp
import com.tugboat.regex.Symbol

/**
 * Parses a list of regex tokens using recursive descent.
 * See: the grammar.
 *
 * @throws RegexParsingException if a syntax error is detected.
 */
fun parse(tokens: List<Token>): Regexp {
    return StatefulParser(tokens).parse()
}

class RegexParsingException(message: String): Exception(message)

/**
 * A class that uses its state to track the parsing, hence it's not
 * thread-safe.
 */
private class StatefulParser(tokens: List<Token>) {

    var tokensIt = tokens.listIterator()

    fun parse(): Regexp {
        val parsedExpression = regexp()
        if (hasNext()) {
            throw RegexParsingException("Tokens left over after attempted parse.")
        }
        return parsedExpression
    }

    private fun regexp(): Regexp {
        // This is called inside a group, it can be "empty" but with characters
        // still remaining. Would possibly be neater to allow or() to return
        // an empty concatenation.
        if (!hasNext() || nextMatches(TokenType.RIGHT_ROUND_BRACKET)) {
            return Regexp.Concatenation()
        }
        return or()
    }

    private fun or(): Regexp {
        return orOptional(concatenation())
    }

    private fun orOptional(leftPart: Regexp): Regexp {
        if (nextMatches(TokenType.OR)) {
            skip()
            return orOptional(Regexp.Or(leftPart, concatenation()))
        }
        return leftPart
    }

    private fun skip() {
        tokensIt.next()
    }

    private fun hasNext(): Boolean {
        return tokensIt.hasNext()
    }

    private fun nextMatches(vararg matchTypes: TokenType): Boolean {
        if (!hasNext()) {
            return false
        }
        val next: Token = getNext()
        rewind()
        return matchTypes.any { it == next.type }
    }

    private fun nextType(): TokenType? {
        if (!hasNext()) {
            return null
        }

        val type = getNext().type
        rewind()
        return type
    }

    private fun nextMatchesRaw(match: Char): Boolean {
        if (!hasNext())
            return false

            val next = getNext()
            rewind()
            return next.raw == match
    }

    private fun getNext(): Token {
        return tokensIt.next()
    }

    private fun nextOrThrow(msg: String): Token {
        if (!hasNext())
            throw RegexParsingException(msg)

        return getNext()
    }

    private fun rewind() {
        tokensIt.previous()
    }

    private fun concatenation(): Regexp {
        val subexpressions: MutableList<Regexp> = mutableListOf()
        while (nextMatches(TokenType.LEFT_ROUND_BRACKET, TokenType.DOT, TokenType.CHARACTER, TokenType.BACKSLASH, TokenType.LEFT_SQUARE_BRACKET)) {
            subexpressions.add(unit())
        }
        if (subexpressions.isEmpty()) {
            throw RegexParsingException("Expected symbols but found none.")
        } else if (subexpressions.size == 1) {
            return subexpressions.first()
        }
        return Regexp.Concatenation(subexpressions)
    }

    private fun unit(): Regexp {
        return modifier(unmodifiedUnit())
    }

    private fun unmodifiedUnit(): Regexp {
        val next: Token = getNext()
        return when(next.type) {
            TokenType.CHARACTER -> Regexp.CharMatcher(Symbol.RawCharacter(next.raw))
            TokenType.DOT -> Regexp.CharMatcher(Symbol.Dot)
            TokenType.LEFT_ROUND_BRACKET -> group()
            TokenType.BACKSLASH -> backslashedCharacter()
            TokenType.LEFT_SQUARE_BRACKET -> characterRange()
            else -> throw RegexParsingException("Failed to parse $next!")
        }
    }

    private fun modifier(expressionToModify: Regexp): Regexp {
        if (!hasNext()) {
            return expressionToModify
        }

        val type = nextType()
        skip()
        return when (type) {
            TokenType.STAR -> Regexp.ZeroOrMoreTimes(expressionToModify)
            TokenType.PLUS -> Regexp.OneOrMoreTimes(expressionToModify)
            TokenType.QUESTION_MARK -> Regexp.ZeroOrOneTime(expressionToModify)
            else -> {
                rewind()
                expressionToModify
            }
        }
    }

    private fun group(): Regexp {
        val subexpression = regexp()
        if (!nextMatches(TokenType.RIGHT_ROUND_BRACKET)) {
            throw RegexParsingException("Unclosed group!")
        }
        skip()
        return Regexp.Group(subexpression)
    }

    private fun characterRange(): Regexp {
        val inverted = nextMatchesRaw('^')
        if (inverted) skip()

        val symbol = if (inverted)
            Symbol.LogicalNot(characterRangeDefinition())
        else
            characterRangeDefinition()

        return Regexp.CharMatcher(symbol)
    }

    private fun characterRangeDefinition(): Symbol {
        val characters = mutableListOf<Char>()

        while (hasNext() && !nextMatches(TokenType.RIGHT_SQUARE_BRACKET)) {
            val next = nonSpecialCharacter("-^]\\".toList()) ?: throw RegexParsingException("Unexpected special character in a character range")

            if (nextMatchesRaw('-')) {  // comprehension
                skip()
                val end = nonSpecialCharacter("-^]\\".toList()) ?: throw RegexParsingException("Unexpected special character after a hyphen in a character range")

                if (next > end)
                    throw RegexParsingException("Bad comprehension in a character range (first character has larger ASCII value than the second one)")

                characters += next .. end
            } else {
                characters += next
            }
        }

        if (!nextMatches(TokenType.RIGHT_SQUARE_BRACKET))
            throw RegexParsingException("Unclosed character range")
        skip()

        return Symbol.AnyOf(characters)
    }

    private fun backslashedCharacter(): Regexp {
        if (!hasNext()) {
            throw RegexParsingException("No character after a backslash!")
        }

        val next = getNext()

        return when {
            next in specialTokens -> Regexp.CharMatcher(Symbol.RawCharacter(next.raw))
            CharClasses.isSingleCharClass(next.raw) -> Regexp.CharMatcher(CharClasses.getSingleCharClass(next.raw)!!)
            else -> throw RegexParsingException("Invalid character after a backslash: \\${next.raw}")
        }
    }

    /**
     * A helper function which either returns a regular character (not in
     * `specialChars`) or a backslash-escaped character from `specialChars`.
     */
    private fun nonSpecialCharacter(specialChars: Iterable<Char>): Char? {
        val next = nextOrThrow("Unexpected end of input!")
        when {
            next.type == TokenType.BACKSLASH -> {
                val escaped = nextOrThrow("No character after a backslash!")

                if (escaped.raw in specialChars)
                    return escaped.raw
                else
                    throw RegexParsingException("Invalid character after a backslash: \\${escaped.raw}")
            }
            next.raw in specialChars -> return null
            else -> return next.raw
        }
    }
}

/**
 * This is just for separating the code for character classes from the main parser
 */
private object CharClasses {
    private val singleCharClasses: Map<Char, Symbol> = mapOf(
        's' to Symbol.AnyOf(" \t\r\n\u000B\u000C"),
        'S' to Symbol.NoneOf(" \t\r\n\u000B\u000C"),
        'd' to Symbol.AnyOf("0123456789"),
        'D' to Symbol.NoneOf("0123456789"),
        'w' to Symbol.AnyOf(('a'..'z') + ('A'..'Z') + ('0'..'9') + arrayOf('_')),
        'W' to Symbol.NoneOf(('a'..'z') + ('A'..'Z') + ('0'..'9') + arrayOf('_')),
        'x' to Symbol.AnyOf(('a'..'f') + ('A'..'F') + ('0'..'9')),
        'O' to Symbol.AnyOf("01234567"),
        'n' to Symbol.RawCharacter('\n'),
        'r' to Symbol.RawCharacter('\r'),
        't' to Symbol.RawCharacter('\t'),
        'v' to Symbol.RawCharacter('\u000B'),
        'f' to Symbol.RawCharacter('\u000C')
    )

    fun isSingleCharClass(c: Char) = c in singleCharClasses
    fun getSingleCharClass(c: Char) = singleCharClasses[c]
}
