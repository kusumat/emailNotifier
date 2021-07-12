package com.kony.appfactory.visualizer.customhooks

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.enums.BuildType
import hudson.model.*

class CustomHook implements Serializable {
    /* Pipeline object */
    private final script

    /* Library configuration */
    private libraryProperties

    /* NodeJS Property */
    private nodejsVersion

    /* Commons */
    protected final projectName = script.env.PROJECT_NAME
    /* parameters */
    protected final buildAction = script.params.BUILD_ACTION
    protected final scriptArguments = script.params.SCRIPT_ARGUMENTS

    /* Hidden Parameters */
    protected final hookSlave = script.params.HOOK_SLAVE
    protected final buildSlave = script.params.BUILD_SLAVE
    protected final upstreamJobWorkspace = script.params.UPSTREAM_JOB_WORKSPACE
    protected final buildType = script.params.BUILD_TYPE

    /* customhooks hook definitions */
    protected hookDir
    protected buildWorkspace

    CustomHook(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        nodejsVersion = libraryProperties.'customhooks.nodejs.version'
    }

    /* CustomHooks pipeline, each hook follows same execution process */
    protected final void processPipeline() {
        script.ansiColor('xterm') {
            upstreamJobWorkspace && buildSlave && hookSlave ?: script.echoCustom("CustomHooks aren't supposed to be triggered directly. CustomHooks will only be triggered as part of the Iris jobs.", 'ERROR')
        }
        if (BuildType.Iris.toString().equalsIgnoreCase(buildType)) {
            buildWorkspace = [upstreamJobWorkspace, libraryProperties.'project.workspace.folder.name'].join('/')
        } else {
            buildWorkspace = [upstreamJobWorkspace, libraryProperties.'fabric.project.workspace.folder.name'].join('/')
        }

        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.node(hookSlave) {
                    def hookLabel = script.env.NODE_LABELS

                    if (hookLabel.contains(libraryProperties.'test.hooks.node.label') && BuildType.Iris.toString().equalsIgnoreCase(buildType)) {
                        hookDir = buildWorkspace + "/" + projectName + "/deviceFarm/" + "Hook"
                    } else {
                        hookDir = buildWorkspace + "/" + projectName + "/Hook"
                    }

                    script.ws(buildWorkspace) {
                        def javaHome = script.env.JDK_1_8_0_112_HOME
                        def antBinPath = script.env.ANT_1_8_2_HOME + '/bin'
                        def mavenBinPath = script.env.MAVEN_3_5_2_HOME + '/bin'

                        def pathSeparator = script.isUnix() ? ':' : ';'

                        script.echoCustom("Running CustomHook")
                        script.withEnv(["JAVA_HOME=${javaHome}", "PATH+TOOLS=${javaHome}${pathSeparator}${antBinPath}"]) {
                            script.stage("Run CustomHook") {
                                /* Get all Environment Variables defined for AppFactory instance.
                                 * Unset all these Env Variables to run customhook program on a clean shell.
                                 * This way, securely preventing user to access any Jenkins global parameters like S3, AWS etc..,
                                 * Below line gets all Environment variables defined in UpperCase since we follow System Variables
                                 * definitions with same convention.
                                 */
                                def EnvVariablesList = script.env.getEnvironment().findAll { envkey, envvalue ->
                                    envkey.equals(envkey.toUpperCase())
                                }.keySet().join(' ')

                                def defaultParams = getCustomhookDefaultArgs()

                                script.dir(hookDir) {
                                    try {
                                        script.nodejs(nodejsVersion) {
                                            if (buildAction == "Execute Ant") {
                                                def antCmd = "set +ex; unset $EnvVariablesList; set -e; $antBinPath" + "/ant" + " -f build.xml ${scriptArguments} $defaultParams"
                                                script.shellCustom("$antCmd", true)
                                            } else if (buildAction == "Execute Maven") {
                                                def mvnCmd = "set +ex; unset $EnvVariablesList; set -e; $mavenBinPath" + "/mvn" + " ${scriptArguments} $defaultParams"
                                                script.shellCustom("$mvnCmd ", true)
                                            } else {
                                                script.echoCustom("unknown build script", 'WARN')
                                            }
                                        }
                                    } catch (Exception e) {
                                        throw new AppFactoryException("Hook build execution failed!!", 'ERROR')
                                    } finally {
                                        script.stage('Prepare Environment for actual Build Run') {
                                            def customHooksLogDir = [buildWorkspace, projectName, libraryProperties.'customhooks.buildlog.folder.name'].join('/')
                                            script.dir(customHooksLogDir) {
                                                def buildLogName = script.env.JOB_NAME.replaceAll("/", "_") + ".log"
                                                script.writeFile file: buildLogName, text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
                                            }

                                            /* Applying ACLs, allow buildslave/jenkins user permissions*/
                                            if (hookLabel.contains(libraryProperties.'visualizer.hooks.node.label')) {
                                                macACLafterRun()
                                            } else if (hookLabel.contains(libraryProperties.'test.hooks.node.label')) {
                                                linuxACLafterRun()
                                            } else {
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
        script.dir(buildWorkspace) {
            def buildSlaveACLapply_fordirs = 'set +xe;find . -user hookslave -type d -print0 | xargs -0 chmod -R +a "buildslave allow list,add_file,search,delete,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit"'
            def buildSlaveACLapply_forfiles = 'set +xe;find . -user hookslave -type f -print0 | xargs -0 chmod -R +a "buildslave allow read,write,append,delete,readattr,writeattr,readextattr,writeextattr,readsecurity"'
            def buildSlaveACLapplygroup_forfiles = 'set +xe;find . -user hookslave -type d -print0 | xargs -0 chmod 775'
    
            script.shellCustom("$buildSlaveACLapply_fordirs", true)
            script.shellCustom("$buildSlaveACLapply_forfiles", true)
            script.shellCustom("$buildSlaveACLapplygroup_forfiles", true)
    
            /* Clean any @tmp files created after build run */
            def cleanTmpFiles = 'set +xe;find . -user hookslave -type d -name "*@tmp" -empty -delete'
            script.shellCustom("$cleanTmpFiles", true)
        }
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

    /** Construct a string with current Job Parameters key-pair list with -Dkey=value space separated format.
     * Below line gets all Jenkins job Parameters defined in UpperCase, since we follow same convention
     * while defining Parameters.
     *
     * Append these Job parameters with Parent Job parameters, to get consolidate list of all Parameters
     * Append additional built-in properties like workspace paths, build_numbers etc..,
     * This final string is then passed to ANT and Maven scripts in the build action step.
     */
    protected final getCustomhookDefaultArgs() {
        def defaultParams = script.params.PARENTJOB_PARAMS
        /* Exclude the PARENTJOB_PARAMS input parameter while collecting customhook job parameters now */
        script.params.findAll { propkey, propvalue -> propkey.equals(propkey.toUpperCase()) && !propkey.contains("PARENTJOB_PARAMS") }.each {
            defaultParams = [defaultParams,"-D${it.key}=\"${it.value}\""].join(' ')
        }
        /** Add additional most useful properties to quickly access with in a hook program. A pre-defined variables apart
         * from Build Input Parameters
         **/
        defaultParams += " -DPROJECT_NAME=$script.env.PROJECT_NAME"
        defaultParams += " -DPROJECT_WORKSPACE=$buildWorkspace" + "/" + script.env.PROJECT_NAME
        defaultParams += " -DPROJECT_VMWORKSPACE_PATH=$buildWorkspace" + "/KonyiOSWorkspace/VMAppWithVoltmxlib/"
        defaultParams += " -DPROJECT_XCODEPROJECT=VMAppWithVoltmxlib.xcodeproj/project.pbxproj"

        return defaultParams
    }

}
