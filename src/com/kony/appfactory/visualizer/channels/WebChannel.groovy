package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.FabricHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.enums.BuildType
import groovy.json.JsonSlurper

/**
 * Implements logic for WEB channel builds.
 */
class WebChannel extends Channel {
    protected fabricCliFileName
    protected libraryProperties
    /* Build parameters */
    def publishToFabricParamName = BuildHelper.getCurrentParamName(script, 'PUBLISH_FABRIC_APP', 'PUBLISH_WEB_APP')
    protected final publishWebApp = script.params[publishToFabricParamName]
    protected final desktopWebChannel = script.params.DESKTOP_WEB
    /* For below 8.2 AppFactory versions WEB_APP_VERSION is available as SPA_APP_VERSION. Therefore adding backward compatibility. */
    def appVersionParameterName = BuildHelper.getCurrentParamName(script, 'WEB_APP_VERSION', 'SPA_APP_VERSION')
    protected final webAppVersion = script.params[appVersionParameterName]
    protected final webProtectionPreset = script.params.PROTECTION_LEVEL
    protected final webProtectionExcludeListFile = (script.params.EXCLUDE_LIST_PATH)?.trim()
    protected final webProtectionBlueprintFile = (script.params.CUSTOM_PROTECTION_PATH)?.trim()
    protected final webProtectionID = script.params.OBFUSCATION_PROPERTIES
    protected webAppUrl
    protected final selectedSpaChannels

    /* Build agent resources */
    def resourceList
    def nodeLabel
    /* Web Protection Obfuscation Keys File */
    def secureJsFileName

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
        this.hookHelper = new CustomHookHelper(script, BuildType.Iris)
        selectedSpaChannels = getSelectedSpaChannels(this.script.params)
        /* Expose SPA and DESKTOP_WEB build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = webAppVersion
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        secureJsFileName = libraryProperties.'web_protected.obfuscation.file.name'
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
     * Adds env property for securejs properties file.
     */
    protected void setProtectedModePropertiesPath(){
        String prefix = ""
        String delimiter = "\n"
        String secureJsPropsContent = ""
        def secureJsProps = new Properties()

        script.withCredentials([
                script.webProtectionKeys(
                        credentialsId: "${webProtectionID}",
                        clientIdVariable: 'CLIENT_ID',
                        clientSecretVariable: 'CLIENT_SECRET',
                        encryptionKeyVariable: 'ENCRYPTION_KEY'
                )
        ]) {
            secureJsProps.setProperty('ci', script.env.CLIENT_ID)
            secureJsProps.setProperty('cs', script.env.CLIENT_SECRET)
            secureJsProps.setProperty('id', script.env.ENCRYPTION_KEY)
            script.dir(projectFullPath) {
                secureJsProps.each { key, value ->
                    secureJsPropsContent += prefix + "$key=$value"
                    prefix = delimiter
                }
                script.writeFile file: secureJsFileName, text: secureJsPropsContent, encoding: 'UTF-8'
                if (script.fileExists(secureJsFileName)) {
                    script.echoCustom("Securejs properties file created successfully! Injecting environment property for it's location.")
                    script.env['WEB_PROTECTEDMODE_PROPERTIES_PATH'] = [projectFullPath, secureJsFileName].join(separator)
                }
                else {
                    throw new AppFactoryException("Failed to inject env property for securejs properties file path, unable to find the file!", 'ERROR')
                }

            }

        }
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
        channelBuildStats.put("aver", webAppVersion)
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    if (!(webChannelType.equalsIgnoreCase("DESKTOP_WEB")) && !(selectedSpaChannels || desktopWebChannel)) {
                        script.echoCustom('Please select at least one channel to build!', 'ERROR')
                    }
                    ValidationHelper.checkBuildConfiguration(script)
                    ValidationHelper.checkBuildConfiguration(script, [appVersionParameterName, 'FABRIC_APP_CONFIG'])
                    // These validations only apply to AppFactory Project >= 9.2.0
                    if(buildMode == libraryProperties.'buildmode.release.protected.type' && script.params.containsKey('OBFUSCATION_PROPERTIES')) {
                        def webProtectionMandatoryParams = ['OBFUSCATION_PROPERTIES', 'PROTECTION_LEVEL', 'PROTECTED_KEYS']
                        if(webProtectionPreset == 'CUSTOM')
                            webProtectionMandatoryParams.add('CUSTOM_PROTECTION_PATH')
                        ValidationHelper.checkBuildConfiguration(script, webProtectionMandatoryParams)
                    }
                }

                /*
                 *  Allocate a slave for the run
                 *  CustomHook always must run in MAC (to use ACLs), However Web Channels can run in both Windows and MAC
                 *  Due to this, if user need to run Custom Hooks on Web (SPA/DesktopWeb/combine) (runCustomHook is checked) then run Web
                 *  build on MAC Agent. Otherwise default node strategy will be followed (WIN || MAC)
                 */
                resourceList = BuildHelper.getResourcesList()
                isCustomHookRunBuild = BuildHelper.isThisBuildWithCustomHooksRun(projectName, BuildType.Iris, runCustomHook, libraryProperties)
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

                            if (publishWebApp) {
                                mandatoryParameters.addAll(['FABRIC_APP_NAME', 'CLOUD_ACCOUNT_ID'])
                            }

                            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                        }

                        script.stage('Checkout') {
                            // source code checkout from scm
                            scmMeta = BuildHelper.checkoutProject script: script,
                                    checkoutType: "scm",
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: scmBranch,
                                    scmCredentialsId: scmCredentialsId,
                                    scmUrl: scmUrl
                        }

                        artifactMeta.add("version": ["App Version": webAppVersion])
                        script.stage('Check PreBuild Hook Points') {
                            if (isCustomHookRunBuild) {
                                triggerHooksBasedOnSelectedChannels(webChannelType, libraryProperties.'customhooks.prebuild.name')
                            } else {
                                script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
                            }
                        }

                        script.stage('Build') {
                            if(buildMode == libraryProperties.'buildmode.release.protected.type') {
                                script.echoCustom("For Iris 9.2.0 below projects release-protected mode is not applicable for DesktopWeb channel build." +
                                            " It will build as Release mode only.", 'WARN')
                                if(webProtectionID) {
                                    setProtectedModePropertiesPath()
                                    if (webProtectionExcludeListFile) {
                                        def excludeListFilePath = [projectFullPath, webProtectionExcludeListFile].join(separator)
                                        // storing with absolute path in EXCLUDE_LIST_PATH variable.
                                        script.env.EXCLUDE_LIST_PATH = excludeListFilePath
                                        def excludeListFileName = excludeListFilePath.substring(excludeListFilePath.lastIndexOf("/") + 1)
                                        if (!script.fileExists(excludeListFilePath)) {
                                            throw new AppFactoryException('Failed to find exclude list file at the location \n' + excludeListFilePath, 'ERROR')
                                        }
                                        /* Check the exclude list file type and name */
                                        if(!excludeListFileName.endsWith(".txt") || excludeListFileName.contains(" ")) {
                                            throw new AppFactoryException("Invalid file name or type given for Exclude list file! Expecting Exclude list in '.txt' file format and should not contains spaces in file name.", "ERROR")
                                        }
                                    }
                                    if (webProtectionPreset == 'CUSTOM') {
                                        def blueprintFilePath = [projectFullPath, webProtectionBlueprintFile].join(separator)
                                        // storing with absolute path in CUSTOM_PROTECTION_PATH variable.
                                        script.env.CUSTOM_PROTECTION_PATH = blueprintFilePath
                                        if (!script.fileExists(blueprintFilePath)) {
                                            throw new AppFactoryException('Failed to find blueprint file at the location  \n' + blueprintFilePath, 'ERROR')
                                        }
                                    }
                                    script.echoCustom("Placing encryptions keys for protected mode build.")
                                    copyProtectedKeysToProjectWorkspace()
                                }
                            }
                            build()
                            /*
                                Workaround for Zip extension web app binaries to copy to temp location
                                because in getArtifactTempPath function we refer single temp path for each channel
                                and Web builds can contain binaries with either war or zip extension.
                            */
                            if (artifactExtension == 'zip') {
                                def copyArtifactsCmd = isUnixNode ? 'cp' : 'copy'
                                def tempBasePath = [projectWorkspacePath, 'temp', script.env['VISUALIZER_PROJECT_ROOT_FOLDER_NAME']]
                                def destPath = (tempBasePath + ['middleware_mobileweb']).join(separator)
                                def sourcePath = (tempBasePath + ['build', 'wap', 'build', "${script.env.projectAppId}.zip"]).join(separator)
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
                            for (artifact in buildArtifacts) {
                                script.dir(artifact.path) {
                                    channelBuildStats.put('binsize', getBinarySize(artifact.path, artifact.name));
                                }
                            }
                        }


                        script.stage('Publish to Foundry') {
                            /* Publish Foundry application if PUBLISH_FABRIC_APP/PUBLISH_WEB_APP set to true */
                            if (publishWebApp) {
                                if (webChannelType.equalsIgnoreCase("WEB")) {
                                    script.echoCustom("As you are building both SPA and DesktopWeb channels and PUBLISH_TO_FABRIC checkbox is selected, a combined archive will be generated and published to the Foundry environment you've chosen.")
                                }
                                FabricHelper.fetchFabricCli(script, libraryProperties, libraryProperties.'fabric.cli.version')
                                FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, fabricCredentialsID, script.env.FABRIC_ACCOUNT_ID, script.env.FABRIC_ENV_NAME, isUnixNode)
                                
                                /* Foundry option for cliCommands */
                                def fabricCommandOptions = ['-t': "\"${script.env.FABRIC_ACCOUNT_ID}\"",
                                                            '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                                            '-v': "\"${script.env.FABRIC_APP_VERSION}\"",
                                                            '--webAppVersion': "\"${script.env.APP_VERSION}\"",
                                                            '-e': "\"${script.env.FABRIC_ENV_NAME}\"",]
                                /* Prepare string with shell script to run */
                                FabricHelper.fabricCli(script, 'publish', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions)
                                script.echoCustom("Published to Foundry Successfully, Fetching AppInfo")
                                fabricCommandOptions.remove('--webAppVersion')
                                webAppUrl = fetchWebAppUrl("appinfo", fabricCommandOptions)
                                script.echoCustom("Your published app is accessible at : " + webAppUrl)
                            } else {
                                script.echoCustom("${publishToFabricParamName} flag set to false, " +
                                        "skipping Foundry application publishing...")

                            }
                        }


                        script.stage("Publish artifacts to S3") {
                            /* Rename artifacts for publishing */
                            def channelArtifacts = renameArtifacts(buildArtifacts)

                            channelArtifacts?.each { artifact ->
                                String artifactName = artifact.name
                                String artifactPath = artifact.path
                                String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                        sourceFileName: artifactName, sourceFilePath: artifactPath, script


                                String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true)
                                /* Add War/Zip to MustHaves Artifacts */
                                mustHaveArtifacts.add([name: artifact.name, path: artifactPath])
                                artifacts.add([
                                        channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl, webAppUrl: webAppUrl, jasmineTestsUrl: script.env.JASMINE_TEST_URL
                                ])
                            }
                            script.env['CHANNEL_ARTIFACTS'] = artifacts?.inspect()
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
     * Runs Foundry CLI commands which have to return something.
     * @param cliCommand cliCommand for fabric cli command that need to be executed.
     * @param fabricCommandOptions other fabric cli command options that can be provided.
     * @return WebAppUrl of the web app.
     */
    protected fetchWebAppUrl(cliCommand = "appinfo", fabricCommandOptions = [:]) {
        def webAppUrlText
        
        webAppUrlText = FabricHelper.fabricCli(script, cliCommand, fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions, [returnStdout: true])

        def jsonSlurper = new JsonSlurper()
        webAppUrlText = webAppUrlText.substring(webAppUrlText.indexOf("{"), webAppUrlText.lastIndexOf("}") + 1)
        webAppUrlText.trim()
        def webAppUrlJson = jsonSlurper.parseText(webAppUrlText)
        def webAppUrl = (webAppUrlJson.Webapp?.url) ? webAppUrlJson.Webapp.url : ''
        if(webAppUrl.isEmpty())
            throw new AppFactoryException('Web app url is not found! It seems Foundry app is not published with uploaded web application.', 'ERROR')
        webAppUrl.toString()
    }

}
