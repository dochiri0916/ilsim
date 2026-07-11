package com.dochiri.convention.support

class JavaLexicalScanner {

    static int findMatchingBrace(String source, int openBraceIndex) {
        int depth = 0
        State state = State.CODE

        for (int index = openBraceIndex; index < source.length(); index++) {
            char current = source.charAt(index)
            char next = index + 1 < source.length() ? source.charAt(index + 1) : (char) 0

            if (state == State.LINE_COMMENT) {
                if (current == '\n' as char || current == '\r' as char) {
                    state = State.CODE
                }
                continue
            }
            if (state == State.BLOCK_COMMENT) {
                if (current == '*' as char && next == '/' as char) {
                    state = State.CODE
                    index++
                }
                continue
            }
            if (state == State.TEXT_BLOCK) {
                if (startsWithTripleQuote(source, index) && !isEscaped(source, index)) {
                    state = State.CODE
                    index += 2
                }
                continue
            }
            if (state == State.STRING) {
                if (current == '"' as char && !isEscaped(source, index)) {
                    state = State.CODE
                }
                continue
            }
            if (state == State.CHARACTER) {
                if (current == '\'' as char && !isEscaped(source, index)) {
                    state = State.CODE
                }
                continue
            }

            if (current == '/' as char && next == '/' as char) {
                state = State.LINE_COMMENT
                index++
                continue
            }
            if (current == '/' as char && next == '*' as char) {
                state = State.BLOCK_COMMENT
                index++
                continue
            }
            if (startsWithTripleQuote(source, index)) {
                state = State.TEXT_BLOCK
                index += 2
                continue
            }
            if (current == '"' as char) {
                state = State.STRING
                continue
            }
            if (current == '\'' as char) {
                state = State.CHARACTER
                continue
            }
            if (current == '{' as char) {
                depth++
                continue
            }
            if (current == '}' as char) {
                depth--
                if (depth == 0) {
                    return index
                }
            }
        }
        return -1
    }

    private static boolean startsWithTripleQuote(String source, int index) {
        return index + 2 < source.length()
                && source.charAt(index) == '"' as char
                && source.charAt(index + 1) == '"' as char
                && source.charAt(index + 2) == '"' as char
    }

    private static boolean isEscaped(String source, int index) {
        int backslashCount = 0
        for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\' as char; cursor--) {
            backslashCount++
        }
        return backslashCount % 2 != 0
    }

    private static enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER,
        TEXT_BLOCK
    }
}
