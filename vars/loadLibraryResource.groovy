def call(resourcePath) {
    def resource = ''
    String successMessage = 'Resource loading finished successfully'
    String errorMessage = 'FAILED to load resource'

    catchErrorCustom(successMessage, errorMessage) {
        resource = libraryResource resourcePath
    }

    resource
}