package com.kony.appfactory.sanitizers

/**
 * Wrapper of TextSanitizer which allow us to replace
 * given range of lines from logs.
 */
class RemoveLinesSanitizer extends TextSanitizer {
    private TextSanitizer nextSanitizer
    int startLine
    int endLine

    RemoveLinesSanitizer(int startLineNumber, int endLineNumber, sanitizer) {
        this.startLine = startLineNumber
        this.endLine = endLineNumber
        this.nextSanitizer = sanitizer
    }

    /**
     * This method removes lines starting from <p>startLine</p>
     * till <p>endLine</p>. Once this operation is done delegate
     * call the next wrapper in chain.
     *
     * @param buildLogs
     * @return modified buildLogs
     */
    def sanitize(String text) {
        def modifiedLogs = ''
        int count = 1
        text.readLines().each { line ->
            if (count < startLine || count > endLine) {
                modifiedLogs = modifiedLogs + line + "\n"
            }
            count++
        }
        return nextSanitizer.sanitize(modifiedLogs)
    }
}
