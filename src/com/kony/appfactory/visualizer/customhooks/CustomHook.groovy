package com.kony.appfactory.visualizer.customhooks

import com.kony.appfactory.helper.BuildHelper

class CustomHook implements Serializable {
    /* Pipeline object */
    private final script

    /* Library configuration */
    private libraryProperties

    /* Commons */
    protected final projectName = script.env.PROJECT_NAME
    /* parameters */
    protected final hookName = script.params.HOOK_NAME
    protected final buildStep = script.params.BUILD_STEP
    protected final buildAction = script.params.BUILD_ACTION
    protected final buildScript = script.params.BUILD_SCRIPT
    protected final scriptArguments = script.params.SCRIPT_ARGUMENTS
    protected final blocking = script.params.BLOCKING

    /*Hidden Parameters*/
    protected final hookSlave = script.params.HOOK_SLAVE
    protected final buildSlave = script.params.BUILD_SLAVE
    protected final upstreamJobWorkspace = script.params.UPSTREAM_JOB_WORKSPACE

    /* others */
    protected final outputFile = "Hook.zip"
    protected final hookDir = projectName + "/Hook"

    CustomHook(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
    }

    /* customHooks pipeline, each hook follows same execution process */
    protected final void processPipeline(){

        String upstreamJobName = getUpstreamJobName(script)
        String visWorkspace = [upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')

        script.node(buildSlave) {
            script.ws(visWorkspace) {

                script.stage('Clean Environment'){
                    script.dir(hookDir){
                        script.deleteDir()
                    }
                    script.sh "set +e; mkdir -p $projectName/Hook";
                }

                script.stage("Download Hook Scripts") {
                    script.dir(hookDir){
                        script.httpRequest url: buildScript, outputFile: outputFile, validResponseCodes: '200'
                    }
                }

                script.stage("Extract Hook Archive") {
                    script.dir(hookDir){
                        script.unzip zipFile: "Hook.zip"
                    }
                }

                script.stage('Prepare Environment for Run'){
                    def command =
                    script.sh 'pwd'
                    script.sh 'chmod -R +a "hookslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" ../vis_ws'
                    //script.sh 'find vis_ws -type f -exec chmod -R +a "hookslave read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\ ;'
                    /*This is to get change permission for upstream folder which will be same as Jenkins job name*/
                    script.sh "chmod 710 ../../$upstreamJobName"
                }
            }
        }

        script.node(hookSlave){
            script.ws([upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')) {

                script.stage("Running CustomHook") {
                    script.dir(hookDir) {
                        if (buildAction == "Execute Ant") {
                            script.sh "export JAVA_HOME='/Appfactory/Jenkins/tools/jdk1.8.0_112.jdk' && /Appfactory/Jenkins/tools/ant-1.8.2/bin/ant -f build.xml ${scriptArguments}"
                        } else if (buildAction == "Execute Maven") {
                            script.sh "mvn ${scriptArguments}"
                        } else {
                            script.echo("unknown build script ")
                        }
                    }

                    script.dir(hookDir){
                        script.sh 'set +e && find . -user hookslave -exec chmod -R +a "buildslave allow read,write,delete,list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" {} \\;'
                    }
                }
            }
        }
    }

    /* This is required as each build can be trigger from IOS Android or SPA.
    *  To give permission to channel jobs workspace we need into about Upstream job
    *
    *  @param script
    *  return upstreamJobName
    * */
    def getUpstreamJobName(script) {
        String upstreamJobName = null
        script.currentBuild.rawBuild.actions.each { action ->
            if (action.hasProperty("causes")) {
                action.causes.each { cause ->
                    if (cause instanceof hudson.model.Cause$UpstreamCause && cause.hasProperty("shortDescription") && cause.shortDescription.contains("Started by upstream project")) {
                        upstreamJobName = cause.upstreamRun.getEnvironment(TaskListener.NULL).get("JOB_BASE_NAME")
                    }
                }
            }
        }
        upstreamJobName
    }

}
