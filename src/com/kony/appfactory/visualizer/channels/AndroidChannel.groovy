package com.kony.appfactory.visualizer.channels

class AndroidChannel extends Channel {
    private androidHome

    /* Build parameters */
    private String keystoreFileID = script.params.KS_FILE
    private String keystorePasswordID = script.params.KS_PASSWORD
    private String privateKeyPassword = script.params.PRIVATE_KEY_PASSWORD

    AndroidChannel(script) {
        super(script)
//        setBuildParameters()
        /* Set build artifact extension, if channel SPA artifact extension should be war */
        artifactsExtension = (channelName.contains('SPA')) ? 'war' :'apk'
        nodeLabel = 'win || mac'
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
                        script.choice(name: 'ENVIRONMENT', choices: "DEV\nQA\nRELEASE", defaultValue: 'dev', description: 'Define target environment'),
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: '', description: 'Private key and certificate chain reside in the given Java-based KeyStore file', name: 'KS_FILE', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the KeyStore', name: 'KS_PASSWORD', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the private key', name: 'PRIVATE_KEY_PASSWORD', required: false],
                        script.stringParam(name: 'VIZ_VERSION', defaultValue: '7.2.1', description: 'Kony Vizualizer version'),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam(name: 'S3_BUCKET_NAME', defaultValue: '', description: 'S3 Bucket Name')
                ])
        ])
    }

    private final void withKeyStore(closure) {
        script.withCredentials([
                script.file(credentialsId: "${keystoreFileID}", variable: 'KSFILE'),
                script.string(credentialsId: "${keystorePasswordID}", variable: 'KSPASS'),
                script.string(credentialsId: "${privateKeyPassword}", variable: 'KEYPASS')
        ]) {
            closure()
        }
    }

    private final void signArtifact(artifactName, artifactPath) {
        String successMessage = 'Artifact signed successfully'
        String errorMessage = 'FAILED to sign artifact'
        String apksigner = androidHome + ((isUnixNode) ? '/build-tools/25.0.0/apksigner' :
                '\\build-tools\\25.0.0\\apksigner.bat')
        String debugSingCommand = "${apksigner} sign --ks debug.keystore --ks-pass pass:android ${artifactName}"

        String keyGenCommandUnix = """\
            keytool -genkey -noprompt \
             -alias androiddebugkey \
             -dname "CN=Android Debug,O=Android,C=US" \
             -keystore debug.keystore \
             -storepass android \
             -keypass android \
             -keyalg RSA \
             -keysize 2048 \
             -validity 10000
        """

        String keyGenCommandWindows = keyGenCommandUnix.replaceAll('\\\\', '^')

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifactPath) {
                if (isUnixNode) {
                    if (buildMode == 'release') {
                        withKeyStore() {
                            script.sh apksigner +
                                    ' sign --ks $KSFILE --ks-pass pass:$KSPASS --key-pass pass:$KEYPASS ' +
                                    artifactName
                        }
                    } else {
                        script.sh keyGenCommandUnix
                        script.sh debugSingCommand
                    }

                    script.sh "${apksigner} verify --verbose ${artifactName}"
                } else {
                    if (buildMode == 'release') {
                        withKeyStore() {
                            script.bat apksigner +
                                    ' sign --ks %KSFILE% --ks-pass pass:%KSPASS% --key-pass pass:%KEYPASS% ' +
                                    artifactName
                        }
                    } else {
                        script.bat keyGenCommandWindows
                        script.bat debugSingCommand
                    }

                    script.bat "${apksigner} verify --verbose ${artifactName}"
                }
            }
        }
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = (isUnixNode) ? workSpace + '/' + projectName :
                    workSpace + '\\' + projectName
            artifactsBasePath = projectFullPath + ((isUnixNode) ? "/binaries" : "\\binaries")

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                    /* Setting android home variable */
                    androidHome = script.readProperties(
                            file: projectFullPath + '/HeadlessBuild-Global.properties').'android.home'
                    /* Search for build artifacts */
                    def foundArtifacts = getArtifacts(artifactsExtension)
                    /* Rename artifacts for publishing */
                    artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) : script.error('FAILED build artifacts are missing!')
                }

                script.stage("Sign artifact") {
                    for (artifact in artifacts) {
                        signArtifact(artifact.name, artifact.path)
                    }
                }

                script.stage("Publish artifacts to S3") {
                    for (artifact in artifacts) {
                        publishToS3 artifactName: artifact.name, artifactPath: artifact.path
                    }
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
