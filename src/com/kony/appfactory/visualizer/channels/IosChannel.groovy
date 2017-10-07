package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class IosChannel extends Channel {
    private bundleID
    private karFile
    private plistFileName

    /* Build parameters */
    private final appleID = script.params.APPLE_ID
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    /* At least one of application id parameters should be set */
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
            iosMobileAppId : iosTabletAppId

    IosChannel(script) {
        super(script)
        nodeLabel = 'mac'
        channelOs = 'iOS'
        channelType = 'Native'
        plistFileName = "${projectName}_${jobBuildNumber}.plist"
        /* Expose iOS bundle ID to environment variables to use it in HeadlessBuild.properties */
        this.script.env['IOS_BUNDLE_ID'] = iosBundleId
    }

    protected final exposeFastlaneConfig() {
        def libraryProperties = script.loadLibraryProperties(resourceBasePath + 'configurations/' + 'common.properties')
        def fastlaneEnvFileName = libraryProperties.'fastlane.envfile.name'
        def fastlaneEnvFileConfigBucketPath = libraryProperties.'fastlane.envfile.path' + '/' + fastlaneEnvFileName
        /* For using temporary access keys (AssumeRole) */
        def awsIAMRole = script.env.AWS_IAM_ROLE
        def configBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        def configBucketName = script.env.S3_CONFIG_BUCKET

        script.catchErrorCustom('FAILED to fetch fastlane configuration') {
            script.withAWS(region: configBucketRegion, role: awsIAMRole) {
                script.s3Download file: fastlaneEnvFileName,
                        bucket: configBucketName,
                        path: fastlaneEnvFileConfigBucketPath,
                        force: true

                /* Read fastlane configuration for file */
                def config = script.readFile file: fastlaneEnvFileName

                /* Convert to properties */
                def fastlaneConfig = script.readProperties text: config

                /* Expose values from config as env variables to use them during IPA file creation */
                for (item in fastlaneConfig) {
                    script.env[item.key] = item.value
                }
            }
        }
    }

    private final void createIPA() {
        String successMessage = 'IPA file created successfully'
        String errorMessage = 'FAILED to create IPA file'
        String fastLaneBuildCommand = (buildMode == 'release') ? 'release' : 'debug'
        String visualizerDropinsPath = [visualizerHome, 'Kony_Visualizer_Enterprise', 'dropins'].join(separator)
        String codeSignIdentity = (iosDistributionType == 'development') ? 'iPhone Developer' : 'iPhone Distribution'
        String iosDummyProjectBasePath = [projectWorkspacePath, 'KonyiOSWorkspace'].join(separator)
        String iosDummyProjectWorkspacePath = [iosDummyProjectBasePath, 'VMAppWithKonylib'].join(separator)
        String iosDummyProjectGenPath = [iosDummyProjectWorkspacePath, 'gen'].join(separator)

        script.catchErrorCustom(errorMessage, successMessage) {
            /* Get bundle identifier and iOS plugin version */
            script.dir(projectFullPath) {
                bundleID = bundleIdentifier(script.readFile('projectprop.xml'))
            }
            /* Extract Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectBasePath) {
                script.sh "cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip"
                script.unzip dir: 'iOS-plugin', zipFile: 'iOS-plugin.zip'
                def dummyProjectArchive = script.findFiles(glob: 'iOS-plugin/iOS-GA-*.zip')
                script.unzip zipFile: "${dummyProjectArchive[0].path}"
            }
            /* Extract necessary files from KAR file to Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectGenPath) {
                script.sh """
                    cp ${karFile.path}/${karFile.name} .
                    perl extract.pl ${karFile.name} sqd
                """
            }
            /* Build project and export IPA using Fastlane */
            script.dir(iosDummyProjectWorkspacePath) {
                script.withCredentials([
                    script.usernamePassword(
                        credentialsId: "${appleID}",
                        passwordVariable: 'FASTLANE_PASSWORD',
                        usernameVariable: 'MATCH_USERNAME'
                    )
                ]) {
                    script.withEnv([
                            "FASTLANE_DONT_STORE_PASSWORD=true",
                            "MATCH_APP_IDENTIFIER=${bundleID}",
                            "MATCH_GIT_URL=${script.env.MATCH_GIT_URL}",
                            "MATCH_GIT_BRANCH=${(appleDeveloperTeamId) ?: script.env.MATCH_USERNAME}",
                            "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                            "GYM_OUTPUT_DIRECTORY=${karFile.path}",
                            "GYM_OUTPUT_NAME=${projectName}",
                            "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}",
                            "FL_PROJECT_SIGNING_PROJECT_PATH=${iosDummyProjectWorkspacePath}/VMAppWithKonylib.xcodeproj",
                            "MATCH_TYPE=${iosDistributionType}"
                    ]) {
                        script.dir('fastlane') {
                            def fastFileName = 'Fastfile'
                            def fastFileContent = script.loadLibraryResource(resourceBasePath + fastFileName)
                            script.writeFile file: fastFileName, text: fastFileContent
                        }
                        script.sshagent (credentials: ['jenkins_github_ssh-certificates']) {
                            script.sh '$FASTLANE_DIR/fastlane kony_ios_' + fastLaneBuildCommand
                        }
                    }
                }
            }
        }
    }

    private final void createPlist() {
        String successMessage = 'PLIST file created successfully'
        String errorMessage = 'FAILED to create PLIST file'
        String plistResourcesFileName = 'apple_orig.plist'
        String plistPathTagValue = AWSHelper.getS3ArtifactURL(script, ['Builds', environment].join('/'))

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifacts[0].path) {
                /* Load property list file template */
                String plist = script.loadLibraryResource(resourceBasePath + plistResourcesFileName)

                /* Substitute required values */
                String plistUpdated = plist.replaceAll('\\$path', plistPathTagValue)
                        .replaceAll('\\$bundleIdentifier', bundleID)

                /* Write updated property list file to current working directory */
                script.writeFile file: plistFileName, text: plistUpdated
            }
        }
    }

    private final bundleIdentifier(text) {
        def matcher = text =~ '<attributes name="iphonebundleidentifierkey" value="(.+)"/>'
        return matcher ? matcher[0][1] : null
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            def channelAppIdType = (channelFormFactor.equalsIgnoreCase('Mobile')) ? ['IOS_MOBILE_APP_ID'] :
                    ['IOS_TABLET_APP_ID']

            BuildHelper.checkBuildConfiguration(script, ['IOS_DISTRIBUTION_TYPE', 'APPLE_ID', 'IOS_BUNDLE_VERSION'] + channelAppIdType)
        }

        script.node(nodeLabel) {
            /* Get and expose configuration file for fastlane */
            exposeFastlaneConfig()

            pipelineWrapper {
                script.deleteDir()

                script.stage('Check build-node environment') {
                    BuildHelper.checkBuildConfiguration(script, ['VISUALIZER_HOME', channelVariableName, 'IOS_BUNDLE_ID'])
                }

                script.stage('Checkout') {
                    BuildHelper.checkoutProject script: script,
                            projectRelativePath: checkoutRelativeTargetFolder,
                            gitBranch: gitBranch,
                            gitCredentialsID: gitCredentialsID,
                            gitURL: gitURL
                }

                script.stage('Build') {
                    build()
                    /* Search for build artifacts */
                    karFile = getArtifactLocations(artifactExtension)[0] ?:
                            script.error('Build artifacts were not found!')
                }

                script.stage('Generate IPA file') {
                    createIPA()
                    /* Get ipa file name and path */
                    def foundArtifacts = getArtifactLocations('ipa')
                    /* Rename artifacts for publishing */
                    artifacts = renameArtifacts(foundArtifacts)
                }

                script.stage("Generate property list file") {
                    createPlist()
                    /* Get plist artifact */
                    artifacts.add([name: plistFileName, path: "${karFile.path}"])
                }

                script.stage("Publish artifacts to S3") {
                    /* Create a list with artifact objects for e-mail template */
                    def channelArtifacts = []

                    artifacts?.each { artifact ->
                        String artifactName = artifact.name
                        String artifactPath = artifact.path
                        String artifactUrl = AWSHelper.publishToS3 script: script, bucketPath: s3ArtifactPath,
                                exposeURL: true, sourceFileName: artifactName, sourceFilePath: artifactPath

                        if (!artifact.name.contains('ipa')) { // Exclude ipa from artifacts list

                            channelArtifacts.add([channelPath: channelPath,
                                                  name       : artifactName,
                                                  url        : artifactUrl])
                        }
                    }

                    script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                }
            }
        }
    }
}
