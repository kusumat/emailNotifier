package com.kony.appfactory.visualizer

class Facade implements Serializable {
    private script
    private environment
    private String nodeLabel = 'master'
    private channelsLogicToRun = [:]
    private channelsToRun

    Facade(script) {
        this.script = script
        this.environment = this.script.params.ENVIRONMENT
    }

    private static getSelectedChannels(buildParams) {
        def result = []

        for (param in buildParams) {
            if (param.value instanceof Boolean && param.key != 'TEST_AUTOMATION_ENABLED' && param.value) {
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

        return parameters
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

        if (channelsToRun) {
            for (x in channelsToRun) {
                /* Need to bind the channel variable before the closure - can't do 'for (channel in channelsToRun)' */
                def channel = x
                def jobParameters = getJobParameters(channel)
                def channelPath = getChannelPath(channel)
                script.env[channel] = true
                channelsLogicToRun[channel] = {
                    script.stage(channel) {
                        /* Trigger channel job */
                        script.build job: "${environment}/${channelPath}", parameters: jobParameters
                    }
                }
            }
        } else {
            script.error 'Please choose channels to build!'
        }
    }

    protected final void run() {
        prepareRun()

        script.node(nodeLabel) {
            try {
                script.parallel channelsLogicToRun
            } catch (Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                if (script.currentBuild.result != 'FAILURE') {
                    script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', 'sshepe@softserveinc.com')
                }
            }
        }

//        if (script.params.TEST_AUTOMATION_ENABLED) {
//            script.stage('Prepare properties for HeadlessBuild') {
//                script.node('linux') {
//                    script.step([$class: 'WsCleanup', deleteDirs: true])
//                    script.git branch: "${script.env.GIT_BRANCH}", credentialsId: "${script.env.GIT_CREDENTIALS_ID}", url: "${script.env.GIT_TEST_URL}"
//                    script.sh '/usr/local/maven-3.3.9/bin/mvn clean package -DskipTests=true'
//                    script.sh "cp target/zip-with-dependencies.zip ${script.env.PROJECT_NAME}_TestApp.zip"
//                    script.git branch: "${script.env.GIT_BRANCH}", credentialsId: "${script.env.GIT_CREDENTIALS_ID}", url: "${script.env.GIT_URL}"
//                    script.sh "chmod +x ci_config/TestAutomationScripts/*"
//                    script.sh "./ci_config/TestAutomationScripts/DeviceFarmCLIInit.sh ${script.env.WORKSPACE}/ci_config/DeviceFarmCLI.properties ${script.env.PROJECT_NAME} ${script.env.PROJECT_NAME}_${script.env.BUILD_NUMBER}.apk ${script.env.PROJECT_NAME}_${script.env.BUILD_NUMBER}.ipa s3://${script.env.S3_BUCKET_NAME}/TestApplication"
//                    script.emailext attachLog: true, body: '${FILE,path="index.html"}', subject: '$JOB_NAME - # $BUILD_NUMBER', to: 'KonyAppFactoryTeam@softserveinc.com'
//                }
//            }
//        }
    }
}
