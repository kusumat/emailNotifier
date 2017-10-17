package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

class SpaChannel extends Channel {
    /* Build parameters */
    private final spaAppVersion = script.params.SPA_APP_VERSION
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final selectedSpaChannels

    SpaChannel(script) {
        super(script)
        channelOs = channelFormFactor = channelType = 'SPA'
        selectedSpaChannels = getSelectedSpaChannels(this.script.params)
        /* Expose SPA build parameters to environment variables to use it in HeadlessBuild.properties */
        this.script.env['APP_VERSION'] = spaAppVersion
    }

    @NonCPS
    private static getSelectedSpaChannels(buildParameters) {
        buildParameters.findAll {
            it.value instanceof  Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.value
        }.keySet().collect()
    }

    protected final void createPipeline() {
        script.timestamps {
            script.stage('Check provided parameters') {
                ValidationHelper.checkBuildConfiguration(script)

                if (!selectedSpaChannels) {
                    script.error('Please select at least one channel to build!')
                }

                ValidationHelper.checkBuildConfiguration(script, ['SPA_APP_VERSION'])
            }

            script.node(libraryProperties.'spa.node.label') {
                pipelineWrapper {
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
                            fabric.fetchFabricCli(libraryProperties.'fabric.cli.version')
                            fabric.fabricCli('publish', cloudCredentialsID, isUnixNode, [
                                    '-t': "\"${script.env.CLOUD_ACCOUNT_ID}\"",
                                    '-a': "\"${script.env.FABRIC_APP_NAME}\"",
                                    '-e': "\"${script.env.FABRIC_ENV_NAME}\""
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
                            String artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                                    sourceFileName: artifactName, sourceFilePath: artifactPath, script, true

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
