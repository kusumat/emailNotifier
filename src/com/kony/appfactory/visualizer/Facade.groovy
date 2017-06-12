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
    private triggeredBy
    private artifacts = ''
    private isStageFailed = []

    Facade(script) {
        this.script = script
        projectName = this.script.env.PROJECT_NAME
        environment = this.script.env.ENVIRONMENT
        s3BucketRegion = this.script.env.S3_BUCKET_REGION
        s3BucketName = this.script.env.S3_BUCKET_NAME
        setS3ArtifactURL()
        /* Get build cause for e-mail notification */
        getBuildCause()
        this.script.env['TRIGGERED_BY'] = "${triggeredBy}"
    }

    @NonCPS
    private final void getBuildCause() {
        def causes = []
        def buildCauses = script.currentBuild.rawBuild.getCauses()

        for (cause in buildCauses) {
            if (cause instanceof hudson.model.Cause$UpstreamCause) {
                causes.add('upstream')
                triggeredBy = cause.getUpstreamRun().getCause(hudson.model.Cause.UserIdCause).getUserName()
            } else if (cause instanceof hudson.model.Cause$RemoteCause) {
                causes.add('remote')
            } else if (cause instanceof hudson.model.Cause$UserIdCause) {
                causes.add('user')
                triggeredBy = cause.getUserName()
            } else {
                causes.add('unknown')
            }
        }
    }

    @NonCPS
    private final void setS3ArtifactURL() {
        String s3ArtifactURL = 'https://' + 's3-' + s3BucketRegion + '.amazonaws.com/' + "${s3BucketName}/${projectName}/${environment}"
        script.env['S3_ARTIFACT_URL'] = s3ArtifactURL
    }

    private static getSelectedChannels(buildParams) {
        def result = []

        for (param in buildParams) {
            if (param.value instanceof Boolean && param.key != 'TEST_AUTOMATION' && param.value) {
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
        } else if (channel.startsWith('APPLE')) {
            parameters = parameters + [
                    [$class: 'CredentialsParameterValue', name: 'APPLE_ID', description: 'Apple ID credentials',  value: "${script.params.APPLE_ID}"],
                    script.stringParam(name: 'APPLE_DEVELOPER_PROFILE_TYPE', description: 'Define the signing profile type', value: "${script.params.APPLE_DEVELOPER_PROFILE_TYPE}")
            ]
        }

        parameters
    }

    private final getTestAutomationJobParameters() {
        def parameters = [
                script.stringParam(name: 'GIT_BRANCH', description: 'Project Git Branch', value: "${script.params.GIT_BRANCH}"),
                [$class: 'CredentialsParameterValue', description: 'GitHub.com Credentials', name: 'GIT_CREDENTIALS_ID', value: "${script.params.GIT_CREDENTIALS_ID}"]
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
            } else {
                item.toLowerCase().capitalize()
            }
        }.join('/')
        return channelPath
    }

    private final void prepareRun() {
        channelsToRun = getSelectedChannels(script.params)

        /* Check if at least one of the options been chosen */
        if (!channelsToRun && !script.params.TEST_AUTOMATION) {
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
                        def channelJob = script.build job: "${environment}/${channelPath}", parameters: jobParameters, propagate: false
                        /* Collect job statuses */
                        isStageFailed.add(channelJob.currentResult)
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

        if (script.params.TEST_AUTOMATION) {
            runList['TEST_AUTOMATION'] = {
                script.stage('TEST_AUTOMATION') {
                    def testAutomationJob = script.build job: "${environment}/Test_Automation", parameters: getTestAutomationJobParameters(), propagate: false
                    isStageFailed.add(testAutomationJob.currentResult)
                    script.echo "Status of the channel TEST_AUTOMATION build is: ${testAutomationJob.currentResult}"
                }
            }
        }

    }

    protected final void run() {
        prepareRun()

        script.node(nodeLabel) {
            try {
                script.parallel(runList)
                script.env['CHANNEL_ARTIFACTS'] = artifacts
                if (isStageFailed.contains('FAILURE') || isStageFailed.contains('UNSTABLE')) {
                    script.currentBuild.result = 'UNSTABLE'
                } else {
                    script.currentBuild.result = 'SUCCESS'
                }
            } catch (Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                if (channelsToRun) {
                    script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', 'KonyAppFactoryTeam@softserveinc.com')
                }
            }
        }
    }
}
