package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper

/**
 * Implements logic for Windows channel builds.
 */
class WindowsChannel extends Channel {
    private final shortenedWorkspaceBasePath
    private final shortenedWorkspace

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    WindowsChannel(script) {
        super(script)
        channelOs = this.script.params.OS
        channelType = 'Native'
        shortenedWorkspaceBasePath = libraryProperties.'windows.shortened.workspace.base.path'
        shortenedWorkspace = [shortenedWorkspaceBasePath, projectName, script.env.JOB_BASE_NAME].join('\\')
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    ValidationHelper.checkBuildConfiguration(script)

                    def mandatoryParameters = ['OS', 'FORM_FACTOR']

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                /* Allocate a slave for the run */
                script.node(libraryProperties.'windows.node.label') {
                    /* Workaround to fix path limitation on windows slaves, allocating new job workspace */
                    script.ws(shortenedWorkspace) {
                        pipelineWrapper {
                            /*
                            Clean workspace, to be sure that we have not any items from previous build,
                            and build environment completely new.
                         */
                            script.cleanWs deleteDirs: true

                            script.stage('Check build-node environment') {
                                ValidationHelper.checkBuildConfiguration(script,
                                        ['VISUALIZER_HOME', channelVariableName, 'PROJECT_WORKSPACE', 'FABRIC_ENV_NAME'])
                            }

                            script.stage('Checkout') {
                                // source code checkout from scm
                                scmMeta = BuildHelper.checkoutProject script: script,
                                        checkoutType: "scm",
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }

                            script.stage('Build') {
                                build()
                                /* Search for build artifacts */
                                buildArtifacts = getArtifactLocations(artifactExtension) ?:
                                        script.echoCustom('Build artifacts were not found!','ERROR')
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
                                            sourceFileName: artifactName, sourceFilePath: artifactPath, script

                                    String authenticatedArtifactUrl = BuildHelper.createAuthUrl(artifactUrl, script, true);

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
    }
}
