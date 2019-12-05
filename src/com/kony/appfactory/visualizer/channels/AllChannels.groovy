package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.annotations.Path
import com.kony.appfactory.dto.visualizer.ProjectPropertiesDTO
import com.kony.appfactory.enums.ChannelType
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CredentialsHelper
import com.kony.appfactory.helper.JsonHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.visualizer.BuildStatus
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.CloudBuildHelper

/**
 * Implements logic for all channel builds.
 */
class AllChannels implements Serializable {
    /* Pipeline object */
    private script

    /* Library configuration */
    private libraryProperties

    /* nodeLabel store slave label */
    private nodeLabel

    /* List of channels to build */
    private channelsToRun
    protected BuildStatus buildStatus

    /* Channel objects for each channel-type */
    private AndroidChannel androidMobileChannel, androidTabletChannel, androidUniversalChannel
    private IosChannel iosMobileChannel, iosTabletChannel, iosUniversalChannel

    /* Create a list with artifact objects for e-mail template */
    private channelArtifacts = []
    private channelObjects = [:]
    protected channelObject
    private artifactsMeta = [:]

    /*
        Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and
        can not be used.
     */
    final projectWorkspaceFolderName
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    protected checkoutFolder

    protected separator = '/'

    /* Common variables */
    protected final projectName = script.env.PROJECT_NAME
    protected String projectFileName

    protected CredentialsHelper credentialsHelper

    /* This variable contains content of projectProperties.json file */
    protected ProjectPropertiesDTO projectProperties

    /*This will contain the project root location */
    protected String projectDir

    /*This will contain the project root location */
    protected String projectPropertyFileName


    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    AllChannels(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        projectFileName = libraryProperties.'cloudbuild.sourceproject.download.file.name'
        /* Checking if at least one channel been selected */
        channelsToRun = (BuildHelper.getSelectedChannels(this.script.params)) ?:
                script.echoCustom('Please select at least one channel to build!', 'ERROR')

        buildStatus = new BuildStatus(script, channelsToRun)
        credentialsHelper = new CredentialsHelper()
        projectPropertyFileName = libraryProperties.'ios.project.props.json.file.name'
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
        for (item in channelsToRun) {
            switch (item) {
                case ChannelType.ANDROID_MOBILE_NATIVE.toString():
                    androidMobileChannel = new AndroidChannel(script, "Mobile")
                    channelObjects.put(item, androidMobileChannel as Object)
                    break
                case ChannelType.ANDROID_TABLET_NATIVE.toString():
                    androidTabletChannel = new AndroidChannel(script, "Tablet")
                    channelObjects.put(item, androidTabletChannel as Object)
                    break
                case ChannelType.IOS_MOBILE_NATIVE.toString():
                    iosMobileChannel = new IosChannel(script, "Mobile")
                    channelObjects.put(item, iosMobileChannel as Object)
                    break
                case ChannelType.IOS_TABLET_NATIVE.toString():
                    iosTabletChannel = new IosChannel(script, "Tablet")
                    channelObjects.put(item, iosTabletChannel as Object)
                    break
                case ChannelType.ANDROID_UNIVERSAL_NATIVE.toString():
                    androidUniversalChannel = new AndroidChannel(script, "Universal")
                    channelObjects.put(item, androidUniversalChannel as Object)
                    break
                case ChannelType.IOS_UNIVERSAL_NATIVE.toString():
                    iosUniversalChannel = new IosChannel(script, "Universal")
                    channelObjects.put(item, iosUniversalChannel as Object)
                    break
                default:
                    break
            }
        }

        def androidChannels = channelsToRun?.findAll { it.matches('^ANDROID_.*_NATIVE$') }
        def iosChannels = channelsToRun?.findAll { it.matches('^IOS_.*_NATIVE$') }

        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                /* Allocating Mac slave for CloudBuilds run */
                nodeLabel = libraryProperties.'ios.node.label'

                script.node(nodeLabel) {
                    try {
                        // Clear workspace so no items remains from previous build
                        script.cleanWs deleteDirs: true

                        // Initialize build status Object by mapping buildStatus.json file uploaded by Facade Job
                        AwsHelper.s3Download(script, BuildStatus.BUILD_STATUS_FILE_NAME, script.params.BUILD_STATUS_PATH)
                        if (script.fileExists(BuildStatus.BUILD_STATUS_FILE_NAME)) {
                            def statusFileJson = script.readJSON file: BuildStatus.BUILD_STATUS_FILE_NAME
                            buildStatus = BuildStatus.getBuildStatusObject(script, statusFileJson, channelsToRun)

                            buildStatus.buildJson.platforms.each { platform ->
                                platform.buildNumber = script.env.BUILD_NUMBER
                            }
                            buildStatus.updateBuildStatusOnS3()
                        } else {
                            script.callStageCustom(buildStatus, "Starting build") {
                                // When no buildStatus.json is present in s3, init a new BuildStatus class Object.
                                buildStatus.prepareBuildServiceEnvironment(channelsToRun)
                                buildStatus.prepareStatusJson(true)
                            }
                        }

                        script.env.IS_KONYQUANTUM_APP_BUILD = ValidationHelper.isValidStringParam(script, 'IS_KONYQUANTUM_APP_BUILD') ? script.params.IS_KONYQUANTUM_APP_BUILD : false

                        checkoutFolder = checkoutRelativeTargetFolder

                        script.callStageCustom(buildStatus, "Downloading Project/Application source binaries") {
                            if (script.env.IS_KONYQUANTUM_APP_BUILD.equalsIgnoreCase("true")) {
                                // if Quantum preview app build, checkout  AppViewer master source code for reading version mapper json
                                BuildHelper.checkoutProject script:script,
                                        checkoutType: "scm",
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmCredentialsId: libraryProperties.'quantum.app.repo.credential.id',
                                        scmUrl: libraryProperties.'quantum.app.repo.url',
                                        scmBranch: libraryProperties.'quantum.app.repo.master.branch'

                                // lets re-set checkoutFolder for downloading actual child app for Quantum preview app build in different location.
                                checkoutFolder = [projectWorkspaceFolderName,
                                                  libraryProperties.'quantum.childapp.temp.download.dir'].join(separator)
                            }

                            String url = script.params.PROJECT_SOURCE_URL
                            // source code checkout from downloadZipUrl
                            BuildHelper.checkoutProject script: script,
                                    checkoutType: "downloadzip",
                                    projectRelativePath: checkoutFolder,
                                    downloadURL: url,
                                    projectFileName: projectFileName

                            // if Quantum preview app build, checkout the AppViewer source for the dependent branch/tag
                            if (script.env.IS_KONYQUANTUM_APP_BUILD.equalsIgnoreCase("true")) {
                                script.env.QUANTUM_CHILDAPP_VIZ_VERSION = CloudBuildHelper.getQuantumChildAppVizVersion(script, checkoutFolder, projectPropertyFileName)
                                // Get the mapper json file name for cloud env to be read
                                def quantumAppMapperJsonFile = CloudBuildHelper.getMapperJsonFileForQuantumAppBuid(
                                        script.env.CLOUD_DOMAIN,
                                        libraryProperties.'quantum.app.non_prod.version.mapper.json',
                                        libraryProperties.'quantum.app.prod.version.mapper.json'
                                )

                                script.dir(checkoutRelativeTargetFolder) {
                                    def quantumAppViewerCheckOutVersion = CloudBuildHelper.getQuantumAppCheckoutVersionTag script: script,
                                            quantumAppMapperJsonFilePath: quantumAppMapperJsonFile,
                                            quantumChildAppVisualizerVersion: script.env.QUANTUM_CHILDAPP_VIZ_VERSION

                                    // Checkout AppViewer source for version/tag fetched
                                    String checkoutCommand = "git checkout \"${quantumAppViewerCheckOutVersion}\""
                                    script.shellCustom(checkoutCommand, true)
                                }
                            }

                        }

                        script.callStageCustom(buildStatus, "Executing Pre-Build tasks") {
                            // setting project root folder path
                            if (!script.fileExists([checkoutRelativeTargetFolder, projectPropertyFileName].join(separator))) {
                                script.dir(checkoutRelativeTargetFolder) {
                                    def projectRoot = script.findFiles glob: '**/' + projectPropertyFileName
                                    if (projectRoot)
                                        script.env.PROJECT_ROOT_FOLDER_NAME = projectRoot[0].path.minus(separator + projectPropertyFileName)
                                    else
                                        script.echoCustom("Unable to recognize Visualizer project source code.", 'ERROR')
                                }
                            }
                            projectDir = script.env.PROJECT_ROOT_FOLDER_NAME ?
                                    [checkoutRelativeTargetFolder, script.env.PROJECT_ROOT_FOLDER_NAME].join(separator) :
                                    checkoutRelativeTargetFolder

                            checkoutFolder = (script.env.IS_KONYQUANTUM_APP_BUILD.equalsIgnoreCase("true")) ? checkoutFolder : projectDir

                            script.dir(checkoutFolder) {
                                String projectPropertiesText = script.readFile file: projectPropertyFileName
                                projectProperties = JsonHelper.parseJson(projectPropertiesText, ProjectPropertiesDTO.class)
                                modifyPaths(projectProperties)

                                // Removed android Keystore path to STOP CI build auto signing
                                def jsonContent = script.readJSON text: projectPropertiesText
                                jsonContent['keyStoreFilePath'] = ''
                                script.writeJSON file: projectPropertyFileName, json: jsonContent
                            }

                            // Let's check whether third party authentication is enabled or not
                            BuildHelper.getExternalAuthInfoForCloudBuild(script, script.env['CLOUD_DOMAIN'], script.kony['MF_API_VERSION'], script.env['CLOUD_ENVIRONMENT_GUID'])


                            channelObjects.findAll { channelId, channelObject -> channelId.contains('ANDROID') }.each {
                                it.value.pipelineWrapper {
                                    it.value.artifactMeta.add("version": ["App Version": it.value.androidAppVersion, "Build Version": it.value.androidAppVersionCode])
                                    artifactsMeta.put(it.value.channelPath, it.value.artifactMeta)
                                }
                            }



                            channelObjects.findAll { channelId, channelObject -> channelId.contains('IOS') }.each {
                                it.value.pipelineWrapper {
                                    it.value.artifactMeta.add("version": ["App Version": it.value.iosAppVersion, "Build Version": it.value.iosBundleVersion])
                                    artifactsMeta.put(it.value.channelPath, it.value.artifactMeta)
                                }
                            }

                            /* Run PreBuild Hooks */
                            script.stage("Pre-build custom hooks") {
                                channelObjects.each {
                                    it.value.runPreBuildHook()
                                }
                            }
                        }

                        script.callStageCustom(buildStatus, "Build in progress") {
                            /* Since CI tool is same for both android and ios, we can use any channel wrapper to initiate actual build */
                            /* preferring to use first channel object, can be one of MobileChannel and TabletChannel of Android and iOS */
                            def channelObjectsFirstKey = channelObjects.keySet().toArray()[0]
                            channelObject = channelObjects.get(channelObjectsFirstKey)
                            channelObject.pipelineWrapper {
                                try {
                                    if (channelObject.buildMode == libraryProperties.'buildmode.release.protected.type') {
                                        copyProtectedModeKeys()
                                    }

                                    channelObject.build()
                                }
                                catch (Exception Ex) {
                                    String exceptionMessage = (Ex.getLocalizedMessage()) ?: 'Something went wrong...'
                                    script.echoCustom(exceptionMessage, 'ERROR', false)
                                    script.currentBuild.result = "UNSTABLE"
                                }
                            }
                        }

                        script.callStageCustom(buildStatus, "Build completed. Copying binaries") {
                            if (androidChannels) {
                                channelObjects.findAll { channelId, channelObject -> channelId.contains('ANDROID') }.each {
                                    try {
                                        it.value.pipelineWrapper {
                                            def androidArtifactExtension = it.value.getArtifactExtension(it.key)
                                            it.value.buildArtifacts = it.value.getArtifactLocations(androidArtifactExtension) ?:
                                                    script.echoCustom('Build artifacts for Android were not found!', 'ERROR')
                                        }
                                    }
                                    catch (AppFactoryException ignored) {
                                        String exceptionMessage = "Build artifacts for ${it.key} were not found!!"
                                        script.echoCustom(exceptionMessage, 'ERROR', false)
                                        channelArtifacts.add([channelPath: it.value.channelPath, extensionType: 'APK'])
                                        buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(it.key))
                                        channelObjects.remove(it.key)
                                        script.currentBuild.result = "UNSTABLE"
                                    }
                                }
                            }

                            if (iosChannels) {
                                channelObjects.findAll { channelId, channelObject -> channelId.contains('IOS') }.each {
                                    try {
                                        it.value.pipelineWrapper {
                                            def iosArtifactExtension = it.value.getArtifactExtension(it.key)
                                            it.value.karArtifact = it.value.getArtifactLocations(iosArtifactExtension).first() ?:
                                                    script.echoCustom('Build artifacts for iOS KAR were not found!', 'ERROR')
                                        }
                                    }
                                    catch (AppFactoryException ignored) {
                                        String exceptionMessage = "Build artifacts for ${it.key} were not found!!"
                                        script.echoCustom(exceptionMessage, 'ERROR', false)
                                        channelArtifacts.add([channelPath: it.value.channelPath, extensionType: 'IPA'])
                                        buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(it.key))
                                        channelObjects.remove(it.key)
                                        script.currentBuild.result = "UNSTABLE"
                                    }
                                }
                            }
                        }

                        script.callStageCustom(buildStatus, "Executing post build tasks") {
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('ANDROID') }.each {
                                def android_channel_id = it.key
                                def android_channel = it.value
                                def channel_name = getChannelName(android_channel_id)

                                script.callStageCustom(buildStatus, "Signing and publishing ${channel_name} binary") {
                                    try {
                                        android_channel.pipelineWrapper {
                                            if (android_channel.buildMode != libraryProperties.'buildmode.debug.type') {
                                                androidSigningCloudWrapper(script) {
                                                    // Follow up ticket: APPFACT-1465. We should rethink of better solution in next release
                                                    android_channel.visualizerDependencies = channelObject.visualizerDependencies
                                                    android_channel.signArtifacts(android_channel.buildArtifacts)
                                                }
                                            } else {
                                                script.echoCustom("Build mode is ${android_channel.buildMode}, " +
                                                        "skipping signing (artifact already signed with debug certificate)!")
                                            }

                                            /* artifacts for publishing */
                                            android_channel.artifacts = android_channel.buildArtifacts.first()

                                            /* Publish Android artifact to S3 */
                                            String artifactUrl = AwsHelper.publishToS3 bucketPath: android_channel.s3ArtifactPath,
                                                    sourceFileName: android_channel.artifacts.name, sourceFilePath: android_channel.artifacts.path, script

                                            String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true)
                                            channelArtifacts.add([
                                                    channelPath: android_channel.channelPath, name: android_channel.artifacts.name, url: artifactUrl, authurl: authenticatedArtifactUrl
                                            ])

                                            String artifactPath = [script.env['CLOUD_ACCOUNT_ID'], projectName, android_channel.s3ArtifactPath, android_channel.artifacts.name].join('/')
                                            buildStatus.updateSuccessBuildStatusOnS3(ChannelType.valueOf(android_channel_id), artifactPath)
                                        }
                                    }
                                    catch (AppFactoryException ignored) {
                                        String exceptionMessage = "Exception Found while Sign and Publishing Android artifact!!"
                                        script.echoCustom(exceptionMessage, 'ERROR', false)
                                        buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(android_channel_id))
                                        channelArtifacts.add([channelPath: android_channel.channelPath, extensionType: 'APK'])
                                        script.currentBuild.result = "UNSTABLE"
                                    }
                                }
                            }

                            /* Search for build artifacts for iOS and publish to S3 */
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('IOS') }.each {
                                def ios_channel_id = it.key
                                IosChannel ios_channel = it.value
                                def channel_name = getChannelName(ios_channel_id)

                                script.callStageCustom(buildStatus, "Generating IPA and publishing ${channel_name} binaries") {
                                    try {
                                        ios_channel.pipelineWrapper {
                                            ios_channel.fetchFastlaneConfig()
                                            iosSigningCloudWrapper(script, ios_channel) {
                                                ios_channel.setIosBundleId();
                                                ios_channel.createIPA()
                                            }

                                            /* Get ipa file name and path */
                                            def foundArtifacts = ios_channel.getArtifactLocations('ipa')

                                            /* artifacts for publishing */
                                            ios_channel.ipaArtifact = foundArtifacts.first()

                                            /* Publish iOS ipa artifact to S3 */
                                            ios_channel.ipaArtifactUrl = AwsHelper.publishToS3 bucketPath: ios_channel.s3ArtifactPath,
                                                    sourceFileName: ios_channel.ipaArtifact.name, sourceFilePath: ios_channel.ipaArtifact.path, script

                                            ios_channel.authenticatedIPAArtifactUrl = BuildHelper.createAuthUrl(ios_channel.ipaArtifactUrl, script, false)

                                            /* Get plist artifact */
                                            ios_channel.plistArtifact = ios_channel.createPlist(ios_channel.authenticatedIPAArtifactUrl)

                                            String artifactName = ios_channel.plistArtifact.name
                                            String artifactPath = ios_channel.plistArtifact.path

                                            /* Publish iOS plist artifact to S3 */
                                            String artifactUrl = AwsHelper.publishToS3 bucketPath: ios_channel.s3ArtifactPath,
                                                    sourceFileName: artifactName, sourceFilePath: artifactPath, script

                                            String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true)
                                            String plistArtifactOTAUrl = authenticatedArtifactUrl

                                            channelArtifacts.add([
                                                    channelPath: ios_channel.channelPath, name: artifactName, url: artifactUrl, otaurl: plistArtifactOTAUrl, ipaName: ios_channel.ipaArtifact.name, ipaAuthUrl: ios_channel.authenticatedIPAArtifactUrl
                                            ])

                                            artifactPath = [script.env['CLOUD_ACCOUNT_ID'], projectName, ios_channel.s3ArtifactPath, ios_channel.ipaArtifact.name].join('/')
                                            buildStatus.updateSuccessBuildStatusOnS3(ChannelType.valueOf(ios_channel_id), artifactPath)
                                        }
                                    }
                                    catch (AppFactoryException ignored) {
                                        String exceptionMessage = "Exception Found while Sign and Publishing iOS artifact!! ${ios_channel_id}"
                                        script.echoCustom(exceptionMessage, 'ERROR', false)
                                        buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(ios_channel_id))

                                        /* Place KAR file into channelArtifacts and StatusJson file */
                                        ios_channel.pipelineWrapper {
                                            channelArtifacts.add([
                                                    channelPath: ios_channel.channelPath, name: ios_channel.karArtifact.name, karAuthUrl: ios_channel.authenticatedKARArtifactUrl
                                            ])
                                            def artifactPath = [script.env['CLOUD_ACCOUNT_ID'], projectName, ios_channel.s3ArtifactPath, ios_channel.karArtifact.name].join('/')
                                            buildStatus.updateDownloadLink(ChannelType.valueOf(ios_channel_id), artifactPath)
                                        }

                                        script.currentBuild.result = "UNSTABLE"
                                    }
                                }
                            }

                            /* Run PostBuild Hooks */
                            script.stage("Post-build custom hooks") {
                                channelObjects.each {
                                    it.value.runPostBuildHook()
                                }
                            }
                        }

                        /* check in case all the builds got failed */
                        if (channelArtifacts.every { channel -> !channel.containsKey('name') }) {
                            script.currentBuild.result = 'FAILURE'
                        } else {
                            script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                            script.echoCustom("Total artifacts: ${script.env['CHANNEL_ARTIFACTS']}")
                        }
                    }
                    catch (Exception err) {
                        String exceptionMessage = (err.getLocalizedMessage()) ?: 'Something went wrong....'
                        script.echoCustom(exceptionMessage, 'ERROR', false)
                        script.echoCustom("Build failed either due to errors or abort!!", 'ERROR', false)
                    }
                    finally {
                        def buildNumber = BuildHelper.getUpstreamJobNumber(script)
                        def jobName = 'buildVisualizerApp'
                        def abortMsg = ""
                        if (!script.currentBuild.rawBuild.getActions(jenkins.model.InterruptedBuildAction.class).isEmpty()) {
                            abortMsg = "BUILD ABORTED!!"
                            script.echoCustom(abortMsg, 'ERROR', false)
                        }

                        /* Collecting MustHaves.
                         * Let's use first channel object, can be one of MobileChannel and TabletChannel of Android or iOS,
                         * for collecting musthaves from signle channel object for the entire cloudbuild.
                         */
                        if (channelObjects) {
                            def channelObjectsFirstKey = channelObjects.keySet().toArray()[0]
                            channelObject = channelObjects.get(channelObjectsFirstKey)
                            channelObject.pipelineWrapper {
                                try {
                                    channelObject.mustHavePath = [channelObject.projectFullPath, 'mustHaves'].join(separator)
                                    if (script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED') {
                                        channelObject.upstreamJob = BuildHelper.getUpstreamJobName(script)
                                        channelObject.isRebuild = BuildHelper.isRebuildTriggered(script)
                                        channelObject.PrepareMustHaves()
                                    }
    
                                }
                                catch (Exception Ex) {
                                    String exceptionMessage = (Ex.getLocalizedMessage()) ?: 'Something went wrong...'
                                    script.echoCustom(exceptionMessage, 'ERROR', false)
                                    script.currentBuild.result = "UNSTABLE"
                                }
                            }
                            
                        }
                        
                        /* Prepare log for cloud build and publish to s3 */
                        def consoleLogsLink = buildStatus.createAndUploadLogFile(script.env.JOB_NAME, script.env.BUILD_ID, abortMsg)
                        // Send build results email notification with proper buildStatus
                        if (abortMsg?.trim()) {
                            script.currentBuild.result = 'ABORTED'
                        }
                        script.env['CHANNEL_ARTIFACT_META'] = artifactsMeta?.inspect()
                        NotificationsHelper.sendEmail(script, 'cloudBuild', [artifacts: channelArtifacts, consolelogs: consoleLogsLink, buildNumber: buildNumber, jobName: jobName, artifactMeta: artifactsMeta], true)
                    }
                }
            }
        }
    }

    protected final void androidSigningCloudWrapper(script, closure) {

        script.dir(projectDir) {

            def isValid = validateAndroidCertProperties()
            if(!isValid) {
                script.echoCustom('Skipping android apk signing and unsigned apk will be shared.', 'WARN')
                return
            }

            def KSFILE = [script.pwd(), projectProperties.keyStoreFilePath].join('/')

            def signingInfo = credentialsHelper.getSigningIdMap(script.env['BUILD_NUMBER'])
            def androidSigningInfo = signingInfo['android']

            try {
                credentialsHelper.addSecretText(
                        androidSigningInfo['KSPASS'].id,
                        androidSigningInfo['KSPASS'].description,
                        projectProperties.keyStorePassword)
                credentialsHelper.addSecretText(
                        androidSigningInfo['KEYPASS'].id,
                        androidSigningInfo['KEYPASS'].description,
                        projectProperties.keyPassword)
                credentialsHelper.addSecretText(
                        androidSigningInfo['KEY_ALIAS'].id,
                        androidSigningInfo['KEY_ALIAS'].description,
                        projectProperties.keyAlias)
                credentialsHelper.addSecretFile(
                        androidSigningInfo['KSFILE'].id,
                        androidSigningInfo['KSFILE'].description,
                        KSFILE, script)

                script.withCredentials([
                        script.string(credentialsId: androidSigningInfo['KSPASS'].id, variable: 'KSPASS'),
                        script.string(credentialsId: androidSigningInfo['KEYPASS'].id, variable: 'KEYPASS'),
                        script.string(credentialsId: androidSigningInfo['KEY_ALIAS'].id, variable: 'KEY_ALIAS'),
                        script.file(credentialsId: androidSigningInfo['KSFILE'].id, variable: 'KSFILE')
                ]) {
                    closure()
                }
            }
            finally {
                credentialsHelper.deleteUserCredentials(
                        [androidSigningInfo['KSPASS'].id,
                         androidSigningInfo['KEYPASS'].id,
                         androidSigningInfo['KEY_ALIAS'].id,
                         androidSigningInfo['KSFILE'].id
                        ])
            }
        }
    }

    /**
     * Validate IOS properties in projectProperties.json
     */
    def validateIosCertProperties() {
        projectProperties.developmentMethod ?:
                script.echoCustom("Missing developmentMethod in $projectPropertyFileName in project. ", 'ERROR')
        projectProperties.iOSP12Password ?:
                script.echoCustom("Missing iOSP12Password in $projectPropertyFileName in project. ", 'ERROR')
        projectProperties.iOSP12FilePath ?:
                script.echoCustom("Missing iOSP12FilePath property in $projectPropertyFileName in project. ", 'ERROR')
        projectProperties.iOSMobileProvision ?:
                script.echoCustom("Missing iOSMobileProvision property in $projectPropertyFileName in project. ", 'ERROR')
        script.fileExists(projectProperties.iOSP12FilePath) ?:
                script.echoCustom('Failed to locate apple certificate(.iOSP12FilePath) in project. Failed to sign IOS application.', 'ERROR')
        script.fileExists(projectProperties.iOSMobileProvision) ?:
                script.echoCustom('Failed to locate iOS MobileProvisioning File(.mobileprovision) in project. Failed to sign IOS application.', 'ERROR')
    }

    /**
     * Validate android properties in projectProperties.json
     */
    def validateAndroidCertProperties() {

        def missingKeys = []

        missingKeys.add(projectProperties.keyStoreFilePath ? 'OK' : 'keyStoreFilePath')
        missingKeys.add(projectProperties.keyStorePassword ? 'OK' : 'keyStorePassword')
        missingKeys.add(projectProperties.keyAlias ? 'OK' : 'keyAlias')
        missingKeys.add(projectProperties.keyPassword ? 'OK' : 'keyPassword')

        if (missingKeys.every { it == 'OK' }) {
            def isLocated = script.fileExists(projectProperties.keyStoreFilePath)
            isLocated ?: script.echoCustom('Failed to locate keystore in project.', 'WARN')
            return isLocated
        } else {
            missingKeys.each {
                if (it != 'OK')
                    script.echoCustom("Missing $it in $projectPropertyFileName in project. ", 'WARN')
            }
            return false
        }
    }

    /**
     * Validate Protect Mode encryption properties in projectProperties.json
     */
    def validateProtectedModeProperties() {
        projectProperties.protectedModePublicKey ?:
                script.echoCustom("Missing protectedModePublicKey in $projectPropertyFileName in project. ", 'ERROR')
        projectProperties.protectedModePrivateKey ?:
                script.echoCustom("Missing protectedModePrivateKey in $projectPropertyFileName in project. ", 'ERROR')

        script.fileExists(projectProperties.protectedModePublicKey) ?:
                script.echoCustom('Failed to locate public key File(.dat) in project. Failed to load encryption keys.', 'ERROR')
        script.fileExists(projectProperties.protectedModePrivateKey) ?:
                script.echoCustom('Failed to locate iOS Private key File(.pem) in project. Failed to load encryption keys.', 'ERROR')
    }

    protected final void iosSigningCloudWrapper(script, IosChannel ios_channel, closure) {

        String appleId = script.params.APPLE_ID
        if(!appleId || appleId == "null") {

            def absAppleCertPath = ''
            def absAppleMobileProvisionPath = ''

            def signingInfo = credentialsHelper.getSigningIdMap(script.env['BUILD_NUMBER'])
            def iosSigningInfo = signingInfo['iOS']

            script.dir(projectDir) {

                validateIosCertProperties()

                absAppleCertPath = [script.pwd(), projectProperties.iOSP12FilePath].join('/')
                absAppleMobileProvisionPath = [script.pwd(), projectProperties.iOSMobileProvision].join('/')

                script.echoCustom("Found p12 Cert located at $absAppleCertPath \n" +
                        "Found Provisioning profile located at $absAppleMobileProvisionPath")
            }
            try {
                credentialsHelper.addAppleCert(
                        iosSigningInfo['APPLE_SIGNING_CERT'].id,
                        iosSigningInfo['APPLE_SIGNING_CERT'].description,
                        projectProperties.iOSP12Password,
                        absAppleCertPath,
                        absAppleMobileProvisionPath,
                        script
                )

                // Nullifying AppleID as manual certs from CloudBuild are surfaced.
                ios_channel.setAppleID(null)
                ios_channel.setAppleCertID(iosSigningInfo['APPLE_SIGNING_CERT'].id)
                ios_channel.setIosDistributionType(projectProperties.developmentMethod)
                script.withEnv(["IS_SINGLE_PROFILE=true",
                                "PROVISION_PASSWORD=$projectProperties.iOSP12Password"]) {
                    closure()
                }
            }
            finally {
                credentialsHelper.deleteUserCredentials([iosSigningInfo['APPLE_SIGNING_CERT'].id])
            }
        }
        else {
            closure()
        }
    }

    protected final void copyProtectedModeKeys() {
        def isUnix = script.isUnix()
        script.dir(projectDir) {

            validateProtectedModeProperties()

            String absPublicKeyFilePath = [script.pwd(), projectProperties.protectedModePublicKey].join(separator)
            String absPrivateKeyFilePath = [script.pwd(), projectProperties.protectedModePrivateKey].join(separator)

            def targetDir = ['..', "__encryptionkeys"].join(separator)
            script.shellCustom("mkdir -p $targetDir", isUnix)

            // Fin key directory creation will be done as part of - APPFACT-1365
            def targetFinDir = ['..', "__encryptionkeys", "fin"].join(separator)
            script.shellCustom("mkdir -p $targetFinDir", isUnix)

            def copyCmd = script.isUnix() ? 'cp' : 'copy'
            script.shellCustom([copyCmd, absPublicKeyFilePath, targetDir].join(' '), isUnix)
            script.shellCustom([copyCmd, absPrivateKeyFilePath, targetDir].join(' '), isUnix)
        }
    }

    /**
     * This method help in converting all the paths into OS specific path.
     * It iterate over all the fields in given object and modify fields
     * which are Annotated with Path annotation.
     * @param targetObject
     */
    @NonCPS
    protected void modifyPaths(Object targetObject) {
        if (targetObject) {
            targetObject.getClass().declaredFields.each { field ->
                if (Path.class in field.declaredAnnotations*.annotationType()) {
                    if (targetObject."$field.name") {
                        targetObject."$field.name" = String.valueOf(targetObject."$field.name").replace('\\', separator)
                    }
                }
            }
        }
    }

    /**
     * Returns channel name in more readable format..
     * @param channel build parameter name. eg: ANDROID_MOBILE_NATIVE
     * @return relative channel name. eg: Android Mobile Native
     */
    private final getChannelName(channelId) {
        def channelName = channelId.tokenize('_').collect() { item ->
            item.toLowerCase().capitalize()
        }.join(' ')

        channelName
    }
}
