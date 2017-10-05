package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper

class WindowsChannel extends Channel {
    private final shortenedWorkspace = ['C:', 'J', projectName, script.env.JOB_BASE_NAME].join('\\')

    WindowsChannel(script) {
        super(script)
        nodeLabel = 'win'
        channelOs = this.script.params.OS
        channelType = 'Native'
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            BuildHelper.checkBuildConfiguration(script)
            BuildHelper.checkBuildConfiguration(script, ['OS'])
        }

        script.node(nodeLabel) {
            script.ws(shortenedWorkspace) { // Workaround to fix path limitation on windows slaves
                pipelineWrapper {
                    script.deleteDir()

                    script.stage('Check build-node environment') {
                        BuildHelper.checkBuildConfiguration(script, ['VISUALIZER_HOME', channelVariableName])
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
}
