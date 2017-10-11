package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

class AndroidChannel extends Channel {
    /* Build parameters */
    private final keystoreFileID = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordID = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private final keystoreAlias = script.params.ANDROID_KEY_ALIAS
    /* At least one of application id parameters should be set */
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidPackageName = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
            androidMobileAppId : androidTabletAppId

    AndroidChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
        channelOs = 'Android'
        channelType = 'Native'
        /* Expose Android package name to environment variables to use it in HeadlessBuild.properties */
        this.script.env['ANDROID_PACKAGE_NAME'] = androidPackageName
    }

    private final void signArtifacts(buildArtifacts) {
        String errorMessage = 'FAILED to sign artifact'
        String signer = 'jarsigner'
        String androidBuildToolsPath = (visualizerDependencies.find { it.variableName == 'ANDROID_BUILD_TOOLS'} ?.homePath) ?:
                script.error('Android build tools path is missing!')
        String javaBinPath = (visualizerDependencies.find { it.variableName == 'JAVA_HOME' } ?.binPath) ?:
                script.error('Java binaries path is missing!')

        script.catchErrorCustom(errorMessage) {
            for (artifact in buildArtifacts) {
                script.dir(artifact.path) {
                    script.withEnv(["PATH+TOOLS=${javaBinPath}${pathSeparator}${androidBuildToolsPath}"]) {
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
                    }
                }
            }
        }
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            ValidationHelper.checkBuildConfiguration(script)

            def mandatoryParameters = ['ANDROID_VERSION', 'ANDROID_VERSION_CODE', 'FORM_FACTOR']

            channelFormFactor.equalsIgnoreCase('Mobile') ? mandatoryParameters.add('ANDROID_MOBILE_APP_ID') :
                    mandatoryParameters.add('ANDROID_TABLET_APP_ID')

            if (keystoreFileID || keystorePasswordID || privateKeyPassword || keystoreAlias) {
                mandatoryParameters.addAll([
                        'ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD', 'ANDROID_KEY_ALIAS'
                ])
            }

            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
        }

        script.node(nodeLabel) {
            pipelineWrapper {
                script.cleanWs deleteDirs: true

                script.stage('Check build-node environment') {
                    ValidationHelper.checkBuildConfiguration(script,
                            ['VISUALIZER_HOME', 'ANDROID_HOME', channelVariableName, 'ANDROID_PACKAGE_NAME',
                             'PROJECT_WORKSPACE', 'FABRIC_ENV_NAME']
                    )
                }

                script.stage('Checkout') {
                    BuildHelper.checkoutProject script: script,
                            projectRelativePath: checkoutRelativeTargetFolder,
                            gitBranch: gitBranch,
                            gitCredentialsID: gitCredentialsID,
                            gitURL: gitURL
                }

                script.stage('Build') {
                    build()
                    /* Search for build artifacts */
                    buildArtifacts = getArtifactLocations(artifactExtension) ?:
                            script.error('Build artifacts were not found!')
                }

                script.stage("Sign artifacts") {
                    if (buildMode == 'release') {
                        signArtifacts(buildArtifacts)
                    } else {
                        script.println "Build mode is $buildMode, skipping signing!"
                    }
                }

                script.stage("Publish artifacts to S3") {
                    /* Rename artifacts for publishing */
                    artifacts = renameArtifacts(buildArtifacts)

                    /* Create a list with artifact objects for e-mail template */
                    def channelArtifacts = []

                    artifacts?.each { artifact ->
                        String artifactName = artifact.name
                        String artifactPath = artifact.path
                        String artifactUrl = AWSHelper.publishToS3 script: script, bucketPath: s3ArtifactPath,
                                exposeURL: true, sourceFileName: artifactName, sourceFilePath: artifactPath

                        channelArtifacts.add([channelPath: channelPath,
                                              name       : artifactName,
                                              url        : artifactUrl])
                    }

                    script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                }
            }
        }
    }
}