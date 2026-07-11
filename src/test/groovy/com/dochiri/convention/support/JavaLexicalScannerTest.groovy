package com.dochiri.convention.support

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JavaLexicalScannerTest {

    @Test
    @DisplayName('CR line comment와 escape 문자열 뒤의 실제 닫는 brace를 찾는다')
    void findsBraceAfterCarriageReturnAndEscapedQuotes() {
        // given
        String source = "{ // fake }\r \"escaped \\\\\\\" brace }\"; char quote = '\\\''; }"

        // when
        int closingBrace = JavaLexicalScanner.findMatchingBrace(source, 0)

        // then
        assert closingBrace == source.length() - 1
    }

    @Test
    @DisplayName('text block의 escaped delimiter와 brace를 건너뛴다')
    void ignoresEscapedTextBlockDelimiterAndBrace() {
        // given
        String source = '''{ """
                text \\""" with fake brace }
                still text
                """; }'''

        // when
        int closingBrace = JavaLexicalScanner.findMatchingBrace(source, 0)

        // then
        assert closingBrace == source.length() - 1
    }

    @Test
    @DisplayName('닫히지 않은 lexical token이나 brace는 일치 위치가 없다고 반환한다')
    void returnsMissingForUnclosedLexicalStates() {
        // given
        List<String> sources = [
                '{',
                '{ "unterminated string',
                "{ 'unterminated character",
                '{ /* unterminated block comment',
                '{ // unterminated line comment',
                '{ """ unterminated text block'
        ]

        // when
        List<Integer> results = sources.collect { source ->
            JavaLexicalScanner.findMatchingBrace(source, 0)
        }

        // then
        assert results.every { result -> result == -1 }
    }
}
