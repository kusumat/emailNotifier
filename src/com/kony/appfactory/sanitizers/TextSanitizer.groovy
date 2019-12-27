package com.kony.appfactory.sanitizers

/**
 * Base Text Sanitizer. All Sanitizer follows Decorator Pattern (Groovy).
 * Using the Design pattern we can warp TextSanitizer into other wrapper.
 *
 * Each wrapper is a decorator which perform some operation and update the
 * content for base class.
 *
 * Each new wrapper needs to extend this class and implement its own sanitize
 * method which doesn't custom operation and delegates modified content to
 * base class.
 */
class TextSanitizer implements Serializable {

    /**
     * Wrapper of TextSanitizer needs to call this methods to
     * stop further chaining and return response.
     */
    def sanitize(text) {
        return text
    }
}


