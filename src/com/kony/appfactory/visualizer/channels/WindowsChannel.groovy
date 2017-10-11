package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AWSHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

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
            ValidationHelper.checkBuildConfiguration(script)

            def mandatoryParameters = ['OS', 'FORM_FACTOR']

            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
        }

        script.node(nodeLabel) {
            script.ws(shortenedWorkspace) { // Workaround to fix path limitation on windows slaves
                pipelineWrapper {
                    script.cleanWs deleteDirs: true

                    script.stage('Check build-node environment') {
                        ValidationHelper.checkBuildConfiguration(script,
                                ['VISUALIZER_HOME', channelVariableName, 'PROJECT_WORKSPACE', 'FABRIC_ENV_NAME'])
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
