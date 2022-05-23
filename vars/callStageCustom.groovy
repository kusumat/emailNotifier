

def call(BuildStatus buildStatus, String stageName, closure) {
    stage("${stageName}"){

        closure()
    }
}
