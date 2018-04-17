package com.kony.appfactory.visualizer.customhooks

import com.kony.appfactory.helper.BuildHelper
import hudson.model.*

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

    /* customhooks hook definitions */
    protected hookDir
    protected upstreamJobName

    CustomHook(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
    }

    /* CustomHooks pipeline, each hook follows same execution process */
    protected final void processPipeline(){
        script.ansiColor('xterm') {
            upstreamJobWorkspace && buildSlave && hookSlave ?: script.echoCustom("CustomHooks aren't supposed to be triggered directly. CustomHooks will only be triggered as part of the Visualizer jobs.", 'ERROR')
        }
        upstreamJobName = BuildHelper.getUpstreamJobName(script)
        String visWorkspace = [upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')

        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.node(hookSlave) {
                    def hookLabel = script.env.NODE_LABELS

                    if (hookLabel.contains(libraryProperties.'test.hooks.node.label')) {
                        hookDir = visWorkspace + "/" + projectName + "/deviceFarm/" + "Hook"
                    }
                    else{
                        hookDir = visWorkspace + "/" + projectName + "/Hook"
                    }

                    script.ws(visWorkspace) {
                        def javaHome = script.env.JDK_1_8_0_112_HOME
                        def antBinPath = script.env.ANT_1_8_2_HOME + '/bin'
                        def mavenBinPath = script.env.MAVEN_3_5_2_HOME + '/bin'

                        def pathSeparator = script.isUnix() ? ':' : ';'

                        script.echoCustom("Running CustomHook")
                        script.withEnv(["JAVA_HOME=${javaHome}", "PATH+TOOLS=${javaHome}${pathSeparator}${antBinPath}"]) {
                            script.stage("Run CustomHook") {
                                script.dir(hookDir) {
                                    try {
                                        if (buildAction == "Execute Ant") {
                                            def antCmd = "$antBinPath" + "/ant" + " -f build.xml ${scriptArguments}"
                                            script.shellCustom("$antCmd", true)
                                        } else if (buildAction == "Execute Maven") {
                                            def mvnCmd = "$mavenBinPath" + "/mvn" + " ${scriptArguments}"
                                            script.shellCustom("$mvnCmd ", true)
                                        } else {
                                            script.echoCustom("unknown build script",'WARN')
                                        }
                                    } catch (Exception e) {
                                        script.echoCustom("Hook build execution failed!!",'ERROR')
                                    } finally {
                                        script.stage('Prepare Environment for actual Build Run') {
                                            def customHooksLogDir = [visWorkspace, projectName, libraryProperties.'customhooks.buildlog.folder.name'].join('/')
                                            script.dir(customHooksLogDir){
                                                def buildLogName = script.env.JOB_NAME.replaceAll("/", "_") + ".log"
                                                script.writeFile file: buildLogName, text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
                                            }
                                            
                                            /* Applying ACLs, allow buildslave/jenkins user permissions*/
                                            if (hookLabel.contains(libraryProperties.'visualizer.hooks.node.label')) {
                                                macACLafterRun()
                                            }
                                            else if (hookLabel.contains(libraryProperties.'test.hooks.node.label')) {
                                                linuxACLafterRun()
                                            }
                                            else {
                                                script.echoCustom("Something went wrong.. unable to run hook", 'ERROR')
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /* applying ACLs - allow buildslave/jenkins user with read, write permissions on hookslave owned files.*/
    def macACLafterRun()
    {
        def buildSlaveACLapply_fordirs = 'set +xe;find . -user hookslave -type d -exec chmod -R +a "buildslave allow list,add_file,search,delete,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" "{}" \\+'
        def buildSlaveACLapply_forfiles = 'set +xe;find . -user hookslave -type f -exec chmod -R +a "buildslave allow read,write,append,delete,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\+'
        def buildSlaveACLapplygroup_forfiles = 'set +xe;find . -user hookslave -type d -exec chmod -R 775 {} \\+'

        script.shellCustom("$buildSlaveACLapply_fordirs", true)
        script.shellCustom("$buildSlaveACLapply_forfiles", true)
        script.shellCustom("$buildSlaveACLapplygroup_forfiles", true)

        /* Clean any @tmp files created after build run */
        def cleanTmpFiles = 'set +xe;find . -user hookslave -type d -name "*@tmp" -empty -delete'
        script.shellCustom("$cleanTmpFiles", true)
    }

    /* applying ACLs - allow buildslave/jenkins user with read, write permissions on hookslave owned files.*/
    def linuxACLafterRun()
    {
        def buildSlaveACLapply = 'set +xe && find . -user hookslave -exec setfacl -R -m u:jenkins:rwx {} \\;'
        script.shellCustom("$buildSlaveACLapply", true)

        /* Clean any @tmp files created after build run */
        def cleanTmpFiles = 'set +xe && find . -type d -name "*@tmp" -empty -delete'
        script.shellCustom("$cleanTmpFiles", true)
    }

}
