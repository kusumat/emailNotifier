import com.kony.appfactory.visualizer.BuildStatus

def call(BuildStatus buildStatus, String stageName, closure) {
    stage("${stageName}"){
        buildStatus.updateStage(stageName)
        closure()
    }
}
