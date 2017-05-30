package com.kony.appfactory.visualizer.channels

abstract class Channel implements Serializable {
    protected script
    protected boolean isUnixNode
    protected String workSpace
    protected String projectFullPath
    protected String channelName
    protected artifacts
    protected String artifactsBasePath
    protected String artifactsExtension
    protected String s3artifactPath
    protected String s3BucketRegion = script.env.S3_BUCKET_REGION
    protected String s3BucketName = script.env.S3_BUCKET_NAME
    protected String nodeLabel
    protected final String resourceBasePath = 'com/kony/appfactory/visualizer/'
    protected isSPA

    /* Required for triggering emails */
    protected buildCause
    protected triggeredBy = ''

    /* Common build parameters */
    protected String projectName = script.env.PROJECT_NAME
    protected String gitCredentialsID = script.params.GIT_CREDENTIALS_ID
    protected String gitURL = script.env.PROJECT_GIT_URL
    protected String gitBranch = script.params.GIT_BRANCH
    protected String environment = script.params.ENVIRONMENT
    protected String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    protected String visualizerVersion = script.params.VIS_VERSION

    protected String mainBuildNumber = script.params.MAIN_BUILD_NUMBER
    protected String buildMode = script.params.BUILD_MODE

    Channel(script) {
        this.script = script

        channelName = (this.script.env.JOB_NAME - 'Visualizer/' - "${projectName}/" - "${environment}/").toUpperCase().replaceAll('/','_')
        s3artifactPath = getS3AtrifactPath(channelName)
        isSPA = channelName.contains('SPA')

        /* Workaround to build only specific channel */
        this.script.env[channelName] = true

        setS3ArtifactURL()
        getBuildCause()
        this.script.env['TRIGGERED_BY'] = "${triggeredBy}"
    }

    @NonCPS
    private final getS3AtrifactPath(channel) {
        def channelPath = channel.tokenize('_').collect() { item ->
            /* Workaround for windows phone jobs */
            if (item.contains('WINDOWSPHONE')) {
                item.replaceAll('WINDOWSPHONE', 'WindowsPhone')
                /* Workaround for SPA jobs */
            } else if (item.contains('SPA')) {
                item
            } else {
                item.toLowerCase().capitalize()
            }
        }.join('/')
        return "${s3BucketName}/${projectName}/${environment}/${channelPath}"
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

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(args.artifactPath) {
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
                                      sourceFile       : "${args.artifactName}"]
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
        String s3ArtifactURL = 'https://' + s3BucketRegion + '.amazonaws.com/' + s3artifactPath
        script.env['S3_ARTIFACT_URL'] = s3ArtifactURL
    }

    protected final getArtifacts(extension) {
        def artifactsFiles
        String successMessage = 'Search finished successfully'
        String errorMessage = 'FAILED to search artifacts'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifactsBasePath) {
                artifactsFiles = script.findFiles(glob: "**/*.${extension}")
            }
        }

        artifactsFiles
    }

    protected final renameArtifacts(artifactsList) {
        def renamedArtifacts = []
        String successMessage = 'Artifacts renamed successfully'
        String errorMessage = 'FAILED to rename artifacts'
        String shell = (isUnixNode) ? 'sh' : 'bat'
        String shellCommand = (isUnixNode) ? 'mv' : 'rename'
        String artifactTargetName = projectName + '_' + mainBuildNumber + '.' + artifactsExtension

        script.catchErrorCustom(successMessage, errorMessage) {
            for (int i=0; i < artifactsList.size(); ++i) {
                String artifactName = artifactsList[i].name
                String targetName = (artifactName.toLowerCase().contains('ARM'.toLowerCase())) ?
                        artifactTargetName.replaceFirst('_', '_ARM_') :
                        artifactTargetName
                String targetArtifactFolder = artifactsBasePath +
                        ((isUnixNode) ? "/" : "\\") +
                        (artifactsList[i].path.minus(((isUnixNode) ? "/" : "\\") + "${artifactsList[i].name}"))
                String command = "${shellCommand} ${artifactName} ${targetName}"

                /* Rename artifact */
                script.dir(targetArtifactFolder) {
                    script."${shell}" script: command
                }

                renamedArtifacts.add([name: targetName, path: targetArtifactFolder])
            }
        }

        renamedArtifacts
    }
}
