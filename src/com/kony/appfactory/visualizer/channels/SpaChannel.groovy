package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

class SpaChannel extends Channel {
    /* Build parameters */
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final fabricAppName = script.params.FABRIC_APP_NAME
    private final cloudAccountId = script.params.CLOUD_ACCOUNT_ID
    private final selectedSpaChannels

    SpaChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
        channelOs = channelFormFactor = channelType = 'SPA'
        selectedSpaChannels = getSelectedSpaChannels(this.script.params)
    }

    @NonCPS
    private static getSelectedSpaChannels(buildParameters) {
        buildParameters.findAll {
            it.value instanceof  Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.value
        }.keySet().collect()
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            ValidationHelper.checkBuildConfiguration(script)

            def mandatoryParameters = []

            if (publishFabricApp) {
                mandatoryParameters.addAll(['FABRIC_APP_NAME', 'CLOUD_ACCOUNT_ID'])
            }

            if (!selectedSpaChannels) {
                script.error('Please select at least one channel to build!')
            }

            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
        }

        script.node(nodeLabel) {
            pipelineWrapper {
                script.cleanWs deleteDirs: true

                script.stage('Check build-node environment') {
                    ValidationHelper.checkBuildConfiguration(script,
                            ['VISUALIZER_HOME', channelVariableName, 'PROJECT_WORKSPACE'])
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

                script.stage('Publish to Fabric') {
                    if (publishFabricApp) {
                        fabric.fetchFabricCli('7.3.0.43')
                        fabric.fabricCli('publish', cloudCredentialsID, isUnixNode, [
                                '-t': cloudAccountId, '-a': fabricAppName, '-e': "\"$environment\""
                        ])
                    } else {
                        script.echo 'PUBLISH_FABRIC_APP flag set to false, skipping Fabric application publishing...'
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
