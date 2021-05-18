package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.enums.BuildType
import com.kony.AppFactory.Jenkins.credentials.impl.AppleSigningCertUtils
import com.kony.AppFactory.Jenkins.credentials.impl.ProfileInfo

/**
 * Implements logic for iOS channel builds.
 */
class IosChannel extends Channel {
    private karArtifact
    private plistArtifact
    private ipaArtifact
    /* IPA file S3 URL, used for PLIST file creation */
    private ipaArtifactUrl
    private authenticatedIPAArtifactUrl

    /* KAR file S3 URL */
    private karArtifactUrl
    private authenticatedKARArtifactUrl

    /* Stash name for fastlane configuration */
    private fastlaneConfigStashName

    /* Build parameters */
    private appleID = script.params.APPLE_ID
    private appleCertID = script.params.APPLE_SIGNING_CERTIFICATES
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    /* At least one of application id parameters should be set */
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosUniversalAppId = script.params.IOS_UNIVERSAL_APP_ID
    private final iosAppVersion = script.params.IOS_APP_VERSION
    private final iosBundleVersion = script.params.IOS_BUNDLE_VERSION
    private iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    protected iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
        iosMobileAppId : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
        iosTabletAppId : iosUniversalAppId
    private final iosOTAPrefix = "itms-services://?action=download-manifest&url="
    private iosWatchExtension = script.env.APPLE_WATCH_EXTENSION ?: false

    void setAppleCertID(appleCertID) {
        this.appleCertID = appleCertID
    }

    void setAppleID(appleID) {
        this.appleID = appleID
    }

    void setIosDistributionType(iosDistributionType) {
        this.iosDistributionType = iosDistributionType
    }

    def getIosMobileAppId() {
        return iosMobileAppId
    }

    void setIosBundleId() {
        this.iosBundleId = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
                iosMobileAppId : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
                iosTabletAppId : iosUniversalAppId
    }
/**
 * Class constructor.
 *
 * @param script pipeline object.
 */
    IosChannel(script) {
        this(script, script.params.FORM_FACTOR)
    }

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     * @param channelFormFactor
     */
    IosChannel(script, channelFormFactor) {
        super(script)
        this.hookHelper = new CustomHookHelper(script, BuildType.Visualizer)
        channelOs = 'iOS'
        channelType = 'Native'
        fastlaneConfigStashName = libraryProperties.'fastlane.config.stash.name'
        /* Expose iOS bundle ID to environment variables to use it in HeadlessBuild.properties */
        this.script.env['IOS_BUNDLE_ID'] = iosBundleId
        this.script.env['APP_VERSION'] = ValidationHelper.isValidStringParam(script, 'IOS_APP_VERSION') ? iosAppVersion : iosBundleVersion
        this.script.env['IOS_BUNDLE_VERSION'] = iosBundleVersion
        this.channelFormFactor = channelFormFactor
        customHookStage = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
                "IOS_MOBILE_STAGE" : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
                "IOS_TABLET_STAGE" : "IOS_UNIVERSAL_STAGE"
        customHookIPAStage = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
                "IOS_MOBILE_IPA_STAGE" : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
                "IOS_TABLET_IPA_STAGE" : "IOS_UNIVERSAL_IPA_STAGE"
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
        String awsIAMRole = script.env.S3_CONFIG_BUCKET_IAM_ROLE
        String configBucketRegion = script.env.S3_CONFIG_BUCKET_REGION
        String configBucketName = script.env.S3_CONFIG_BUCKET_NAME

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

        def projectPropertyXMLFile = libraryProperties.'ios.project.props.xml.file.name'
        def projectPropertyJSonFile = libraryProperties.'project.props.json.file.name'

        String successMessage = 'Bundle ID updated successfully.'
        String errorMessage = 'Failed to update Bundle ID!'

        /* Store property file content*/
        def propertyFileContent

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(projectFullPath) {
                if (script.fileExists(projectPropertyXMLFile)) {
                    /* Reading xml from Workspace */
                    propertyFileContent = script.readFile file: projectPropertyXMLFile

                    /*
                     *  In projectprop.xml visualizer ensure this property is present
                     *  So we just need to replace it
                     */
                    String updatedProjectPropFileContent = propertyFileContent.replaceAll(
                            '<attributes name="iphonebundleidentifierkey".*',
                            '<attributes name="iphonebundleidentifierkey" value="' + iosBundleId + '"/>'
                    )

                    script.writeFile file: projectPropertyXMLFile, text: updatedProjectPropFileContent
                }
                else if (script.fileExists(projectPropertyJSonFile)) {
                    /* Reading json from Workspace */
                    propertyFileContent = script.readFile file: projectPropertyJSonFile

                    /*
                     * In projectProperties.json visualizer ensure this property is present from SP4
                     * So we just need to update the value.
                     */

                    def jsonContent = script.readJSON text: propertyFileContent
                    jsonContent['iphonebundleidentifierkey'] = iosBundleId

                    script.writeJSON file: projectPropertyJSonFile, json: jsonContent
                } else {
                    throw new AppFactoryException("Failed to find project Property files to update Bundle ID!", 'ERROR')
                }
            }
        }
    }

    /**
     * Publish iOS KAR artifact to S3
     */
    private final void publishKar() {
            script.echoCustom('Publishing KAR artifact to S3...')
            karArtifact = renameArtifacts([karArtifact]).first()
            karArtifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                    sourceFileName: karArtifact.name, sourceFilePath: karArtifact.path, script
            authenticatedKARArtifactUrl = BuildHelper.createAuthUrl(karArtifactUrl, script, false)
    }

    /**
     * Signs build artifacts.
     */
    private final void createIPA() {
        String successMessage = 'IPA file created successfully'
        String errorMessage = 'Failed to create IPA file'
        /* Point Dropins folder based on Headless build and CI build to location whereVolt MXplugins are stored */
        String visualizerDropinsPath = [projectWorkspacePath, 'kony-plugins'].join(separator)
        String codeSignIdentity = (iosDistributionType == 'development') ? 'Develop' : 'Distribution'
        String iosDummyProjectBasePath = [projectWorkspacePath, 'KonyiOSWorkspace'].join(separator)
        String iosDummyProjectWorkspacePath = [iosDummyProjectBasePath, 'VMAppWithKonylib'].join(separator)
        String iosDummyProjectGenPath = [iosDummyProjectWorkspacePath, 'gen'].join(separator)
        String fastlaneName
        boolean isFileShareEnabled = false

        try {
            /* Extract Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectBasePath) {
                script.shellCustom("cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip", true)
                BuildHelper.deleteDirectories(script,["iOS-plugin","VMAppWithKonylib"])
                script.unzip dir: 'iOS-plugin', zipFile: 'iOS-plugin.zip'
                def dummyProjectArchive = script.findFiles(glob: 'iOS-plugin/*.zip')
                script.shellCustom("unzip -q ${dummyProjectArchive[0].path}", true)
            }

            script.dir(iosDummyProjectGenPath) {
                setExecutePermissions("nativebinding", true)
                setExecutePermissions("crypt", false)
            }

            /* Extract necessary files from KAR file to Visualizer iOS Dummy Project */
            script.dir(iosDummyProjectGenPath) {
                def karExtractErrorLog = "error.txt"
                try {
                    script.shellCustom("""
                    cp ${karArtifact.path}/${karArtifact.name} .
                    perl extract.pl ${karArtifact.name}
                """, true)
                    
                    if (script.fileExists(karExtractErrorLog)) {
                        throw new Exception("Error with KAR extraction!!")
                    }
                }
                catch (Exception err) {
                    channelBuildStats.put('errmsg', (err.getLocalizedMessage()) ?: 'Something went wrong...')
                    channelBuildStats.put('errstack', err.getStackTrace().toString())
                    if (script.fileExists(karExtractErrorLog)) {
                        script.shellCustom("cat ${karExtractErrorLog}", true)
                        mustHaveArtifacts.add([name: karExtractErrorLog, path: iosDummyProjectGenPath])
                    }
                    throw new AppFactoryException("KAR extraction failed!!", 'ERROR')
                }
            }
            script.stage('Check PreBuild IPA Hook Points') {
                if (isCustomHookRunBuild) {
                    /* Run Pre Build iOS IPA stage Hooks */
                    hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.prebuild.name', customHookIPAStage)
                } else {
                    script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
                }
            }

            /* Set Export Method for Fastlane according to iosDistributionType
             * NOTE : For adhoc distribution type export method should be ad-hoc
             *   For appstore distribution type export method should be app-store
             */
            def iOSExportMethod;
            switch (iosDistributionType) {
                case 'adhoc':
                    iOSExportMethod = "ad-hoc"
                    break
                case 'appstore':
                    iOSExportMethod = "app-store"
                    break
                default:
                    iOSExportMethod = iosDistributionType
                    break
            }

            /* Build project and export IPA using fastlane */
            script.dir(iosDummyProjectWorkspacePath) {
                /*Note:
                * If the build mode is "release-protected" need to change the build mode to 'Protected' because
                * xcode will not have 'release-protected' as mode. So, changing the build mode explicitly here in that case.
                */
                def ProjectBuildMode = buildMode.equals(libraryProperties.'buildmode.release.protected.type') ?
                          buildMode.substring(buildMode.lastIndexOf("-") + 1).capitalize() : buildMode.capitalize()
                
                script.dir('fastlane') {
                    script.unstash name: fastlaneConfigStashName
                }
                
                /* set iOS build configuration to debug/release based on Visualizer version,
                 * note that, in 8.1.0 and above versions, to build debug mode binary, set the build configuration of KRelease as debug.
                 */
                 def iOSSchemaChangedVersion = libraryProperties.'ios.schema.buildconfig.changed.version'
                 def compareViziOSSchemaChangedVersions = ValidationHelper.compareVersions(script.env.visualizerVersion, iOSSchemaChangedVersion)
                 if ((compareViziOSSchemaChangedVersions >= 0) && (buildMode != libraryProperties.'buildmode.release.protected.type')) {
                     fastlaneName = 'kony_ios_build'
                 } else {
                     fastlaneName = 'kony_ios_' + buildMode.replaceAll('-', '_')
                 }
                 
                 /* Enable the flag to read/write to the disk while running the jasmine tests */
                 isFileShareEnabled = isJasmineTestsExecEnabled && isBuildModeTest
                 
                /*
                 * APPFACT-779
                 * Custom IOS App display name can be given using the Key "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}"
                 * But this is should be picked from projectprop.xml, So this key is removed, and user committed app name will
                 * be considered.
                 *
                 * Note: In debug mode, Visualizer prefixes 'debugger' word in App display name.
                 * */
                script.withEnv([
                    "FASTLANE_DONT_STORE_PASSWORD=true",
                    "MATCH_APP_IDENTIFIER=${iosBundleId}",
                    "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                    "GYM_OUTPUT_DIRECTORY=${karArtifact.path}",
                    "GYM_OUTPUT_NAME=${projectName}",
                    "FL_PROJECT_SIGNING_PROJECT_PATH=${iosDummyProjectWorkspacePath}/VMAppWithKonylib.xcodeproj",
                    "MATCH_TYPE=${iosDistributionType}",
                    "EXPORT_METHOD=${iOSExportMethod}",
                    "BUILD_NUMBER=${script.env.BUILD_NUMBER}",
                    "PROJECT_WORKSPACE=${iosDummyProjectBasePath}",
                    "PROJECT_BUILDMODE=${ProjectBuildMode}",
                    "FASTLANE_TEAM_ID=${script.env.APPLE_DEVELOPER_TEAM_ID}",
                    "FASTLANE_SKIP_UPDATE_CHECK=1",
                    "APP_VERSION=${script.env.APP_VERSION}",
                    "ENABLE_FILE_SHARING=${isFileShareEnabled}",
                    "FORM_FACTOR=${channelFormFactor}"
                ]) {
                    def fastlaneBashEnvironment="export {LANG,LANGUAGE,LC_ALL}=en_US.UTF-8"

                    if (appleID){
                        script.withCredentials([
                                script.usernamePassword(
                                    credentialsId: "${appleID}",
                                    passwordVariable: 'FASTLANE_PASSWORD',
                                    usernameVariable: 'MATCH_USERNAME'
                                )
                        ]) {
                            script.withEnv([
                                    "MATCH_GIT_BRANCH=${(appleDeveloperTeamId) ?: script.env.MATCH_USERNAME}",
                                    "MANUAL_CERTS=false"
                            ]) {
                                script.sshagent(credentials: [libraryProperties.'fastlane.certificates.repo.credentials.id']) {
                                    script.shellCustom('${fastlaneBashEnvironment};$FASTLANE_DIR/fastlane ' + fastlaneName, true)
                                }
                            }
                        }
                    } else if(appleCertID) {
                        def profileFileNames = [], profilesEnv = []
                        script.withCredentials([
                            script.AppleSigningCerts(
                                credentialsId: "${appleCertID}",
                                filePath: "${iosDummyProjectWorkspacePath}",
                                provisionPWD: 'PROVISION_PASSWORD',
                                isSingleProfile: 'IS_SINGLE_PROFILE'
                            )
                        ]) {
                            if(script.env.IS_SINGLE_PROFILE.equalsIgnoreCase("false")){
                                script.unzip zipFile: 'AppleSigningProfiles.zip'
                            }
                            script.dir(iosDummyProjectWorkspacePath){
                                def files = script.findFiles glob: '**/*.mobileprovision'
                                for (file in files) {
                                    profileFileNames.add([iosDummyProjectWorkspacePath, file.path].join(separator))
                                }
                                profilesEnv = getProfileEnvVarsFromFiles(profileFileNames)
                            }
                            script.withEnv([
                                            "PROVISION_CERT_FILE=${iosDummyProjectWorkspacePath}/AppleProvisioningCert.p12",
                                            "PROVISION_CERT_PASSWORD=${script.env.PROVISION_PASSWORD}",
                                            "MANUAL_CERTS=true"
                                            ] + profilesEnv) {
                                script.shellCustom('${fastlaneBashEnvironment};$FASTLANE_DIR/fastlane ' + fastlaneName, true)
                            }
                        }
                    }
                }
                script.dir('fastlane') {
                    /* Cleanup fastlane configuration files post the build, as these should not be exposed/accessed
                     * by other steps like post-build CustomHooks.
                     */
                    script.deleteDir()
                }
            }
            script.echoCustom(successMessage)
        }
        catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, 'ERROR', false)
            script.env['CHANNEL_ARTIFACTS'] = artifacts?.inspect()
            channelBuildStats.put('errmsg', exceptionMessage)
            channelBuildStats.put('errstack', e.getStackTrace().toString())

            throw new AppFactoryException(errorMessage, 'ERROR')
        }
    }

    /**
     * This method will collect all the information (like bundle id, Creation Date, Expiry Date) 
     * about the mobile provisioing profiles from the list of the given files.
     * 
     * @param profileFileNames
     * @return list of ProfileInfo objects
     */
    private final getProfileEnvVarsFromFiles(profileFileNames){
        def profileEnvVars = [], appIdentifiers = [], copyCMDs = []
        def profileHome = "~/Library/MobileDevice/Provisioning\\ Profiles"
        boolean isValidBundleID = false
        
        profileFileNames.each{filePath ->
            def profileContent = script.readFile file: filePath
            def profileInfo = AppleSigningCertUtils.parseProvisioning(profileContent)
            def appIdentifier = profileInfo.getAppIdentifier()
            def teamID = profileInfo.getTeamID()
            def UUID = profileInfo.getUUID()
            appIdentifier = appIdentifier.split('\\.').minus(teamID).join(".")
            def testAppIdentifier = appIdentifier.replaceAll('\\.', '\\\\.')
            if(iosBundleId.matches(testAppIdentifier.replaceAll('\\*', '(\\.\\*)'))){
                appIdentifier = iosBundleId
                isValidBundleID = true
            }

            if(profileInfo.isProfileExpired())
                throw new AppFactoryException("Provisioning profile is expired. Please check and upload the new provisioing profile for the app identifier : ${appIdentifier}.", "ERROR")
                
            appIdentifiers.add(appIdentifier) // this is for the wildcard profiles - fastlane needs this info
            profileEnvVars.add("sigh_${appIdentifier}_${iosDistributionType}_profile-path=${filePath}")
            profileEnvVars.add("sigh_${appIdentifier}_${iosDistributionType}_profile-name=" + profileInfo.getProfileName())
            profileEnvVars.add("sigh_${appIdentifier}_${iosDistributionType}_team-id=${teamID}")
            profileEnvVars.add("sigh_${appIdentifier}_${iosDistributionType}=${UUID}")

            // Preparing the commands to copy the profile files. If we run the copy command here, we are getting the java.io.NotSerializableException
            copyCMDs.add("mkdir -p ${profileHome}")
            copyCMDs.add("cp -f ${filePath} ${profileHome}/${UUID}.mobileprovision")
        }
        
        if(!isValidBundleID){
            script.echoCustom("There is no matching profile found for the given bundle identifier (Application Identifier). " + 
                            "Looks like mapping profiles are not available in the APPLE_SIGNING_CERTIFICATES uploaded files.", "ERROR")
        }
        copyCMDs.each{
            script.shellCustom(it, true)
        }
        profileEnvVars.add("APP_IDENTIFIERS=" + appIdentifiers.join(","))
        profileEnvVars
    }
    
    /**
     * Creates PLIST file.
     * @param ipaArtifactUrl IPA file S3 URL.
     * @return PLIST file object, format: {name: <NameOfPlistFile>, path: <PlistFilePath>}.
     */
    private final createPlist(String ipaArtifactUrl) {
        (ipaArtifactUrl) ?: script.echoCustom("ipaArtifactUrl argument can't be null!", 'ERROR')

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
                        .replaceAll('\\$project', projectName)

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
        channelBuildStats.put("aver", iosAppVersion)
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    ValidationHelper.checkBuildConfiguration(script)

                    def mandatoryParameters = ['IOS_DISTRIBUTION_TYPE', 'IOS_BUNDLE_VERSION', 'FORM_FACTOR']
                    def eitherOrParameters = [['APPLE_ID', 'APPLE_SIGNING_CERTIFICATES']]
                    if (channelOs && channelFormFactor) {
                        def appIdType = BuildHelper.getAppIdTypeBasedOnChannleAndFormFactor(channelOs, channelFormFactor)
                        mandatoryParameters.add(appIdType)
                    } else {
                        script.echoCustom("Something went wrong. Unable to figure out valid application id type", 'ERROR')
                    }

                    if (buildMode == libraryProperties.'buildmode.release.protected.type') {
                        mandatoryParameters.add('PROTECTED_KEYS')
                    }

                    if (ValidationHelper.isValidStringParam(script, 'IOS_APP_VERSION')) {
                        mandatoryParameters.add('IOS_APP_VERSION')
                    }

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters, eitherOrParameters)
                    if ((channelFormFactor?.equalsIgnoreCase('tablet')) && iosWatchExtension) {
                        script.echoCustom("Skipping Apple Watch extension build for iOS Tablet channel.", 'WARN')
                        /* Resetting Watch variables to false for fastlane to ignore watch extension signing */
                        iosWatchExtension = false
                        /* Reset Watch build environment variable as well, to reflect it in HeadlessBuild.properties */
                        script.env.APPLE_WATCH_EXTENSION = false
                    }
                }

                /* Allocate a slave for the run */
                script.node(libraryProperties.'ios.node.label') {
                    /* Get and expose configuration file for fastlane */
                    fetchFastlaneConfig()

                    pipelineWrapper {
                        /**
                         * Clean workspace, to be sure that we have not any items from previous build,
                         * and build environment completely new.
                         */
                        script.cleanWs deleteDirs: true

                        script.stage('Check build-node environment') {
                            /**
                             * FABRIC_ENV_NAME is optional parameter which should be validated only if
                             * if user entered some values
                             */
                            def parametersToValidate = ['VISUALIZER_HOME', channelVariableName, 'IOS_BUNDLE_ID', 'PROJECT_WORKSPACE']
                            if (script.env.FABRIC_ENV_NAME) {
                                parametersToValidate << 'FABRIC_ENV_NAME'
                            }
                            ValidationHelper.checkBuildConfiguration(script, parametersToValidate)
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

                        artifactMeta.add("version": ["App Version": script.env.APP_VERSION, "Build Version": iosBundleVersion])
                        /* Run PreBuild Hooks */
                        runPreBuildHook()

                        script.stage('Update Bundle ID') {
                            updateIosBundleId()
                        }

                        script.stage('KAR Build') {
                            /* Copy protected keys to project workspace if build mode is "release-protected" */
                            if (buildMode == libraryProperties.'buildmode.release.protected.type') {
                                script.echoCustom("Placing encryptions keys for protected mode build.")
                                copyProtectedKeysToProjectWorkspace()
                            }
                            build()
                            /* Search for build artifacts */
                            karArtifact = getArtifactLocations(artifactExtension).first() ?:
                                    script.echoCustom('Build artifacts were not found!', 'ERROR')
                            mustHaveArtifacts.add([name: karArtifact.name, path: karArtifact.path])

                            /* Publish iOS KAR artifact to S3 */
                            if(karArtifact) {
                                publishKar()
                                artifacts.add([
                                        channelPath: channelPath, name: karArtifact.name, authurl: authenticatedKARArtifactUrl, extension: 'KAR'
                                ])
                            }
                            channelBuildStats.put('binsize', getBinarySize(karArtifact.path,karArtifact.name))
                        }

                        script.stage('Generate IPA file') {
                            createIPA()
                            /* Get ipa file name and path */
                            def foundArtifacts = getArtifactLocations('ipa')
                            /* Rename artifacts for publishing */
                            ipaArtifact = renameArtifacts(foundArtifacts).first()
                            channelBuildStats.put('binsize', getBinarySize(ipaArtifact.path, ipaArtifact.name))
                        }

                        script.stage("Publish IPA artifact to S3") {
                            ipaArtifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                    sourceFileName: ipaArtifact.name, sourceFilePath: ipaArtifact.path, script

                        }

                        script.stage("Generate PLIST file") {
                            authenticatedIPAArtifactUrl = BuildHelper.createAuthUrl(ipaArtifactUrl, script, false);

                            /* Get plist artifact */
                            plistArtifact = createPlist(authenticatedIPAArtifactUrl)
                        }

                        script.stage("Publish PLIST artifact to S3") {
                            String artifactName = plistArtifact.name
                            String artifactPath = plistArtifact.path
                            String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                    sourceFileName: artifactName, sourceFilePath: artifactPath, script

                            String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true);
                            String plistArtifactOTAUrl = authenticatedArtifactUrl

                            if(ipaArtifact.name)
                                artifacts.add([
                                        channelPath: channelPath, name: ipaArtifact.name, authurl: authenticatedIPAArtifactUrl, extension: 'IPA'
                                ])
                            artifacts.add([
                                    channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: plistArtifactOTAUrl, extension: 'OTA'
                            ])
                        }

                        script.env['CHANNEL_ARTIFACTS'] = artifacts?.inspect()

                        /* Run PostBuild Hooks */
                        runPostBuildHook()
                    }
                }
            }
        }
    }
}
