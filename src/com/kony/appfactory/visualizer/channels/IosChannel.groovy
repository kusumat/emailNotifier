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
    /* Stash name for fastlane configuration */
    private fastlaneConfigStashName

    /* Build parameters */
    private final appleID = script.params.APPLE_ID
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    /* At least one of application id parameters should be set */
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ? iosMobileAppId : iosTabletAppId
    private final iosOTAPrefix = "itms-services://?action=download-manifest&url="

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    IosChannel(script) {
        super(script)
        channelOs = 'iOS'
        channelType = 'Native'
        fastlaneConfigStashName = libraryProperties.'fastlane.config.stash.name'
        /* Expose iOS bundle ID to environment variables to use it in HeadlessBuild.properties */
        this.script.env['IOS_BUNDLE_ID'] = iosBundleId
    }

    /**
     * Fetches fastlane configuration files for signing build artifacts from S3.
     */
    protected final void fetchFastlaneConfig() {
        String fastlaneFastfileName = libraryProperties.'fastlane.fastfile.name'
        String fastlaneFastfileNameConfigBucketPath = libraryProperties.'fastlane.envfile.path' + '/' +
                fastlaneFastfileName
        String fastlaneEnvFileName = libraryProperties.'fastlane.envfile.name'
        String fastlaneEnvFileConfigBucketPath = libraryProperties.'fastlane.envfile.path' + '/' + fastlaneEnvFileName
        String awsIAMRole = script.env.AWS_IAM_ROLE
        String configBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        String configBucketName = script.env.S3_CONFIG_BUCKET

        script.catchErrorCustom('Failed to fetch fastlane configuration') {
            /* Switch to configuration bucket region, and use role to pretend aws instance that has S3 access */
            script.withAWS(region: configBucketRegion, role: awsIAMRole) {
                script.dir(fastlaneConfigStashName) {
                    /* Fetch fastlane configuration */
                    script.s3Download file: fastlaneEnvFileName,
                            bucket: configBucketName,
                            path: fastlaneEnvFileConfigBucketPath,
                            force: true

                    script.s3Download file: fastlaneFastfileName,
                            bucket: configBucketName,
                            path: fastlaneFastfileNameConfigBucketPath,
                            force: true

                    /* Stash fetch fastlane configuration files to be able to use them during signing */
                    script.stash name: fastlaneConfigStashName

                    /* Remove fetched fastlane configuration files */
                    script.deleteDir()
                }
            }
        }
    }

    /**
     * Updates projectprop.xml and projectProperties.json file with user provided Bundle ID.
     */
    private final void updateIosBundleId() {

            def projectPropFileName = [
                    libraryProperties.'ios.project.props.xml.file.name',
                    libraryProperties.'ios.project.props.json.file.name'
            ]
        projectPropFileName.each { propertyFileName ->

            String successMessage = 'Bundle ID updated successfully in ' + propertyFileName + '.'
            String errorMessage = 'Failed to update ' + propertyFileName + ' file with provided Bundle ID!'

            /* Store property file content*/
            def propertyFileContent

            script.catchErrorCustom(errorMessage, successMessage) {
                script.dir(projectFullPath) {
                    if (script.fileExists(propertyFileName)) {
                        if (propertyFileName.endsWith('.xml')){
                            /* Reading xml from Workspace */
                            propertyFileContent = script.readFile file: propertyFileName

                            /*
                            *  In projectprop.xml visualizer ensure this property is present
                            *  So we just need to replace it
                            */
                            String updatedProjectPropFileContent = propertyFileContent.replaceAll(
                                    '<attributes name="iphonebundleidentifierkey".*',
                                    '<attributes name="iphonebundleidentifierkey" value="' + iosBundleId + '"/>'
                            )

                            script.writeFile file: propertyFileName, text: updatedProjectPropFileContent
                        }
                        if (propertyFileName.endsWith('.json')){
                            /* Reading Json from Workspace */
                            propertyFileContent = script.readJSON file: propertyFileName

                            /*
                            *  projectProperties.json may or may not contain this key
                            *  So we have to inject below key if key is not present otherwise update existing key
                            */
                            propertyFileContent['iphonebundleidentifierkey'] = iosBundleId

                            script.writeJSON file: propertyFileName, json: propertyFileContent
                        }

                    } else {
                        script.error("Failed to find $projectPropFileName file to update Bundle ID!")
                    }
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
        /* Point Dropins folder based on Headless build and CI build to location where kony plugins are stored */
        String visualizerDropinsPath = script.env.isCIBUILD ? [projectWorkspacePath, 'kony-plugins'].join(separator) : [visualizerHome, 'Kony_Visualizer_Enterprise', 'dropins'].join(separator)
        String codeSignIdentity = (iosDistributionType == 'development') ? 'iPhone Developer' : 'iPhone Distribution'
        String iosDummyProjectBasePath = [projectWorkspacePath, 'KonyiOSWorkspace'].join(separator)
        String iosDummyProjectWorkspacePath = [iosDummyProjectBasePath, 'VMAppWithKonylib'].join(separator)
        String iosDummyProjectGenPath = [iosDummyProjectWorkspacePath, 'gen'].join(separator)

        script.catchErrorCustom(errorMessage, successMessage) {
            /* Extract Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectBasePath) {
                script.shellCustom("cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip", true)
                script.unzip dir: 'iOS-plugin', zipFile: 'iOS-plugin.zip'
                def dummyProjectArchive = script.findFiles(glob: 'iOS-plugin/*.zip')
                script.unzip zipFile: "${dummyProjectArchive[0].path}"
            }

            script.dir(iosDummyProjectGenPath){
                setExecutePermissions("nativebinding",true)
                setExecutePermissions("crypt",false)
            }

            /* Extract necessary files from KAR file to Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectGenPath) {
                script.shellCustom("""
                    cp ${karArtifact.path}/${karArtifact.name} .
                    perl extract.pl ${karArtifact.name}
                """, true)
            }

            /* Set Export Method for Fastlane according to iosDistributionType
             * NOTE : For adhoc distribution type export method should be ad-hoc
             *   For appstore distribution type export method should be app-store
             */
            def iOSExportMethod;
            switch (iosDistributionType) {
                case 'adhoc':
                    iOSExportMethod="ad-hoc"
                    break
                case 'appstore':
                    iOSExportMethod="app-store"
                    break
                default:
                    iOSExportMethod=iosDistributionType
                    break
            }

            /* Build project and export IPA using fastlane */
            script.dir(iosDummyProjectWorkspacePath) {
                /* Inject required environment variables */
                script.withCredentials([
                    script.usernamePassword(
                        credentialsId: "${appleID}",
                        passwordVariable: 'FASTLANE_PASSWORD',
                        usernameVariable: 'MATCH_USERNAME'
                    )
                ]) {
                    def ProjectBuildMode = buildMode.capitalize()
                    script.withEnv([
                            "FASTLANE_DONT_STORE_PASSWORD=true",
                            "MATCH_APP_IDENTIFIER=${iosBundleId}",
                            "MATCH_GIT_BRANCH=${(appleDeveloperTeamId) ?: script.env.MATCH_USERNAME}",
                            "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                            "GYM_OUTPUT_DIRECTORY=${karArtifact.path}",
                            "GYM_OUTPUT_NAME=${projectName}",
                            "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}",
                            "FL_PROJECT_SIGNING_PROJECT_PATH=${iosDummyProjectWorkspacePath}/VMAppWithKonylib.xcodeproj",
                            "MATCH_TYPE=${iosDistributionType}",
                            "EXPORT_METHOD=${iOSExportMethod}",
                            "BUILD_NUMBER=${script.env.BUILD_NUMBER}",
                            "PROJECT_WORKSPACE=${iosDummyProjectBasePath}",
                            "PROJECT_BUILDMODE=${ProjectBuildMode}",
                            "FASTLANE_TEAM_ID=${script.env.APPLE_DEVELOPER_TEAM_ID}",
                            "FASTLANE_SKIP_UPDATE_CHECK=1"
                    ]) {
                        script.dir('fastlane') {
                            script.unstash name: fastlaneConfigStashName
                        }
                        script.sshagent (credentials: [libraryProperties.'fastlane.certificates.repo.credentials.id']) {
                            /* set iOS build configuration to debug/release based on Visualizer version,
                            * note that, in 8.1.0 and above versions, to build debug mode binary, set the build configuration of KRelease as debug.
                            */
                            if (getVisualizerPackVersion(script.env.visualizerVersion) >= getVisualizerPackVersion(libraryProperties.'ios.schema.buildconfig.changed.version')) {
                                script.shellCustom('$FASTLANE_DIR/fastlane kony_ios_build', true)
                            }
                            else {
                                script.shellCustom('$FASTLANE_DIR/fastlane kony_ios_' + buildMode, true)
                            }
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
                fetchFastlaneConfig()

                pipelineWrapper {
                    /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                    script.cleanWs deleteDirs: true

                    script.stage('Check build-node environment') {
                    /*
                        FABRIC_ENV_NAME is optional parameter which should be validated only if 
                        if user entered some values
                    */
                        def parametersToValidate = ['VISUALIZER_HOME', channelVariableName, 'IOS_BUNDLE_ID', 'PROJECT_WORKSPACE']
                        if (script.env.FABRIC_ENV_NAME) {
                            parametersToValidate << 'FABRIC_ENV_NAME'
                        }
                        ValidationHelper.checkBuildConfiguration(script, parametersToValidate)
                    }

                    script.stage('Checkout') {
                        BuildHelper.checkoutProject script: script,
                                projectRelativePath: checkoutRelativeTargetFolder,
                                scmBranch: scmBranch,
                                scmCredentialsId: scmCredentialsId,
                                scmUrl: scmUrl
                    }

                    script.stage('Update Bundle ID') {
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

                    script.stage("Publish IPA artifact to S3") {
                        ipaArtifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                sourceFileName: ipaArtifact.name, sourceFilePath: ipaArtifact.path, script
                    }

                    script.stage("Generate PLIST file") {
                        String authenticatedIPAArtifactUrl = BuildHelper.createAuthUrl(ipaArtifactUrl, script, false);
                        /* Get plist artifact */
                        plistArtifact = createPlist(authenticatedIPAArtifactUrl)
                    }

                    script.stage("Publish PLIST artifact to S3") {
                        String artifactName = plistArtifact.name
                        String artifactPath = plistArtifact.path
                        String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                sourceFileName: artifactName, sourceFilePath: artifactPath, script
								
						String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true);
						String plistArtifactOTAUrl = iosOTAPrefix + authenticatedArtifactUrl

                        artifacts.add([
                                channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl, otaurl: plistArtifactOTAUrl
                        ])
                    }

                    script.env['CHANNEL_ARTIFACTS'] = artifacts?.inspect()
                }
            }
        }
    }
}
