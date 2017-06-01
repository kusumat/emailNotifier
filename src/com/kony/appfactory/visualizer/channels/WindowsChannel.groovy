package com.kony.appfactory.visualizer.channels

class WindowsChannel extends Channel {
    def shortenedWorkspace = 'C:\\J\\' + projectName + '\\' + script.env.JOB_BASE_NAME

    WindowsChannel(script) {
        super(script)
        nodeLabel = 'win'
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Workaround to fix path limitaion on windows slaves */
            script.ws(shortenedWorkspace) {
                /* Set environment-dependent variables */
                isUnixNode = script.isUnix()
                workspace = script.env.WORKSPACE
                projectFullPath = workspace + '\\' + projectName
                artifactsBasePath = projectFullPath + "\\binaries"

                try {
                    script.deleteDir()

                    script.stage('Checkout') {
                        checkoutProject()
                    }

                    script.stage('Build') {
                        build()
                        /* Search for build artifacts */
                        def foundArtifacts = getArtifacts(artifactExtension)
                        /* Rename artifacts for publishing */
                        artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) : script.error('FAILED build artifacts are missing!')
                    }

                    script.stage("Publish artifacts to S3") {
                        for (artifact in artifacts) {
                            publishToS3 artifactName: artifact.name, artifactPath: artifact.path
                        }
                    }
                } catch(Exception e) {
                    script.echo e.getMessage()
                    script.currentBuild.result = 'FAILURE'
                } finally {
                    if (buildCause == 'user' || script.currentBuild.result == 'FAILURE') {
                        script.sendMail('com/kony/appfactory/visualizer/', 'Kony_OTA_Installers.jelly', 'KonyAppFactoryTeam@softserveinc.com')
                    }
                }
            }
        }
    }
}
