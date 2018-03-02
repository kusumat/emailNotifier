package com.kony.appfactory.helper

import jenkins.model.Jenkins

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
                    && ((it['parameter']['PIPELINE_STAGE']).equals(pipelineBuildStage))
                    || (it['parameter']['PIPELINE_STAGE']).equals("ALL")){
                hookList[(it['index']).toInteger()] = it['hookName']
            }
        }
        script.echo("Indexing "+ hookList.toString())
        def updatedIndexHookList = [];
        hookList.each{
            if(it){
                updatedIndexHookList.push(it)
            }
        }
        script.echo("Indexing2 "+ updatedIndexHookList.toString())
        return updatedIndexHookList;
    }

    protected static isPropogateBuildStatus(hookName, hookStage, jsonContent){
        def isPropogateBuildResult = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                isPropogateBuildResult =  it['propogateBuildStatus']
            }
        }

        return isPropogateBuildResult
    }

    protected static triggerHooks(script, projectName, hookStage, pipelineBuildStage){

        getConfigFileInWorkspace(script, projectName)
        def hookProperties = script.readJSON file:"${projectName}.json"

        def hookList = getHookList(script, hookStage, pipelineBuildStage,  hookProperties)
        
        script.stage(hookStage){
            for (hookName in hookList){

                def isPropogateBuildResult = isPropogateBuildStatus(hookName, hookStage, hookProperties)
                script.echo(isPropogateBuildResult.toString() + hookName)

                def hookJobName = getHookJobName(script, projectName, hookName, hookStage)
                if(Boolean.valueOf(isPropogateBuildResult)){
                    script.build job: hookJobName,
                            propagate: true, wait: true,
                            parameters: [[$class: 'WHideParameterValue',
                                          name: 'UPSTREAM_JOB_WORKSPACE',
                                          value: "$script.env.WORKSPACE"]]
                }
                else{
                    script.build job: hookJobName,
                            propagate: false,
                            wait: true, parameters: [[$class: 'WHideParameterValue',
                                                      name: 'UPSTREAM_JOB_WORKSPACE',
                                                      value: "$script.env.WORKSPACE"]]
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

}
