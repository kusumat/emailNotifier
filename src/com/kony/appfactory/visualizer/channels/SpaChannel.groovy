package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

/**
 * Implements logic for SPA channel builds.
 */
class SpaChannel extends Channel {
    /* Build parameters */
    private final spaAppVersion = script.params.SPA_APP_VERSION
    private final publishFabricApp = script.params.PUBLISH_FABRIC_APP
    private final selectedSpaChannels

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    SpaChannel(script) {
        super(script)
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
            it.value instanceof  Boolean && it.key != 'PUBLISH_FABRIC_APP' && it.value
        }.keySet().collect()
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        script.timestamps {
            script.stage('Check provided parameters') {
                ValidationHelper.checkBuildConfiguration(script)

                if (!selectedSpaChannels) {
                    script.error('Please select at least one channel to build!')
                }

                ValidationHelper.checkBuildConfiguration(script, ['SPA_APP_VERSION', 'FABRIC_APP_CONFIG'])
            }

            /* Allocate a slave for the run */
            script.node(libraryProperties.'spa.node.label') {
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

                    script.stage('Build') {
                        build()
                        /* Search for build artifacts */
                        buildArtifacts = getArtifactLocations(artifactExtension) ?:
                                script.error('Build artifacts were not found!')
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
                            script.echo 'PUBLISH_FABRIC_APP flag set to false, ' +
                                    'skipping Fabric application publishing...'
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

                            String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script);

                            channelArtifacts.add([
                                    channelPath: channelPath, name: artifactName, url: artifactUrl, authurl: authenticatedArtifactUrl
                            ])
                        }

                        script.env['CHANNEL_ARTIFACTS'] = channelArtifacts?.inspect()
                    }
                }
            }
        }
    }
}
