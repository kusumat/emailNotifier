package com.kony.appfactory.visualizer

import com.kony.appfactory.enums.PlatformType
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AwsHelper
import com.kony.AppFactory.Jenkins.rootactions.AppFactoryVersions
import com.kony.appfactory.helper.CredentialsHelper

/**
 * Implements logic for buildVisualizerApp job.
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

    /* List of channels to build */
    private channelsToRun
    /*
        List of channel artifacts in format:
            [channelPath: <relative path to the artifact on S3>, name: <artifact file name>, url: <S3 artifact URL>]
     */
    private artifacts = []
    private mustHaveArtifacts = []
    /* List of job statuses (job results), used for setting up final result of the buildVisualizer job */
    private jobResultList = []
    /* Common build parameters */
    private final projectName = script.env.PROJECT_NAME
    private final projectSourceCodeRepositoryCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private final projectSourceCodeBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private final buildMode = script.params.BUILD_MODE
    private final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final recipientsList = script.params.RECIPIENTS_LIST
    private final defaultLocale = script.params.DEFAULT_LOCALE
    private final universalAndroid = script.params.ANDROID_UNIVERSAL_NATIVE
    private final universalIos = script.params.IOS_UNIVERSAL_NATIVE
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
    /* WEB build parameters */
    private
    final webAppVersion = script.params.WEB_APP_VERSION ? script.params.WEB_APP_VERSION : script.params.SPA_APP_VERSION ? script.params.SPA_APP_VERSION : null
    private
    final webVersionParameterName = script.params.containsKey('WEB_APP_VERSION') ? 'WEB_APP_VERSION' : 'SPA_APP_VERSION'
    private final desktopWebChannel = script.params.DESKTOP_WEB
    private final compatibilityMode = script.params.FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE
    /* TestAutomation build parameters */
    private final availableTestPools = script.params.AVAILABLE_TEST_POOLS
    private final availableBrowsers = script.params.AVAILABLE_BROWSERS
    private final desktopWebTestsArguments = script.params.RUN_DESKTOPWEB_TESTS_ARGUMENTS
    private final runDesktopwebTests = script.params.RUN_DESKTOPWEB_TESTS
    /* CustomHooks build Parameters*/
    private final runCustomHook = script.params.RUN_CUSTOM_HOOKS
    /* Protected mode build parameters */
    private final protectedKeys = script.params.PROTECTED_KEYS
    private appleCredentialsId
    private buildNumber = script.env.BUILD_NUMBER

    protected CredentialsHelper credentialsHelper
    protected BuildStatus status

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
        /* Checking if at least one channel been selected. */
        channelsToRun = (BuildHelper.getSelectedChannels(this.script.params)) ?:
                /* Note :- script.error has been used instead of script.echoCustom as part of fix for APPFACT-1101. Please do not replace. */
                script.error('Please select at least one channel to build!')
        this.script.env['CLOUD_ACCOUNT_ID'] = (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
        credentialsHelper = new CredentialsHelper()
        status = new BuildStatus(script, channelsToRun)
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
     * Return group of common build parameters.
     *
     * @return group of common build parameters.
     */
    private final getCommonJobBuildParameters() {
        [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH', value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'BUILD_MODE', value: "${buildMode}"),
                script.credentials(name: 'CLOUD_CREDENTIALS_ID', value: "${cloudCredentialsID}"),
                script.credentials(name: 'FABRIC_APP_CONFIG', value: "${fabricAppConfig}"),
                script.booleanParam(name: 'PUBLISH_FABRIC_APP', value: publishFabricApp),
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
                script.string(name: 'PROJECT_NAME', value: "${script.params.PROJECT_NAME}")
        ]
    }

    /**
     * Return specific to WEB channel build parameters.
     * @param spaChannelsToBuildJobParameters list of SPA channels to build.
     * @return WEB specific build parameters.
     */
    private final getWebChannelJobBuildParameters(spaChannelsToBuildJobParameters = null) {
        if (spaChannelsToBuildJobParameters && desktopWebChannel) {
            getCommonJobBuildParameters() +
                    [script.string(name: "${webVersionParameterName}", value: "${webAppVersion}")] +
                    [script.booleanParam(name: "DESKTOP_WEB", value: desktopWebChannel)] + [script.booleanParam(name: "FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE", value: compatibilityMode)] +
                    spaChannelsToBuildJobParameters
        } else if (spaChannelsToBuildJobParameters) {
            getCommonJobBuildParameters() +
                    [script.string(name: "${webVersionParameterName}", value: "${webAppVersion}")] +
                    [script.booleanParam(name: "FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE", value: compatibilityMode)] +
                    spaChannelsToBuildJobParameters
        } else {
            getCommonJobBuildParameters() +
                    [script.string(name: "${webVersionParameterName}", value: "${webAppVersion}")] +
                    [script.booleanParam(name: "FORCE_WEB_APP_BUILD_COMPATIBILITY_MODE", value: compatibilityMode)]
        }

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
                        script.credentials(name: 'PROTECTED_KEYS', value: "${protectedKeys}")
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
        [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH',
                        value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                        value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'NATIVE_TESTS_URL', value: ''),
                script.string(name: 'DESKTOPWEB_TESTS_URL', value: ''),
                script.string(name: 'AVAILABLE_TEST_POOLS', value: "${availableTestPools}"),
                script.string(name: 'RUN_DESKTOPWEB_TESTS', value: "${runDesktopwebTests}"),
                script.string(name: 'AVAILABLE_BROWSERS', value: "${availableBrowsers}"),
                script.string(name: 'RUN_DESKTOPWEB_TESTS_ARGUMENTS', value: "${desktopWebTestsArguments}"),
                script.string(name: 'RECIPIENTS_LIST', value: "${recipientsList}"),
                script.booleanParam(name: 'RUN_CUSTOM_HOOKS', value: runCustomHook)
        ]
    }

    /**
     * Deserializes channel artifact object.
     *
     * @param channelPath relative path to the artifact on S3, generated from channel build parameter.
     * @param artifacts serialized list of channel artifacts.
     * @return list of channel artifacts.
     */
    private final getArtifactObjects(channelPath, artifacts) {
        (artifacts) ? Eval.me(artifacts) : [[name: '', url: '', channelPath: channelPath]]
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
        buildJobArtifacts.findResults { artifact ->
            /* Filter Android and iOS channels */
            String artifactName = (artifact.name && artifact.name.matches("^.*.?(plist|ipa|apk|war|zip)\$")) ? artifact.name : ''
            /*
                Workaround to get ipa URL for iOS, just switching extension in URL to ipa,
                because ipa file should be places nearby plist file on S3.
             */
            String artifactUrl = artifact.url ? (!artifact.url.contains('.plist') ? artifact.url :
                    artifact.url.replaceAll('.plist', '.ipa')) : ''
            String channelName = artifact.channelPath.toUpperCase().replaceAll('/', '_')

            /* Create build parameter for Test Automation job */
            artifactName ? (artifact.name.matches("^.*.?(war|zip)\$")) ? script.stringParam(name: "FABRIC_APP_URL", value: artifact.webappurl) : script.stringParam(name: "${channelName}_BINARY_URL", value: artifactUrl) : null
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

                    /* Collect job artifacts */
                    artifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS))

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

        /* Set CloudBuild environment variables for multi-tenant AppFactory */
        //setting CLOUD _ACCOUNT_ID and CLOUD_ENVIRONMENT_GUID with MF_ACCOUNT_ID MF_ENVIRONMENT_GUID build parameter values
        script.env['CLOUD_ACCOUNT_ID'] = (script.params.MF_ACCOUNT_ID) ?: ''
        script.env['CLOUD_ENVIRONMENT_GUID'] = (script.params.MF_ENVIRONMENT_GUID) ?: ''

        /* Set CloudBuild environment variables for multi-tenant AppFactory */
        /* Setting blank credential id so that CloudBuild service won't fail to find Cloud credentials.
         Note: This account is not relevant for Visualizer CI build to run, just to ensure single-tenant backward flow
         works. Setting one blank account for this build scope. Post the build, it will get this removed.*/
        cloudCredentialsID = "CloudID-" + buildNumber
        credentialsHelper.addUsernamePassword(cloudCredentialsID, "Cloud Creds", "dummyuser", "dummypasswd")

        /* Collect iOS channel parameters to check */
        def iosChannels = channelsToRun?.findAll { it.matches('^IOS_.*_NATIVE$') }
        if(iosChannels) {
            String appleUsername = script.params.APPLE_USERNAME
            String applePassword = script.params.APPLE_PASSWORD
            appleID = PlatformType.IOS.toString() + buildNumber
            credentialsHelper.addUsernamePassword(appleID, "Apple Credentials", appleUsername, applePassword)
        }

        // Prepare Status Json with build in-progress
        status.prepareStatusJson()

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

                /* Collect job artifacts */
                artifacts.addAll(getArtifactObjects("CloudBuild", channelJob.buildVariables.CHANNEL_ARTIFACTS))

            }
        }
    }

    /**
     * Prepares runList for WebChannels
     * @params spaChannelsToRun , desktopWebChannel web channels to run.
     */
    private final void runWebChannels(spaChannelsToRun = null, desktopWebChannel = null) {
        def channelName = (spaChannelsToRun && desktopWebChannel) ? 'WEB' : spaChannelsToRun ? 'SPA' : desktopWebChannel ? 'DESKTOP_WEB' : null
        def channelJobBuildParameters

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

        def channelPath = getChannelPath(channelName)

        runList[channelName] = {
            script.stage(channelName) {
                /* Trigger channel job */
                def channelJob = script.build job: channelJobName, parameters: channelJobBuildParameters,
                        propagate: false
                /* Collect job result */
                jobResultList.add(channelJob.currentResult)

                /* Collect job artifacts */
                artifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS))

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
     * Sets build description at the end of the build.
     */
    private final void setBuildDescription(s3MustHaveAuthUrl) {
        String EnvironmentDescription = ""
        String mustHavesDescription = ""
        if (script.env.FABRIC_ENV_NAME && script.env.FABRIC_ENV_NAME != '_') {
            EnvironmentDescription = "<p>Environment: $script.env.FABRIC_ENV_NAME</p>"
        }

        if (s3MustHaveAuthUrl)
            mustHavesDescription = "<p><a href='${s3MustHaveAuthUrl}'>Logs</a></p>"

        script.currentBuild.description = """\
            <div id="build-description">
                ${EnvironmentDescription}
                <p>Rebuild: <a href='${script.env.BUILD_URL}rebuild' class="task-icon-link">
                <img src="/static/b33030df/images/24x24/clock.png"
                style="width: 24px; height: 24px; width: 24px; height: 24px; margin: 2px;"
                class="icon-clock icon-md"></a></p>
                ${mustHavesDescription}
            </div>\
            """.stripIndent()
    }

    /**
     * Get the AppFactory version information (appfactory plugin version, core plugins versions, Kony Libarary branch information )
     */
    private final String getYourAppFactoryVersions() {
        def apver = new AppFactoryVersions()
        def versionInfo = StringBuilder.newInstance()

        versionInfo.append "PipeLine Version : " + apver.getPipelineVersion()
        versionInfo.append "\nDSL Job Version : " + apver.getJobDslVersion()
        versionInfo.append "\nAppFactory Plugin Version : " + apver.getAppFactoryPluginVersion()
        versionInfo.append "\nAppFactory Custom View Plugin Version : " + apver.getCustomViewPluginVersion()
        versionInfo.append "\nAppFactory Build Parameters Plugin : " + apver.getBuildParametersPluginVersion()

        def corePlugInVersionInfo = apver.getCorePluginVersions()

        corePlugInVersionInfo.each { pluginName, pluginVersion ->
            versionInfo.append "\n$pluginName : $pluginVersion"
        }

        versionInfo.toString()
    }

    /**
     * Prepare must haves for the debugging
     */
    private final String PrepareMustHaves() {
        String s3MustHaveAuthUrl
        String separator = script.isUnix() ? '/' : '\\'
        String mustHaveFolderPath = [script.env.WORKSPACE, "vizMustHaves"].join(separator)
        String mustHaveFile = ["vizMustHaves", script.env.BUILD_NUMBER].join("_") + ".zip"
        String mustHaveFilePath = [script.env.WORKSPACE, mustHaveFile].join(separator)
        script.cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: 'vizMustHaves*', type: 'INCLUDE']]

        script.dir(mustHaveFolderPath) {
            script.writeFile file: "vizbuildlog.log", text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
            script.writeFile file: "AppFactoryVersionInfo.txt", text: getYourAppFactoryVersions()
            script.writeFile file: "environmentInfo.txt", text: BuildHelper.getEnvironmentInfo(script)
            script.writeFile file: "ParamInputs.txt", text: BuildHelper.getInputParamsAsString(script)
            mustHaveArtifacts.each {
                if (it.path.trim().length() > 0) {
                    script.withAWS(region: script.env.S3_BUCKET_REGION) {
                        script.s3Download(file: it.name, bucket: script.env.S3_BUCKET_NAME, path: [it.path, it.name].join('/'))
                        script.s3Delete(bucket: script.env.S3_BUCKET_NAME, path: [it.path, it.name].join('/'))
                    }
                }
            }
        }

        script.dir(script.env.WORKSPACE) {
            script.zip dir: "vizMustHaves", zipFile: mustHaveFile
            script.catchErrorCustom("Failed to create the Zip file") {
                if (script.fileExists(mustHaveFilePath)) {
                    String s3ArtifactPath = ['Builds', script.env.PROJECT_NAME].join('/')
                    s3MustHaveAuthUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath, sourceFileName: mustHaveFile,
                            sourceFilePath: script.env.WORKSPACE, script
                    s3MustHaveAuthUrl = BuildHelper.createAuthUrl(s3MustHaveAuthUrl, script)
                }
            }
        }
        s3MustHaveAuthUrl
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {

        try {
            /* Wrapper for injecting timestamp to the build console output */
            script.timestamps {
                /* Wrapper for colorize the console output in a pipeline build */
                script.ansiColor('xterm') {
                    script.stage('Check provided parameters') {
                        script.echoCustom("Running the build in ${buildMode} mode..")

                        status.prepareBuildServiceEnvironment(channelsToRun)
                        status.prepareStatusJson()

                        /* Check common params */
                        ValidationHelper.checkBuildConfiguration(script)

                        /* Check params for universal application build */
                        if (universalAndroid || universalIos) {
                            ValidationHelper.checkBuildConfigurationForUniversalApp(script)
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
                            def webMandatoryParams = ["${webVersionParameterName}", 'FABRIC_APP_CONFIG']

                            checkParams.addAll(webMandatoryParams)
                        }

                        /* Check all required parameters depending on user input */
                        /* For CloudBuild, scan the checkParams list and clean unwanted params */
                        if (script.params.IS_SOURCE_VISUALIZER) {
                            def cloudBuildNotExistingParams = [
                                    'FABRIC_APP_CONFIG', 'PUBLISH_FABRIC_APP', 'RECIPIENTS_LIST',
                                    'RUN_CUSTOM_HOOKS', 'FORM_FACTOR', 'PROTECTED_KEYS', 'APPLE_ID'
                            ]
                            checkParams.removeAll(cloudBuildNotExistingParams)
                            ValidationHelper.checkBuildConfiguration(script, checkParams)
                        }
                        else
                        {
                            ValidationHelper.checkBuildConfiguration(script, checkParams, eitherOrParameters)

                        }
                    }
					
                    /* Allocate a slave for the run */
                    script.node(libraryProperties.'facade.node.label') {

                        script.params.IS_SOURCE_VISUALIZER ? prepareCloudBuildRun():prepareRun()
                        try {
                            /* Expose Fabric configuration */
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
                                            script.echoCustom("Fabric environment value can't be null", 'ERROR')
                                }
                            }
                            /* Run channel builds in parallel */
                            script.parallel(runList)
                            /* If test pool been provided, prepare build parameters and trigger runTests job */
                            if (availableTestPools || runDesktopwebTests) {
                                script.stage('TESTS') {
                                    def testAutomationJobParameters = getTestAutomationJobParameters() ?:
                                            script.echoCustom("runTests job parameters are missing!", 'ERROR')
                                    def testAutomationJobBinaryParameters = getTestAutomationJobBinaryParameters(artifacts) ?:
                                            script.echoCustom("runTests job binary URL parameters are missing!", 'ERROR')
                                    String testAutomationJobBasePath = "${script.env.JOB_NAME}" -
                                            "${script.env.JOB_BASE_NAME}" -
                                            'Builds/'
                                    String testAutomationJobName = "${testAutomationJobBasePath}Tests/runTests"

                                    /* Trigger runTests job to test build binaries */
                                    def testAutomationJob = script.build job: testAutomationJobName,
                                            parameters: testAutomationJobParameters + testAutomationJobBinaryParameters,
                                            propagate: false
                                    def testAutomationJobResult = testAutomationJob.currentResult

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
                        } catch (Exception e) {
                            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage, 'WARN')
                            script.currentBuild.result = 'FAILURE'
                        } finally {


                            /*
                            Been agreed to send notification from buildVisualizerApp job only
                            if result not equals 'FAILURE', all notification with failed channel builds
                            will be sent directly from channel job.
                            */
                            if(!script.params.IS_SOURCE_VISUALIZER) {
                                String s3MustHaveAuthUrl
                                if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                                    s3MustHaveAuthUrl = PrepareMustHaves()
                                }
                                setBuildDescription(s3MustHaveAuthUrl)
                                if (channelsToRun && script.currentBuild.result != 'FAILURE') {
                                    NotificationsHelper.sendEmail(script, 'buildVisualizerApp', [artifacts: artifacts], true)
                                }
                            }
                        }
                    }
                }
            }
        }
        catch(Exception e){
            String exceptionMessage = (e.getMessage()) ?: 'Something went wrong...'
            script.currentBuild.result='FAILURE'
            cloudBuildCreateAndUploadLogFileInFailureCase(exceptionMessage)
        } finally {
            credentialsHelper.deleteUserCredentials([buildNumber, PlatformType.IOS.toString() + buildNumber, "Fabric" + buildNumber])
        }
    }

    /**
     * This function helps in uploading the logfile to the s3 with an exception message, This explicitly allocates a node
     * Uploads both status json as well as the exception message
     *
     * @param message holds the exception message that has to be kept in the log file
     */
    public void cloudBuildCreateAndUploadLogFileInFailureCase(String message){
        if (!script.params.IS_SOURCE_VISUALIZER) {
            return
        }
        script.node(libraryProperties.'facade.node.label') {
            status.createAndUploadLogFileOnFailureCase(channelsToRun, message)
        }
    }
}