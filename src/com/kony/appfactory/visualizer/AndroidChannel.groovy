package com.kony.appfactory.visualizer

class AndroidChannel implements Serializable {
    private script, androidHome
    private boolean isUnixNode
    private String workSpace
    private String projectFullPath
    private String nodeLabel = 'win || mac'
    private String resourceBasePath = 'com/kony/appfactory/visualizer/'

    /* Build parameters */
    private String projectName = script.params.PROJECT_NAME
    private String gitCredentialsID = script.params.GIT_CREDENTIALS_ID
    private String gitURL = script.params.GIT_URL
    private String gitBranch = script.params.GIT_BRANCH
    private String visualizerVersion = script.params.VIZ_VERSION
    private String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private String mainBuildNumber = script.params.MAIN_BUILD_NUMBER
    private String buildMode = script.params.BUILD_MODE
    private String keystoreFileID = script.params.KS_FILE
    private String keystorePasswordID = script.params.KS_PASSWORD
    private String privateKeyPassword = script.params.PRIVATE_KEY_PASSWORD
    private String s3BucketName = script.params.S3_BUCKET_NAME
    private String environment = script.params.ENVIRONMENT

    private String artifactFileName = projectName + '_' + mainBuildNumber + '.apk'

    AndroidChannel(script) {
        this.script = script
        setBuildParameters()
    }

    @NonCPS
    private final void setBuildParameters(){
        script.properties([
                script.parameters([
                        script.stringParam( name: 'PROJECT_NAME', defaultValue: '', description: 'Project Name' ),
                        script.stringParam( name: 'GIT_URL', defaultValue: '', description: 'Project Git URL' ),
                        script.stringParam( name: 'GIT_BRANCH', defaultValue: '', description: 'Project Git Branch' ),
                        [$class: 'CredentialsParameterDefinition', name: 'GIT_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'GitHub.com Credentials', required: true],
                        script.stringParam( name: 'MAIN_BUILD_NUMBER', defaultValue: '', description: 'Build Number for artifact' ),
                        script.choice( name: 'BUILD_MODE', choices: "debug\nrelease", defaultValue: '', description: 'Choose build mode (debug or release)' ),
                        script.choice( name: 'ENVIRONMENT', choices: "dev\nqa\nrelease", defaultValue: 'dev', description: 'Define target environment' ),
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: '', description: 'Private key and certificate chain reside in the given Java-based KeyStore file', name: 'KS_FILE', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the KeyStore', name: 'KS_PASSWORD', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The password for the private key', name: 'PRIVATE_KEY_PASSWORD', required: false],
                        script.stringParam( name: 'VIZ_VERSION', defaultValue: '7.2.1', description: 'Kony Vizualizer version' ),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam( name: 'S3_BUCKET_NAME', defaultValue: '', description: 'S3 Bucket Name' ),
                        script.booleanParam( name: 'ANDRPHONE', defaultValue: true, description: 'Select the box if your build is for Android Phones' )
                ])
        ])
    }

    private final void checkoutProject() {
        String successMessage = 'Project has been checkout successfully'
        String errorMessage = 'FAILED to checkout the project'

        catchErrorCustom(successMessage, errorMessage) {
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

    private final void visualizerEnvWrapper(closure) {
        String visualizerBasePath = (isUnixNode) ? "/Jenkins/KonyVisualizerEnterprise${visualizerVersion}/" :
                "C:\\Jenkins\\KonyVisualizerEnterprise${visualizerVersion}\\"
        String antHome = visualizerBasePath + 'Ant'
        String javaHome = visualizerBasePath + ((isUnixNode) ? 'jdk1.8.0_112.jdk/Contents/Home' :
                'Java\\jdk1.8.0_112\\bin\\java')
        String javaHomeEnvVarName = (isUnixNode) ? 'JAVA_HOME' : 'JAVACMD'

        script.withEnv(["ANT_HOME=${antHome}", "${javaHomeEnvVarName}=${javaHome}"]) {
            script.withCredentials([script.usernamePassword(credentialsId: "${cloudCredentialsID}",
                    passwordVariable: 'CLOUD_PASS', usernameVariable: 'CLOUD_NAME')]) {
                closure()
            }
        }
    }

    private final void withKeyStore(closure) {
        script.withCredentials([
                script.file(credentialsId: "${keystoreFileID}", variable: 'KSFILE'),
                script.string(credentialsId: "${keystorePasswordID}", variable: 'KSPASS'),
                script.string(credentialsId: "${privateKeyPassword}", variable: 'KEYPASS')
        ]) {
            closure()
        }
    }

    private final void build() {
        String successMessage = 'Project has been built successfully'
        String errorMessage = 'FAILED to build the project'
        def requiredResources = ['property.xml', 'ivysettings.xml']

        catchErrorCustom(successMessage, errorMessage) {
            script.dir(projectFullPath) {
                /* Load required resources and store them in project folder */
                for (int i=0; i<requiredResources.size(); i++) {
                    String resource = loadLibraryResource(resourceBasePath + requiredResources[i])
                    script.writeFile file: requiredResources[i], text: resource
                }

                /* This wrapper responsible for adding ANT_HOME, JAVA_HOME and Kony Cloud credentials */
                visualizerEnvWrapper() {
                    if (isUnixNode) {
                        script.sh '$ANT_HOME/bin/ant -buildfile property.xml'
                        script.sh '$ANT_HOME/bin/ant'
                    } else {
                        script.bat "%ANT_HOME%\\bin\\ant -buildfile property.xml"
                        script.bat "%ANT_HOME%\\bin\\ant"
                    }
                }
            }
        }
    }

    private final void signArtifact() {
        String successMessage = 'Artifact signed successfully'
        String errorMessage = 'FAILED to sign artifact'
        String artifactPath = projectName + ((isUnixNode) ? '/binaries/android/' : '\\binaries\\android\\')
        String apksigner = androidHome + ((isUnixNode) ? '/build-tools/25.0.0/apksigner' :
                '\\build-tools\\25.0.0\\apksigner.bat')
        String debugSingCommand = "${apksigner} sign --ks debug.keystore --ks-pass pass:android ${artifactFileName}"

        String keyGenCommandUnix = """\
            keytool -genkey -noprompt \
             -alias androiddebugkey \
             -dname "CN=Android Debug,O=Android,C=US" \
             -keystore debug.keystore \
             -storepass android \
             -keypass android \
             -keyalg RSA \
             -keysize 2048 \
             -validity 10000
        """

        String keyGenCommandWindows = keyGenCommandUnix.replaceAll('\\\\', '^')

        catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifactPath) {
                if (isUnixNode) {
                    script.sh "mv luavmandroid.apk ${artifactFileName}"

                    if (buildMode == 'release') {
                        withKeyStore() {
                            script.sh apksigner +
                                    ' sign --ks $KSFILE --ks-pass pass:$KSPASS --key-pass pass:$KEYPASS ' +
                                    artifactFileName
                        }
                    } else {
                        script.sh keyGenCommandUnix
                        script.sh debugSingCommand
                    }

                    script.sh "${apksigner} verify --verbose ${artifactFileName}"
                } else {
                    script.bat "rename luavmandroid.apk ${artifactFileName}"

                    if (buildMode == 'release') {
                        withKeyStore() {
                            script.bat apksigner +
                                    ' sign --ks %KSFILE% --ks-pass pass:%KSPASS% --key-pass pass:%KEYPASS% ' +
                                    artifactFileName
                        }
                    } else {
                        script.bat keyGenCommandWindows
                        script.bat debugSingCommand
                    }

                    script.bat "${apksigner} verify --verbose ${artifactFileName}"
                }
            }
        }
    }

    private final void publishToS3() {
        String successMessage = 'Artifact published successfully'
        String errorMessage = 'FAILED to publish artifact'

        catchErrorCustom(successMessage, errorMessage) {
            script.step([$class                              : 'S3BucketPublisher',
                         consoleLogLevel                     : 'INFO',
                         dontWaitForConcurrentBuildCompletion: false,
                         entries                             : [
                                 [bucket           : "${s3BucketName}/${projectName}/${environment}/android",
                                  flatten          : true,
                                  keepForever      : true,
                                  managedArtifacts : false,
                                  noUploadOnFailure: true,
                                  selectedRegion   : 'eu-west-1',
                                  sourceFile       : "${projectName}/binaries/android/${artifactFileName}"]
                         ],
                         pluginFailureResultConstraint       : 'FAILURE'])
        }
    }

    private final void catchErrorCustom(successMsg, errorMsg, closure) {
        try {
            closure()
            script.echo successMsg
        } catch(Exception e) {
            script.error errorMsg
        }
    }

    private final String loadLibraryResource(resourcePath) {
        def resource = ''
        String successMessage = 'Resource loading finished successfully'
        String errorMessage = 'FAILED to load resource'

        catchErrorCustom(successMessage, errorMessage) {
            resource = script.libraryResource resourcePath
        }

        resource
    }

    private final void sendNotification() {
        String emailTemplateName = 'email.jelly'
        String emailBody = '${JELLY_SCRIPT, template="' + emailTemplateName + '"}'
        String emailSubject = "${script.currentBuild.currentResult}: ${projectName} (${script.env.JOB_NAME}-#${script.env.BUILD_NUMBER})"
        String emailRecipientsList = 'sshepe@softserveinc.com'

        /* Load email template */
        String emailTemplate = loadLibraryResource(resourceBasePath + emailTemplateName)
        script.writeFile file: 'email.jelly', text: emailTemplate

        /* Sending email */
        script.emailext body: emailBody, subject: emailSubject, to: emailRecipientsList
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {

            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = (isUnixNode) ? workSpace + '/' + projectName + '/' :
                    workSpace + '\\' + projectName + '\\'

            try {
                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()

                    /* Setting android home variable */
                    androidHome = script.readProperties(
                            file: projectFullPath + 'HeadlessBuild-Global.properties'
                    ).'android.home'
                }

                script.stage("Sign an apk") {
                    signArtifact()
                }

                script.stage("Publish artifact to S3") {
                    publishToS3()
                }
            } catch(Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                script.step([$class: 'WsCleanup', deleteDirs: true])
                sendNotification()
            }
        }
    }
}
