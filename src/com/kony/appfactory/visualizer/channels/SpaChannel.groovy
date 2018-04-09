package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper

/**
 * Implements logic for SPA channel builds.
 */
class SpaChannel extends Channel {
    /* Build parameters */
    private final spaAppVersion = script.params.SPA_APP_VERSION
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    /* CustomHooks build Parameters*/
    private final runCustomHook = script.params.RUN_CUSTOM_HOOKS

    private final selectedSpaChannels

    /* CustomHookHelper object */
    protected hookHelper

    /* Build agent resources */
    def resourceList
    def nodeLabel
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    SpaChannel(script) {
        super(script)
        this.hookHelper = new CustomHookHelper(script)
        channelOs = channelFormFactor = channelType = 'SPA'
        selectedSpaChannels = getSelectedSpaChannels(this.script.params)
        /* Expose SPA build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = spaAppVersion
    }

    /**
     *
     * @param buildParameters
     * @return
     */
    @NonCPS
    private static getSelectedSpaChannels(buildParameters) {
        buildParameters.findAll {
            it.value instanceof  Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.key != 'RUN_CUSTOM_HOOKS' && it.value
        }.keySet().collect()
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    ValidationHelper.checkBuildConfiguration(script)

                    if (!selectedSpaChannels) {
                        script.echoCustom('Please select at least one channel to build!','ERROR')
                    }

                    ValidationHelper.checkBuildConfiguration(script, ['SPA_APP_VERSION', 'FABRIC_APP_CONFIG'])
                }

                /*
                *  Allocate a slave for the run
                *  CustomHook always must run in MAC (to use ACLs), However SPA can run in both Windows and MAC
                *  Due to this, if user need to run Custom Hooks on SPA (runCustomHook is checked) then run SPA
                *  build on MAC Agent. Otherwise default node strategy will be followed (WIN || MAC)
                */
                resourceList = BuildHelper.getResourcesList()
                nodeLabel = BuildHelper.getAvailableNode(runCustomHook, resourceList, libraryProperties, script)

                script.node(nodeLabel) {
                    pipelineWrapper {
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        script.stage('Check build-node environment') {
                            def mandatoryParameters = [
                                    'VISUALIZER_HOME', channelVariableName, 'PROJECT_WORKSPACE', 'FABRIC_ENV_NAME',
                                    'APP_VERSION'
                            ]

                            if (publishFabricApp) {
                                mandatoryParameters.addAll(['FABRIC_APP_NAME', 'CLOUD_ACCOUNT_ID'])
                            }

                            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                        }

                        script.stage('Checkout') {
                            BuildHelper.checkoutProject script: script,
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: scmBranch,
                                    scmCredentialsId: scmCredentialsId,
                                    scmUrl: scmUrl
                        }

                        script.stage('Check PreBuild Hook Points'){
                            if(runCustomHook) {

                                ['ANDROID_MOBILE_SPA', 'ANDROID_TABLET_SPA', 'IOS_MOBILE_SPA', 'IOS_TABLET_SPA'].each { project ->
                                    def projectStage = "SPA_" + project - "_SPA" + "_STAGE"
                                    if(selectedSpaChannels.contains(project)){
                                        def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.prebuild.name', projectStage)
                                        if(!isSuccess)
                                            throw new Exception("Something went wrong with the Custom hooks execution.")
                                    }
                                }

                            }
                            else{
                                script.echoCustom('runCustomHook parameter is not selected by user, Hence CustomHooks execution is skipped.','WARN')
                            }
                        }

                        script.stage('Build') {
                            build()
                            /* Search for build artifacts */
                            buildArtifacts = getArtifactLocations(artifactExtension) ?:
                                    script.echoCustom('Build artifacts were not found!','ERROR')
                        }

                        script.stage('Publish to Fabric') {
                            /* Publish Fabric application if PUBLISH_FABRIC_APP set to true */
                            if (publishFabricApp) {
                                fabric.fetchFabricCli(libraryProperties.'fabric.cli.version')
                                fabric.fabricCli('publish', cloudCredentialsID, isUnixNode, [
                                        '-t': "\"${script.env.CLOUD_ACCOUNT_ID}\"",
                                        '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                        '-e': "\"${script.env.FABRIC_ENV_NAME}\""
                                ])
                            } else {
                                script.echoCustom("PUBLISH_FABRIC_APP flag set to false, " +
                                        "skipping Fabric application publishing...")

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
                        }

                        /* Run Post Build SPA Hooks */
                        script.stage('Check PostBuild Hook Points') {
                            if (script.currentBuild.currentResult == 'SUCCESS') {
                                if (runCustomHook) {

                                    ['ANDROID_MOBILE_SPA', 'ANDROID_TABLET_SPA', 'IOS_MOBILE_SPA', 'IOS_TABLET_SPA'].each { project ->
                                        def projectStage = "SPA_" + project - "_SPA" + "_STAGE"
                                        if (selectedSpaChannels.contains(project)) {
                                            def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.postbuild.name', projectStage)
                                            if (!isSuccess)
                                                throw new Exception("Something went wrong with the Custom hooks execution.")
                                        }

                                    }
                                } else {
                                    script.echoCustom('runCustomHook parameter is not selected by user, Hence CustomHooks execution is skipped.', 'WARN')
                                }
                            } else {
                                script.echoCustom('CustomHooks execution skipped as current build result not SUCCESS.', 'WARN')
                            }
                        }
                    }
                }
            }
        }
    }
}
