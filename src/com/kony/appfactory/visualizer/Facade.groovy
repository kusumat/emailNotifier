package com.kony.appfactory.visualizer

class Facade implements Serializable {
    private script
    private environment
    private nodeLabel = 'master'
    private runList = [:]
    private channelsToRun
    private s3BucketName
    private s3BucketRegion
    private projectName
    private artifacts = ''
    private jobResultList = []
    private recipientList

    Facade(script) {
        this.script = script
        projectName = this.script.env.PROJECT_NAME
        recipientList = this.script.env.RECIPIENT_LIST
        environment = this.script.params.ENVIRONMENT
        s3BucketRegion = this.script.env.S3_BUCKET_REGION
        s3BucketName = this.script.env.S3_BUCKET_NAME
        setS3ArtifactURL()
        /* Get build cause for e-mail notification */
        this.script.env['TRIGGERED_BY'] = getBuildCause()
    }

    @NonCPS
    private final getRootCause(cause) {
        def causedBy = null

        if (cause.class.toString().contains('UpstreamCause')) {
            for (upCause in cause.upstreamCauses) {
                causedBy = getRootCause(upCause)
            }
        } else {
            switch (cause.class.toString()) {
                case ~/^.*UserIdCause.*$/:
                    causedBy = cause.getUserName()
                    break
                case ~/^.*SCMTriggerCause.*$/:
                    causedBy = 'SCM'
                    break
                case ~/^.*TimerTriggerCause.*$/:
                    causedBy = 'CRON'
                    break
                case ~/^.*GitHubPushCause.*$/:
                    causedBy = 'GitHub hook'
                    break
                default:
                    break
            }
        }

        causedBy
    }

    @NonCPS
    private final getBuildCause() {
        def buildCauses = script.currentBuild.rawBuild.getCauses()
        def causedBy

        for (cause in buildCauses) {
            causedBy = getRootCause(cause)
        }

        causedBy
    }

    @NonCPS
    private final void setS3ArtifactURL() {
        String s3ArtifactURL = 'https://' + 's3-' + s3BucketRegion + '.amazonaws.com/' + "${s3BucketName}/${projectName}/Builds/${environment}"
        script.env['S3_ARTIFACT_URL'] = s3ArtifactURL
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
                [$class: 'CredentialsParameterValue', name: 'CLOUD_CREDENTIALS_ID', description: 'Cloud Mode credentials (Applicable only for cloud)', value: "${script.params.CLOUD_CREDENTIALS_ID}"]
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
                    script.stringParam(name: 'APPLE_DEVELOPER_PROFILE_TYPE', description: 'Define the signing profile type', value: "${script.params.APPLE_DEVELOPER_PROFILE_TYPE}")
            ]
        }

        parameters
    }

    private final getBinariesURL() {
        def binaries = []
        def artifactNamesMap = script.env.CHANNEL_ARTIFACTS.split(',')

        for (channel in channelsToRun) {
            if (channel.contains('ANDROID') || channel.contains('IOS')) {
                def channelPath = getChannelPath(channel)
                for (item in artifactNamesMap) {
                    def artifactList = item.split(':')
                    if (channelPath in artifactList) {
                        if (artifactList[1] != '-') {
                            def artifactName = (artifactList[1].contains('.plist')) ? artifactList[1].replaceAll('.plist', '.ipa') : artifactList[1]
                            binaries.add(script.stringParam(name: "${channel}_BINARY_URL", value: "${script.env.S3_ARTIFACT_URL}/${channelPath}/${artifactName}"))
                        }
                    }
                }
            }
        }

        binaries
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

    @NonCPS
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
        return channelPath
    }

    private final void prepareRun() {
        channelsToRun = getSelectedChannels(script.params)

        /* Check if at least one of the options been chosen */
        if (!channelsToRun) {
            script.error 'Please choose options to build!'
        }

        if (channelsToRun) {
            for (x in channelsToRun) {
                /* Need to bind the channel variable before the closure - can't do 'for (channel in channelsToRun)' */
                def channel = x
                def jobParameters = getJobParameters(channel)
                def channelPath = getChannelPath(channel)
                script.env[channel] = true
                runList[channel] = {
                    script.stage(channel) {
                        /* Trigger channel job */
                        def channelJob = script.build job: "${channelPath}", parameters: jobParameters, propagate: false
                        /* Collect job results */
                        jobResultList.add(channelJob.currentResult)
                        if (channelJob.currentResult != 'SUCCESS') {
                            artifacts += "${channelPath}:-,"
                            script.echo "Status of the channel ${channel} build is: ${channelJob.currentResult}"
                        } else {
                            artifacts += channelJob.buildVariables.CHANNEL_ARTIFACTS
                        }
                    }
                }
            }
        }
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
                script.env['CHANNEL_ARTIFACTS'] = artifacts

                if (script.params.AVAILABLE_TEST_POOLS) {
                    def binaries = (getBinariesURL()) ?: script.error("Artifacts binaries were not found!")

                    script.stage('TESTS') {
                        def testAutomationJob = script.build job: "${script.env.JOB_NAME - script.env.JOB_BASE_NAME - 'Builds/'}Tests/runTests",
                                parameters: getTestAutomationJobParameters() + binaries,
                                propagate: false
                        jobResultList.add(testAutomationJob.currentResult)
                        script.echo "Status of the runTests job: ${testAutomationJob.currentResult}"
                    }
                }

                if (jobResultList.contains('FAILURE') || jobResultList.contains('UNSTABLE') || jobResultList.contains('ABORTED')) {
                    script.currentBuild.result = 'UNSTABLE'
                } else {
                    script.currentBuild.result = 'SUCCESS'
                }
            } catch (Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                setBuildDescription()
                if (channelsToRun && script.currentBuild.result != 'FAILURE') {
                    script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', recipientList)
                }
            }
        }
    }
}
