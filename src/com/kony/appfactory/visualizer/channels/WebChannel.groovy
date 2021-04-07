package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.ArtifactHelper
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
    protected final webChannelParameterName = BuildHelper.getCurrentParamName(script, 'RESPONSIVE_WEB', 'DESKTOP_WEB')
    protected final desktopWebChannel = script.params[webChannelParameterName]
    protected final webAppVersion = script.params.WEB_APP_VERSION
    protected final webProtectionPreset = script.params.PROTECTION_LEVEL
    protected final webProtectionExcludeListFile = (script.params.EXCLUDE_LIST_PATH)?.trim()
    protected final webProtectionBlueprintFile = (script.params.CUSTOM_PROTECTION_PATH)?.trim()
    protected final webProtectionID = script.params.OBFUSCATION_PROPERTIES
    protected webAppUrl

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
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        this.hookHelper = new CustomHookHelper(script, BuildType.Visualizer)
        /* Expose Web build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = webAppVersion
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        secureJsFileName = libraryProperties.'web_protected.obfuscation.file.name'
        channelOs = channelFormFactor = channelType = webChannelParameterName.replaceAll('_', '')
    }

    WebChannel(script) {
        this(script, 'WEB')
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
     * Creates job pipeline for given Web Channel type (DesktopWeb/ResponsiveWeb).
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void pipelineWrapperForWebChannels(webChannelType) {
        channelBuildStats.put("aver", webAppVersion)
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    if (!(webChannelType.equalsIgnoreCase("DESKTOP_WEB") || webChannelType.equalsIgnoreCase("RESPONSIVE_WEB")) && !(desktopWebChannel)) {
                        script.echoCustom('Please select at least one channel to build!', 'ERROR')
                    }
                    ValidationHelper.checkBuildConfiguration(script)
                    ValidationHelper.checkBuildConfiguration(script, ['WEB_APP_VERSION', 'FABRIC_APP_CONFIG'])
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
                 *  Due to this, if user need to run Custom Hooks on Web (runCustomHook is checked) then run Web
                 *  build on MAC Agent. Otherwise default node strategy will be followed (WIN || MAC)
                 */
                resourceList = BuildHelper.getResourcesList()
                isCustomHookRunBuild = BuildHelper.isThisBuildWithCustomHooksRun(projectName, BuildType.Visualizer, runCustomHook, libraryProperties)
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
                                triggerHooksBasedOnSelectedChannels(libraryProperties.'customhooks.prebuild.name')
                            } else {
                                script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
                            }
                        }

                        script.stage('Build') {
                            if(buildMode == libraryProperties.'buildmode.release.protected.type') {
                                if(webProtectionID) {
                                    def securejsFullPath = [workspace, projectWorkspaceFolderName, "temp", projectName, libraryProperties.'web.protection.securejs.log.folder.path'].join(separator)
                                    mustHaveArtifacts.add([name: "securejs.log", path: securejsFullPath])
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
                                        if (!excludeListFileName.endsWith(".txt") || excludeListFileName.contains(" ")) {
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


                        script.stage('Publish to Fabric') {
                            /* Publish Fabric application if PUBLISH_FABRIC_APP/PUBLISH_WEB_APP set to true */
                            if (publishWebApp) {
                                FabricHelper.fetchFabricCli(script, libraryProperties, libraryProperties.'fabric.cli.version')
                                FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, fabricCredentialsID, script.env.FABRIC_ACCOUNT_ID, script.env.FABRIC_ENV_NAME, isUnixNode)
                                
                                /* Fabric option for cliCommands */
                                def fabricCommandOptions = ['-t': "\"${script.env.FABRIC_ACCOUNT_ID}\"",
                                                            '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                                            '-v': "\"${script.env.FABRIC_APP_VERSION}\"",
                                                            '--webAppVersion': "\"${script.env.APP_VERSION}\"",
                                                            '-e': "\"${script.env.FABRIC_ENV_NAME}\"",]
                                /* Prepare string with shell script to run */
                                FabricHelper.fabricCli(script, 'publish', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions)
                                script.echoCustom("Published to Fabric Successfully, Fetching AppInfo")
                                fabricCommandOptions.remove('--webAppVersion')
                                webAppUrl = fetchWebAppUrl("appinfo", fabricCommandOptions)
                                script.echoCustom("Your published app is accessible at : " + webAppUrl)
                            } else {
                                script.echoCustom("${publishToFabricParamName} flag set to false, " +
                                        "skipping Fabric application publishing...")

                            }
                        }


                        script.stage("Publish artifacts") {
                            /* Rename artifacts for publishing */
                            def channelArtifacts = renameArtifacts(buildArtifacts)

                            channelArtifacts?.each { artifact ->
                                String artifactName = artifact.name
                                String artifactPath = artifact.path
                                String artifactUrl = ArtifactHelper.publishArtifact sourceFileName: artifactName,
                                        sourceFilePath: artifactPath, destinationPath: destinationArtifactPath, script

                                String authenticatedArtifactUrl = ArtifactHelper.createAuthUrl(artifactUrl, script, true)
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
                                    triggerHooksBasedOnSelectedChannels(libraryProperties.'customhooks.postbuild.name')
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
     * In this method, depending upon the webChannelType (DESKTOP_WEB, RESPONSIVE_WEB) call runCustomHooks for WEB_STAGE
     * @param buildStage is the hook stage such as PRE_BUILD, POST_BUILD, POST_TEST
     */
    protected triggerHooksBasedOnSelectedChannels(buildStage){
        def projectStage = "WEB_STAGE"
        hookHelper.runCustomHooks(projectName, buildStage, projectStage)
    }

    /**
     * Runs Fabric CLI commands which have to return something.
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
            throw new AppFactoryException('Web app url is not found! It seems Fabric app is not published with uploaded web application.', 'ERROR')
        webAppUrl.toString()
    }

}
