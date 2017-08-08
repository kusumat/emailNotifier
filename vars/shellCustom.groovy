def call(String shellScript, Boolean isUnixNode = true, Map args = [:]) {
    String shellType = (isUnixNode) ? 'sh' : 'bat'

    "$shellType" script: ((!isUnixNode) ? shellScript : '#!/bin/sh -e\n' + shellScript),
            returnStatus: (args.returnStatus) ?: false,
            returnStdout: (args.returnStdout) ?: false
}
