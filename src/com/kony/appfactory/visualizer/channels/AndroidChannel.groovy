package com.kony.appfactory.visualizer.channels

class AndroidChannel extends Channel {
    private androidHome

    /* Build parameters */
    private String keystoreFileID = script.params.KS_FILE
    private String keystorePasswordID = script.params.KS_PASSWORD
    private String privateKeyPassword = script.params.PRIVATE_KEY_PASSWORD

    AndroidChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
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
            workspace = script.env.WORKSPACE
            projectFullPath = (isUnixNode) ? workspace + '/' + projectName :
                    workspace + '\\' + projectName
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
                    def foundArtifacts = getArtifacts(artifactExtension)
                    /* Rename artifacts for publishing */
                    artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) : script.error('FAILED build artifacts are missing!')
                }

                if (artifactExtension != 'war') {
                    script.stage("Sign artifact") {
                        for (artifact in artifacts) {
                            signArtifact(artifact.name, artifact.path)
                        }
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
