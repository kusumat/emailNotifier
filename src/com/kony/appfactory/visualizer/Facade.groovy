package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper

class Facade implements Serializable {
    private script
    private nodeLabel = 'master'
    private runList = [:]
    private channelsToRun
    private artifacts = []
    private jobResultList = []
    /* Common build parameters */
    private final projectSourceCodeRepositoryCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private final projectSourceCodeBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private final fabricEnvironmentName = script.params.FABRIC_ENVIRONMENT_NAME
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
    private final iosMobileAppId =  script.params.IOS_MOBILE_APP_ID
    private final iosTabletAppId = script.params.IOS_TABLET_APP_ID
    private final iosBundleVersion = script.params.IOS_BUNDLE_VERSION
    /* Android build parameters */
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidVersion = script.params.ANDROID_VERSION
    private final androidVersionCode = script.params.ANDROID_VERSION_CODE
    private final googleMapsKey = script.params.GOOGLE_MAPS_KEY
    private final keystoreFileID = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordID = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private final keystoreAlias = script.params.ANDROID_KEY_ALIAS
    /* TestAutomation build parameters */
    private final availableTestPools = script.params.AVAILABLE_TEST_POOLS

    Facade(script) {
        this.script = script
        /* Checking if at least one channel been selected */
        channelsToRun = (getSelectedChannels(this.script.params)) ?:
                script.error('Please select at least one channel to build!')
    }

    @NonCPS
    private static getSelectedChannels(buildParameters) {
        buildParameters.findAll {
            it.value instanceof  Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.value
        }.keySet().collect()
    }

    private final getSpaChannels(channelsToRun) {
        channelsToRun.findAll { it.contains('SPA') }
    }

    private final getNativeChannels(channelsToRun) {
        channelsToRun.findAll { !it.contains('SPA') }
    }

    private final convertSpaChannelsToBuildParameters(channels) {
        channels.collect { script.booleanParam(name: it, value: true) }
    }

    private final getChannelFormFactor(channelName) {
        channelName.tokenize('_')[1].toLowerCase().capitalize()
    }

    private final getChannelOs(channelName) {
        channelName.tokenize('_')[0].toLowerCase().capitalize()
    }

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

    private final getSpaChannelJobBuildParameters(spaChannelsToBuildJobParameters) {
        getCommonJobBuildParameters() + spaChannelsToBuildJobParameters
    }

    private final getNativeChannelJobBuildParameters(channelName, channelOs = '', channelFormFactor) {
        def channelJobParameters = []
        def commonParameters = getCommonJobBuildParameters()

        switch (channelName) {
            case ~/^.*ANDROID.*$/:
                channelJobParameters = commonParameters + [
                        script.string(name: 'ANDROID_MOBILE_APP_ID', value: "${androidMobileAppId}"),
                        script.string(name: 'ANDROID_TABLET_APP_ID', value: "${androidTabletAppId}"),
                        script.string(name: 'ANDROID_VERSION', value: "${androidVersion}"),
                        script.string(name: 'ANDROID_VERSION_CODE', value: "${androidVersionCode}"),
                        script.string(name: 'GOOGLE_MAPS_KEY', value: "${googleMapsKey}"),
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

    private final getTestAutomationJobParameters() {
        def parameters = [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH',
                        value: "${projectSourceCodeBranch}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                        value: "${projectSourceCodeRepositoryCredentialsId}"),
                script.string(name: 'TESTS_BINARY_URL', value: ''),
                script.string(name: 'AVAILABLE_TEST_POOLS', value: "${availableTestPools}")
        ]

        parameters
    }

    private final getArtifactObjects(channelPath, artifacts) {
        return (artifacts) ? Eval.me(artifacts) : [[name: '', url: '', channelPath: channelPath]]
    }

    private final getTestAutomationJobBinaryParameters(buildJobArtifacts) {
        def binaryParameters = []

        for (buildJobArtifact in buildJobArtifacts) {
            def artifactName = (!buildJobArtifact['name'].contains('.plist')) ?:
                    buildJobArtifact['name'].replaceAll('.plist', '.ipa')
            def artifactURL = buildJobArtifact.url
            def channelName = buildJobArtifact.channelPath.toUpperCase().replaceAll('/', '_')
            if (artifactName != '-') {
                binaryParameters.add(script.stringParam(name: "${channelName}_BINARY_URL", value: artifactURL))
            }
        }

        binaryParameters
    }

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

                    if (channelJob.currentResult != 'SUCCESS') {
                        script.echo("Status of the channel ${channelName} build is: ${channelJob.currentResult}")
                    }
                }
            }
        }

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
                    /* Collect job results */
                    jobResultList.add(channelJob.currentResult)

                    /* Collect job artifacts */
                    artifacts.addAll(getArtifactObjects(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS))

                    if (channelJob.currentResult != 'SUCCESS') {
                        script.echo("Status of the channel ${channelName} build is: ${channelJob.currentResult}")
                    }
                }
            }
        }
    }

    private final void setBuildDescription() {
        script.currentBuild.description = """\
            <div id="build-description">
                <p>Environment: $fabricEnvironmentName</p>
                <p>Rebuild: <a href='${script.env.BUILD_URL}rebuild' class="task-icon-link">
                <img src="/static/b33030df/images/24x24/clock.png"
                style="width: 24px; height: 24px; width: 24px; height: 24px; margin: 2px;"
                class="icon-clock icon-md"></a></p>
            </div>\
            """.stripIndent()
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            /* Check common params */
            ValidationHelper.checkBuildConfiguration(script)

            def checkParams = []

            def androidChannels = channelsToRun?.findAll { it.contains('ANDROID') }
            /* Check Android specific params */
            if (androidChannels) {
                def androidMandatoryParams = ['ANDROID_VERSION', 'ANDROID_VERSION_CODE']

                if (androidChannels.findAll { it.contains('MOBILE') }) {
                    androidMandatoryParams.add('ANDROID_MOBILE_APP_ID')
                }

                if (androidChannels.findAll { it.contains('TABLET') }) {
                    androidMandatoryParams.add('ANDROID_TABLET_APP_ID')
                }

                if (keystoreFileID || keystorePasswordID || privateKeyPassword || keystoreAlias) {
                    androidMandatoryParams.addAll([
                            'ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD', 'ANDROID_KEY_ALIAS'
                    ])
                }

                checkParams.addAll(androidMandatoryParams)
            }

            def iosChannels = channelsToRun?.findAll { it.contains('IOS') }
            /* Check iOS specific params */
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

            /* Check all required parameters depending on user input */
            ValidationHelper.checkBuildConfiguration(script, checkParams)
        }

        script.node(nodeLabel) {
            prepareRun()

            try {
                script.parallel(runList)

                if (availableTestPools) {
                    def testAutomationJobParameters = getTestAutomationJobParameters() ?:
                            script.error("runTests job parameters are missing!")
                    def testAutomationJobBinaryParameters = (getTestAutomationJobBinaryParameters(artifacts)) ?:
                            script.error("runTests job binary URL parameters are missing!")

                    script.stage('TESTS') {
                        def testAutomationJobName = "${script.env.JOB_NAME - script.env.JOB_BASE_NAME - 'Builds/'}Tests/runTests"
                        def testAutomationJob = script.build job: testAutomationJobName,
                                parameters: testAutomationJobParameters + testAutomationJobBinaryParameters,
                                propagate: false
                        def testAutomationJobResult = testAutomationJob.currentResult

                        jobResultList.add(testAutomationJobResult)

                        if (testAutomationJobResult != 'SUCCESS') {
                            script.echo "Status of the runTests job: ${testAutomationJobResult}"
                        }
                    }
                }

                if (jobResultList.contains('FAILURE') ||
                        jobResultList.contains('UNSTABLE') ||
                        jobResultList.contains('ABORTED')
                ) {
                    script.currentBuild.result = 'UNSTABLE'
                } else {
                    script.currentBuild.result = 'SUCCESS'
                }
            } catch (Exception e) {
                String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                script.echo "ERROR: $exceptionMessage"
                script.currentBuild.result = 'FAILURE'
            } finally {
                setBuildDescription()
                if (channelsToRun && script.currentBuild.result != 'FAILURE') {
                    NotificationsHelper.sendEmail(script, 'buildVisualizerApp', [artifacts: artifacts], true)
                }
            }
        }
    }
}
