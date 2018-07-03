package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import groovy.json.JsonSlurper

/**
 * Implements logic for WEB channel builds.
 */
class WebChannel extends Channel {
    private fabricCliFileName
    private libraryProperties
    /* Build parameters */
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final desktopWebChannel = script.params.DESKTOP_WEB
    private final String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private webAppUrl
    /* For below 8.2 AppFactory versions WEB_APP_VERSION is available as SPA_APP_VERSION. Therefore adding backward compatibility. */
    def appVersionParameterName = script.params.containsKey('WEB_APP_VERSION') ? 'WEB_APP_VERSION' : 'SPA_APP_VERSION'
    private final webAppVersion = script.params[appVersionParameterName]
    /* CustomHooks build Parameters*/
    private final runCustomHook = script.params.RUN_CUSTOM_HOOKS
    private final selectedSpaChannels

    /* CustomHookHelper object */
    protected hookHelper

    /* Build agent resources */
    def resourceList
    def nodeLabel
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
            channelOs = channelFormFactor = channelType = 'DESKTOP WEB'
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
        if (webChannelType.equalsIgnoreCase("WEB") && !(desktopWebChannel)) {
            channelVariableName = 'SPA'
            channelType = channelVariableName
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
    protected final void createPipeline() {
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
                nodeLabel = BuildHelper.getAvailableNode(projectName, runCustomHook, resourceList, libraryProperties, script)

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
                            BuildHelper.checkoutProject script: script,
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: scmBranch,
                                    scmCredentialsId: scmCredentialsId,
                                    scmUrl: scmUrl
                        }

                        script.stage('Check PreBuild Hook Points') {
                            if (runCustomHook) {
                                def projectStage
                                def projectsToRun = []
                                if (webChannelType.equalsIgnoreCase("SPA")) {
                                    ['ANDROID_MOBILE_SPA', 'ANDROID_TABLET_SPA', 'IOS_MOBILE_SPA', 'IOS_TABLET_SPA'].each { project ->
                                        if (selectedSpaChannels.contains(project))
                                            projectsToRun << project
                                    }
                                } else {
                                    projectsToRun << webChannelType
                                }
                                projectsToRun.each { project ->
                                    if (project.contains('SPA')) {
                                        projectStage = "SPA_" + project - "_SPA" + "_STAGE"
                                    } else {
                                        projectStage = project + "_STAGE"
                                    }
                                    def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.prebuild.name', projectStage)
                                    if (!isSuccess)
                                        throw new Exception("Something went wrong with the Custom hooks execution.")

                                }

                            } else {
                                script.echoCustom('runCustomHook parameter is not selected by user, Hence CustomHooks execution is skipped.', 'WARN')
                            }
                        }

                        script.stage('Build') {
                            build()
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
                                fabric.fetchFabricCli(libraryProperties.'fabric.cli.version')
                                /* Fabric option for cliCommands */
                                def fabricCommandOptions = ['-t': "\"${script.env.CLOUD_ACCOUNT_ID}\"",
                                                            '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                                            '-e': "\"${script.env.FABRIC_ENV_NAME}\"",]
                                /* Prepare string with shell script to run */
                                fabric.fabricCli('publish', cloudCredentialsID, isUnixNode, fabricCommandOptions)
                                script.echoCustom("Published to Fabric Successfully, Fetching AppInfo")
                                webAppUrl = getWebAppUrl("appinfo", cloudCredentialsID, fabricCommandOptions)
                                script.echoCustom("Your published app is accessible at : " + webAppUrl)
                            } else {
                                script.echoCustom("PUBLISH_FABRIC_APP flag set to false, " +
                                        "skipping Fabric application publishing...")

                            }
                        }


                        script.stage("Publish artifacts to S3") {
                            /* Rename artifacts for publishing */
                            artifacts = renameArtifacts(buildArtifacts)

                            /* Create a list with artifact objects for e-mail template */
                            def channelArtifacts = []

                            artifacts?.each { artifact ->
                                String artifactName = artifact.name
                                String artifactPath = artifact.path
                                String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                        sourceFileName: artifactName, sourceFilePath: artifactPath, script

                                String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true);
                                /* Add War/Zip to MustHaves Artifacts */
                                mustHaveArtifacts.add([name: artifact.name, path: artifactPath])
                                channelArtifacts.add([
                                        channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl, webappurl: webAppUrl
                                ])
                            }
                            script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                        }

                        /* Run Post Build Web Hooks */
                        script.stage('Check PostBuild Hook Points') {
                            if (script.currentBuild.currentResult == 'SUCCESS') {
                                if (runCustomHook) {
                                    def projectStage
                                    def projectsToRun = []
                                    if (webChannelType.equalsIgnoreCase("SPA")) {
                                        ['ANDROID_MOBILE_SPA', 'ANDROID_TABLET_SPA', 'IOS_MOBILE_SPA', 'IOS_TABLET_SPA'].each { project ->
                                            if (selectedSpaChannels.contains(project))
                                                projectsToRun << project
                                        }
                                    } else {
                                        projectsToRun << webChannelType
                                    }
                                    projectsToRun.each { project ->
                                        if (project.contains('SPA')) {
                                            projectStage = "SPA_" + project - "_SPA" + "_STAGE"
                                        } else {
                                            projectStage = project + "_STAGE"
                                        }
                                        def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.postbuild.name', projectStage)
                                        if (!isSuccess)
                                            throw new Exception("Something went wrong with the Custom hooks execution.")

                                    }
                                } else {
                                    script.echoCustom('runCustomHook parameter is not selected by user, Hence CustomHooks execution is skipped.', 'WARN')
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
     * Runs Fabric CLI commands which have to return something.
     * @params cliCommand , cloudCredentialsID , fabricCommandOptions cliCommand for fabric command,cloudCredentialsID for accessing the fabric credentials,fabricCommandOptions other options to be provided.
     * @return WebAppUrl of the web app.
     */
    protected getWebAppUrl(cliCommand, cloudCredentialsID, fabricCommandOptions = [:]) {
        def webAppUrlText
        String errorMessage = ['Failed to run', cliCommand, 'command'].join(' ')
        script.catchErrorCustom(errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : cloudCredentialsID,
                     passwordVariable: 'fabricPassword',
                     usernameVariable: 'fabricUsername']
            ]) {

                //  Adding the cloud type if the domain contains other than kony.com
                if (script.env.CLOUD_DOMAIN && script.env.CLOUD_DOMAIN.indexOf("-kony.com") > 0) {
                    def domainParam = script.env.CLOUD_DOMAIN.substring(0, script.env.CLOUD_DOMAIN.indexOf("-kony.com") + 1)
                    fabricCommandOptions['--cloud-type'] = "\"${domainParam}\""
                }
                /* Collect Fabric command options */
                String options = fabricCommandOptions?.collect { option, value ->
                    [option, value].join(' ')
                }?.join(' ')
                /* Prepare string with shell script to run */
                String shellString = [
                        'java -jar', fabricCliFileName, cliCommand,
                        '-u', script.env.fabricUsername,
                        '-p', script.env.fabricPassword,
                        options
                ].join(' ')
                webAppUrlText = script.shellCustom(shellString, isUnixNode, [returnStdout: true])
            }
        }
        def jsonSlurper = new JsonSlurper()
        webAppUrlText = webAppUrlText.substring(webAppUrlText.indexOf("{"), webAppUrlText.lastIndexOf("}") + 1)
        webAppUrlText.trim()
        def webAppUrlJson = jsonSlurper.parseText(webAppUrlText)
        webAppUrl = webAppUrlJson.Webapp.url
        webAppUrl
    }

}