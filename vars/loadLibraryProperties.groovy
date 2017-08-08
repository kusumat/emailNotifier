def call(String propertyFileName) {
    def libraryProperties

    catchErrorCustom('FAILED to load properties') {
        libraryProperties = readProperties text: loadLibraryResource(propertyFileName)
    }

    libraryProperties
}