package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.enums.ChannelType
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.visualizer.BuildStatus
import com.kony.appfactory.helper.AppFactoryException

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

    /* CustomHooks build Parameters*/
    private final runCustomHook = script.params.RUN_CUSTOM_HOOKS
    private boolean isCustomHookRunBuild

    /* CustomHookHelper object */
    protected hookHelper

    protected BuildStatus buildStatus

    /* Channel objects for each channel-type */
    private AndroidChannel androidMobileChannel, androidTabletChannel, androidUniversalChannel
    private IosChannel iosMobileChannel, iosTabletChannel, iosUniversalChannel

    /* Create a list with artifact objects for e-mail template */
    private channelArtifacts = []
    private channelObjects = [:]
    protected channelObject

    /*
        Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and
        can not be used.
     */
    final projectWorkspaceFolderName
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    protected separator = '/'

    /* Common variables */
    protected final projectName = script.env.PROJECT_NAME
    protected String projectFileName = "project.zip"

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

        hookHelper = new CustomHookHelper(script)
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        /* Checking if at least one channel been selected */
        channelsToRun = (BuildHelper.getSelectedChannels(this.script.params)) ?:
                script.echoCustom('Please select at least one channel to build!', 'ERROR')

        buildStatus = new BuildStatus(script, channelsToRun)
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
                    androidMobileChannel = new AndroidChannel(script)
                    androidMobileChannel.channelFormFactor = "Mobile"
                    channelObjects.put(item, androidMobileChannel as Object)
                    break
                case ChannelType.ANDROID_TABLET_NATIVE.toString():
                    androidTabletChannel = new AndroidChannel(script)
                    androidTabletChannel.channelFormFactor = "Tablet"
                    channelObjects.put(item, androidTabletChannel as Object)
                    break
                case ChannelType.IOS_MOBILE_NATIVE.toString():
                    iosMobileChannel = new IosChannel(script)
                    iosMobileChannel.channelFormFactor = "Mobile"
                    channelObjects.put(item, iosMobileChannel as Object)
                    break
                case ChannelType.IOS_TABLET_NATIVE.toString():
                    iosTabletChannel = new IosChannel(script)
                    iosTabletChannel.channelFormFactor = "Tablet"
                    channelObjects.put(item, iosTabletChannel as Object)
                    break
                case ChannelType.ANDROID_UNIVERSAL_NATIVE.toString():
                    androidUniversalChannel = new AndroidChannel(script)
                    androidUniversalChannel.channelFormFactor = "Universal"
                    channelObjects.put(item, androidUniversalChannel as Object)
                    break
                case ChannelType.IOS_UNIVERSAL_NATIVE.toString():
                    iosUniversalChannel = new IosChannel(script)
                    iosUniversalChannel.channelFormFactor = "Universal"
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
                    /*
                    Clean workspace, to be sure that we have not any items from previous build,
                    and build environment completely new.
                    */
                    buildStatus.prepareBuildServiceEnvironment(channelsToRun)
                    buildStatus.prepareStatusJson(true)
                    script.cleanWs deleteDirs: true
                    script.stage('Checkout') {
                        String url = script.params.PROJECT_SOURCE_URL
                        // source code checkout from downloadZipUrl
                        BuildHelper.checkoutProject script: script,
                                checkoutType: "downloadzip",
                                projectRelativePath: checkoutRelativeTargetFolder,
                                downloadURL: url,
                                projectFileName: projectFileName
                    }

                    script.stage('Pre Build') {
                        script.stage('Android Task') {
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('ANDROID') }.each {
                                it.value.pipelineWrapper {
                                    /* reserving space for any pre-setups needed for any android builds */
                                }
                            }
                        }
                        script.stage('Update iOS Bundle ID') {
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('IOS') }.each {
                                it.value.pipelineWrapper {
                                    it.value.updateIosBundleId()
                                }
                            }
                        }
                    }

                    script.stage("Build") {
                        /* Since CI tool is same for both android and ios, we can use any channel wrapper to initiate actual build */
                        /* preferring to use first channel object, can be one of MobileChannel and TabletChannel of Android and iOS */
                        def channelObjectsFirstKey = channelObjects.keySet().toArray()[0]
                        def channelObjectsFirstVal = channelObjects.get(channelObjectsFirstKey)
                        channelObject = channelObjectsFirstVal
                        channelObjectsFirstVal.pipelineWrapper {
                            try {
                                channelObjectsFirstVal.build()
                            }
                            catch(Exception ignored) {
                                String exceptionMessage = "CI build failed for this project, script returned with exit code"
                                script.echoCustom(exceptionMessage, 'ERROR', false)
                                script.currentBuild.result = "UNSTABLE"
                            }
                        }
                    }

                    script.stage("Fetch Binaries") {
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
                                    channelArtifacts.add([channelPath: it.value.channelPath, extensionType: 'KAR'])
                                    channelObjects.remove(it.key)
                                    script.currentBuild.result = "UNSTABLE"
                                }
                            }
                        }
                    }

                    script.stage('Post Build') {
                        script.stage('Sign and Publish Android artifacts') {
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('ANDROID') }.each {
                                def android_channel_id = it.key
                                def android_channel = it.value

                                try {
                                    android_channel.pipelineWrapper {
                                        if (android_channel.buildMode != libraryProperties.'buildmode.debug.type') {
                                            android_channel.signArtifacts(android_channel.buildArtifacts)
                                        } else {
                                            script.echoCustom("Build mode is ${android_channel.buildMode}, " +
                                                    "skipping signing (artifact already signed with debug certificate)!")
                                        }

                                        /* Rename artifacts for publishing */
                                        android_channel.artifacts = android_channel.renameArtifacts(android_channel.buildArtifacts).first()

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
                                catch(AppFactoryException ignored) {
                                    String exceptionMessage = "Exception Found while Sign and Publishing Android artifact!!"
                                    script.echoCustom(exceptionMessage, 'ERROR', false)
                                    buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(android_channel_id))
                                    channelArtifacts.add([channelPath: android_channel.channelPath, extensionType: 'APK'])
                                    script.currentBuild.result = "UNSTABLE"
                                }
                            }
                        }
                        script.stage("IPA generate and Publish iOS artifacts") {
                            /* Search for build artifacts for iOS and publish to S3 */
                            channelObjects.findAll { channelId, channelObject -> channelId.contains('IOS') }.each {
                                def ios_channel_id = it.key
                                def ios_channel = it.value

                                try {
                                    ios_channel.pipelineWrapper {
                                        ios_channel.fetchFastlaneConfig()
                                        ios_channel.createIPA()

                                        /* Get ipa file name and path */
                                        def foundArtifacts = ios_channel.getArtifactLocations('ipa')

                                        /* Rename artifacts for publishing */
                                        ios_channel.ipaArtifact = ios_channel.renameArtifacts(foundArtifacts).first()

                                        /* Publish iOS ipa artifact to S3 */
                                        ios_channel.ipaArtifactUrl = AwsHelper.publishToS3 bucketPath: ios_channel.s3ArtifactPath,
                                                sourceFileName: ios_channel.ipaArtifact.name, sourceFilePath: ios_channel.ipaArtifact.path, script

                                        ios_channel.authenticatedIPAArtifactUrl = BuildHelper.createAuthUrl(ios_channel.ipaArtifactUrl, script, false)

                                        /* Get plist artifact */
                                        ios_channel.plistArtifact = ios_channel.createPlist(ios_channel.authenticatedIPAArtifactUrl)
                                    }

                                    ios_channel.pipelineWrapper {
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
                                catch(AppFactoryException ignored) {
                                    String exceptionMessage = "Exception Found while Sign and Publishing iOS artifact!! ${ios_channel_id}"
                                    script.echoCustom(exceptionMessage, 'ERROR', false)
                                    buildStatus.updateFailureBuildStatusOnS3(ChannelType.valueOf(ios_channel_id))
                                    channelArtifacts.add([channelPath: ios_channel.channelPath, extensionType: 'IPA'])
                                    script.currentBuild.result = "UNSTABLE"
                                }
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

                    /* Prepare log for cloud build and publish to s3 */
                    String buildLogs = BuildHelper.getBuildLogText(
                            script.env.JOB_NAME,
                            script.env.BUILD_ID,
                            script)
                    def consoleLogUrl = buildStatus.createAndUploadLogFile(channelsToRun, buildLogs)

                    // update status file on S3 with Logs Link
                    buildStatus.updateBuildStatusOnS3()
                    // finally update the global status of the build and update status file on S3
                    buildStatus.deriveGlobalBuildStatus(channelsToRun)
                    // send build results email notification

                    NotificationsHelper.sendEmail(script, 'cloudBuild', [artifacts: channelArtifacts, consolelogs: consoleLogUrl])
                }
            }
        }
    }
}