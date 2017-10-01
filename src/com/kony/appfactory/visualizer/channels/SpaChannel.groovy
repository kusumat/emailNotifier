package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class SpaChannel extends Channel {
    /* Build parameters */
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final fabricAppName = script.params.FABRIC_APP_NAME
    private final fabricAccountId = script.params.FABRIC_ACCOUNT_ID

    SpaChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
        channelVariableName = channelPath = 'SPA'
    }

    protected final void createPipeline() {
        script.stage('Check build configuration') {
            BuildHelper.checkBuildConfiguration(script)

            if (publishFabricApp) {
                BuildHelper.checkBuildConfiguration(script, ['FABRIC_APP_NAME', 'FABRIC_ACCOUNT_ID', 'FABRIC_URL'])
            }
        }

        script.node(nodeLabel) {
            script.stage('Check build-node environment') {
                BuildHelper.checkBuildConfiguration(script, ['VISUALIZER_HOME', channelVariableName])
            }

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
                    buildArtifacts = getArtifactLocations(artifactExtension) ?:
                            script.error('Build artifacts were not found!')
                }

                script.stage('Publish to Fabric') {
                    if (publishFabricApp) {
                        fabric.fetchFabricCli('7.3.0.43')
                        fabric.fabricCli('publish', cloudCredentialsID, [
                                '-t': fabricAccountId, '-a': fabricAppName, '-e': "\"$environment\""
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

                    artifacts.each { artifact ->
                        String artifactUrl = AWSHelper.publishToS3 script: script, bucketPath: s3ArtifactPath,
                                exposeURL: true, sourceFileName: artifact.name, sourceFilePath: artifact.path

                        channelArtifacts.add([channelPath: channelPath,
                                              name       : artifact.name,
                                              url        : artifactUrl])
                    }

                    script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                }
            }
        }
    }
}
