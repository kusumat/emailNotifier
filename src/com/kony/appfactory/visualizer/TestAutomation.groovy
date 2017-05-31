package com.kony.appfactory.visualizer

class TestAutomation implements Serializable {
    def script
    private String nodeLabel = 'linux'
    private String workspace
    private String projectFullPath
    private String testFolder
    private String projectName = script.env.PROJECT_NAME
    private String gitURL = script.env.PROJECT_GIT_URL
    private String gitBranch = script.params.GIT_BRANCH
    private String gitCredentialsID = script.params.GIT_CREDENTIALS_ID

    TestAutomation(script) {
        this.script = script
    }

    protected final void checkoutProject() {
        String successMessage = 'Project has been checkout successfully'
        String errorMessage = 'FAILED to checkout the project'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: [$class                           : 'GitSCM',
                          branches                         : [[name: "*/${gitBranch}"]],
                          doGenerateSubmoduleConfigurations: false,
                          extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                               relativeTargetDir: "${projectName}"]],
                          submoduleCfg                     : [],
                          userRemoteConfigs                : [[credentialsId: "${gitCredentialsID}",
                                                               url          : "${gitURL}"]]]
            )
        }
    }

    protected final void build() {
        String successMessage = 'Test Automation scripts have been built successfully'
        String errorMessage = 'FAILED to build the Test Automation scripts'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(testFolder) {
                script.sh 'mvn clean package -DskipTests=true'
                script.sh "mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip"
            }
        }
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            workspace = script.env.WORKSPACE
            projectFullPath = workspace + '/' + projectName
            testFolder = projectFullPath + '/' + 'test/TestNG'

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                }

                script.stage('Archive artifacts') {
                    script.dir(testFolder) {
                        if (script.fileExists("target/${projectName}_TestApp.zip")) {
                            script.archiveArtifacts artifacts: "target/${projectName}_TestApp.zip", fingerprint: true
                        } else {
                            script.error 'FAILED to find artifacts'
                        }
                    }
                }

//                script.stage('Prepare scripts') {
//                    script.sh "chmod +x ci_config/TestAutomationScripts/*"
//                }
//
//                script.stage('Run test') {
//                    script.sh "./ci_config/TestAutomationScripts/DeviceFarmCLIInit.sh ${script.env.WORKSPACE}/ci_config/DeviceFarmCLI.properties ${script.env.PROJECT_NAME} ${script.env.PROJECT_NAME}_${script.env.BUILD_NUMBER}.apk ${script.env.PROJECT_NAME}_${script.env.BUILD_NUMBER}.ipa s3://${script.env.S3_BUCKET_NAME}/TestApplication"
//                }
            } catch (Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                script.sendMail('com/kony/appfactory/visualizer/', 'Kony_Test_Automation_Build.jelly', 'KonyAppFactoryTeam@softserveinc.com')
            }
        }
    }
}
