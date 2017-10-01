package com.kony.appfactory.helper

class ValidationHelper implements Serializable {
    protected static checkForNull(items, requiredItems) {
        items?.findResults {
            if (requiredItems?.contains(it.key) && !it.value) {
                it.key
            }
        }
    }
}
