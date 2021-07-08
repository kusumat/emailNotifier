package com.kony.appfactory.visualizer

import com.kony.appfactory.enums.PlatformType
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.CredentialsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.project.settings.dto.ProjectSettingsDTO
import com.kony.appfactory.project.settings.dto.visualizer.quality.Scans
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import hudson.AbortException
import com.kony.appfactory.enums.BuildType


/**
 * Implements logic for buildIrisApp job.
 *
 * buildVisualizer job is the main job that responsible for orchestration of channel builds and
 *  testing application binaries.
 *
 * Logic here validates user provided parameters, prepares build parameters for channels and test automation job,
 *  triggers channel jobs and/or test automation job with prepared parameters, stores e-mail notification body on S3
 *  for App Factory console.
 */
class Facade implements Serializable {
    /* Pipeline object */
    private script
    /* Library configuration */
    private libraryProperties
    /* List of steps for parallel run */
    private runList = [:]
    /* List of Pre Build Hooks */
    private preBuildHookList = [:]
    /* List of scans to run in parallel */
    private scansList = [:]
    private scanResultsMap = [:]

    /* List of channels to build */
    private channelsToRun
    /*
        List of channel artifacts in format:
            [channelPath: <relative path to the artifact on S3>, name: <artifact file name>, url: <S3 artifact URL>]
     */
    private artifacts = []
    private mustHaveArtifacts = []
    private artifactsMeta = [:]
    private scmMeta = [:]
    /* List of job statuses (job results), used for setting up final result of the buildVisualizer job */
    private jobResultList = []

    /* Common Project Settings Parameters*/
    private final projectSourceCodeRepositoryCredentialsId
    private final recipientsList
    private final defaultLocale

    /* Common build parameters */
    private final projectName = script.env.PROJECT_NAME
    private final projectSourceCodeBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    protected final fabricCredentialsParamName = BuildHelper.getCurrentParamName(script, 'CLOUD_CREDENTIALS_ID', 'FOUNDRY_CREDENTIALS_ID')
    private fabricCredentialsID = script.params[fabricCredentialsParamName]
    private final buildMode = script.params.BUILD_MODE
    private final fabricAppConfig = script.params.FOUNDRY_APP_CONFIG
    private final publishToFabricParamName = BuildHelper.getCurrentParamName(script, 'PUBLISH_FABRIC_APP', 'PUBLISH_WEB_APP')
    private final publishWebApp = script.params[publishToFabricParamName]
    private final universalAndroid = script.params.ANDROID_UNIVERSAL_NATIVE
    private final universalIos = script.params.IOS_UNIVERSAL_NATIVE
    private fabricEnvironmentName
    /* Temporary Base Job name for Cloud Build.
     * Since console is only looking for 'buildIrisApp' in S3 path
     * to fetch the results,for now, hardcoding this value.
     * Later, a change has to be made on console side to fetch results using 'cloudBuildVisualizerApp' also.
     * */
    protected jobName = 'buildIrisApp'
    /* iOS build parameters */
    private appleID = script.params.APPLE_ID
    private final appleCertID = script.params.APPLE_SIGNING_CERTIFICATES
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosUniversalAppId = script.params.IOS_UNIVERSAL_APP_ID
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosAppVersion = script.params.IOS_APP_VERSION
    private final iosBundleVersion = script.params.IOS_BUNDLE_VERSION
    private final iosWatchApp = script.params.APPLE_WATCH_EXTENSION

    /* Android build parameters */
    private final androidUniversalAppId = script.params.ANDROID_UNIVERSAL_APP_ID
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidAppVersion = script.params.ANDROID_APP_VERSION
    private final androidVersionCode = script.params.ANDROID_VERSION_CODE
    private final keystoreFileID = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordID = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private final keystoreAlias = script.params.ANDROID_KEY_ALIAS
    private final androidAppBundle = script.params.ANDROID_APP_BUNDLE
    private final supportX86Devices = script.params.SUPPORT_x86_DEVICES

    /* WEB build parameters */
    private
    final webAppVersion = script.params.WEB_APP_VERSION ? script.params.WEB_APP_VERSION : script.params.SPA_APP_VERSION ? script.params.SPA_APP_VERSION : null
    private
    final webVersionParameterName = BuildHelper.getCurrentParamName(script, 'WEB_APP_VERSION', 'SPA_APP_VERSION')
    private final desktopWebChannel = script.params.DESKTOP_WEB
    private final compatibilityMode = script.params.FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE
    private final webProtectionPreset = script.params.PROTECTION_LEVEL
    private final webProtectionExcludeListFile = script.params.EXCLUDE_LIST_PATH
    private final webProtectionBlueprintFile = script.params.CUSTOM_PROTECTION_PATH
    private final webProtectionID = script.params.OBFUSCATION_PROPERTIES


    /* TestAutomation build parameters */
    private final testFramework = BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG')
    private final availableTestPools = script.params.AVAILABLE_TEST_POOLS
    private final availableBrowsers = script.params.AVAILABLE_BROWSERS
    private final desktopWebTestsArguments = script.params.RUN_DESKTOPWEB_TESTS_ARGUMENTS
    private final runDesktopwebTests = script.params.RUN_DESKTOPWEB_TESTS
    
    private final screenResolution = BuildHelper.getParamValueOrDefault(script, 'SCREEN_RESOLUTION', '1024x768')

    /* CustomHooks build Parameters*/
    private final runCustomHook = script.params.RUN_CUSTOM_HOOKS

    /* Protected mode build parameters */
    private final protectedKeys = script.params.PROTECTED_KEYS
    private final buildNumber = script.env.BUILD_NUMBER

    /* AWS Custom Test Environment parameters */
    private runInCustomTestEnvironment = (script.params.containsKey("TEST_ENVIRONMENT")) ? ((script.params.TEST_ENVIRONMENT == 'Custom') ? true : false ) : script.params.RUN_IN_CUSTOM_TEST_ENVIRONMENT
    private appiumVersion = script.params.APPIUM_VERSION
    private testngFiles = script.params.TESTNG_FILES
    private jasmineWebTestPlan = BuildHelper.getParamValueOrDefault(script, "WEB_TEST_PLAN", null)
    private jasmineNativeTestPlan = BuildHelper.getParamValueOrDefault(script, "NATIVE_TEST_PLAN", null)
    /* Cloud Build properties */
    protected CredentialsHelper credentialsHelper
    protected BuildStatus status

    /* Jasmine Test - To be used in the validations for the build mode and test */
    private boolean isJasmineEnabled = BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG')?.trim()?.equalsIgnoreCase("jasmine")
    /* Build Stats */
    def buildStats = [:]
    def runListStats = [:]

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    Facade(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        /* Set the visualizer project settings values to the corresponding visualizer environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Iris')

        projectSourceCodeRepositoryCredentialsId = script.env.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
        recipientsList = script.env.RECIPIENTS_LIST
        defaultLocale = script.env.DEFAULT_LOCALE

        /* Checking if at least one channel been selected. */
        channelsToRun = (BuildHelper.getSelectedChannels(this.script.params)) ?: []
        this.script.env['CLOUD_ACCOUNT_ID'] = (script.params.MF_ACCOUNT_ID) ?: (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (script.params.MF_ENVIRONMENT_GUID) ?: (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
        credentialsHelper = new CredentialsHelper()
        status = new BuildStatus(script, channelsToRun)
        /* Set AWS Test Environment based on framework selection */
        runInCustomTestEnvironment = isJasmineEnabled || runInCustomTestEnvironment
    }

    /**
     * Filters SPA channels.
     *
     * @param channelsToRun list of selected channels.
     * @return list of SPA selected channels.
     */
    private final getSpaChannels(channelsToRun) {
        channelsToRun.findAll { it.contains('SPA') }
    }

    /**
     * Filters Native channels.
     *
     * @param channelsToRun channelsToRun list of selected channels.
     * @return list of Native selected channels.
     */
    private final getNativeChannels(channelsToRun) {
        channelsToRun.findAll { it.contains('NATIVE') }
    }

    /**
     * Converts selected SPA channels to build parameters for SPA job.
     *
     * @param channels list of selected SPA channels.
     * @return
     */
    private final convertSpaChannelsToBuildParameters(channels) {
        channels.collect { script.booleanParam(name: it, value: true) }
    }

    /**
     * Returns channel form factor.
     *
     * @param channelName channel build parameter name.
     * @return channel form factor.
     */
    private final getChannelFormFactor(channelName) {
        channelName.tokenize('_')[1].toLowerCase().capitalize()
    }

    /**
     * Returns channel operating system.
     *
     * @param channelName channel build parameter name.
     * @return channel operating system.
     */
    private final getChannelOs(channelName) {
        channelName.tokenize('_')[0].toLowerCase().capitalize()
    }

    /**
     * Returns job name for specific channel.
     * Because of naming conventions the channel job name has following format:
     *  build<ChannelName(Capitalized OS name)>.
     *
     * @param channelName channel build parameter name.
     * @return channel job name.
     */
    private final getChannelJobName(channelName) {
        String channelsBaseFolder = 'Channels'
        String channelType = ''

        switch (channelName) {
            case ~/^.*ANDROID.*$/:
                channelType = 'Android'
                break
            case ~/^.*IOS.*$/:
                channelType = 'Ios'
                break
            case ~/^.*SPA.*$/:
                channelType = 'Spa'
                break
            case ~/^.*WINDOWS.*$/:
                channelType = 'Windows'
                break
            case 'DESKTOP_WEB':
                channelType = 'DesktopWeb'
                break
            case 'WEB':
                channelType = 'Web'
                break
            default:
                break
        }

        channelsBaseFolder + '/' + 'build' + (channelType) ?: script.echoCustom('Unknown channel type!', 'ERROR')
    }

    /**
     * Returns relative channel path on S3.
     *
     * @param channel channel build parameter name.
     * @return relative channel path on S3.
     */
    private final getChannelPath(channel) {
        def channelPath = channel.tokenize('_').collect() { item ->
            /* Workaround for SPA jobs */
            if (item.contains('SPA')) {
                item
            } else if (item.contains('IOS')) {
                'iOS'
            } else {
                item.toLowerCase().capitalize()
            }
        }.join('/')

        channelPath
    }

    /**
     * This method returns if the respective scan is enabled or not from the Project Settings.
     * @param scanType is the type of scan that is needed.
     * @return true if the scan is enabled, false if the scan is not enabled
     */
    private final getScanDetails(scanType) {
        boolean isScanEnabled = false
        boolean isBuildNeeded = true
        ProjectSettingsDTO projectSettings = BuildHelper.getAppFactoryProjectSettings(projectName)
        if(projectSettings) {
            Scans scans = projectSettings.getVisualizerSettings()?.getScans()
            if (scans) {
                switch (scanType) {
                    case 'SonarQube':
                        isScanEnabled = scans.getSonar() ? scans.getSonar().getRunSonar() : false
                        isBuildNeeded = scans.getSonar() ? scans.getSonar().getFailBuildOnQualityGateStatus() : true
                        break
                    default:
                        break
                }
            }
        }
        ["isScanEnabled" : isScanEnabled, "isBuildNeeded" : isBuildNeeded]
    }

    /**
     * Return group of common build parameters.
     *
     * @return group of common build parameters.
     */
    private final getScanJobParameters(scanType) {
        def scanJobParameters = []
        switch(scanType) {
            case 'SonarQube' :
                scanJobParameters = [
                    script.string(name: 'SCM_BRANCH', value: "${projectSourceCodeBranch}"),
                    script.credentials(name: 'SCM_CREDENTIALS', value: "${projectSourceCodeRepositoryCredentialsId}")
                ]
                break
            default :
                break
        }

        scanJobParameters
    }

    /**
     * Return group of common build parameters.
     *
     * @return group of common build parameters.
     */
    private final getCommonJobBuildParameters() {
        [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH', value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'BUILD_MODE', value: "${buildMode}"),
                script.credentials(name: fabricCredentialsParamName, value: "${fabricCredentialsID}"),
                script.credentials(name: 'FOUNDRY_APP_CONFIG', value: "${fabricAppConfig}"),
                script.booleanParam(name: publishToFabricParamName, value: publishWebApp),
                script.stringParam(name: "TEST_FRAMEWORK", value: testFramework),
                script.string(name: 'DEFAULT_LOCALE', value: "${defaultLocale}"),
                script.string(name: 'RECIPIENTS_LIST', value: "${recipientsList}"),
                script.booleanParam(name: 'RUN_CUSTOM_HOOKS', value: runCustomHook)
        ]
    }

    /**
     * Return group of common build parameters for cloudbuild.
     *
     * @return group of common build parameters.
     */
    private final getCommonJobCloudBuildParameters() {
        [
                script.booleanParam(name: 'IS_SOURCE_VISUALIZER', value: script.params.IS_SOURCE_VISUALIZER),
                script.string(name: 'MF_ACCOUNT_ID', value: "${script.params.MF_ACCOUNT_ID}"),
                script.string(name: 'MF_ENVIRONMENT_GUID', value: "${script.params.MF_ENVIRONMENT_GUID}"),
                script.string(name: 'PROJECT_SOURCE_URL', value: "${script.params.PROJECT_SOURCE_URL}"),
                script.string(name: 'BUILD_STATUS_PATH', value: "${script.params.BUILD_STATUS_PATH}"),
                script.string(name: 'MF_TOKEN', value: "${script.params.MF_TOKEN}"),
                script.string(name: 'PROJECT_NAME', value: "${script.params.PROJECT_NAME}"),
                script.string(name: 'FABRIC_ENV_NAME', value: "${script.params.FABRIC_ENV_NAME}"),
                script.string(name: 'FABRIC_APP_NAME', value: "${script.params.FABRIC_APP_NAME}"),
                script.string(name: 'FABRIC_APP_VERSION', value: "${script.params.FABRIC_APP_VERSION}"),
                script.string(name: 'FABRIC_ACCOUNT_ID', value: "${script.params.FABRIC_ACCOUNT_ID}"),
                script.string(name: 'IS_KONYQUANTUM_APP_BUILD', value:"${script.params.IS_KONYQUANTUM_APP_BUILD}")
        ]
    }

    /**
     * Return specific to WEB channel build parameters.
     * @param spaChannelsToBuildJobParameters list of SPA channels to build.
     * @return WEB specific build parameters.
     */
    private final getWebChannelJobBuildParameters(spaChannelsToBuildJobParameters = null) {
        def commonWebParameters = getCommonJobBuildParameters() +
        [script.string(name: "${webVersionParameterName}", value: "${webAppVersion}")] +
        [script.booleanParam(name: "FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE", value: compatibilityMode)]
        if (script.params.containsKey("OBFUSCATION_PROPERTIES")) {
            commonWebParameters += [script.string(name: "PROTECTION_LEVEL", value: "${webProtectionPreset}")] +
                    [script.string(name: "EXCLUDE_LIST_PATH", value: "${webProtectionExcludeListFile}")] +
                    [script.string(name: "CUSTOM_PROTECTION_PATH", value: "${webProtectionBlueprintFile}")] +
                    [script.credentials(name: 'OBFUSCATION_PROPERTIES', value: "${webProtectionID}")] +
                    [script.credentials(name: 'PROTECTED_KEYS', value: "${protectedKeys}")]
        }
        if (spaChannelsToBuildJobParameters && desktopWebChannel) {
            commonWebParameters +
                [script.booleanParam(name: "DESKTOP_WEB", value: desktopWebChannel)] +
                spaChannelsToBuildJobParameters
        } else if (spaChannelsToBuildJobParameters) {
            commonWebParameters +  spaChannelsToBuildJobParameters
        }
        else
            commonWebParameters
    }

    /**
     * Return specific to Native channels build parameters.
     *
     * @param channelName channel build parameter name.
     * @param channelOs channel OS type.
     * @param channelFormFactor channel form factor.
     * @return channel specific build parameters.
     */
    private final getNativeChannelJobBuildParameters(channelName, channelOs = '', channelFormFactor) {
        def channelJobParameters = []
        def commonParameters = getCommonJobBuildParameters()
        switch (channelName) {
            case ~/^.*ANDROID.*$/:
                channelJobParameters = commonParameters + [
                        script.string(name: 'ANDROID_MOBILE_APP_ID', value: "${androidMobileAppId}"),
                        script.string(name: 'ANDROID_TABLET_APP_ID', value: "${androidTabletAppId}"),
                        script.string(name: 'ANDROID_APP_VERSION', value: "${androidAppVersion}"),
                        script.string(name: 'ANDROID_VERSION_CODE', value: "${androidVersionCode}"),
                        script.credentials(name: 'ANDROID_KEYSTORE_FILE', value: "${keystoreFileID}"),
                        script.credentials(name: 'ANDROID_KEYSTORE_PASSWORD', value: "${keystorePasswordID}"),
                        script.credentials(name: 'ANDROID_KEY_PASSWORD', value: "${privateKeyPassword}"),
                        script.string(name: 'ANDROID_KEY_ALIAS', value: "${keystoreAlias}"),
                        script.string(name: 'ANDROID_UNIVERSAL_APP_ID', value: "${androidUniversalAppId}"),
                        script.credentials(name: 'PROTECTED_KEYS', value: "${protectedKeys}"),
                        script.booleanParam(name: 'ANDROID_APP_BUNDLE', value: "${androidAppBundle}"),
                        script.booleanParam(name: 'SUPPORT_x86_DEVICES', value: "${supportX86Devices}")

                ]
                break
            case ~/^.*IOS.*$/:
                channelJobParameters = commonParameters + [
                        script.credentials(name: 'APPLE_ID', value: "${appleID}"),
                        script.credentials(name: 'APPLE_SIGNING_CERTIFICATES', value: "${appleCertID}"),
                        script.string(name: 'APPLE_DEVELOPER_TEAM_ID', value: "${appleDeveloperTeamId}"),
                        script.string(name: 'IOS_DISTRIBUTION_TYPE', value: "${iosDistributionType}"),
                        script.string(name: 'IOS_MOBILE_APP_ID', value: "${iosMobileAppId}"),
                        script.string(name: 'IOS_TABLET_APP_ID', value: "${iosTabletAppId}"),
                        script.string(name: 'IOS_APP_VERSION', value: "${iosAppVersion}"),
                        script.string(name: 'IOS_BUNDLE_VERSION', value: "${iosBundleVersion}"),
                        script.string(name: 'IOS_UNIVERSAL_APP_ID', value: "${iosUniversalAppId}"),
                        script.booleanParam(name: 'APPLE_WATCH_EXTENSION', value: iosWatchApp),
                        script.credentials(name: 'PROTECTED_KEYS', value: "${protectedKeys}")
                ]
                break
            case ~/^.*WINDOWS.*$/:
                channelJobParameters = commonParameters + [
                        script.string(name: 'OS', value: channelOs)
                ]
                break
            default:
                break
        }

        channelJobParameters + [
                script.string(name: 'FORM_FACTOR', value: channelFormFactor)
        ]
    }

    /**
     * Return specific to Test Automation job build parameters.
     *
     * @return Test Automation job build parameters.
     */
    private final getTestAutomationJobParameters() {
        def automationParams = [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH',
                        value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                        value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'NATIVE_TESTS_URL', value: ''),
                script.string(name: 'DESKTOPWEB_TESTS_URL', value: ''),
                script.string(name: 'AVAILABLE_TEST_POOLS', value: "${availableTestPools}"),
                script.booleanParam(name: 'RUN_DESKTOPWEB_TESTS', value: "${runDesktopwebTests}"),
                script.string(name: 'AVAILABLE_BROWSERS', value: "${availableBrowsers}"),
                script.string(name: 'RUN_DESKTOPWEB_TESTS_ARGUMENTS', value: "${desktopWebTestsArguments}"),
                script.string(name: 'RECIPIENTS_LIST', value: "${recipientsList}"),
                script.booleanParam(name: 'RUN_CUSTOM_HOOKS', value: runCustomHook),
                script.string(name: 'TEST_FRAMEWORK', value: "${testFramework}"),
                script.string(name: 'WEB_TEST_PLAN', value: "${jasmineWebTestPlan}"),
                script.string(name: 'NATIVE_TEST_PLAN', value: "${jasmineNativeTestPlan}"),
                script.string(name: 'SCREEN_RESOLUTION', value: "${screenResolution}")
            ]
        if(script.params.containsKey("RUN_NATIVE_TESTS"))
            automationParams.add(script.booleanParam(name: 'RUN_NATIVE_TESTS', value: script.params.RUN_NATIVE_TESTS))

        automationParams
    }

    /**
     * Deserializes channel artifact object.
     *
     * @param channelPath relative path to the artifact on S3, generated from channel build parameter.
     * @param artifacts serialized list of channel artifacts.
     * @return list of channel artifacts.
     */
    private final getArtifactObjects(channelPath, artifacts) {
        (artifacts) ? Eval.me(artifacts) : [[name: '', url: '', path: '', channelPath: channelPath]]
    }

    private final getArtifactMetaObjects(artifactsMeta) {
        (artifactsMeta) ? Eval.me(artifactsMeta) : [version: []]
    }

    private final getScmMetaObjects(scmMeta) {
        (scmMeta) ? Eval.me(scmMeta) : [commitID: '', scmUrl: '', commitLogs: []]
    }

    /**
     * Generates Test Automation job application binaries build parameters.
     * If AVAILABLE_TEST_POOLS build parameter been provided, we need generate values with URLs to application binaries
     *  on S3 from artifact object and pass them to Test Automation job.
     *
     * @param buildJobArtifacts list of channel artifacts.
     * @return Test Automation binaries build parameters.
     */
    private final getTestAutomationJobBinaryParameters(buildJobArtifacts) {
        def binaryParams = buildJobArtifacts.findResults { artifact ->
            /* Filter Android and iOS channels */
            String artifactName = (artifact.name && artifact.name.matches("^.*.?(plist|ipa|apk|war|zip)\$")) ? artifact.name : ''

            /* In case of multiple Android artifacts passing only the ARM-64 binary url to the runTests Job */
            if((supportX86Devices) && artifact.channelPath.toUpperCase().contains("ANDROID")) {
                if(!artifactName.contains("ARM-64"))
                    return null
            }

            /*
                Workaround to get ipa URL for iOS, just switching extension in URL to ipa,
                because ipa file should be places nearby plist file on S3.
             */
            String artifactUrl = artifact.url ? (!artifact.url.contains('.plist') ? artifact.url :
                    artifact.url.replaceAll('.plist', '.ipa')) : ''
            String channelName = artifact.channelPath.toUpperCase().replaceAll('/', '_')

            /* Create build parameter for Test Automation job */
            if(artifactName) {
                if((artifact.name.matches("^.*.?(war|zip)\$"))) {
                    if(publishWebApp)
                        [script.stringParam(name: "FABRIC_APP_URL", value: artifact.webAppUrl), script.string(name: 'JASMINE_TEST_URL', value: artifact.jasmineTestsUrl)]
                } else
                    if(availableTestPools)
                        return script.stringParam(name: "${channelName}_BINARY_URL", value: artifactUrl)
            } else
                return null
        }

        binaryParams.flatten()
    }

    /**
     * Prepares the scans that need to be run for the given project
     */
    private final void prepareScans() {
        /* List of Scans */
        def totalScansList = ['SonarQube']
        for (scan in totalScansList) {
            def scanJobName = ["", projectName, 'Quality', scan].join('/')
            def scanDtls = getScanDetails(scan)
            if(! scanDtls.isScanEnabled) {
                continue;
            }
            def scanJobParameters = getScanJobParameters(scan)
            scansList[scan] = {
                script.stage(scan) {
                    /* Trigger channel job */
                    def scanJob = script.build job: scanJobName, parameters: scanJobParameters,
                            propagate: false

                    if(scanDtls.isBuildNeeded) {
                        scanResultsMap.put(scan, scanJob.currentResult)
                    }

                    if (scanJob.currentResult != 'SUCCESS') {
                        script.echoCustom("Status of the ${scan} scan " +
                                "is: ${scanJob.currentResult}", 'WARN')
                    }
                }
            }
        }
    }
    /**
     * Prepares run steps for triggering channel jobs in parallel.
     */
    private final void prepareRun() {
        /* Filter Native channels */
        def nativeChannelsToRun = getNativeChannels(channelsToRun)
        /* Filter SPA channels */
        def spaChannelsToRun = getSpaChannels(channelsToRun)
        for (item in nativeChannelsToRun) {
            def channelName = item
            def channelJobName = (getChannelJobName(channelName)) ?:
                    script.echoCustom("Channel job name can't be null", 'ERROR')
            def channelOs = getChannelOs(channelName)
            def channelFormFactor = (getChannelFormFactor(channelName)) ?:
                    script.echoCustom("Channel form factor can't be null", 'ERROR')
            def channelJobBuildParameters = (
                    getNativeChannelJobBuildParameters(channelName, channelOs, channelFormFactor)
            ) ?: script.echoCustom("Channel job build parameters list can't be null", 'ERROR')
            def channelPath = getChannelPath(channelName)

            runList[channelName] = {
                script.stage(channelName) {
                    /* Trigger channel job */
                    def channelJob = script.build job: channelJobName, parameters: channelJobBuildParameters,
                            propagate: false
                    /* Collect job results */
                    jobResultList.add(channelJob.currentResult)

                    /* collect job run id to build stats */
                    runListStats.put(channelJob.fullProjectName + "/" + channelJob.number, channelJob.fullProjectName)
                    /* Collect job artifacts */
                    artifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS))
                    artifactsMeta.put(channelPath, getArtifactMetaObjects(channelJob.buildVariables.CHANNEL_ARTIFACT_META))

                    /* Collect source code details results */
                    scmMeta.put(channelPath, getScmMetaObjects(channelJob.buildVariables.CHANNEL_SCM_META))

                    /* Collect must have artifacts */
                    mustHaveArtifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.MUSTHAVE_ARTIFACTS))

                    /* Notify user that one of the channels failed */
                    if (channelJob.currentResult != 'SUCCESS') {
                        script.echoCustom("Status of the channel ${channelName} " +
                                "build is: ${channelJob.currentResult}", 'WARN')
                    }
                }
            }
        }

        if (spaChannelsToRun || desktopWebChannel) {
            runWebChannels(spaChannelsToRun, desktopWebChannel)
        }

    }

    /**
     * Prepares run step for Cloud Build Service
     */
    private final void prepareCloudBuildRun() {

        /* Setting blank credential id so that CloudBuild service won't fail to find Cloud credentials.
         Note: This account is not relevant for Iris CI build to run, just to ensure single-tenant backward flow
         works. Setting one blank account for this build scope. Post the build, it will get this removed.*/
        fabricCredentialsID = "CloudID-" + buildNumber
        credentialsHelper.addUsernamePassword(fabricCredentialsID, "Cloud Creds", "dummyuser", "dummypasswd")

        // Let's check whether third party authentication is enabled or not
        BuildHelper.getExternalAuthInfoForCloudBuild(script, script.kony['MF_API_VERSION'], script.env['CLOUD_ENVIRONMENT_GUID'])

        /* Collect iOS channel parameters to check */
        def iosChannels = channelsToRun?.findAll { it.matches('^IOS_.*_NATIVE$') }

        if (iosChannels) {
            String appleUsername = script.params.APPLE_USERNAME ?: ""
            String applePassword = script.params.APPLE_PASSWORD ?: ""

            if (appleUsername as Boolean ^ applePassword as Boolean) {
                script.echoCustom('Please specify both APPLE_USERNAME and APPLE_PASSWORD.', 'ERROR')
            }

            if (appleUsername && applePassword) {
                appleID = PlatformType.IOS.toString() + buildNumber
                credentialsHelper.addUsernamePassword(appleID, "Apple Credentials", appleUsername, applePassword)
            }
        }

        /* Filter Native channels */
        def channelJobName = 'Channels/buildAll'
        def channelJobBuildParameters = getCommonJobCloudBuildParameters()
        def nativeChannelsToRun = getNativeChannels(channelsToRun)

        for (channelName in nativeChannelsToRun) {
            def channelOs = getChannelOs(channelName)
            def channelFormFactor = (getChannelFormFactor(channelName)) ?:
                    script.echoCustom("Channel form factor can't be null", 'ERROR')
            channelJobBuildParameters = channelJobBuildParameters + [script.booleanParam(name: channelName, value: true)]
            channelJobBuildParameters = channelJobBuildParameters + (
                    getNativeChannelJobBuildParameters(channelName, channelOs, channelFormFactor)
            ) ?: script.echoCustom("Channel job build parameters list can't be null", 'ERROR')
        }

        channelJobBuildParameters = channelJobBuildParameters.unique()

        runList["cloudbuild"] = {
            script.stage("cloudbuild") {
                /* Trigger channel job */
                def channelJob = script.build job: channelJobName, parameters: channelJobBuildParameters,
                        propagate: false
                /* Collect job results */
                jobResultList.add(channelJob.currentResult)
                /* collect job run id to build stats */
                runListStats.put(channelJob.fullProjectName + "/" + channelJob.number, channelJob.fullProjectName)
                /* Collect job artifacts */
                artifacts.addAll(getArtifactObjects("CloudBuild", channelJob.buildVariables.CHANNEL_ARTIFACTS))
                artifactsMeta = getArtifactMetaObjects(channelJob.buildVariables?.CHANNEL_ARTIFACT_META)

                /* Collect must have artifacts */
                mustHaveArtifacts.addAll(getArtifactObjects("CloudBuild", channelJob.buildVariables.MUSTHAVE_ARTIFACTS))
            }
        }

    }

    /**
     * Prepares runList for WebChannels
     * @params spaChannelsToRun , desktopWebChannel web channels to run.
     */
    private final void runWebChannels(spaChannelsToRun = null, desktopWebChannel = null) {
        def channelName = (spaChannelsToRun && desktopWebChannel) ? 'WEB' : spaChannelsToRun ? 'SPA' : desktopWebChannel ? 'DESKTOP_WEB' : null
        def channelJobBuildParameters, channelPath

        def channelJobName = (getChannelJobName(channelName)) ?:
                script.echoCustom("Channel job name can't be null", 'ERROR')

        if (spaChannelsToRun) {
            /* Convert selected SPA channels to build parameters for SPA job */
            def spaChannelsToBuildJobParameters = convertSpaChannelsToBuildParameters(spaChannelsToRun)
            channelJobBuildParameters = (getWebChannelJobBuildParameters(spaChannelsToBuildJobParameters)) ?:
                    script.echoCustom("Channel job build parameters list can't be null", 'ERROR')
        } else if (desktopWebChannel) {
            channelJobBuildParameters = (getWebChannelJobBuildParameters()) ?:
                    script.echoCustom("Channel job build parameters list can't be null", 'ERROR')
        }

        // For Web related channels, channelPaths and channelNames slightly different.
        if (desktopWebChannel)
            channelPath = "DESKTOPWEB"
        else
            channelPath = getChannelPath(channelName)
        runList[channelName] = {
            script.stage(channelName) {
                /* Trigger channel job */
                def channelJob = script.build job: channelJobName, parameters: channelJobBuildParameters,
                        propagate: false
                /* Collect job result */
                jobResultList.add(channelJob.currentResult)

                /* collect job run id to build stats */
                runListStats.put(channelJob.fullProjectName + "/" +channelJob.number, channelJob.fullProjectName)

                /* Collect job artifacts */
                artifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS))
                artifactsMeta.put(channelPath, getArtifactMetaObjects(channelJob.buildVariables.CHANNEL_ARTIFACT_META))

                /* Collect source code details results */
                scmMeta.put(channelPath,getScmMetaObjects(channelJob.buildVariables.CHANNEL_SCM_META))

                /* Collect must have artifacts */
                mustHaveArtifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.MUSTHAVE_ARTIFACTS))

                /* Notify user that Web channel build failed */
                if (channelJob.currentResult != 'SUCCESS') {
                    script.echoCustom("Status of the channel ${channelName} " +
                            "build is: ${channelJob.currentResult}", 'WARN')
                }
            }
        }
    }

    /**
     * This method will check if the channel builds are success and returns true if any channel build is successfull.
     * @return boolean flag which decides whether to run the tests or not.
     */
    private final boolean shallWeRunTests() {
        boolean testFlags = availableTestPools || (runDesktopwebTests && publishWebApp)
        return testFlags && jobResultList.contains('SUCCESS')
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        /* Allocate a slave for the run */
        script.node(libraryProperties.'facade.node.label') {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                try {
                    /* Wrapper for injecting timestamp to the build console output */
                    script.timestamps {
                        script.stage('Check provided parameters') {
                            status.prepareBuildServiceEnvironment(channelsToRun)
                            status.prepareStatusJson(true)

                            channelsToRun ?: script.echoCustom('Please select at least one channel to build!', 'ERROR');

                            /* Check common params */
                            ValidationHelper.checkBuildConfiguration(script)

                            /* Check params for universal application build */
                            if (universalAndroid || universalIos) {
                                ValidationHelper.checkBuildConfigurationForUniversalApp(script)
                            }

                            /* Check the required param for run DesktopWeb test */
                            if(runDesktopwebTests) {
                                ValidationHelper.checkBuildConfigurationForDesktopWebTest(script, libraryProperties)
                            }

                            /* List of required parameters */
                            def checkParams = [], eitherOrParameters = []
                            def tempBuildMode = (buildMode == 'release-protected [native-only]') ? 'release-protected' : script.params.BUILD_MODE
                            /* Collect Android channel parameters to check */
                            def androidChannels = channelsToRun?.findAll { it.matches('^ANDROID_.*_NATIVE$') }

                            if (androidChannels) {
                                def androidMandatoryParams = ['ANDROID_APP_VERSION', 'ANDROID_VERSION_CODE']

                                if (androidChannels.findAll { it.contains('MOBILE') }) {
                                    androidMandatoryParams.add('ANDROID_MOBILE_APP_ID')
                                }

                                if (androidChannels.findAll { it.contains('TABLET') }) {
                                    androidMandatoryParams.add('ANDROID_TABLET_APP_ID')
                                }

                                if (androidChannels.findAll { it.contains('UNIVERSAL') }) {
                                    androidMandatoryParams.add('ANDROID_UNIVERSAL_APP_ID')
                                }

                                if (tempBuildMode == libraryProperties.'buildmode.release.protected.type') {
                                    androidMandatoryParams.add('PROTECTED_KEYS')
                                }

                                checkParams.addAll(androidMandatoryParams)
                            }

                            /* Collect iOS channel parameters to check */
                            def iosChannels = channelsToRun?.findAll { it.matches('^IOS_.*_NATIVE$') }

                            if (iosChannels) {

                                def iosMandatoryParams = ['IOS_DISTRIBUTION_TYPE', 'IOS_BUNDLE_VERSION']
                                eitherOrParameters.add(['APPLE_ID', 'APPLE_SIGNING_CERTIFICATES'])

                                if (iosChannels.findAll { it.contains('MOBILE') }) {
                                    iosMandatoryParams.add('IOS_MOBILE_APP_ID')
                                }

                                if (iosChannels.findAll { it.contains('TABLET') }) {
                                    iosMandatoryParams.add('IOS_TABLET_APP_ID')
                                }

                                if (iosChannels.findAll { it.contains('UNIVERSAL') }) {
                                    iosMandatoryParams.add('IOS_UNIVERSAL_APP_ID')
                                }

                                if (tempBuildMode == libraryProperties.'buildmode.release.protected.type') {
                                    iosMandatoryParams.add('PROTECTED_KEYS')
                                }

                                if (ValidationHelper.isValidStringParam(script, 'IOS_APP_VERSION')) {
                                    iosMandatoryParams.add('IOS_APP_VERSION')
                                }
                                checkParams.addAll(iosMandatoryParams)
                            }
                            /* Collect SPA channel parameters to check */
                            def spaChannels = channelsToRun?.findAll { it.matches('^.*_.*_SPA$') }

                            if (spaChannels || desktopWebChannel) {
                                def webMandatoryParams = ["${webVersionParameterName}", 'FOUNDRY_APP_CONFIG']
                                // Below Validations only apply to AppFactory Projects >= 9.2.0
                                if (tempBuildMode == libraryProperties.'buildmode.release.protected.type' && script.params.containsKey('OBFUSCATION_PROPERTIES')) {
                                        webMandatoryParams.addAll(['OBFUSCATION_PROPERTIES', 'PROTECTION_LEVEL', 'PROTECTED_KEYS'])
                                        if (webProtectionPreset == 'CUSTOM')
                                            webMandatoryParams.add('CUSTOM_PROTECTION_PATH')
                                }
                                checkParams.addAll(webMandatoryParams)
                            }

                            /* Check the valid values for Test Framework */
                            def expectedValuesForTestFramework = ['TestNG', 'Jasmine']
                            ValidationHelper.checkValidValueForParam(script, 'TEST_FRAMEWORK', expectedValuesForTestFramework)

                            /* Add Appium Version to Mandatory Params for Native Tests and Custom Test Environment only */
                            if (availableTestPools && runInCustomTestEnvironment){
                                checkParams.add('APPIUM_VERSION')
                            }

                            /* 'test' BUILD_MODE type is only applicable for Jasmine TEST_FRAMEWORK in CI build. So let's validate this. */
                            if(isJasmineEnabled && script.params.BUILD_MODE != libraryProperties.'buildmode.test.type') {
                                throw new AppFactoryException("Jasmine tests can only be executed when the app is built in Test mode!", 'ERROR')
                            }

                            /* If user trying to run different framework (other than Jasmine) with 'test' BUILD_MODE selection, CI forcibly run with debug mode, so let's notify the user on same. */
                            if(!isJasmineEnabled && script.params.BUILD_MODE == libraryProperties.'buildmode.test.type') {
                                script.echoCustom('Building application binaries in Debug mode, as the Test mode is not applicable for the selected test framework.', 'WARN')
                            }

                            /* Check all required parameters depending on user input */
                            /* For CloudBuild, scan the checkParams list and clean unwanted params */
                            if (script.params.IS_SOURCE_VISUALIZER) {
                                def cloudBuildNotExistingParams = [
                                        'FOUNDRY_APP_CONFIG', publishToFabricParamName, 'RECIPIENTS_LIST',
                                        'RUN_CUSTOM_HOOKS', 'FORM_FACTOR', 'PROTECTED_KEYS', 'APPLE_ID'
                                ]
                                checkParams.removeAll(cloudBuildNotExistingParams)
                                ValidationHelper.checkBuildConfiguration(script, checkParams)
                            } else {
                                ValidationHelper.checkBuildConfiguration(script, checkParams, eitherOrParameters)

                            }

                        }

                        script.params.IS_SOURCE_VISUALIZER ?: prepareScans()
                        if(!scansList.isEmpty()) {
                            script.parallel(scansList)
                            if(scanResultsMap.containsValue('FAILURE') ||
                                scanResultsMap.containsValue('UNSTABLE') ||
                                scanResultsMap.containsValue('ABORTED') ) {
                                throw new AppFactoryException("One or more scans are failed, hence not proceeding to build the application!!", 'ERROR')
                            }
                        }

                        script.params.IS_SOURCE_VISUALIZER ? prepareCloudBuildRun() : prepareRun()

                        /* Expose Foundry configuration */
                        if (fabricAppConfig && !fabricAppConfig.equals("null")) {
                            BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                                /*
                                Workaround to fix masking of the values from fabricAppTriplet credentials build parameter,
                                to not mask required values during the build we simply need redefine parameter values.
                                Also, because of the case, when user didn't provide some not mandatory values we can get
                                null value and script.env object returns only String values,
                                been added elvis operator for assigning variable value as ''(empty).
                                */
                                script.env.FABRIC_ENV_NAME = (script.env.FABRIC_ENV_NAME) ?:
                                        script.echoCustom("Foundry environment value can't be null", 'ERROR')
                                fabricEnvironmentName = script.env.FABRIC_ENV_NAME
                                script.env['CONSOLE_URL'] = (script.env.MF_CONSOLE_URL) ?: script.kony.FABRIC_CONSOLE_URL
                                script.env['IDENTITY_URL'] = script.env.MF_IDENTITY_URL ?: null
                            }
                        }
                        buildStats.put('buildplat', getBuildPlat(channelsToRun))

                        /* Run channel builds in parallel */
                        script.parallel(runList)

                        /* If test pool been provided, prepare build parameters and trigger runTests job */
                        if (shallWeRunTests()) {
                            script.stage('TESTS') {

                                def testAutomationJobParameters = getTestAutomationJobParameters() ?:
                                        script.echoCustom("runTests job parameters are missing!", 'ERROR')
                                def testAutomationJobBinaryParameters = getTestAutomationJobBinaryParameters(artifacts) ?: []

                                if (runDesktopwebTests) {
                                    /* Finding the desktopweb artifact(war/zip) auth url from the list of channel artifacts */
                                    def artifactUrl = artifacts.findResults { artifact ->
                                        String artifactUrl = artifact.authurl ? artifact.authurl : ''

                                        /* Filtering by extension */
                                        !artifact.name ? '' : (artifact.name.matches("^.*.?(war|zip)\$")) ? artifactUrl : ''
                                    }
                                    testAutomationJobBinaryParameters.add(script.string(name: 'DESKTOPWEB_ARTIFACT_URL', value: "${artifactUrl[0]}"))
                                }

                                def awsCustomEnvParameters = []
                                if (runInCustomTestEnvironment) {
                                    /* Filter AWS Test Environment related parameters */
                                    awsCustomEnvParameters = [script.booleanParam(name: 'RUN_IN_CUSTOM_TEST_ENVIRONMENT', value: runInCustomTestEnvironment),
                                                              script.string(name: 'TESTNG_FILES', value: "${testngFiles}")
                                    ]
                                }

                                // passing the TEST_ENVIRONMENT variable to test job
                                if(script.params.containsKey("TEST_ENVIRONMENT"))
                                {
                                    awsCustomEnvParameters.add(script.string(name: 'TEST_ENVIRONMENT', value: script.params.TEST_ENVIRONMENT))
                                }

                                // APPIUM_VERSION variable will be passed on to the test job, even if the jasmine framework is selected.
                                if (isJasmineEnabled || runInCustomTestEnvironment) {
                                    awsCustomEnvParameters.add(script.string(name: 'APPIUM_VERSION', value: "${appiumVersion}"))
                                }

                                String testAutomationJobBasePath = "${script.env.JOB_NAME}" -
                                        "${script.env.JOB_BASE_NAME}" -
                                        'Builds/'
                                String testAutomationJobName = "${testAutomationJobBasePath}Tests/runTests"

                                /* Trigger runTests job to test build binaries */
                                def testAutomationJob = script.build job: testAutomationJobName,
                                        parameters: testAutomationJobParameters + testAutomationJobBinaryParameters + awsCustomEnvParameters,
                                        propagate: false
                                def testAutomationJobResult = testAutomationJob.currentResult

                                /* collect job run id to build stats */
                                runListStats.put(testAutomationJob.fullProjectName + "/" + testAutomationJob.number, testAutomationJob.fullProjectName)
                                /* Collect job result */
                                jobResultList.add(testAutomationJobResult)

                                mustHaveArtifacts.addAll(getArtifactObjects("Tests", testAutomationJob.buildVariables.MUSTHAVE_ARTIFACTS))

                                /* Notify user that runTests job build failed */
                                if (testAutomationJobResult != 'SUCCESS') {
                                    script.echoCustom("Status of the runTests job: ${testAutomationJobResult}", 'WARN')
                                }
                            }
                        }

                        /* Check if there are failed or unstable or aborted jobs */
                        if (jobResultList.contains('FAILURE') ||
                                jobResultList.contains('UNSTABLE') ||
                                jobResultList.contains('ABORTED')
                        ) {
                            /* Set job result to 'UNSTABLE' if above check is true */
                            script.currentBuild.result = 'UNSTABLE'
                        } else {
                            /* Set job result to 'SUCCESS' if above check is false */
                            script.currentBuild.result = 'SUCCESS'
                        }
                    }
                }

                catch (FlowInterruptedException interruptEx) {
                    cancelCloudBuild()
                    script.currentBuild.result = 'ABORTED'
                    buildStats.put('errmsg', (interruptEx.getLocalizedMessage()) ?: 'Something went wrong...' )
                    buildStats.put('errstack', interruptEx.getStackTrace().toString())
                }
                catch (AbortException abortedEx) {
                    cancelCloudBuild()
                    script.currentBuild.result = 'ABORTED'
                    buildStats.put('errmsg', (abortedEx.getLocalizedMessage()) ?: 'Something went wrong...' )
                    buildStats.put('errstack', abortedEx.getStackTrace().toString())
                }
                catch (AppFactoryException AFEx) {
                    String exceptionMessage = (AFEx.getLocalizedMessage()) ?: 'Something went wrong...'
                    script.echoCustom(exceptionMessage, 'ERROR', false)
                    if (script.params.IS_SOURCE_VISUALIZER) {
                        def consoleLogsLink = status.createAndUploadLogFile(script.env.JOB_NAME, script.env.BUILD_ID, exceptionMessage)
                        NotificationsHelper.sendEmail(script, 'cloudBuild', [artifacts: artifacts, consolelogs: consoleLogsLink, jobName: jobName, artifactMeta: artifactsMeta], true)
                    }

                    script.currentBuild.result = 'FAILURE'
                    buildStats.put('errmsg', exceptionMessage)
                    buildStats.put('errstack', AFEx.getStackTrace().toString())
                }
                catch (Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong ...'
                    script.echoCustom(exceptionMessage, 'ERROR', false)
                    if (script.params.IS_SOURCE_VISUALIZER) {
                        def consoleLogsLink = status.createAndUploadLogFile(script.env.JOB_NAME, script.env.BUILD_ID, exceptionMessage)
                        NotificationsHelper.sendEmail(script, 'cloudBuild', [artifacts: artifacts, consolelogs: consoleLogsLink, jobName: jobName, artifactMeta: artifactsMeta], true)
                    }

                    script.currentBuild.result = 'FAILURE'
                    buildStats.put('errmsg', exceptionMessage)
                    buildStats.put('errstack', e.getStackTrace().toString())

                } finally {
                    if (!runListStats.isEmpty())
                        buildStats.put("pipeline-run-jobs", runListStats)
                    buildStats.put("projname", projectName)
                    buildStats.put('buildemlrecipients', script.env["RECIPIENTS_LIST"])
                    buildStats.put('buildtype', "Iris")
                    // Publish Facade metrics keys to build Stats Action class.
                    script.statspublish buildStats.inspect()

                    String s3MustHaveAuthUrl = ''
                    if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                        s3MustHaveAuthUrl = BuildHelper.prepareMustHaves(script, BuildType.Iris, "vizMustHaves", "vizbuildlog.log", libraryProperties, mustHaveArtifacts)
                    }

                    BuildHelper.setBuildDescription(script, s3MustHaveAuthUrl)


                    if (script.params.IS_SOURCE_VISUALIZER) {
                        credentialsHelper.deleteUserCredentials([buildNumber, PlatformType.IOS.toString() + buildNumber, "Foundry" + buildNumber])
                    } else {
                        if (channelsToRun && script.currentBuild.result != 'FAILURE') {
                            /*
                             * Been agreed to send notification from buildIrisApp job only,
                             * if result not equals 'FAILURE',
                             * all notification with failed channel builds will be sent directly from channel job.
                             */
                            NotificationsHelper.sendEmail(script, 'buildIrisApp',
                                    [
                                            artifacts              : artifacts,
                                            fabricEnvironmentName  : fabricEnvironmentName,
                                            projectSourceCodeBranch: projectSourceCodeBranch,
                                            artifactMeta           : artifactsMeta,
                                            scmMeta                : scmMeta
                                    ],
                                    true)
                        }
                    }
                }
            }
        }
    }

    @NonCPS
    private String  getBuildPlat(channelsToRun)
    {
        String ChannelsSelected = channelsToRun.stream().map{channel -> channel.substring(0, channel.indexOf("_"))}.collect{ it.capitalize() }.unique().join(', ')
        return ChannelsSelected
    }

    /**
     * This method updates the build status file on cancel operation of cloud build .
     */
    protected void cancelCloudBuild() {
        if (script.params.IS_SOURCE_VISUALIZER) {
            // lets get the downstream job (buildAll) log file from S3 if it exist, and then map it to current statusJson Object
            // this way, we will get child job status file back to parent to refresh with new status
            AwsHelper.s3Download(script, BuildStatus.BUILD_STATUS_FILE_NAME, script.params.BUILD_STATUS_PATH)

            if (script.fileExists(BuildStatus.BUILD_STATUS_FILE_NAME)) {
                def statusFileJson = script.readJSON file: BuildStatus.BUILD_STATUS_FILE_NAME
                status = BuildStatus.getBuildStatusObject(script, statusFileJson, channelsToRun)
            } else {
                // if S3 file not exist it means no downstream job invoked, so, lets upload parent job log file then notify
                def consoleLogsLink = status.createAndUploadLogFile(script.env.JOB_NAME, script.env.BUILD_ID, "BUILD ABORTED!!")
                // Send build results email notification with build status as aborted
                script.currentBuild.result = 'ABORTED'
                NotificationsHelper.sendEmail(script, 'cloudBuild', [artifacts: artifacts, consolelogs: consoleLogsLink, jobName: jobName, artifactMeta: artifactsMeta], true)
            }
            // update status as cancelled and upload latest json to S3
            status.updateCancelBuildStatusOnS3()
        }
    }
}
