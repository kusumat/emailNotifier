package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.FabricHelper
import com.kony.appfactory.helper.AppFactoryException
import groovy.json.JsonSlurper

/**
 * Implements logic for WEB channel builds.
 */
class WebChannel extends Channel {
    protected fabricCliFileName
    protected libraryProperties
    /* Build parameters */
    protected final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    protected final desktopWebChannel = script.params.DESKTOP_WEB
    protected final String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    /* For below 8.2 AppFactory versions WEB_APP_VERSION is available as SPA_APP_VERSION. Therefore adding backward compatibility. */
    def appVersionParameterName = BuildHelper.getCurrentParamName(script, 'WEB_APP_VERSION', 'SPA_APP_VERSION')
    protected final webAppVersion = script.params[appVersionParameterName]
    protected webAppUrl
    protected final selectedSpaChannels

    /* Build agent resources */
    def resourceList
    def nodeLabel

    /* Create a list with artifact objects for e-mail template */
    def channelArtifacts = []

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    WebChannel(script, webChannelType) {
        super(script)
        if (webChannelType.equalsIgnoreCase("SPA")) {
            channelOs = channelFormFactor = channelType = 'SPA'
        } else if (webChannelType.equalsIgnoreCase("DESKTOP_WEB")) {
            channelOs = channelFormFactor = channelType = 'DESKTOPWEB'
        } else {
            channelOs = channelFormFactor = channelType = 'WEB'
        }
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        this.hookHelper = new CustomHookHelper(script)
        selectedSpaChannels = getSelectedSpaChannels(this.script.params)
        /* Expose SPA and DESKTOP_WEB build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = webAppVersion
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        /* Changing Channel Variable name if only SPA channels are selected */
        if (webChannelType.equalsIgnoreCase("WEB")) {
            if(!desktopWebChannel) {
                channelOs = channelFormFactor = channelType = 'SPA'
            }
            else if(!selectedSpaChannels){
                channelOs = channelFormFactor = channelType = 'DESKTOPWEB'
            }
        }

    }

    WebChannel(script) {
        this(script, 'WEB')
    }

    /**
     *
     * @param buildParameters
     * @return list of selected SPA channels.
     */
    @NonCPS
    private static getSelectedSpaChannels(buildParameters) {
        /* Creating a list of parameters that are not Target Channels */
        def notSpaChannelParams = ['PUBLISH_FABRIC_APP', 'FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE', 'RUN_CUSTOM_HOOKS', 'DESKTOP_WEB']
        buildParameters.findAll {
            it.value instanceof Boolean && !(notSpaChannelParams.contains(it.key)) && it.value
        }.keySet().collect()
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected void createPipeline() {
        pipelineWrapperForWebChannels("WEB")
    }

    /**
     * Creates job pipeline for given Web Channel type (SPA/DesktopWeb/Combined SPA+DesktopWeb).
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void pipelineWrapperForWebChannels(webChannelType) {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    if (!(webChannelType.equalsIgnoreCase("DESKTOP_WEB")) && !(selectedSpaChannels || desktopWebChannel)) {
                        script.echoCustom('Please select at least one channel to build!', 'ERROR')
                    }
                    ValidationHelper.checkBuildConfiguration(script)
                    ValidationHelper.checkBuildConfiguration(script, [appVersionParameterName, 'FABRIC_APP_CONFIG'])
                }

                /*
                 *  Allocate a slave for the run
                 *  CustomHook always must run in MAC (to use ACLs), However Web Channels can run in both Windows and MAC
                 *  Due to this, if user need to run Custom Hooks on Web (SPA/DesktopWeb/combine) (runCustomHook is checked) then run Web
                 *  build on MAC Agent. Otherwise default node strategy will be followed (WIN || MAC)
                 */
                resourceList = BuildHelper.getResourcesList()
                isCustomHookRunBuild = BuildHelper.isThisBuildWithCustomHooksRun(projectName, runCustomHook, libraryProperties)
                nodeLabel = BuildHelper.getAvailableNode(resourceList, libraryProperties, script, isCustomHookRunBuild, channelOs)

                script.node(nodeLabel) {
                    pipelineWrapper {
                        /*
                         * Clean workspace, to be sure that we have not any items from previous build,
                         * and build environment completely new.
                         */
                        script.cleanWs deleteDirs: true

                        script.stage('Check build-node environment') {
                            def mandatoryParameters = [
                                    'VISUALIZER_HOME', channelVariableName, 'PROJECT_WORKSPACE', 'FABRIC_ENV_NAME',
                                    'APP_VERSION'
                            ]

                            if (publishFabricApp) {
                                mandatoryParameters.addAll(['FABRIC_APP_NAME', 'CLOUD_ACCOUNT_ID'])
                            }

                            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                        }

                        script.stage('Checkout') {
                            // source code checkout from scm
                            BuildHelper.checkoutProject script: script,
                                    checkoutType: "scm",
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: scmBranch,
                                    scmCredentialsId: scmCredentialsId,
                                    scmUrl: scmUrl
                        }
                        
                        script.stage('Check PreBuild Hook Points') {
                            if (isCustomHookRunBuild) {
                                triggerHooksBasedOnSelectedChannels(webChannelType, libraryProperties.'customhooks.prebuild.name')

                            } else {
                                script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
                            }
                        }

                        script.stage('Build') {
                            /* Showing warning if triggered the build mode in release-protected for DesktopWeb and SPA channels  */
                            if(buildMode == libraryProperties.'buildmode.release.protected.type') {
                                script.echoCustom("Release-protected mode is not applicable for DesktopWeb and SPA channels build." +
                                    " It will build as Release mode only.", 'WARN')
                            }
                            build()
                            /*
                                Workaround for Zip extension web app binaries to copy to temp location
                                because in getArtifactTempPath function we refer single temp path for each channel
                                and Web builds can contain binaries with either war or zip extension.
                            */
                            if (artifactExtension == 'zip') {
                                def copyArtifactsCmd = isUnixNode ? 'cp' : 'copy'
                                def tempBasePath = [projectWorkspacePath, 'temp', projectName]
                                def destPath = (tempBasePath + ['middleware_mobileweb']).join(separator)
                                def sourcePath = (tempBasePath + ['build', 'wap', 'build', "${projectName}.zip"]).join(separator)
                                if (script.fileExists(sourcePath)) {
                                    copyArtifactsCmd = [copyArtifactsCmd, sourcePath, destPath].join(' ')
                                    script.shellCustom(copyArtifactsCmd, isUnixNode)
                                }
                                else {
                                    throw new AppFactoryException('Failed to find build artifact!', 'ERROR')
                                }
                            }
                            /* Search for build artifacts */
                            buildArtifacts = getArtifactLocations(artifactExtension) ?:
                                    script.echoCustom('Build artifacts were not found!', 'ERROR')
                            /* Add War/Zip to MustHaves Artifacts */
                            buildArtifacts?.each { artifact ->
                                mustHaveArtifacts.add([name: artifact.name, path: artifact.path])
                            }
                        }


                        script.stage('Publish to Fabric') {
                            /* Publish Fabric application if PUBLISH_FABRIC_APP set to true */
                            if (publishFabricApp) {
                                if (webChannelType.equalsIgnoreCase("WEB")) {
                                    script.echoCustom("As you are building both SPA and DesktopWeb channels and PUBLISH_TO_FABRIC checkbox is selected, a combined archive will be generated and published to the Fabric environment you've chosen.")
                                }
                                FabricHelper.fetchFabricCli(script, libraryProperties, libraryProperties.'fabric.cli.version')
                                /* Fabric option for cliCommands */
                                def fabricCommandOptions = ['-t': "\"${script.env.CLOUD_ACCOUNT_ID}\"",
                                                            '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                                            '-e': "\"${script.env.FABRIC_ENV_NAME}\"",]
                                /* Prepare string with shell script to run */
                                FabricHelper.fabricCli(script, 'publish', cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions)
                                script.echoCustom("Published to Fabric Successfully, Fetching AppInfo")
                                webAppUrl = fetchWebAppUrl("appinfo", fabricCommandOptions)
                                script.echoCustom("Your published app is accessible at : " + webAppUrl)
                            } else {
                                script.echoCustom("PUBLISH_FABRIC_APP flag set to false, " +
                                        "skipping Fabric application publishing...")

                            }
                        }


                        script.stage("Publish artifacts to S3") {
                            /* Rename artifacts for publishing */
                            artifacts = renameArtifacts(buildArtifacts)

                            artifacts?.each { artifact ->
                                String artifactName = artifact.name
                                String artifactPath = artifact.path
                                String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                        sourceFileName: artifactName, sourceFilePath: artifactPath, script



                                String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true)
                                /* Add War/Zip to MustHaves Artifacts */
                                mustHaveArtifacts.add([name: artifact.name, path: artifactPath])
                                channelArtifacts.add([
                                        channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl, webAppUrl: webAppUrl
                                ])
                            }
                            script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                        }

                        /* Run Post Build Web Hooks */
                        script.stage('Check PostBuild Hook Points') {
                            if (script.currentBuild.currentResult == 'SUCCESS') {
                                if (isCustomHookRunBuild) {
                                    triggerHooksBasedOnSelectedChannels(webChannelType, libraryProperties.'customhooks.postbuild.name')
                                } else {
                                    script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
                                }
                            } else {
                                script.echoCustom('CustomHooks execution skipped as current build result not SUCCESS.', 'WARN')
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * In this method, we will prepare a list of channels for which we need to run the hooks, 
     * depending upon the webChannelType and then call runCustomHooks
     * @param webChannelType tells us whether the channel is SPA or WEB or DESKTOP_WEB
     * @param buildStage is the hook stage such as PRE_BUILD, POST_BUILD, POST_TEST
     */
    protected triggerHooksBasedOnSelectedChannels(webChannelType, buildStage){
        
        def projectsToRun = [], projectStage
        //In order to avoid duplication of code, added OR condition to trigger hooks of SPA and DESTOPWEB channel in WEB channel
        if (webChannelType.equalsIgnoreCase("DESKTOP_WEB") || webChannelType.equalsIgnoreCase("WEB")) {
            projectsToRun << "DESKTOP_WEB"
        }

        if (webChannelType.equalsIgnoreCase("SPA") || webChannelType.equalsIgnoreCase("WEB")) {
            ['ANDROID_MOBILE_SPA', 'ANDROID_TABLET_SPA', 'IOS_MOBILE_SPA', 'IOS_TABLET_SPA'].each { project ->
                if (selectedSpaChannels.contains(project))
                    projectsToRun << project
            }
        }

        projectsToRun.each { project ->
            if (project.contains('SPA')) {
                projectStage = "SPA_" + project - "_SPA" + "_STAGE"
            }
            if (project.contains('DESKTOP_WEB')) {
                projectStage = project + "_STAGE"
            }
            hookHelper.runCustomHooks(projectName, buildStage, projectStage)
        }
    }

    /**
     * Runs Fabric CLI commands which have to return something.
     * @param cliCommand cliCommand for fabric cli command that need to be executed.
     * @param fabricCommandOptions other fabric cli command options that can be provided.
     * @return WebAppUrl of the web app.
     */
    protected fetchWebAppUrl(cliCommand = "appinfo", fabricCommandOptions = [:]) {
        def webAppUrlText
        
        webAppUrlText = FabricHelper.fabricCli(script, cliCommand, cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions, [returnStdout: true])

        def jsonSlurper = new JsonSlurper()
        webAppUrlText = webAppUrlText.substring(webAppUrlText.indexOf("{"), webAppUrlText.lastIndexOf("}") + 1)
        webAppUrlText.trim()
        def webAppUrlJson = jsonSlurper.parseText(webAppUrlText)
        def webAppUrl = webAppUrlJson.Webapp.url
        webAppUrl.toString()
    }

}
