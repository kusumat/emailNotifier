package com.kony.appfactory.visualizer.channels

abstract class Channel implements Serializable {
    protected script
    protected boolean isUnixNode
    protected String workSpace
    protected String projectFullPath
    protected String channelName
    protected String artifactPath
    protected String s3artifactPath
    protected final String resourceBasePath = 'com/kony/appfactory/visualizer/'
    /* Required for triggering emails */
    protected buildCause
    protected triggeredBy = ''

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

        channelName = (this.script.env.JOB_NAME - "${projectName}/" - "${environment}/").toUpperCase().replaceAll('/','_')
        s3artifactPath = "${s3BucketName}/${projectName}/${environment}/${channelName.toLowerCase().replaceAll('_','/')}"

        /* Workaround to build only specific channel */
        this.script.env[channelName] = true

        setS3ArtifactURL()
        getBuildCause()
        this.script.env['TRIGGERED_BY'] = "${triggeredBy}"
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

    protected final void publishToS3(args) {
        String successMessage = 'Artifact published successfully'
        String errorMessage = 'FAILED to publish artifact'

        String artifact = args.artifact

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifactPath) {
                script.step([$class                              : 'S3BucketPublisher',
                             consoleLogLevel                     : 'INFO',
                             dontWaitForConcurrentBuildCompletion: false,
                             entries                             : [
                                     [bucket           : "${s3artifactPath}",
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

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(projectFullPath) {
                /* Load required resources and store them in project folder */
                for (int i=0; i<requiredResources.size(); i++) {
                    String resource = script.loadLibraryResource(resourceBasePath + requiredResources[i])
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

    @NonCPS
    private final void getBuildCause() {
        def causes = []
        def buildCauses = script.currentBuild.rawBuild.getCauses()

        for (cause in buildCauses) {
            if (cause instanceof hudson.model.Cause$UpstreamCause) {
                causes.add('upstream')
                triggeredBy = cause.getUpstreamRun().getCause(hudson.model.Cause.UserIdCause).getUserName()
            } else if (cause instanceof hudson.model.Cause$RemoteCause) {
                causes.add('remote')
            } else if (cause instanceof hudson.model.Cause$UserIdCause) {
                causes.add('user')
                triggeredBy = cause.getUserName()
            } else {
                causes.add('unknown')
            }
        }

        if (causes.contains('upstream')) {
            buildCause = 'upstream'
        } else if (causes.contains('user')) {
            buildCause = 'user'
        }
    }

    @NonCPS
    private final void setS3ArtifactURL() {
        String s3ArtifactURL = 'https://' + 's3-eu-west-1.amazonaws.com' + '/' + s3artifactPath
        script.env['S3_ARTIFACT_URL'] = s3ArtifactURL
    }
}
