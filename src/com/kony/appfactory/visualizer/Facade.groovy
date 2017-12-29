package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper

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
    /* List of channels to build */
    private channelsToRun
    /*
        List of channel artifacts in format:
            [channelPath: <relative path to the artifact on S3>, name: <artifact file name>, url: <S3 artifact URL>]
     */
    private artifacts = []
    /* List of job statuses (job results), used for setting up final result of the buildVisualizer job */
    private jobResultList = []
    /* Common build parameters */
    private final projectSourceCodeRepositoryCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private final projectSourceCodeBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private final cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private final buildMode = script.params.BUILD_MODE
    private final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final recipientsList = script.params.RECIPIENTS_LIST
    private final defaultLocale = script.params.DEFAULT_LOCALE
    /* iOS build parameters */
    private final appleID = script.params.APPLE_ID
    private final appleDeveloperTeamId = script.params.APPLE_DEVELOPER_TEAM_ID
    private final iosDistributionType = script.params.IOS_DISTRIBUTION_TYPE
    private final iosMobileAppId = script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosBundleVersion = script.params.IOS_BUNDLE_VERSION
    /* Android build parameters */
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidAppVersion = script.params.ANDROID_APP_VERSION
    private final androidVersionCode = script.params.ANDROID_VERSION_CODE
    private final googleMapsKeyId = script.params.GOOGLE_MAPS_KEY_ID
    private final keystoreFileID = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordID = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private final keystoreAlias = script.params.ANDROID_KEY_ALIAS
    /* SPA build parameters */
    private final spaAppVersion = script.params.SPA_APP_VERSION
    /* TestAutomation build parameters */
    private final availableTestPools = script.params.AVAILABLE_TEST_POOLS

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
        /* Checking if at least one channel been selected */
        channelsToRun = (getSelectedChannels(this.script.params)) ?:
                script.error('Please select at least one channel to build!')
    }

    /**
     * Collects selected channels to build.
     *
     * @param buildParameters job parameters.
     * @return list of selected channels.
     */
    @NonCPS
    private static getSelectedChannels(buildParameters) {
        buildParameters.findAll {
            it.value instanceof Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.value
        }.keySet().collect()
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
        channelsToRun.findAll { !it.contains('SPA') }
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
            default:
                break
        }

        channelsBaseFolder + '/' + 'build' + (channelType) ?: script.error('Unknown channel type!')
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
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH',
                        value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                        value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'BUILD_MODE', value: "${buildMode}"),
                script.credentials(name: 'CLOUD_CREDENTIALS_ID', value: "${cloudCredentialsID}"),
                script.credentials(name: 'FABRIC_APP_CONFIG', value: "${fabricAppConfig}"),
                script.booleanParam(name: 'PUBLISH_FABRIC_APP', value: publishFabricApp),
                script.string(name: 'DEFAULT_LOCALE', value: "${defaultLocale}"),
                script.string(name: 'RECIPIENTS_LIST', value: "${recipientsList}")
        ]
    }

    /**
     * Return SPA specific build parameters.
     *
     * @param spaChannelsToBuildJobParameters list of SPA channels to build.
     * @return SPA specific build parameters.
     */
    private final getSpaChannelJobBuildParameters(spaChannelsToBuildJobParameters) {
        getCommonJobBuildParameters() + [script.string(name: 'SPA_APP_VERSION', value: "${spaAppVersion}")] +
                spaChannelsToBuildJobParameters
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
                        script.string(name: 'GOOGLE_MAPS_KEY_ID', value: "${googleMapsKeyId}"),
                        script.credentials(name: 'ANDROID_KEYSTORE_FILE', value: "${keystoreFileID}"),
                        script.credentials(name: 'ANDROID_KEYSTORE_PASSWORD', value: "${keystorePasswordID}"),
                        script.credentials(name: 'ANDROID_KEY_PASSWORD', value: "${privateKeyPassword}"),
                        script.string(name: 'ANDROID_KEY_ALIAS', value: "${keystoreAlias}")
                ]
                break
            case ~/^.*IOS.*$/:
                channelJobParameters = commonParameters + [
                        script.credentials(name: 'APPLE_ID', value: "${appleID}"),
                        script.string(name: 'APPLE_DEVELOPER_TEAM_ID', value: "${appleDeveloperTeamId}"),
                        script.string(name: 'IOS_DISTRIBUTION_TYPE', value: "${iosDistributionType}"),
                        script.string(name: 'IOS_MOBILE_APP_ID', value: "${iosMobileAppId}"),
                        script.string(name: 'IOS_TABLET_APP_ID', value: "${iosTabletAppId}"),
                        script.string(name: 'IOS_BUNDLE_VERSION', value: "${iosBundleVersion}")
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
                script.string(name: 'TESTS_BINARY_URL', value: ''),
                script.string(name: 'AVAILABLE_TEST_POOLS', value: "${availableTestPools}"),
                script.string(name: 'RECIPIENTS_LIST', value: "${recipientsList}")
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
            String artifactName = (artifact.name && artifact.name.matches("^.*.?(plist|apk)\$")) ? artifact.name : ''
            /*
                Workaround to get ipa URL for iOS, just switching extension in URL to ipa,
                because ipa file should be places nearby plist file on S3.
             */
            String artifactUrl = artifact.url ? (!artifact.url.contains('.plist') ? artifact.url :
                    artifact.url.replaceAll('.plist', '.ipa')) : ''
            String channelName = artifact.channelPath.toUpperCase().replaceAll('/', '_')

            /* Create build parameter for Test Automation job */
            artifactName ? script.stringParam(name: "${channelName}_BINARY_URL", value: artifactUrl) : null
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
                    script.error("Channel job name can't be null")
            def channelOs = getChannelOs(channelName)
            def channelFormFactor = (getChannelFormFactor(channelName)) ?:
                    script.error("Channel form factor can't be null")
            def channelJobBuildParameters = (
                    getNativeChannelJobBuildParameters(channelName, channelOs, channelFormFactor)
            ) ?: script.error("Channel job build parameters list can't be null")
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

                    /* Notify user that one of the channels failed */
                    if (channelJob.currentResult != 'SUCCESS') {
                        script.echo("Status of the channel ${channelName} build is: ${channelJob.currentResult}")
                    }
                }
            }
        }

        /* If SPA channels been set */
        if (spaChannelsToRun) {
            def channelName = 'SPA'
            def channelJobName = (getChannelJobName(channelName)) ?:
                    script.error("Channel job name can't be null")
            /* Convert selected SPA channels to build parameters for SPA job */
            def spaChannelsToBuildJobParameters = convertSpaChannelsToBuildParameters(spaChannelsToRun)
            def channelJobBuildParameters = (getSpaChannelJobBuildParameters(spaChannelsToBuildJobParameters)) ?:
                    script.error("Channel job build parameters list can't be null")
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

                    /* Notify user that SPA channel build failed */
                    if (channelJob.currentResult != 'SUCCESS') {
                        script.echo("Status of the channel ${channelName} build is: ${channelJob.currentResult}")
                    }
                }
            }
        }
    }

    /**
     * Sets build description at the end of the build.
     */
    private final void setBuildDescription() {
        String EnvironmentDescription = ""
        if (script.env.FABRIC_ENV_NAME && script.env.FABRIC_ENV_NAME != '_') {
            EnvironmentDescription = "<p>Environment: $script.env.FABRIC_ENV_NAME</p>"
        }

        script.currentBuild.description = """\
            <div id="build-description">
                ${EnvironmentDescription}
                <p>Rebuild: <a href='${script.env.BUILD_URL}rebuild' class="task-icon-link">
                <img src="/static/b33030df/images/24x24/clock.png"
                style="width: 24px; height: 24px; width: 24px; height: 24px; margin: 2px;"
                class="icon-clock icon-md"></a></p>
            </div>\
            """.stripIndent()
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            script.stage('Check provided parameters') {
                /* Check common params */
                ValidationHelper.checkBuildConfiguration(script)

                /* List of required parameters */
                def checkParams = []

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

                    if (buildMode == 'release') {
                        androidMandatoryParams.addAll([
                                'ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD',
                                'ANDROID_KEY_ALIAS'
                        ])
                    }

                    checkParams.addAll(androidMandatoryParams)
                }

                /* Collect iOS channel parameters to check */
                def iosChannels = channelsToRun?.findAll { it.matches('^IOS_.*_NATIVE$') }

                if (iosChannels) {
                    def iosMandatoryParams = ['IOS_DISTRIBUTION_TYPE', 'APPLE_ID', 'IOS_BUNDLE_VERSION']

                    if (iosChannels.findAll { it.contains('MOBILE') }) {
                        iosMandatoryParams.add('IOS_MOBILE_APP_ID')
                    }

                    if (iosChannels.findAll { it.contains('TABLET') }) {
                        iosMandatoryParams.add('IOS_TABLET_APP_ID')
                    }

                    checkParams.addAll(iosMandatoryParams)
                }

                /* Collect SPA channel parameters to check */
                def spaChannels = channelsToRun?.findAll { it.matches('^.*_.*_SPA$') }

                if (spaChannels) {
                    def spaMandatoryParams = ['SPA_APP_VERSION', 'FABRIC_APP_CONFIG']

                    checkParams.addAll(spaMandatoryParams)
                }

                /* Check all required parameters depending on user input */
                ValidationHelper.checkBuildConfiguration(script, checkParams)
            }

            /* Allocate a slave for the run */
            script.node(libraryProperties.'facade.node.label') {
                prepareRun()

                try {
                    /* Expose Fabric configuration */
                    if (fabricAppConfig) {
                        BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                            /*
                                Workaround to fix masking of the values from fabricAppTriplet credentials build parameter,
                                to not mask required values during the build we simply need redefine parameter values.
                                Also, because of the case, when user didn't provide some not mandatory values we can get
                                null value and script.env object returns only String values,
                                been added elvis operator for assigning variable value as ''(empty).
                            */
                            script.env.FABRIC_ENV_NAME = (script.env.FABRIC_ENV_NAME) ?:
                                    script.error("Fabric environment value can't be null")
                        }
                    }
                    /* Run channel builds in parallel */
                    script.parallel(runList)

                    /* If test pool been provided, prepare build parameters and trigger runTests job */
                    if (availableTestPools) {
                        script.stage('TESTS') {
                            def testAutomationJobParameters = getTestAutomationJobParameters() ?:
                                    script.error("runTests job parameters are missing!")
                            def testAutomationJobBinaryParameters = getTestAutomationJobBinaryParameters(artifacts) ?:
                                    script.error("runTests job binary URL parameters are missing!")
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

                            /* Notify user that runTests job build failed */
                            if (testAutomationJobResult != 'SUCCESS') {
                                script.echo "Status of the runTests job: ${testAutomationJobResult}"
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
                    script.echo "ERROR: $exceptionMessage"
                    script.currentBuild.result = 'FAILURE'
                } finally {
                    setBuildDescription()
                    /*
                        Been agreed to send notification from buildVisualizerApp job only
                        if result not equals 'FAILURE', all notification with failed channel builds
                        will be sent directly from channel job.
                     */
                    if (channelsToRun && script.currentBuild.result != 'FAILURE') {
                        NotificationsHelper.sendEmail(script, 'buildVisualizerApp', [artifacts: artifacts], true)
                    }
                }
            }
        }
    }
}
