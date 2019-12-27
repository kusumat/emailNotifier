package com.kony.appfactory.sanitizers

/**
 * Wrapper of TextSanitizer which allow us to replace
 * logs based on Regular Expression. It will remove all the
 * occurrences matching to Regular Expression in text content.
 */
class RegexSanitizer extends TextSanitizer {
    private TextSanitizer nextSanitizer
    String regex
    String replaceTo

    RegexSanitizer(String regex, String replaceTo, sanitizer) {
        this.regex = regex
        this.nextSanitizer = sanitizer
        this.replaceTo = replaceTo
    }

    /**
     * This method sanitize logs by replacing content based on
     * given regex. Once this operation is done it delegates call
     * to the next TextSanitizer wrapper in chain.
     *
     * @param buildLogs
     * @return modified buildLogs
     */
    def sanitize(String text) {
        if(regex) {
            def modifiedText = text.replaceAll(regex, replaceTo)
            return nextSanitizer.sanitize(modifiedText)
        }
        else
            return text
    }
}
