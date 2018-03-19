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
    protected final buildAction = script.params.BUILD_ACTION
    protected final scriptArguments = script.params.SCRIPT_ARGUMENTS

    /* Hidden Parameters */
    protected final hookSlave = script.params.HOOK_SLAVE
    protected final buildSlave = script.params.BUILD_SLAVE
    protected final upstreamJobWorkspace = script.params.UPSTREAM_JOB_WORKSPACE

    /* Stash name for fastlane configuration */
    private fastlaneConfigStashName

    /* customhooks hook definitions */
    protected final hookDir
    protected final hookScriptFileName

    CustomHook(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        hookDir = projectName + "/Hook"
        hookScriptFileName = libraryProperties.'customhooks.hookzip.name'
        fastlaneConfigStashName = libraryProperties.'fastlane.config.stash.name'
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
                        script.stage("Extract Hook Archive") {
                            script.dir(hookDir) {
                                script.unzip zipFile: hookScriptFileName
                            }
                        }
                        script.stage('Prepare Environment for Run') {
                            /* Applying ACLs for hookslave user */
                            //def hookSlaveACLapply_fordirs = 'set +xe && chmod -R +a "hookslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" ../vis_ws'
                            def hookSlaveACLapply_fordirs = '#!/bin/sh +xe && find . -type d -exec chmod -R +a "hookslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" \'{}\' \\+'
                            def hookSlaveACLapply_forfiles = '#!/bin/sh +xe && find . -type f -exec chmod -R +a "hookslave allow read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" \'{}\' \\+'

                            script.shellCustom("$hookSlaveACLapply_fordirs", true)
                            script.shellCustom("$hookSlaveACLapply_forfiles", true)

                            /*This is to get change permission for upstream folder which will be same as Jenkins job name*/
                            script.shellCustom("#!/bin/sh +xe && chmod 710 ../../$upstreamJobName",true)
                        }
                    }
                }

                script.node(hookSlave) {
                    script.ws(visWorkspace) {
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
                                    def buildSlaveACLapply_fordirs = '#!/bin/sh +xe && find . -user hookslave -type d -exec chmod -R +a "buildslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" "{}" \\+'
                                    def buildSlaveACLapply_forfiles = '#!/bin/sh +xe && find . -user hookslave -type f -exec chmod -R +a "buildslave allow read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\+'
                                    script.shellCustom("$buildSlaveACLapply_fordirs", true)
                                    script.shellCustom("$buildSlaveACLapply_forfiles", true)

                                    /* Clean any @tmp files created after build run */
                                    def cleanTmpFiles = '#!/bin/sh +xe && find . -user hookslave -type d -name "*@*" -empty -delete'
                                    script.shellCustom("$cleanTmpFiles", true)
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
