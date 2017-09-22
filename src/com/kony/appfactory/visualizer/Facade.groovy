package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.NotificationsHelper

class Facade implements Serializable {
    private script
    private environment
    private nodeLabel = 'master'
    private runList = [:]
    private channelsToRun
    private projectName
    private artifacts = []
    private jobResultList = []
    private s3BaseURL

    Facade(script) {
        this.script = script
        projectName = this.script.env.PROJECT_NAME
        environment = this.script.params.FABRIC_ENVIRONMENT_NAME
        s3BaseURL = AWSHelper.getS3ArtifactURL(this.script, ['Builds', environment].join('/'))
    }

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
        channelName.tokenize('_')[1]
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
            /* Workaround for windows phone jobs */
            if (item.contains('WINDOWSPHONE')) {
                item.replaceAll('WINDOWSPHONE', 'WindowsPhone')
                /* Workaround for SPA jobs */
            } else if (item.contains('SPA')) {
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
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH', value: "${script.params.PROJECT_SOURCE_CODE_BRANCH}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', value: "${script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID}"),
                script.string(name: 'BUILD_MODE', value: "${script.params.BUILD_MODE}"),
                script.credentials(name: 'CLOUD_CREDENTIALS_ID', value: "${script.params.CLOUD_CREDENTIALS_ID}"),
                script.credentials(name: 'FABRIC_APP_CONFIG', value: "${script.params.FABRIC_APP_CONFIG}"),
                script.string(name: 'FABRIC_URL', value: "${script.params.FABRIC_URL}"),
                script.string(name: 'FABRIC_APP_NAME', value: "${script.params.FABRIC_APP_NAME}"),
                script.string(name: 'FABRIC_ACCOUNT_ID', value: "${script.params.FABRIC_ACCOUNT_ID}"),
                script.string(name: 'FABRIC_ENVIRONMENT_NAME', value: "${environment}"),
                script.booleanParam(name: 'PUBLISH_FABRIC_APP', value: script.params.PUBLISH_FABRIC_APP),
                script.string(name: 'RECIPIENTS_LIST', value: "${script.params.RECIPIENTS_LIST}")
        ]
    }

    private final getSpaChannelJobBuildParameters(spaChannelsToBuildJobParameters) {
        getCommonJobBuildParameters() + spaChannelsToBuildJobParameters
    }

    private final getNativeChannelJobBuildParameters(channelName, channelFormFactor) {
        def channelJobParameters
        def commonParameters = getCommonJobBuildParameters()

        switch (channelName) {
            case ~/^.*ANDROID.*$/:
                channelJobParameters = commonParameters + [
                        script.credentials(name: 'ANDROID_KEYSTORE_FILE', value: "${script.params.ANDROID_KEYSTORE_FILE}"),
                        script.credentials(name: 'ANDROID_KEYSTORE_PASSWORD', value: "${script.params.ANDROID_KEYSTORE_PASSWORD}"),
                        script.credentials(name: 'ANDROID_KEY_PASSWORD', value: "${script.params.ANDROID_KEY_PASSWORD}")
                ]
                break
            case ~/^.*IOS.*$/:
                channelJobParameters = commonParameters + [
                        script.credentials(name: 'APPLE_ID', value: "${script.params.APPLE_ID}"),
                        script.string(name: 'APPLE_DEVELOPER_TEAM_ID', value: "${script.params.APPLE_DEVELOPER_TEAM_ID}"),
                        script.string(name: 'APPLE_DEVELOPER_PROFILE_TYPE', value: "${script.params.APPLE_DEVELOPER_PROFILE_TYPE}")
                ]
                break
            default:
                channelJobParameters = commonParameters
                break
        }

        channelJobParameters + [
                script.string(name: 'FORM_FACTOR', value: channelFormFactor)
        ]
    }

    private final getTestAutomationJobParameters() {
        def parameters = [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH', value: "${script.params.PROJECT_SOURCE_CODE_BRANCH}"),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', value: "${script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID}"),
                script.string(name: 'TESTS_BINARY_URL', value: ''),
                script.string(name: 'AVAILABLE_TEST_POOLS', value: "${script.params.AVAILABLE_TEST_POOLS}")
        ]

        parameters
    }

    private final getArtifactObjects(channelPath, artifactNames) {
        def artifactObjectsList = []

        if (!artifactNames) {
            artifactObjectsList.add([name: '', url: '', channelPath: channelPath])
        } else {
            def names = artifactNames.tokenize(',')

            for (name in names) {
                String artifactURL = [s3BaseURL, channelPath, name].join('/')
                artifactObjectsList.add([name: name, url: artifactURL, channelPath: channelPath])
            }
        }

        artifactObjectsList
    }

    private final getTestAutomationJobBinaryParameters(buildJobArtifacts) {
        def binaryParameters = []

        for (buildJobArtifact in buildJobArtifacts) {
            def artifactName = (!buildJobArtifact['name'].contains('.plist')) ?: buildJobArtifact['name'].replaceAll('.plist', '.ipa')
            def artifactURL = buildJobArtifact.url
            def channelName = buildJobArtifact.channelPath.toUpperCase().replaceAll('/', '_')
            if (artifactName != '-') {
                binaryParameters.add(script.stringParam(name: "${channelName}_BINARY_URL", value: artifactURL))
            }
        }

        binaryParameters
    }

    private final void prepareRun() {
        /* Checking if at least one channel been selected */
        channelsToRun = (getSelectedChannels(script.params)) ?:
                script.error('Please choose at least one channel to build!')
        /* Filter Native channels */
        def nativeChannelsToRun = getNativeChannels(channelsToRun)
        /* Filter SPA channels */
        def spaChannelsToRun = getSpaChannels(channelsToRun)

        for (item in nativeChannelsToRun) {
            def channelName = item
            def channelJobName = (getChannelJobName(channelName)) ?:
                    script.error("Channel job name can't be null")
            def channelFormFactor = (getChannelFormFactor(channelName)) ?:
                    script.error("Channel form factor can't be null")
            def channelJobBuildParameters = (getNativeChannelJobBuildParameters(channelName, channelFormFactor)) ?:
                    script.error("Channel job build parameters list can't be null")
            def channelPath = getChannelPath(channelName)

            runList[channelName] = {
                script.stage(channelName) {
                    /* Trigger channel job */
                    def channelJob = script.build job: channelJobName, parameters: channelJobBuildParameters,
                            propagate: false
                    /* Collect job results */
                    jobResultList.add(channelJob.currentResult)
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
            <p>Environment: ${environment}</p>
            <p>Rebuild: <a href='${script.env.BUILD_URL}rebuild' class="task-icon-link">
            <img src="/static/b33030df/images/24x24/clock.png"
            style="width: 24px; height: 24px; width: 24px; height: 24px; margin: 2px;"
            class="icon-clock icon-md"></a></p>
            """.stripIndent()
    }

    protected final void run() {
        script.node(nodeLabel) {
            prepareRun()

            try {
                script.parallel(runList)

                if (script.params.AVAILABLE_TEST_POOLS) {
                    def testAutomationJobParameters = getTestAutomationJobParameters() ?:
                            script.error("runTests job parameters are missing!")
                    def testAutomationJobBinaryParameters = (getTestAutomationJobBinaryParameters(artifacts)) ?:
                            script.error("runTests job binary URL parameters are missing!")

                    script.stage('TESTS') {
                        def testAutomationJob = script.build job: "${script.env.JOB_NAME - script.env.JOB_BASE_NAME - 'Builds/'}Tests/runTests",
                                parameters: testAutomationJobParameters + testAutomationJobBinaryParameters,
                                propagate: false
                        def testAutomationJobResult = testAutomationJob.currentResult

                        jobResultList.add(testAutomationJobResult)

                        if (testAutomationJobResult != 'SUCCESS') {
                            script.echo "Status of the runTests job: ${testAutomationJobResult}"
                        }
                    }
                }

                if (jobResultList.contains('FAILURE') || jobResultList.contains('UNSTABLE') || jobResultList.contains('ABORTED')) {
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
