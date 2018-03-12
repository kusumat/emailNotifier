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

    /* CustomHooks pipeline, each hook follows same execution process */
    protected final void processPipeline(){

        String upstreamJobName = getUpstreamJobName(script)
        String visWorkspace = [upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')

        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.node(buildSlave) {
                    script.ws(visWorkspace) {

                        script.stage('Clean Environment') {
                            script.dir(hookDir) {
                                script.deleteDir()
                            }
                            script.shellCustom("set +e; rm -rf $hookDir; mkdir -p $hookDir", true)
                        }

                        script.stage("Download Hook Scripts") {
                            script.dir(hookDir) {
                                script.httpRequest url: buildScript, outputFile: outputFile, validResponseCodes: '200'
                            }
                        }

                        script.stage("Extract Hook Archive") {
                            script.dir(hookDir) {
                                script.unzip zipFile: "Hook.zip"
                            }
                        }

                        script.stage('Prepare Environment for Run') {
                            script.shellCustom('pwd', true)

                            def hookSlaveACLapply_fordirs = 'set +xe && chmod -R +a "hookslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" ../vis_ws'
                            def hookSlaveACLapply_forfiles = 'set +xe && find ../vis_ws -type f -exec chmod -R +a "hookslave allow read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\+'

                            script.shellCustom("$hookSlaveACLapply_fordirs", true)
                            script.shellCustom("$hookSlaveACLapply_forfiles", true)

                            /*This is to get change permission for upstream folder which will be same as Jenkins job name*/
                            script.shellCustom("set +xe && chmod 710 ../../$upstreamJobName",true)
                        }
                    }
                }

                script.node(hookSlave) {
                    script.ws([upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')) {

                        def javaHome = script.env.JDK_1_8_0_112_HOME
                        def antBinPath = script.env.ANT_1_8_2_HOME + '/bin'

                        def pathSeparator = script.isUnix() ? ':' : ';'

                        script.echoCustom("Running CustomHook")
                        script.withEnv(["JAVA_HOME=${javaHome}", "PATH+TOOLS=${javaHome}${pathSeparator}${antBinPath}"]) {
                            script.stage("Running CustomHook") {
                                script.dir(hookDir) {
                                    if (buildAction == "Execute Ant") {
                                        script.shellCustom("ant -f build.xml ${scriptArguments}", true)
                                    } else if (buildAction == "Execute Maven") {
                                        script.shellCustom("mvn ${scriptArguments}", true)
                                    } else {
                                        script.echoCustom("unknown build script",'ERROR')
                                    }
                                }

                                script.stage('Prepare Environment for actual Build Run') {
                                    def buildSlaveACLapply_fordirs = 'set +xe && chmod -R +a "buildslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" .'
                                    def buildSlaveACLapply_forfiles = 'set +xe && find . -type f -exec chmod -R +a "buildslave allow read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\+'

                                    //script.shellCustom("$buildSlaveACLapply_fordirs", true)
                                    //script.shellCustom("$buildSlaveACLapply_forfiles", true)

                                    script.dir(hookDir) {
                                        def buildSlaveACLapply_inhookDir = 'set +xe;find . -user hookslave -exec chmod -R +a "buildslave allow read,write,delete,list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" {} \\+'
                                        script.shellCustom("$buildSlaveACLapply_inhookDir", true)
                                    }
                                }
                            }
                        }
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
