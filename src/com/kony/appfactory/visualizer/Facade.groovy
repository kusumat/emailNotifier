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
        setBuildParameters()
    }

    @NonCPS
    private final void setBuildParameters() {
        script.properties([
                script.parameters([
                        script.stringParam(name: 'PROJECT_NAME', defaultValue: script.params.PROJECT_NAME ?: '', description: 'Project Name'),
                        script.stringParam(name: 'GIT_URL', defaultValue: script.params.GIT_URL ?: '', description: 'Project Git URL'),
                        script.stringParam(name: 'GIT_TEST_URL', defaultValue: script.params.GIT_TEST_URL ?: '', description: 'Project Tests Git URL'),
                        script.stringParam(name: 'GIT_BRANCH', defaultValue: script.params.GIT_BRANCH ?: '', description: 'Project Git Branch'),
                        [$class: 'CredentialsParameterDefinition', name: 'GIT_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: script.params.GIT_CREDENTIALS_ID ?: '', description: 'GitHub.com Credentials', required: true],
                        script.stringParam(name: 'MAIN_BUILD_NUMBER', defaultValue: script.params.MAIN_BUILD_NUMBER ?: '', description: 'Build Number for artifact'),
                        script.choice(name: 'BUILD_MODE', choices: "debug\nrelease", defaultValue: script.params.BUILD_MODE ?: '', description: 'Choose build mode (debug or release)'),
                        script.choice(name: 'ENVIRONMENT', choices: "dev\nqa\nrelease", defaultValue: script.params.ENVIRONMENT ?: 'dev', description: 'Define target environment'),
                        [$class: 'CredentialsParameterDefinition', name: 'KS_FILE', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: script.params.KS_FILE ?: '', description: 'Private key and certificate chain reside in the given Java-based KeyStore file', required: false],
                        [$class: 'CredentialsParameterDefinition', name: 'KS_PASSWORD', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: script.params.KS_PASSWORD ?: '', description: 'The password for the KeyStore', required: false],
                        [$class: 'CredentialsParameterDefinition', name: 'PRIVATE_KEY_PASSWORD', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: script.params.PRIVATE_KEY_PASSWORD ?: '', description: 'The password for the private key', required: false],
                        script.stringParam(name: 'VIZ_VERSION', defaultValue: script.params.VIZ_VERSION ?: '7.2.1', description: 'Kony Vizualizer version'),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: script.params.CLOUD_CREDENTIALS_ID ?: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam(name: 'S3_BUCKET_NAME', defaultValue: script.params.S3_BUCKET_NAME ?: '', description: 'S3 Bucket Name'),
                        [$class: 'CredentialsParameterDefinition', name: 'APPLE_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: script.params.APPLE_ID ?: '', description: 'Apple ID credentials', required: true],
                        [$class: 'CredentialsParameterDefinition', name: 'MATCH_PASSWORD', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: script.params.MATCH_PASSWORD ?: '', description: 'The Encryption password', required: false],
                        [$class: 'CredentialsParameterDefinition', name: 'MATCH_GIT_TOKEN', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: script.params.MATCH_GIT_TOKEN ?: '', description: 'GitHub access token', required: false],
                        script.stringParam(name: 'MATCH_GIT_URL', defaultValue: script.params.MATCH_GIT_URL ?: '', description: 'URL to the git repo containing all the certificates (On-premises only!)'),
                        script.choice(name: 'MATCH_TYPE', choices: "appstore\nadhoc\ndevelopment\nenterprise", defaultValue: script.params.MATCH_TYPE ?: 'development', description: 'Define the signing profile type'),
                        script.booleanParam(name: 'TEST_AUTOMATION_ENABLED', defaultValue: false, description: 'Select the box if you want to run Tests against your builds on AWS DeviceFarm (Android and iOS only!)'),
                        script.booleanParam(name: 'ANDROID_MOBILE_NATIVE', defaultValue: false, description: 'Select the box if your build is for Android phones'),
                        script.booleanParam(name: 'ANDROID_MOBILE_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for Android phones'),
                        script.booleanParam(name: 'ANDROID_TABLET_NATIVE', defaultValue: false, description: 'Select the box if your build is for Android tablets'),
                        script.booleanParam(name: 'ANDROID_TABLET_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for Android tablets'),
                        script.booleanParam(name: 'APPLE_WATCH_NATIVE', defaultValue: false, description: 'Select the box if your build is for watchOS watches'),
                        script.booleanParam(name: 'APPLE_MOBILE_NATIVE', defaultValue: false, description: 'Select the box if your build is for iOS phones'),
                        script.booleanParam(name: 'APPLE_MOBILE_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for iOS phones'),
                        script.booleanParam(name: 'APPLE_TABLET_NATIVE', defaultValue: false, description: 'Select the box if your build is for iOS tablets'),
                        script.booleanParam(name: 'APPLE_TABLET_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for iOS tablets'),
                        script.booleanParam(name: 'WINDOWS_MOBILE_WINDOWSPHONE8', defaultValue: false, description: 'Select the box if your build is for Windows 8 phones'),
                        script.booleanParam(name: 'WINDOWS_MOBILE_WINDOWSPHONE81S', defaultValue: false, description: 'Select the box if your build is for Windows 8.1s phones'),
                        script.booleanParam(name: 'WINDOWS_MOBILE_WINDOWSPHONE10', defaultValue: false, description: 'Select the box if your build is for Windows 10 phones'),
                        script.booleanParam(name: 'WINDOWS_MOBILE_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for windows phones'),
                        script.booleanParam(name: 'WINDOWS_TABLET_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for windows tablets'),
                        script.booleanParam(name: 'WINDOWS_DESKTOP_WINDOWS81', defaultValue: false, description: 'Select the box if your build is for Windows 8.1s desktop'),
                        script.booleanParam(name: 'WINDOWS_DESKTOP_WINDOWS10', defaultValue: false, description: 'Select the box if your build is for Windows 10 desktop'),
                        script.booleanParam(name: 'BLACKBERRY_MOBILE_SPA', defaultValue: false, description: 'elect the box if your build is for single page application for Blackberry'),
                        script.booleanParam(name: 'BLACKBERRY_MOBILE_HYBRID_SPA', defaultValue: false, description: 'Select the box if your build is for single page application for Blackberry hybrid'),
                        script.booleanParam(name: 'KIOSK_DESKTOP_NATIVE', defaultValue: false, description: 'SSelect the box if your build is for Kiosk desktop'),
                        script.booleanParam(name: 'WEB_DESKTOP_NATIVE', defaultValue: false, description: 'Select the box if your build is for Web desktop')
                ])
        ])
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
                script.stringParam(name: 'PROJECT_NAME', description: 'Project Name', value: "${script.params.PROJECT_NAME}"),
                script.stringParam(name: 'GIT_URL', description: 'Project Git URL', value: "${script.params.GIT_URL}"),
                script.stringParam(name: 'GIT_BRANCH', description: 'Project Git Branch', value: "${script.params.GIT_BRANCH}"),
                [$class: 'CredentialsParameterValue', description: 'GitHub.com Credentials', name: 'GIT_CREDENTIALS_ID', value: "${script.params.GIT_CREDENTIALS_ID}"],
                script.stringParam(name: 'MAIN_BUILD_NUMBER', description: 'Build Number for artifact', value: "${script.params.MAIN_BUILD_NUMBER}"),
                script.stringParam(name: 'BUILD_MODE', description: 'Build mode (debug or release)', value: "${script.params.BUILD_MODE}"),
                script.stringParam(name: 'ENVIRONMENT', description: 'Define target environment', value: "${script.params.ENVIRONMENT}"),
                script.stringParam(name: 'VIZ_VERSION', description: 'Kony Vizualizer version', value: "${script.params.VIZ_VERSION}"),
                [$class: 'CredentialsParameterValue', name: 'CLOUD_CREDENTIALS_ID', description: 'Cloud Mode credentials (Applicable only for cloud)', value: "${script.params.CLOUD_CREDENTIALS_ID}"],
                script.stringParam(name: 'S3_BUCKET_NAME', description: 'S3 Bucket Name', value: "${script.params.S3_BUCKET_NAME}")
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

    private final void prepareRun() {
        channelsToRun = getSelectedChannels(script.params)

        if (channelsToRun) {
            for (x in channelsToRun) {
                /* Need to bind the channel variable before the closure - can't do 'for (channel in channelsToRun)' */
                def channel = x
                def jobParameters = getJobParameters(channel)
                script.env[channel] = true
                channelsLogicToRun[channel] = {
                    script.stage(channel) {
                        script.build job: "${environment}/${channel.toLowerCase().replaceAll('_', '/')}", parameters: jobParameters
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
