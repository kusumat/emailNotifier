package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class WindowsChannel extends Channel {
    def shortenedWorkspace = 'C:\\J\\' + projectName + '\\' + script.env.JOB_BASE_NAME

    WindowsChannel(script) {
        super(script)
        nodeLabel = 'win'
    }

    protected final void createPipeline() {
        script.node(nodeLabel) {
            script.ws(shortenedWorkspace) { // Workaround to fix path limitation on windows slaves
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
                        buildArtifacts = getArtifactLocations(artifactExtension)
                        /* Rename artifacts for publishing */
                        artifacts = (buildArtifacts) ? renameArtifacts(buildArtifacts) :
                                script.error('FAILED build artifacts are missing!')
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
}
