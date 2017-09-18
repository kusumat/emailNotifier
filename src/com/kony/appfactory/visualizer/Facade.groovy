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
    private recipientList
    private s3BaseURL

    Facade(script) {
        this.script = script
        projectName = this.script.env.PROJECT_NAME
        recipientList = this.script.env.RECIPIENT_LIST
        environment = this.script.params.ENVIRONMENT
        s3BaseURL = AWSHelper.getS3ArtifactURL(this.script, ['Builds', environment].join('/'))
    }

    private static getSelectedChannels(buildParams) {
        def result = []

        for (param in buildParams) {
            if (param.value instanceof Boolean && param.value) {
                result.add(param.key)
            }
        }

        result
    }

    private final getJobParameters(channel) {
        def parameters = [
                script.stringParam(name: 'GIT_BRANCH', description: 'Project Git Branch', value: "${script.params.GIT_BRANCH}"),
                [$class: 'CredentialsParameterValue', description: 'GitHub.com Credentials', name: 'GIT_CREDENTIALS_ID', value: "${script.params.GIT_CREDENTIALS_ID}"],
                script.stringParam(name: 'BUILD_MODE', description: 'Build mode (debug or release)', value: "${script.params.BUILD_MODE}"),
                script.stringParam(name: 'ENVIRONMENT', description: 'Define target environment', value: "${environment}"),
                [$class: 'CredentialsParameterValue', name: 'CLOUD_CREDENTIALS_ID', description: 'Cloud Mode credentials (Applicable only for cloud)', value: "${script.params.CLOUD_CREDENTIALS_ID}"],
                script.credentials(name: 'MOBILE_FABRIC_APP_CONFIG', value: "${script.params.MOBILE_FABRIC_APP_CONFIG}")
        ]

        if (channel.startsWith('ANDROID')) {
            parameters = parameters + [
                    [$class: 'CredentialsParameterValue', name: 'KS_FILE', description: 'Private key and certificate chain reside in the given Java-based KeyStore file', value: "${script.params.KS_FILE}"],
                    [$class: 'CredentialsParameterValue', name: 'KS_PASSWORD', description: 'The password for the KeyStore', value: "${script.params.KS_PASSWORD}"],
                    [$class: 'CredentialsParameterValue', name: 'PRIVATE_KEY_PASSWORD', description: 'The password for the private key', value: "${script.params.PRIVATE_KEY_PASSWORD}"]
            ]
        } else if (channel.startsWith('IOS')) {
            parameters = parameters + [
                    [$class: 'CredentialsParameterValue', name: 'APPLE_ID', description: 'Apple ID credentials',  value: "${script.params.APPLE_ID}"],
                    script.stringParam(name: 'APPLE_DEVELOPER_TEAM_ID', value: "${script.params.APPLE_DEVELOPER_TEAM_ID}"),
                    script.stringParam(name: 'APPLE_DEVELOPER_PROFILE_TYPE', description: 'Define the signing profile type', value: "${script.params.APPLE_DEVELOPER_PROFILE_TYPE}")
            ]
        }

        parameters
    }

    private final getTestAutomationJobParameters() {
        def parameters = [
                script.stringParam(name: 'GIT_BRANCH', value: "${script.params.GIT_BRANCH}"),
                [$class: 'CredentialsParameterValue', description: 'GitHub.com Credentials', name: 'GIT_CREDENTIALS_ID', value: "${script.params.GIT_CREDENTIALS_ID}"],
                script.stringParam(name: 'TESTS_BINARY_URL', value: ''),
                script.stringParam(name: 'AVAILABLE_TEST_POOLS', value: "${script.params.AVAILABLE_TEST_POOLS}")
        ]

        parameters
    }

    protected final getArtifactObjectList(channelPath, artifactNames = '') {
        def result = []

        for (artifactName in artifactNames.split(',')) {
            def artifactURL = (!artifactName) ?: [s3BaseURL, channelPath, artifactName].join('/')
            result.add([
                    name: artifactName,
                    url:  artifactURL,
                    channelPath: channelPath
            ])
        }

        result
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
        channelsToRun = getSelectedChannels(script.params)

        if (channelsToRun) {
            for (x in channelsToRun) {
                /* Need to bind the channel variable before the closure - can't do 'for (channel in channelsToRun)' */
                def channel = x
                def jobParameters = getJobParameters(channel)
                def channelPath = getChannelPath(channel)

                runList[channel] = {
                    script.stage(channel) {
                        /* Trigger channel job */
                        def channelJob = script.build job: "${channelPath}", parameters: jobParameters, propagate: false
                        /* Collect job results */
                        jobResultList.add(channelJob.currentResult)
                        if (channelJob.currentResult != 'SUCCESS') {
                            artifacts += getArtifactObjectList(channelPath)
                            script.echo "Status of the channel ${channel} build is: ${channelJob.currentResult}"
                        } else {
                            artifacts += getArtifactObjectList(channelPath, channelJob.buildVariables.CHANNEL_ARTIFACTS)
                        }
                    }
                }
            }
        } else {
            script.error 'Please choose at least one channel to build!'
        }
    }

    @NonCPS
    protected static getChannelPath(channel) {
        def channelPath

        switch (channel) {
            case ~/^.*ANDROID.*$/:
                channelPath = 'buildAndroid'
                break
            case ~/^.*IOS.*$/:
                channelPath = 'buildIos'
                break
            case ~/^.*SPA.*$/:
                channelPath = 'buildSpa'
                break
            case ~/^.*WINDOWS.*$/:
                channelPath = 'buildWindows'
                break
            default:
                channelPath = ''
                break
        }

        channelPath
    }

    protected setBuildDescription() {
        script.currentBuild.description = """\
            <p>Environment: ${environment}</p>
            <p>Rebuild: <a href='${script.env.BUILD_URL}rebuild' class="task-icon-link"><img src="/static/b33030df/images/24x24/clock.png" style="width: 24px; height: 24px; width: 24px; height: 24px; margin: 2px;" class="icon-clock icon-md"></a></p>
        """.stripIndent()
    }

    protected final void run() {
        prepareRun()

        script.node(nodeLabel) {
            try {
                script.parallel(runList)

                if (script.params.AVAILABLE_TEST_POOLS) {
                    def testAutomationJobParameters = getTestAutomationJobParameters() ?: script.error("runTests job parameters are missing!")
                    def testAutomationJobBinaryParameters = (getTestAutomationJobBinaryParameters(artifacts)) ?: script.error("runTests job binary URL parameters are missing!")

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
                script.echo e.getLocalizedMessage()
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
