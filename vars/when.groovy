import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(boolean condition, stage, body) {
    def config = [:]
    body.resolveStrategy = Closure.OWNER_FIRST
    body.delegate = config

    if (condition) {
        body()
    } else {
        println("Skipping stage ${stage}")
        Utils.markStageSkippedForConditional(stage)
    }
}