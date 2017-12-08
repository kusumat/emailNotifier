package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper


/**
 * Implements logic for Android channel builds.
 */
class AndroidChannel extends Channel {
    /* Build parameters */
    private final androidAppVersion = script.params.ANDROID_APP_VERSION
    private final keystoreFileId = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordId = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private final keystoreAlias = script.params.ANDROID_KEY_ALIAS
    /* At least one of application id parameters should be set */
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidPackageName = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
            androidMobileAppId : androidTabletAppId

    /* resourceList contains list of locks and their status */
    private resourceList
    /* nodeLabel store slave label */
    private nodeLabel

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    AndroidChannel(script) {
        super(script)
        channelOs = 'Android'
        channelType = 'Native'
        /* Expose Android build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = androidAppVersion
        this.script.env['ANDROID_PACKAGE_NAME'] = androidPackageName
    }

    /**
     * Signs Android build artifacts.
     * More info could be found: https://developer.android.com/studio/publish/app-signing.html#signing-manually
     *                           https://developer.android.com/studio/command-line/zipalign.html
     *                           https://docs.oracle.com/javase/6/docs/technotes/tools/solaris/jarsigner.html
     *
     * @param buildArtifacts build artifacts list.
     */
    private final void signArtifacts(buildArtifacts) {

        String errorMessage = 'Failed to sign artifact'
        String signer = libraryProperties.'android.signer.name'
        String androidBuildToolsPath = script.env.isCIBUILD ? [script.env.ANDROID_HOME, 'build-tools', libraryProperties.'android.build-tools.zipalign.version'].join(separator) : (
                visualizerDependencies.find { it.variableName == 'ANDROID_BUILD_TOOLS'} ?.homePath
        ) ?: script.error('Android build tools path is missing!')

        script.echo "androidBuildToolsPath is $androidBuildToolsPath "

        String javaBinPath = (visualizerDependencies.find { it.variableName == 'JAVA_HOME' } ?.binPath) ?:
                script.error('Java binaries path is missing!')

        script.catchErrorCustom(errorMessage) {
            for (artifact in buildArtifacts) {
                script.dir(artifact.path) {
                    /* Add Java binaries and Android build tools home folder to the PATH variables */
                    script.withEnv(["PATH+TOOLS=${javaBinPath}${pathSeparator}${androidBuildToolsPath}"]) {
                        def finalArtifactName = artifact.name.replaceAll('unsigned', 'aligned')

                        /* Inject keystoreFileId, keystorePasswordId, privateKeyPassword environment variables */
                        script.withCredentials([
                                script.file(credentialsId: "${keystoreFileId}", variable: 'KSFILE'),
                                script.string(credentialsId: "${keystorePasswordId}", variable: 'KSPASS'),
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

                            /* Update artifact name */
                            artifact.name = finalArtifactName
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            script.stage('Check provided parameters') {
                ValidationHelper.checkBuildConfiguration(script)

                def mandatoryParameters = ['APP_VERSION', 'ANDROID_VERSION_CODE', 'FORM_FACTOR']

                channelFormFactor.equalsIgnoreCase('Mobile') ? mandatoryParameters.add('ANDROID_MOBILE_APP_ID') :
                        mandatoryParameters.add('ANDROID_TABLET_APP_ID')

                if (buildMode == 'release') {
                    mandatoryParameters.addAll([
                            'ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD',
                            'ANDROID_KEY_ALIAS'
                    ])
                }

                ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
            }
            script.stage('Check Available Resources') {
                /*
                    To restrict Headless Builds to run in parallel, this workaround implemented
                 */
                resourceList = BuildHelper.getResoursesList()

                nodeLabel = BuildHelper.getAvailableNode(resourceList,libraryProperties)
            }
            /* Allocate a slave for the run */
            script.node(nodeLabel) {
                pipelineWrapper {
                    /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                    script.cleanWs deleteDirs: true

                    script.stage('Check build-node environment') {

                        def parametersToValidate =['VISUALIZER_HOME', 'ANDROID_HOME', channelVariableName, 'ANDROID_PACKAGE_NAME',
                                 'PROJECT_WORKSPACE']
                        if (script.env.FABRIC_ENV_NAME) {
                            parametersToValidate << 'FABRIC_ENV_NAME'
                        }
                        ValidationHelper.checkBuildConfiguration(script, parametersToValidate)
                    }

                    script.stage('Checkout') {
                        BuildHelper.checkoutProject script: script,
                                projectRelativePath: checkoutRelativeTargetFolder,
                                scmBranch: scmBranch,
                                scmCredentialsId: scmCredentialsId,
                                scmUrl: scmUrl
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
                            script.echo "Build mode is $buildMode, " +
                                    "skipping signing (artifact already signed with debug certificate)!"
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
                            String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                    sourceFileName: artifactName, sourceFilePath: artifactPath, script, true
							
							String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script);

                            channelArtifacts.add([
                                    channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl
                            ])
                        }

                        script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                    }
                }
            }
        }
    }
}