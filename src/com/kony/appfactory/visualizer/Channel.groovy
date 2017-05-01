package com.kony.appfactory.visualizer

abstract class Channel implements Serializable {
    protected script
    protected boolean isUnixNode
    protected String workSpace
    protected String projectFullPath
    protected String resourceBasePath = 'com/kony/appfactory/visualizer/'

    /* Common build parameters */
    protected String projectName = script.params.PROJECT_NAME
    protected String gitCredentialsID = script.params.GIT_CREDENTIALS_ID
    protected String gitURL = script.params.GIT_URL
    protected String gitBranch = script.params.GIT_BRANCH
    protected String environment = script.params.ENVIRONMENT
    protected String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    protected String visualizerVersion = script.params.VIZ_VERSION
    protected String s3BucketName = script.params.S3_BUCKET_NAME
    protected String mainBuildNumber = script.params.MAIN_BUILD_NUMBER
    protected String buildMode = script.params.BUILD_MODE

    Channel(script) {
        this.script = script
    }

    protected final void checkoutProject() {
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

    protected final void publishToS3(args) {
        String successMessage = 'Artifact published successfully'
        String errorMessage = 'FAILED to publish artifact'

        String artifact = args.artifact
        String channel = args.channel
        String bucket = "${s3BucketName}/${projectName}/${environment}/${channel}"

        catchErrorCustom(successMessage, errorMessage) {
            script.step([$class                              : 'S3BucketPublisher',
                         consoleLogLevel                     : 'INFO',
                         dontWaitForConcurrentBuildCompletion: false,
                         entries                             : [
                                 [bucket           : bucket,
                                  flatten          : true,
                                  keepForever      : true,
                                  managedArtifacts : false,
                                  noUploadOnFailure: true,
                                  selectedRegion   : 'eu-west-1',
                                  sourceFile       : artifact]
                         ],
                         pluginFailureResultConstraint       : 'FAILURE'])
        }
    }

    protected final void sendNotification() {
        String emailTemplateFolder = 'email/templates/'
        String emailTemplateName = 'Kony_OTA_Installers.jelly'
        String emailBody = '${JELLY_SCRIPT, template="' + emailTemplateName + '"}'
        String emailSubject = String.valueOf(script.currentBuild.currentResult) + ': ' +
                String.valueOf(projectName) + ' (' +
                String.valueOf(script.env.JOB_NAME) + '-#' +
                String.valueOf(script.env.BUILD_NUMBER) + ')'
        String emailRecipientsList = 'KonyAppFactoryTeam@softserveinc.com'

        /* Load email template */
        String emailTemplate = loadLibraryResource(resourceBasePath + emailTemplateFolder + emailTemplateName)
        script.writeFile file: emailTemplateName, text: emailTemplate

        /* Sending email */
        script.emailext body: emailBody, subject: emailSubject, to: emailRecipientsList
    }

    protected final void catchErrorCustom(successMsg, errorMsg, closure) {
        try {
            closure()
            script.echo successMsg
        } catch(Exception e) {
            script.error errorMsg
        }
    }

    protected final String loadLibraryResource(resourcePath) {
        def resource = ''
        String successMessage = 'Resource loading finished successfully'
        String errorMessage = 'FAILED to load resource'

        catchErrorCustom(successMessage, errorMessage) {
            resource = script.libraryResource resourcePath
        }

        resource
    }

    protected final void visualizerEnvWrapper(closure) {
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

    protected final void build() {
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
}
