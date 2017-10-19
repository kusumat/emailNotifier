def call(String shellScript, Boolean isUnixNode, Map args = [:]) {
    String shellType = (isUnixNode) ? 'sh' : 'bat'

    "$shellType" script: shellScript,
            returnStatus: (args.returnStatus) ?: false,
            returnStdout: (args.returnStdout) ?: false
}