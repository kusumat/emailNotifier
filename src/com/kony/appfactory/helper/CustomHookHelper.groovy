package com.kony.appfactory.helper

import com.kony.appfactory.helper.ConfigFileHelper
import jenkins.model.Jenkins
import hudson.slaves.EnvironmentVariablesNodeProperty;


/**
 * Implements logic related to CustomHooks execution process.
 */
class CustomHookHelper implements Serializable {
    /* Pipeline object */
    private script

    String projectName
    /* Library configuration */
    protected libraryProperties

    /* customhooks hook definitions */
    protected hookDir
    protected final hookScriptFileName
    protected customhooksFolderSubpath

    protected CustomHookHelper(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        customhooksFolderSubpath = libraryProperties.'customhooks.folder.subpath' + libraryProperties.'customhooks.folder.name'
        hookScriptFileName = libraryProperties.'customhooks.hookzip.name'
    }

    /*
    * Extract hook list to run from Config File Content
    * @param script
    * @param hookStage
    * @param pipelineBuildStage
    * @param jsonContent
    * @return hookList
    */

    protected getHookList(hookStage, pipelineBuildStage, jsonContent){
        def stageContent = jsonContent[hookStage]

        String[] hookList = new String[stageContent.size()]
        stageContent.each{
            if((it["status"]).equals("enabled")
                    && (((it['parameter']['PIPELINE_STAGE']).equals(pipelineBuildStage))
                    || (it['parameter']['PIPELINE_STAGE']).equals("ALL"))){
                hookList[(it['index']).toInteger()] = it['hookName']
            }
        }
        def updatedIndexHookList = [];
        hookList.each{
            if(it){
                updatedIndexHookList.push(it)
            }
        }
        if(updatedIndexHookList){
            script.echoCustom("Hooks found in $hookStage stage for $pipelineBuildStage channel. List: " + updatedIndexHookList.toString())
        }

        return updatedIndexHookList;
    }

    /*Get boolean isPropagate status of a hook
    * @param hookName
    * @param hookStage
    * @param jsonContent
    * @return isPropagateBuildResult
    */
    protected isPropagateBuildStatus(hookName, hookStage, jsonContent){
        def isPropagateBuildResult = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                isPropagateBuildResult =  it['propagateBuildStatus']
            }
        }
        return isPropagateBuildResult
    }

    protected getbuildScriptURL(hookName, hookStage, jsonContent){
        def buildScriptURL = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                buildScriptURL =  it['hookUrl']
            }
        }
        return buildScriptURL
    }

    /**
     * Fetches Hook zip file for running it locally from S3.
     */
    protected final void fetchHook(buildScriptUrl){
        def customhookBucketURL = script.env.S3_BUCKET_URL
        def customhookBucketName = script.env.S3_BUCKET_NAME
        def customhookBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        def awsIAMRole = script.env.AWS_IAM_ROLE
        def hookScriptFileName = libraryProperties.'customhooks.hookzip.name'

        def hookScriptFileBucketPath = (buildScriptUrl - customhookBucketURL).substring(1)

        script.catchErrorCustom('Failed to fetch Hook zip file') {
            script.withAWS(region: customhookBucketRegion, role: awsIAMRole) {
                script.s3Download bucket: customhookBucketName, file: hookScriptFileName, force: true, path: hookScriptFileBucketPath

            }
        }
    }


    /* Trigger hook
    * @param script
    * @param projectName
    * @param hookStage
    * @param pipelineBuildStage
    */
    protected triggerHooks(projectName, hookStage, pipelineBuildStage){
        def customhooksConfigFolder = projectName + customhooksFolderSubpath
        def content = ConfigFileHelper.getContent(customhooksConfigFolder, projectName)

        if(content) {
            String currentComputer = "${script.env.NODE_NAME}"
            String hookSlave = getHookSlaveForCurrentBuildSlave(currentComputer)
            hookSlave ?: script.echoCustom("Not able to find hookSlave to run CustomHooks","ERROR")

            script.writeFile file: "${projectName}.json", text: content
            def hookProperties = script.readJSON file: "${projectName}.json"

            def hookList = getHookList(hookStage, pipelineBuildStage, hookProperties)
            hookList ?: script.echoCustom("Hooks are either not defined or disabled in $hookStage stage for $pipelineBuildStage channel.")

            script.stage(hookStage) {
                for (hookName in hookList) {
                    def isPropagateBuildResult = isPropagateBuildStatus(hookName, hookStage, hookProperties)
                    def hookJobName = getHookJobName(projectName, hookName, hookStage)
                    def buildScriptUrl = getbuildScriptURL(hookName, hookStage, hookProperties)
                    hookDir = libraryProperties.'project.workspace.folder.name' + "/" + projectName + "/Hook"

                    script.stage('Clean Environment') {
                        script.dir(hookDir) {
                            script.deleteDir()
                        }
                        script.shellCustom("set +e; rm -rf $hookDir; mkdir -p $hookDir", true)
                    }

                    script.stage("Download Hook Scripts") {
                        script.dir(hookDir) {
                            fetchHook(buildScriptUrl)
                        }
                    }

                    def hookJob = script.build job: hookJobName,
                            propagate: Boolean.valueOf(isPropagateBuildResult), wait: true,
                            parameters: [[$class: 'WHideParameterValue',
                                          name  : 'UPSTREAM_JOB_WORKSPACE',
                                          value : "$script.env.WORKSPACE"],

                                         [$class: 'WHideParameterValue',
                                          name  : 'HOOK_SLAVE',
                                          value : "$hookSlave"],

                                         [$class: 'WHideParameterValue',
                                          name  : 'BUILD_SLAVE',
                                          value : "$currentComputer"]]

                    if (hookJob.currentResult == 'SUCCESS') {
                        script.echoCustom("Hook execution for $hookJobName hook is SUCCESS, continuing with next build step..",'INFO')
                    }
                    else {
                        script.echoCustom("Build is completed for the Hook $hookJobName. Hook build status: $hookJob.currentResult", 'ERROR', false)
                        script.echoCustom("Since Hook setting is set with Propagate_Build_Status flag as false, " +
                                "continuing with next build step..",'INFO')
                    }
                }
            }
        }
        else{
            script.echoCustom("Hooks are not defined in $hookStage");
        }
    }

    /* return hook full path */
    protected final getHookJobName(projectName, hookName, hookType) {
        def hookFullName = [projectName, customhooksFolderSubpath, hookType, hookName].join('/')
        hookFullName
    }


  //  @NonCPS
    protected runCustomHooks(String folderName, String hookBuildStage, String pipelineBuildStage){
        script.echoCustom("Trying to fetch $hookBuildStage $pipelineBuildStage hooks. ")

       /* Execute available hooks */
        triggerHooks(folderName, hookBuildStage, pipelineBuildStage)

    }
    
    protected getHookSlaveForCurrentBuildSlave(currentComputer){

        String hookSlaveForCurrentComputer = null;
        Jenkins instance = Jenkins.getInstance()

        instance.computers.each { comp ->
            /*Below hookslave must come from config file */
            if(comp.displayName.equals(currentComputer)){
                hookSlaveForCurrentComputer = comp.getNode()
                        .getNodeProperties()
                        .get(EnvironmentVariablesNodeProperty.class)
                        .getEnvVars()
                        .get("hookSlave")

            }
        }
        hookSlaveForCurrentComputer
    }
}
