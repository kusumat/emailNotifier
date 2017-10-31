def call(String resourcePath) {
    String resource

    catchErrorCustom('Failed to load resource') {
        resource = libraryResource resourcePath
    }

    resource
}
