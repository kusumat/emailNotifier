package com.kony.appfactory.visualizer.channels

class WindowsChannel extends Channel {

    WindowsChannel(script) {
        super(script)
        /* Set build artifact extension, if channel SPA artifact extension should be war */
        artifactsExtension = (isSPA) ? 'war' : 'xap'
        nodeLabel = 'win'
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = workSpace + '\\' + projectName
            artifactsBasePath = projectFullPath + "\\binaries"

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                    /* Search for build artifacts */
                    def foundArtifacts = getArtifacts(artifactsExtension)
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
