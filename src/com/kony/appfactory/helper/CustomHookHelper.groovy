package com.kony.appfactory.helper

import hudson.model.Computer
import jenkins.model.Jenkins
import hudson.EnvVars;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;

/**
 * Implements logic related to customHooks execution process.
 */
class CustomHookHelper implements Serializable {

    String projectName

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
        script.echo("Hooks Available: "+ updatedIndexHookList.toString())
        return updatedIndexHookList;
    }

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

    protected static triggerHooks(script, projectName, hookStage, pipelineBuildStage){


        getConfigFileInWorkspace(script, projectName)
        def hookProperties = script.readJSON file:"${projectName}.json"

        String currentComputer = "${script.env.NODE_NAME}"

        String hookSlave = getHookSlaveForCurrentBuildSlave(currentComputer)


        hookSlave ?: script.error("Not able to find hookSlave to run Custom Hooks");
        script.echo("Initilize hook agent $hookSlave ")

        def hookList = getHookList(script, hookStage, pipelineBuildStage,  hookProperties)
        hookList ?: script.echo("Hooks not defined in $hookStage")

        script.stage(hookStage){
            for (hookName in hookList){

                def isPropagateBuildResult = isPropagateBuildStatus(hookName, hookStage, hookProperties)
                script.echo(isPropagateBuildResult.toString() + hookName)

                def hookJobName = getHookJobName(script, projectName, hookName, hookStage)
                if(Boolean.valueOf(isPropagateBuildResult)){
                    script.build job: hookJobName,
                            propagate: true, wait: true,
                            parameters: [[$class: 'WHideParameterValue',
                                          name: 'UPSTREAM_JOB_WORKSPACE',
                                          value: "$script.env.WORKSPACE"],

                                         [$class: 'WHideParameterValue',
                                          name: 'HOOK_SLAVE',
                                          value: "$hookSlave"],

                                         [$class: 'WHideParameterValue',
                                          name: 'BUILD_SLAVE',
                                          value: "$currentComputer"]]
                }
                else{
                    script.build job: hookJobName,
                            propagate: false,
                            wait: true,
                            parameters: [[$class: 'WHideParameterValue',
                                          name: 'UPSTREAM_JOB_WORKSPACE',
                                          value: "$script.env.WORKSPACE"],

                                         [$class: 'WHideParameterValue',
                                          name: 'HOOK_SLAVE',
                                          value: "$hookSlave"],

                                         [$class: 'WHideParameterValue',
                                          name: 'BUILD_SLAVE',
                                          value: "$currentComputer"]]
                }
            }
        }
    }

    protected static final getHookJobName(script, String projectName, String hookName, String hookType) {
        String hookBaseFolder = 'CustomHook'

        projectName + '/Visualizer/Builds/' + hookBaseFolder + '/' + hookType + '/' + hookName ?: script.error('Unknown Hook Type specified in Function call')
    }


    @NonCPS
    protected static runCustomHooks(script, String folderName, String hookBuildStage, String pipelineBuildStage){
        script.echo("Executing ${hookBuildStage} - ${pipelineBuildStage} hooks. ")

       /* Execute available hooks */
        triggerHooks(script, folderName, hookBuildStage, pipelineBuildStage)

    }


    protected static getConfigFileInWorkspace(script, fileId){
        /* Get hook configuration files in workspace */
        script.configFileProvider([script.configFile(fileId: fileId, targetLocation: "${fileId}.json")]) {
        }
    }

    protected static getHookSlaveForCurrentBuildSlave(currentComputer){
        //String currentComputer = Computer.currentComputer().getDisplayName();
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
