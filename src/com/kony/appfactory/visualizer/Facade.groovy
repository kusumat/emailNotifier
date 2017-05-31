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

    Facade(script) {
        this.script = script
        projectName = this.script.env.PROJECT_NAME
        environment = this.script.params.ENVIRONMENT
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
        String s3ArtifactURL = 'https://' + s3BucketRegion + '.amazonaws.com/' + "${s3BucketName}/${projectName}/${environment}"
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
                script.stringParam(name: 'MAIN_BUILD_NUMBER', description: 'Build Number for artifact', value: "${script.params.MAIN_BUILD_NUMBER}"),
                script.stringParam(name: 'BUILD_MODE', description: 'Build mode (debug or release)', value: "${script.params.BUILD_MODE}"),
                script.stringParam(name: 'ENVIRONMENT', description: 'Define target environment', value: "${script.params.ENVIRONMENT}"),
                script.stringParam(name: 'VIS_VERSION', description: 'Kony Visualizer version', value: "${script.params.VIS_VERSION}"),
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
                    [$class: 'CredentialsParameterValue', name: 'MATCH_PASSWORD', description: 'The Encryption password', value: "${script.params.MATCH_PASSWORD}"],
                    [$class: 'CredentialsParameterValue', name: 'MATCH_GIT_TOKEN', description: 'GitHub access token', value: "${script.params.MATCH_GIT_TOKEN}"],
                    script.stringParam(name: 'MATCH_GIT_URL', description: 'URL to the git repo containing all the certificates (On-premises only!)', value: "${script.params.MATCH_GIT_URL}"),
                    script.stringParam(name: 'MATCH_TYPE', description: 'Define the signing profile type', value: "${script.params.MATCH_TYPE}")
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
                        script.build job: "${environment}/${channelPath}", parameters: jobParameters
                    }
                }
            }
        }

        if (script.params.TEST_AUTOMATION) {
            runList['TEST_AUTOMATION'] = {
                script.stage('TEST_AUTOMATION') {
                    script.build job: "${environment}/Test_Automation", parameters: getTestAutomationJobParameters()
                }
            }
        }

    }

    protected final void run() {
        prepareRun()

        script.node(nodeLabel) {
            try {
                script.parallel(runList)
            } catch (Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                if (script.currentBuild.result != 'FAILURE' && channelsToRun) {
                    script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', 'KonyAppFactoryTeam@softserveinc.com')
                }
            }
        }
    }
}
