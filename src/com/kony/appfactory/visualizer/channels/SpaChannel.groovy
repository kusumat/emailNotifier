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

    private final fetchFabricCli() {
        String mfCliUrl = 'https://s3-eu-west-1.amazonaws.com/konyappfactorydev-ci0001-storage1/configuration/mf/mfcli.jar'
        String mfCliUrlFileName = 'mfcli.jar'

        script.catchErrorCustom('FAILED to fetch MF CLI!') {
            script.httpRequest url: mfCliUrl, outputFile: mfCliUrlFileName, validResponseCodes: '200'
        }

    }

    private final mfCLI(args) {
        String command = args.command
        String options = args.options
        String mfCredID = args.mfCredID
        String successMessage = String.valueOf('publish'.capitalize()) + ' finished successfully'
        String errorMessage = 'FAILED to run ' + String.valueOf('publish') + ' command'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                     credentialsId   : mfCredID,
                                     passwordVariable: 'mfPassword',
                                     usernameVariable: 'mfUser']]) {
                script.shellCustom("java -jar mfcli.jar \"${command}\" -u \"${script.env.mfUser}\" \
                    -p \"${script.env.mfPassword}\" ${options}", isUnixNode)
            }
        }
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

//                script.stage('Publish to Fabric') {
//                    if (publishFabricApp) {
//                        script.echo 'In progress...'
//                        fetchFabricCli()
//                        String publishOptions = "-t \"${fabricAccountId}\" -a \"${fabricAppName}\" -e \"App Factory Dev\""
//
//                        mfCLI command: 'publish', options: publishOptions, mfCredID: cloudCredentialsID
//                    } else {
//                        script.echo 'Ignoring!'
//                    }
//                }

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
