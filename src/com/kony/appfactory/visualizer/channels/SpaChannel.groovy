package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class SpaChannel extends Channel {
    /* Build parameters */
    String publishFabricApp

    SpaChannel(script) {
        super(script)
        nodeLabel = 'win || mac'
        publishFabricApp = this.script.env.PUBLISH_FABRIC_APP
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
                        script.echo 'In progress...'
                    } else {
                        script.echo 'Ignoring!'
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
