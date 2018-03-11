package com.kony.appfactory.helper

import com.kony.appfactory.helper.ConfigFileHelper
import jenkins.model.Jenkins
import hudson.slaves.EnvironmentVariablesNodeProperty;


/**
 * Implements logic related to CustomHooks execution process.
 */
class CustomHookHelper implements Serializable {

    String projectName

    /*
    * Extract hook list to run from Config File Content
    * @param script
    * @param hookStage
    * @param pipelineBuildStage
    * @param jsonContent
    * @return hookList
    */
    protected static getHookList(script, hookStage, pipelineBuildStage, jsonContent){
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
            script.echo("Hooks found in $hookStage stage for $pipelineBuildStage channel. List: " + updatedIndexHookList.toString())
        }

        return updatedIndexHookList;
    }

    /*Get boolean isPropagate status of a hook
    * @param hookName
    * @param hookStage
    * @param jsonContent
    * @return isPropagateBuildResult
    */
    protected static isPropagateBuildStatus(hookName, hookStage, jsonContent){
        def isPropagateBuildResult = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                isPropagateBuildResult =  it['propagateBuildStatus']
            }
        }
        return isPropagateBuildResult
    }

    /* Trigger hook
    * @param script
    * @param projectName
    * @param hookStage
    * @param pipelineBuildStage
    */
    protected static triggerHooks(script, projectName, hookStage, pipelineBuildStage){

        def customhooksConfigFolder = projectName + "/Visualizer/Builds/CustomHooks/"

        def content = ConfigFileHelper.getOlderContent(customhooksConfigFolder, projectName)

        if(content) {

            //getConfigFileInWorkspace(script, customhooksConfigFolder, projectName)

            script.writeFile file: "${projectName}.json", text: content

            def hookProperties = script.readJSON file: "${projectName}.json"

            String currentComputer = "${script.env.NODE_NAME}"

            String hookSlave = getHookSlaveForCurrentBuildSlave(currentComputer)

            hookSlave ?: script.error("Not able to find hookSlave to run CustomHooks");

            def hookList = getHookList(script, hookStage, pipelineBuildStage, hookProperties)
            hookList ?: script.echo("Hooks are not defined in $hookStage stage for $pipelineBuildStage channel.")

            script.stage(hookStage) {
                for (hookName in hookList) {
                    def isPropagateBuildResult = isPropagateBuildStatus(hookName, hookStage, hookProperties)
                    def hookJobName = getHookJobName(projectName, hookName, hookStage)
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
                    script.echo("Build is completed for the Hook $hookJobName. Hook build status: $hookJob.currentResult")
                    if (hookJob.currentResult == 'SUCCESS') {
                        script.echoCustom("Hook execution is SUCCESS, continuing with next build step..",'INFO')
                    }
                    else {
                        script.echoCustom("Since Hook setting is set with Propagate_Build_Status flag as false, " +
                                "continuing with next build step..",'INFO')
                    }
                }
            }
        }
        else{
            script.echo("Hooks not defined in $hookStage");
        }
    }

    /* return hook full path */
    protected static final getHookJobName(projectName, hookName, hookType) {
        def hookFullName = [projectName, 'Visualizer', 'Builds', 'CustomHooks', hookType, hookName].join('/')
        hookFullName
    }


    @NonCPS
    protected static runCustomHooks(script, String folderName, String hookBuildStage, String pipelineBuildStage){
        script.echo("Fetching ${hookBuildStage} - ${pipelineBuildStage} hooks. ")

       /* Execute available hooks */
        triggerHooks(script, folderName, hookBuildStage, pipelineBuildStage)

    }

    protected static getConfigFileInWorkspace(script, folderFullName, fileId){
        /* Get hook configuration files in workspace */
        /*Params Folder Name and Fileid*/
        def content = ConfigFileHelper.getOlderContent(folderFullName, fileId)
        script.writeFile file: "${fileId}.json", text: content
        //script.configFileProvider([script.configFile(fileId: fileId, targetLocation: "${fileId}.json")]) {
        //}
    }
    
    protected static getHookSlaveForCurrentBuildSlave(currentComputer){

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
