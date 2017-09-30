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
                        buildArtifacts = getArtifactLocations(artifactExtension) ?:
                                script.error('Build artifacts were not found!')
                    }

                    script.stage("Publish artifacts to S3") {
                        /* Rename artifacts for publishing */
                        artifacts = renameArtifacts(buildArtifacts)

                        /* Create a list with artifact objects for e-mail template */
                        def channelArtifacts = []

                        artifacts.each { artifact ->
                            String artifactUrl = AWSHelper.publishToS3 script: script, bucketPath: s3ArtifactPath, exposeURL: true,
                                    sourceFileName: artifact.name, sourceFilePath: artifact.path

                            channelArtifacts.add([channelPath: channelPath,
                                                  name       : artifact.name,
                                                  url        : artifactUrl])
                        }

                        script.env['CHANNEL_ARTIFACTS'] = channelArtifacts.inspect()
                    }
                }
            }
        }
    }
}
