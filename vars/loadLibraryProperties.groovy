def call(String propertyFileName) {
    def libraryProperties

    catchErrorCustom('Failed to load properties') {
        libraryProperties = readProperties text: loadLibraryResource(propertyFileName)
    }

    libraryProperties
}