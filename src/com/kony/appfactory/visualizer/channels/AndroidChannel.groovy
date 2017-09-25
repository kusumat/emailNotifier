package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class AndroidChannel extends Channel {
    /* Build parameters */
    private String keystoreFileID = script.env.ANDROID_KEYSTORE_FILE
    private String keystorePasswordID = script.env.ANDROID_KEYSTORE_PASSWORD
    private String privateKeyPassword = script.env.ANDROID_KEY_PASSWORD
    private String keystoreAlias = script.env.ANDROID_KEY_ALIAS

    AndroidChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
    }

    private final void signArtifacts(buildArtifacts) {
        String successMessage = 'Artifact signed successfully'
        String errorMessage = 'FAILED to sign artifact'
        String signer = 'jarsigner'
        String androidBuildToolsPath = (visualizerDependencies.find { it.variableName == 'ANDROID_BUILD_TOOLS'} ?.homePath) ?:
                script.error('Android build tools path is missing!')
        String javaBinPath = (visualizerDependencies.find { it.variableName == 'JAVA_HOME' } ?.binPath) ?:
                script.error('Java binaries path is missing!')

        script.catchErrorCustom(errorMessage, successMessage) {
            for (artifact in buildArtifacts) {
                script.dir(artifact.path) {
                    script.withEnv(["PATH+TOOLS=${javaBinPath}${pathSeparator}${androidBuildToolsPath}"]) {
                        if (buildMode == 'release') {
                            def finalArtifactName = artifact.name.replaceAll('unsigned', 'aligned')
                            script.withCredentials([
                                    script.file(credentialsId: "${keystoreFileID}", variable: 'KSFILE'),
                                    script.string(credentialsId: "${keystorePasswordID}", variable: 'KSPASS'),
                                    script.string(credentialsId: "${privateKeyPassword}", variable: 'KEYPASS')
                            ]) {
                                script.shellCustom(
                                        [signer, '-verbose', '-sigalg', 'SHA1withRSA', '-digestalg', 'SHA1',
                                         '-keystore', "${script.env.KSFILE}",
                                         '-storepass', "${script.env.KSPASS}",
                                         '-keypass', "${script.env.KEYPASS}", artifact.name, keystoreAlias].join(' '),
                                        isUnixNode
                                )

                                script.shellCustom(
                                        [signer, '-verify -certs', artifact.name, keystoreAlias].join(' '),
                                        isUnixNode
                                )

                                script.shellCustom(
                                        ['zipalign', '-v 4', artifact.name, finalArtifactName].join(' '),
                                        isUnixNode
                                )

                                script.shellCustom(
                                        ['zipalign', '-c -v 4', finalArtifactName].join(' '),
                                        isUnixNode
                                )

                                artifact.name = finalArtifactName
                            }
                        } else {
                            script.println "Build mode is $buildMode, skipping signing!"
                        }
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
                    /* Search for build artifacts */
                    buildArtifacts = (getArtifactLocations(artifactExtension)) ?:
                            script.error('Build artifacts were not found!')
                }

                script.stage("Sign artifacts") {
                    signArtifacts(buildArtifacts)
                }

                script.stage("Publish artifacts to S3") {
                    /* Rename artifacts for publishing */
                    artifacts = renameArtifacts(buildArtifacts)

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