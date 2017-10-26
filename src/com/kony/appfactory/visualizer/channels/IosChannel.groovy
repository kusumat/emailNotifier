package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

/**
 * Implements logic for iOS channel builds.
 */
class IosChannel extends Channel {
    private karArtifact
    private plistArtifact
    private ipaArtifact
    /* IPA file S3 URL, used for PLIST file creation */
    private ipaArtifactUrl

    /* Build parameters */
    private final appleID = script.params.APPLE_ID
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    /* At least one of application id parameters should be set */
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ? iosMobileAppId : iosTabletAppId

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    IosChannel(script) {
        super(script)
        channelOs = 'iOS'
        channelType = 'Native'
        /* Expose iOS bundle ID to environment variables to use it in HeadlessBuild.properties */
        this.script.env['IOS_BUNDLE_ID'] = iosBundleId
    }

    /**
     * Exposes Fastlane configuration for signing build artifacts.
     */
    protected final void exposeFastlaneConfig() {
        String fastlaneEnvFileName = libraryProperties.'fastlane.envfile.name'
        String fastlaneEnvFileConfigBucketPath = libraryProperties.'fastlane.envfile.path' + '/' + fastlaneEnvFileName
        String awsIAMRole = script.env.AWS_IAM_ROLE
        String configBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        String configBucketName = script.env.S3_CONFIG_BUCKET

        script.catchErrorCustom('Failed to fetch fastlane configuration') {
            /* Switch to configuration bucket region, and use role to pretend aws instance that has S3 access */
            script.withAWS(region: configBucketRegion, role: awsIAMRole) {
                /* Fetch Fastlane configuration */
                script.s3Download file: fastlaneEnvFileName,
                        bucket: configBucketName,
                        path: fastlaneEnvFileConfigBucketPath,
                        force: true

                /* Read fastlane configuration from a file */
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

    /**
     * Signs build artifacts.
     */
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
                script.shellCustom("cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip", true)
                script.unzip dir: 'iOS-plugin', zipFile: 'iOS-plugin.zip'
                def dummyProjectArchive = script.findFiles(glob: 'iOS-plugin/iOS-GA-*.zip')
                script.unzip zipFile: "${dummyProjectArchive[0].path}"
            }

            /* Extract necessary files from KAR file to Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectGenPath) {
                script.shellCustom("""
                    cp ${karArtifact.path}/${karArtifact.name} .
                    perl extract.pl ${karArtifact.name}
                """, true)
            }

            /* Build project and export IPA using Fastlane */
            script.dir(iosDummyProjectWorkspacePath) {
                /* Inject required environment variables */
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
                            script.shellCustom('$FASTLANE_DIR/fastlane kony_ios_' + fastLaneBuildCommand, true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates PLIST file.
     * @param ipaArtifactUrl IPA file S3 URL.
     * @return PLIST file object, format: {name: <NameOfPlistFile>, path: <PlistFilePath>}.
     */
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

    /**
     * Updates projectprop.xml file with user provided bundle ID.
     */
    private final void updateIosBundleId() {
        String projectPropFileName = libraryProperties.'ios.propject.props.file.name'
        String successMessage = 'Bundle ID updated successfully.'
        String errorMessage = 'Failed to update ' + projectPropFileName + ' file with provided Bundle ID!'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(projectFullPath) {
                if (script.fileExists(projectPropFileName)) {
                    String projectPropFileContent = script.readFile file: projectPropFileName

                    String updatedProjectPropFileContent = projectPropFileContent.replaceAll(
                            '<attributes name="iphonebundleidentifierkey".*',
                            '<attributes name="iphonebundleidentifierkey" value="' + iosBundleId + '"/>'
                    )

                    script.writeFile file: projectPropFileName, text: updatedProjectPropFileContent
                } else {
                    script.error("Failed to find $projectPropFileName file to update bundle ID!")
                }
            }
        }
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        script.timestamps {
            script.stage('Check provided parameters') {
                ValidationHelper.checkBuildConfiguration(script)

                def mandatoryParameters = ['IOS_DISTRIBUTION_TYPE', 'APPLE_ID', 'IOS_BUNDLE_VERSION', 'FORM_FACTOR']

                channelFormFactor.equalsIgnoreCase('Mobile') ? mandatoryParameters.add('IOS_MOBILE_APP_ID') :
                        mandatoryParameters.add('IOS_TABLET_APP_ID')

                ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
            }

            /* Allocate a slave for the run */
            script.node(libraryProperties.'ios.node.label') {
                /* Get and expose configuration file for fastlane */
                exposeFastlaneConfig()

                pipelineWrapper {
                    /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                    script.cleanWs deleteDirs: true

                    script.stage('Check build-node environment') {
                        ValidationHelper.checkBuildConfiguration(script,
                                ['VISUALIZER_HOME', channelVariableName, 'IOS_BUNDLE_ID', 'PROJECT_WORKSPACE',
                                 'FABRIC_ENV_NAME'])
                    }

                    script.stage('Checkout') {
                        BuildHelper.checkoutProject script: script,
                                projectRelativePath: checkoutRelativeTargetFolder,
                                scmBranch: scmBranch,
                                scmCredentialsId: scmCredentialsId,
                                scmUrl: scmUrl
                    }

                    script.stage('Update bundle ID') {
                        updateIosBundleId()
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
