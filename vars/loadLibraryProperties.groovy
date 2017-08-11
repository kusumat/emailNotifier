def call(propertyFileName) {
    def libraryProperties
    String successMessage = 'Properties loading finished successfully'
    String errorMessage = 'FAILED to load properties'

    catchErrorCustom(successMessage, errorMessage) {
        libraryProperties = readProperties text: loadLibraryResource(propertyFileName)
    }

    libraryProperties
}