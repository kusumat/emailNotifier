package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

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
        String keyGenCommand = 'keytool -genkey -noprompt' +
                ' -alias androiddebugkey' +
                ' -dname "CN=Android Debug,O=Android,C=US"' +
                ' -keystore debug.keystore' +
                ' -storepass android' +
                ' -keypass android' +
                ' -keyalg RSA' +
                ' -keysize 2048' +
                ' -validity 10000'
        String javaBin = (visualizerDependencies.find { it.variableName == 'JAVA_HOME' } ?.binPath) ?:
                script.error('Java binaries path is missing!')

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifactPath) {
                script.withEnv(["PATH+TOOLS=${javaBin}"]) {
                    if (isUnixNode) {
                        if (buildMode == 'release') {
                            withKeyStore() {
                                script.sh apksigner +
                                        ' sign --ks $KSFILE --ks-pass pass:$KSPASS --key-pass pass:$KEYPASS ' +
                                        artifactName
                            }
                        } else {
                            script.sh keyGenCommand
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
                            script.bat keyGenCommand
                            script.bat debugSingCommand
                        }

                        script.bat "${apksigner} verify --verbose ${artifactName}"
                    }
                }
            }
        }
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            pipelineWrapper {
                script.deleteDir()

                script.stage('Checkout') {
                    BuildHelper.checkoutProject script: script,
                            projectName: projectName,
                            gitBranch: gitBranch,
                            gitCredentialsID: gitCredentialsID,
                            gitURL: gitURL
                }

                script.stage('Build') {
                    build()
                    /* Setting android home variable */
                    androidHome = script.readProperties(
                            file: projectFullPath + '/HeadlessBuild-Global.properties').'android.home'
                    /* Search for build artifacts */
                    def foundArtifacts = getArtifacts(artifactExtension)
                    /* Rename artifacts for publishing */
                    artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) :
                            script.error('FAILED build artifacts are missing!')

                }

                if (artifactExtension != 'war') {
                    script.stage("Sign artifact") {
                        for (artifact in artifacts) {
                            signArtifact(artifact.name, artifact.path)
                        }
                    }
                }

                script.stage("Publish artifacts to S3") {
                    /* Create a list with artifact names and upload them */
                    def channelArtifacts = []

                    for (artifact in artifacts) {
                        channelArtifacts.add(artifact.name)

                        AWSHelper.publishToS3 script: script, bucketPath: s3ArtifactPath, exposeURL: true,
                                sourceFileName: artifact.name, sourceFilePath: artifact.path
                    }

                    script.env['CHANNEL_ARTIFACTS'] = channelArtifacts.join(',')
                }
            }
        }
    }
}
