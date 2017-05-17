package com.kony.appfactory.visualizer.channels

class WindowsChannel extends Channel {
    private String nodeLabel = 'win'
    private artifactsList = [:]
    private String artifactExtension = 'xap'
    private String artifactFileName = projectName + '_' + mainBuildNumber + '.' + artifactExtension

    WindowsChannel(script) {
        super(script)
        setBuildParameters()

        switch (channelName) {
            case 'WINDOWS_MOBILE_WINDOWSPHONE8':
            case 'WINDOWS_MOBILE_WINDOWSPHONE81S':
                artifactsList['WindowsPhone8'] = artifactFileName
                artifactsList['WindowsPhone8_ARM'] = artifactFileName.replaceFirst('_', '_ARM_')
                break
            case 'WINDOWS_MOBILE_WINDOWSPHONE10':
                artifactsList['WindowsPhone10'] = artifactFileName
                artifactsList['WindowsPhone10_ARM'] = artifactFileName.replaceFirst('_', '_ARM_')
                break
            default:
                break
        }
    }

    @NonCPS
    private final void setBuildParameters() {
        script.properties([
                script.parameters([
                        script.stringParam(name: 'PROJECT_NAME', defaultValue: '', description: 'Project Name'),
                        script.stringParam(name: 'GIT_URL', defaultValue: '', description: 'Project Git URL'),
                        script.stringParam(name: 'GIT_BRANCH', defaultValue: '', description: 'Project Git Branch'),
                        [$class: 'CredentialsParameterDefinition', name: 'GIT_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'GitHub.com Credentials', required: true],
                        script.stringParam(name: 'MAIN_BUILD_NUMBER', defaultValue: '', description: 'Build Number for artifact'),
                        script.choice(name: 'BUILD_MODE', choices: "debug\nrelease", defaultValue: '', description: 'Choose build mode (debug or release)'),
                        script.choice(name: 'ENVIRONMENT', choices: "dev\nqa\nrelease", defaultValue: 'dev', description: 'Define target environment'),
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: '', description: 'Private key and certificate chain reside in the given Java-based KeyStore file', name: 'KS_FILE', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the KeyStore', name: 'KS_PASSWORD', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the private key', name: 'PRIVATE_KEY_PASSWORD', required: false],
                        script.stringParam(name: 'VIZ_VERSION', defaultValue: '7.2.1', description: 'Kony Vizualizer version'),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam(name: 'S3_BUCKET_NAME', defaultValue: '', description: 'S3 Bucket Name')
                ])
        ])
    }

    private final void renameArtifacts() {
        String successMessage = 'Artifacts renamed successfully'
        String errorMessage = 'FAILED to rename artifacts'
        def commands = []

        script.catchErrorCustom(successMessage, errorMessage) {
            for (artifact in artifactsList) {
                String artifactName = artifact.key
                String artifactTargetName = artifact.value
                /* Workaround while for each loop not working properly (iterates only over one item) */
                commands.add("rename ${artifactName}.${artifactExtension} ${artifactTargetName}")
            }

            for (x in commands) {
                String command = x
                script.dir(artifactPath) {
                    script.bat script: command
                }
            }
        }
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = workSpace + '\\' + projectName
            artifactPath = projectFullPath + "\\binaries\\windows\\" + script.env.JOB_BASE_NAME.toLowerCase()

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                }
                
                script.stage("Publish artifact to S3") {
                    renameArtifacts()
                    publishToS3 artifact: artifactFileName
                    publishToS3 artifact: "${artifactFileName.replaceFirst('_', '_ARM_')}"
                }
            } catch(Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                if (buildCause == 'user' || script.currentBuild.result == 'FAILURE') {
                    script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', 'KonyAppFactoryTeam@softserveinc.com')
                }
            }
        }
    }
}
