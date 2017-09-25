package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class SpaChannel extends Channel {
    /* Build parameters */
    Boolean publishFabricApp
    String fabricAppName
    String fabricAccountId

    SpaChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
        publishFabricApp = this.script.params.PUBLISH_FABRIC_APP
        fabricAppName = this.script.env.FABRIC_APP_NAME
        fabricAccountId = this.script.env.FABRIC_ACCOUNT_ID
        channelVariableName = channelPath = 'SPA'
    }

    protected final void createPipeline() {
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

                script.stage('Publish to Fabric') {
                    if (publishFabricApp) {
                        fabric.fetchFabricCli('7.3.0.43')
                        fabric.fabricCli('publish', cloudCredentialsID, [
                                '-t': fabricAccountId, '-a': fabricAppName, '-e': environment
                        ])
                    } else {
                        script.echo 'PUBLISH_FABRIC_APP flag set to false, skipping Fabric application publishing...'
                    }
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
