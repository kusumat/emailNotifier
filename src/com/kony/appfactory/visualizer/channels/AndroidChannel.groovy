package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.AppFactoryException

/**
 * Implements logic for Android channel builds.
 */
class AndroidChannel extends Channel {
    /* Build parameters */
    private final androidAppVersion = script.params.ANDROID_APP_VERSION
    private final keystoreFileId = script.params.ANDROID_KEYSTORE_FILE
    private final keystorePasswordId = script.params.ANDROID_KEYSTORE_PASSWORD
    private final privateKeyPassword = script.params.ANDROID_KEY_PASSWORD
    private boolean doAndroidSigning = false
    /* At least one of application id parameters should be set */
    private final androidMobileAppId = script.params.ANDROID_MOBILE_APP_ID
    private final androidTabletAppId = script.params.ANDROID_TABLET_APP_ID
    private final androidUniversalAppId = script.params.ANDROID_UNIVERSAL_APP_ID
    private final androidPackageName = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
        androidMobileAppId : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
        androidTabletAppId : androidUniversalAppId

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
        this(script, script.params.FORM_FACTOR)
         }

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     * @param channelFormFactor
     */
    AndroidChannel(script, channelFormFactor) {
        super(script)
        channelOs = 'Android'
        channelType = 'Native'
        /* Expose Android build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = androidAppVersion
        this.script.env['ANDROID_PACKAGE_NAME'] = androidPackageName
        this.channelFormFactor = channelFormFactor
        customHookStage = (channelFormFactor?.equalsIgnoreCase('Mobile')) ?
                "ANDROID_MOBILE_STAGE" : (channelFormFactor?.equalsIgnoreCase('Tablet')) ?
                "ANDROID_TABLET_STAGE" : "ANDROID_UNIVERSAL_STAGE"
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

        androidSigningEnvWrapper {
            script.catchErrorCustom(errorMessage) {
                for (artifact in buildArtifacts) {
                    script.dir(artifact.path) {
                        def finalArtifactName = artifact.name.replaceAll('unsigned', 'aligned')

                        script.shellCustom(
                                [signer, '-verbose', '-sigalg', 'SHA1withRSA', '-digestalg', 'SHA1',
                                 '-keystore', "${script.env.KSFILE}",
                                 '-storepass', "${script.env.KSPASS}",
                                 '-keypass', "${script.env.KEYPASS}", artifact.name, script.env.KEY_ALIAS].join(' '),
                                isUnixNode
                        )

                        script.shellCustom(
                                [signer, '-verify -certs', artifact.name, script.env.KEY_ALIAS].join(' '),
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

    private void androidSigningEnvWrapper(closure) {
        String zipAlignUtilVersion = libraryProperties.'android.build-tools.zipalign.version'

        String androidBuildToolsPath = script.env.isCIBUILD ?
                [script.env.ANDROID_HOME, 'build-tools', zipAlignUtilVersion].join(separator) :
                (visualizerDependencies.find { it.variableName == 'ANDROID_BUILD_TOOLS'} ?.homePath) ?:
                        script.echoCustom('Android build tools path is missing!','ERROR')

        script.echoCustom("androidBuildToolsPath is $androidBuildToolsPath ")

        String javaBinPath = (visualizerDependencies.find { it.variableName == 'JAVA_HOME' } ?.binPath) ?:
                script.echoCustom('Java binaries path is missing!','ERROR')

        /* Add Java binaries and Android build tools home folder to the PATH variables */
        script.withEnv(["PATH+TOOLS=${javaBinPath}${pathSeparator}${androidBuildToolsPath}"]) {
            closure()
        }
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    ValidationHelper.checkBuildConfiguration(script)

                    def mandatoryParameters = ['APP_VERSION', 'ANDROID_VERSION_CODE', 'FORM_FACTOR']
                    if (channelOs && channelFormFactor) {
                        def appIdType = BuildHelper.getAppIdTypeBasedOnChannleAndFormFactor(channelOs, channelFormFactor)
                        mandatoryParameters.add(appIdType)
                    } else {
                        throw new AppFactoryException("Something went wrong. Unable to figure out valid application id type", 'ERROR')
                    }

                    if (buildMode != libraryProperties.'buildmode.debug.type') {
                        def androidSigningBuildParams = [
                                'ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD',
                                'ANDROID_KEY_ALIAS'
                        ]
                        /* Collect(filter) build parameters and environment variables to check */
                        def androidSigningBuildConfiguration = ValidationHelper.collectEnvironmentVariables(script.env, androidSigningBuildParams)
                        /* Check if there are empty parameters among required parameters */
                        def emptyParams = ValidationHelper.checkForNull(androidSigningBuildConfiguration)
                        if (!emptyParams) {
                            doAndroidSigning = true
                        }
                    }
                    if (buildMode == libraryProperties.'buildmode.release.protected.type') {
                        mandatoryParameters.add('PROTECTED_KEYS')
                    }

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                script.stage('Check Available Resources') {
                    /*
                    To restrict Headless Builds to run in parallel, this workaround implemented
                    */
                    resourceList = BuildHelper.getResourcesList()
                    isCustomHookRunBuild = BuildHelper.isThisBuildWithCustomHooksRun(projectName, runCustomHook, libraryProperties)
                    nodeLabel = BuildHelper.getAvailableNode(resourceList, libraryProperties, script, isCustomHookRunBuild, channelOs)
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

                            def parametersToValidate = ['VISUALIZER_HOME', 'ANDROID_HOME', channelVariableName, 'ANDROID_PACKAGE_NAME',
                                                        'PROJECT_WORKSPACE']
                            if (script.env.FABRIC_ENV_NAME) {
                                parametersToValidate << 'FABRIC_ENV_NAME'
                            }
                            ValidationHelper.checkBuildConfiguration(script, parametersToValidate)
                        }

                        script.stage('Checkout') {
                            // source code checkout from scm
                            BuildHelper.checkoutProject script: script,
                                    checkoutType: "scm",
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: scmBranch,
                                    scmCredentialsId: scmCredentialsId,
                                    scmUrl: scmUrl
                        }
                        /* Run PreBuild Hooks */
                        runPreBuildHook()

                        script.stage('Build') {
                            /* Copy protected keys to project workspace if build mode is "release-protected" */
                            if (buildMode == libraryProperties.'buildmode.release.protected.type') {
                                script.echoCustom("Placing encryptions keys for protected mode build.")
                                copyProtectedKeysToProjectWorkspace()
                            }
                            build()
                            /* Search for build artifacts */
                            buildArtifacts = getArtifactLocations(artifactExtension)
                            if (!buildArtifacts) {
                                throw new AppFactoryException('Build artifacts were not found!', 'ERROR')
                            }
                        }

                        script.stage("Sign artifacts") {
                            if (buildMode != libraryProperties.'buildmode.debug.type') {
                                /* Inject keystoreFileId, keystorePasswordId, privateKeyPassword environment variables */
                                if (doAndroidSigning) {
                                    script.withCredentials([
                                            script.file(credentialsId: "${keystoreFileId}", variable: 'KSFILE'),
                                            script.string(credentialsId: "${keystorePasswordId}", variable: 'KSPASS'),
                                            script.string(credentialsId: "${privateKeyPassword}", variable: 'KEYPASS')
                                    ]) {
                                        script.withEnv(["KEY_ALIAS=$script.params.ANDROID_KEY_ALIAS"]) {
                                            signArtifacts(buildArtifacts)
                                        }
                                    }
                                } else {
                                    def signingWarning = 'Skipping Android binaries signing, since required keystore ' +
                                            'signing parameters are not fully provided. Unsigned release mode apks ' +
                                            'are provided to sign it locally.'
                                    script.echoCustom(signingWarning, 'WARN')
                                }
                            } else {
                                script.echoCustom("Build mode is ${buildMode}, " +
                                        "skipping signing (artifact already signed with debug certificate)!")
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
                                        sourceFileName: artifactName, sourceFilePath: artifactPath, script

                                String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true);

                                channelArtifacts.add([
                                        channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl
                                ])
                            }

                            script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()

                            /* Run PostBuild Hooks */
                            runPostBuildHook()
                        }
                    }
                }
            }
        }
    }
}