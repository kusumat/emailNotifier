package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

class IosChannel extends Channel {
    private karArtifact
    private plistArtifact
    private ipaArtifact
    private ipaArtifactUrl

    /* Build parameters */
    private final appleID = script.params.APPLE_ID
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    /* At least one of application id parameters should be set */
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ? iosMobileAppId : iosTabletAppId

    IosChannel(script) {
        super(script)
        channelOs = 'iOS'
        channelType = 'Native'
        /* Expose iOS bundle ID to environment variables to use it in HeadlessBuild.properties */
        this.script.env['IOS_BUNDLE_ID'] = iosBundleId
    }

    protected final exposeFastlaneConfig() {
        String fastlaneEnvFileName = libraryProperties.'fastlane.envfile.name'
        String fastlaneEnvFileConfigBucketPath = libraryProperties.'fastlane.envfile.path' + '/' + fastlaneEnvFileName
        /* For using temporary access keys (AssumeRole) */
        String awsIAMRole = script.env.AWS_IAM_ROLE
        String configBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        String configBucketName = script.env.S3_CONFIG_BUCKET

        script.catchErrorCustom('Failed to fetch fastlane configuration') {
            script.withAWS(region: configBucketRegion, role: awsIAMRole) {
                script.s3Download file: fastlaneEnvFileName,
                        bucket: configBucketName,
                        path: fastlaneEnvFileConfigBucketPath,
                        force: true

                /* Read fastlane configuration for file */
                String config = script.readFile file: fastlaneEnvFileName

                /* Convert to properties */
                Properties fastlaneConfig = script.readProperties text: config

                /* Expose values from config as env variables to use them during IPA file creation */
                for (item in fastlaneConfig) {
                    script.env[item.key] = item.value
                }
            }
        }
    }

    private final void createIPA() {
        String successMessage = 'IPA file created successfully'
        String errorMessage = 'Failed to create IPA file'
        String fastLaneBuildCommand = (buildMode == 'release') ? 'release' : 'debug'
        String visualizerDropinsPath = [visualizerHome, 'Kony_Visualizer_Enterprise', 'dropins'].join(separator)
        String codeSignIdentity = (iosDistributionType == 'development') ? 'iPhone Developer' : 'iPhone Distribution'
        String iosDummyProjectBasePath = [projectWorkspacePath, 'KonyiOSWorkspace'].join(separator)
        String iosDummyProjectWorkspacePath = [iosDummyProjectBasePath, 'VMAppWithKonylib'].join(separator)
        String iosDummyProjectGenPath = [iosDummyProjectWorkspacePath, 'gen'].join(separator)

        script.catchErrorCustom(errorMessage, successMessage) {
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
                    cp ${karArtifact.path}/${karArtifact.name} .
                    perl extract.pl ${karArtifact.name}
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
                            "MATCH_APP_IDENTIFIER=${iosBundleId}",
                            "MATCH_GIT_URL=${script.env.MATCH_GIT_URL}",
                            "MATCH_GIT_BRANCH=${(appleDeveloperTeamId) ?: script.env.MATCH_USERNAME}",
                            "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                            "GYM_OUTPUT_DIRECTORY=${karArtifact.path}",
                            "GYM_OUTPUT_NAME=${projectName}",
                            "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}",
                            "FL_PROJECT_SIGNING_PROJECT_PATH=${iosDummyProjectWorkspacePath}/VMAppWithKonylib.xcodeproj",
                            "MATCH_TYPE=${iosDistributionType}"
                    ]) {
                        script.dir('fastlane') {
                            String fastFileName = libraryProperties.'fastlane.fastfile.name'
                            String fastFileContent = script.loadLibraryResource(resourceBasePath + fastFileName)
                            script.writeFile file: fastFileName, text: fastFileContent
                        }
                        script.sshagent (credentials: [libraryProperties.'fastlane.certificates.repo.credentials.id']) {
                            script.sh '$FASTLANE_DIR/fastlane kony_ios_' + fastLaneBuildCommand
                        }
                    }
                }
            }
        }
    }

    private final createPlist(String ipaArtifactUrl) {
        (ipaArtifactUrl) ?: script.error("ipaArtifactUrl argument can't be null!")

        String successMessage = 'PLIST file created successfully.'
        String errorMessage = 'Failed to create PLIST file'
        String plistResourcesFileName = libraryProperties.'ios.plist.file.name'
        String plistFileName = "${projectName}_${jobBuildNumber}.plist"
        String plistFilePath = "${script.pwd()}"

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(plistFilePath) {
                /* Load property list file template */
                String plist = script.loadLibraryResource(resourceBasePath + plistResourcesFileName)

                /* Substitute required values */
                String plistUpdated = plist.replaceAll('\\$path', ipaArtifactUrl)
                        .replaceAll('\\$bundleIdentifier', iosBundleId)

                /* Write updated property list file to current working directory */
                script.writeFile file: plistFileName, text: plistUpdated
            }
        }

        [name: "$plistFileName", path: "$plistFilePath"]
    }

    protected final void createPipeline() {
        script.timestamps {
            script.stage('Check provided parameters') {
                ValidationHelper.checkBuildConfiguration(script)

                def mandatoryParameters = ['IOS_DISTRIBUTION_TYPE', 'APPLE_ID', 'IOS_BUNDLE_VERSION', 'FORM_FACTOR']

                channelFormFactor.equalsIgnoreCase('Mobile') ? mandatoryParameters.add('IOS_MOBILE_APP_ID') :
                        mandatoryParameters.add('IOS_TABLET_APP_ID')

                ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
            }

            script.node(libraryProperties.'ios.node.label') {
                /* Get and expose configuration file for fastlane */
                exposeFastlaneConfig()

                pipelineWrapper {
                    script.cleanWs deleteDirs: true

                    script.stage('Check build-node environment') {
                        ValidationHelper.checkBuildConfiguration(script,
                                ['VISUALIZER_HOME', channelVariableName, 'IOS_BUNDLE_ID', 'PROJECT_WORKSPACE',
                                 'FABRIC_ENV_NAME'])
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
                        karArtifact = getArtifactLocations(artifactExtension).first() ?:
                                script.error('Build artifacts were not found!')
                    }

                    script.stage('Generate IPA file') {
                        createIPA()
                        /* Get ipa file name and path */
                        def foundArtifacts = getArtifactLocations('ipa')
                        /* Rename artifacts for publishing */
                        ipaArtifact = renameArtifacts(foundArtifacts).first()
                    }

                    script.stage("Publish ipa artifact to S3") {
                        ipaArtifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                sourceFileName: ipaArtifact.name, sourceFilePath: ipaArtifact.path, script, true
                    }

                    script.stage("Generate property list file") {
                        /* Get plist artifact */
                        plistArtifact = createPlist(ipaArtifactUrl)
                    }

                    script.stage("Publish PLIST artifact to S3") {
                        String artifactName = plistArtifact.name
                        String artifactPath = plistArtifact.path
                        String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                sourceFileName: artifactName, sourceFilePath: artifactPath, script, true

                        artifacts.add([
                                channelPath: channelPath, name: artifactName, url: artifactUrl
                        ])
                    }

                    script.env['CHANNEL_ARTIFACTS'] = artifacts?.inspect()
                }
            }
        }
    }
}
