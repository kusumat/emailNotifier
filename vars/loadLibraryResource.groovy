def call(String resourcePath) {
    def resource

    catchErrorCustom('FAILED to load resource') {
        resource = libraryResource resourcePath
    }

    resource
}
