package com.kony.appfactory.visualizer.channels

abstract class Channel implements Serializable {
    protected script
    protected boolean isUnixNode
    protected String workspace
    protected String projectFullPath
    protected String channelName
    protected artifacts
    protected String artifactsBasePath
    protected String artifactExtension
    protected String s3artifactPath
    protected String s3BucketRegion = script.env.S3_BUCKET_REGION
    protected String s3BucketName = script.env.S3_BUCKET_NAME
    protected String nodeLabel
    protected final String resourceBasePath = 'com/kony/appfactory/visualizer/'
    protected String recipientList

    /* Required for triggering emails */
    protected buildCause

    /* Common build parameters */
    protected String projectName = script.env.PROJECT_NAME
    protected String gitCredentialsID = script.params.GIT_CREDENTIALS_ID
    protected String gitURL = script.env.PROJECT_GIT_URL
    protected String gitBranch = script.params.GIT_BRANCH
    protected String environment = script.params.ENVIRONMENT
    protected String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    protected String visualizerVersion = script.env.VIS_VERSION
    protected String jobBuildNumber = script.env.BUILD_NUMBER
    protected String buildMode = script.params.BUILD_MODE

    Channel(script) {
        this.script = script
        recipientList = this.script.env.RECIPIENT_LIST
        /* Workaround to build only specific channel */
        channelName = (this.script.env.JOB_NAME - 'Visualizer/' - "${projectName}/" - "${environment}/").toUpperCase().replaceAll('/','_')
        this.script.env[channelName] = true
        artifactExtension = setArtifactExtension(channelName)
        s3artifactPath = getS3ArtifactPath(channelName)
        setS3ArtifactURL()
        /* Get build cause for e-mail notification */
        getBuildCause()
    }

    @NonCPS
    protected static final setArtifactExtension(channel) {
        def result

        switch (channel) {
            case ~/^.*SPA.*$/:
                result = 'war'
                break
            case ~/^.*WINDOWS_TABLET.*$/:
            case ~/^.*WINDOWS_MOBILE.*$/:
                result = 'appx'
                break
            case ~/^.*WINDOWS.*$/:
                result = 'xap'
                break
            case ~/^.*IOS.*$/:
                result = 'ipa'
                break
            case ~/^.*ANDROID.*$/:
                result = 'apk'
                break
            default:
                result = 'unknown'
                break
        }

        result
    }

    @NonCPS
    protected final getS3ArtifactPath(channel) {
        def channelPath = channel.tokenize('_').collect() { item ->
            /* Workaround for windows phone jobs */
            if (item.contains('WINDOWSPHONE')) {
                item.replaceAll('WINDOWSPHONE', 'WindowsPhone')
                /* Workaround for SPA jobs */
            } else if (item.contains('SPA')) {
                item
            } else if (item.contains('IOS')) {
                    'iOS'
            } else {
                item.toLowerCase().capitalize()
            }
        }.join('/')
        return "${s3BucketName}/${projectName}/${environment}/${channelPath}"
    }

    protected final getSCMConfiguration() {
        def scm
        def projectInSubfolder = (script.env.PROJECT_IN_SUBFOLDER?.trim()) ?: 'false'
        def checkoutSubfolder = (projectInSubfolder == 'true') ? '.' : projectName

        switch (gitURL) {
            case ~/^.*svn.*$/:
                scm = [$class                : 'SubversionSCM',
                       additionalCredentials : [],
                       excludedCommitMessages: '',
                       excludedRegions       : '',
                       excludedRevprop       : '',
                       excludedUsers         : '',
                       filterChangelog       : false,
                       ignoreDirPropChanges  : false,
                       includedRegions       : '',
                       locations             : [
                               [credentialsId        : "${gitCredentialsID}",
                                depthOption          : 'infinity',
                                ignoreExternalsOption: true,
                                local                : "${checkoutSubfolder}",
                                remote               : "${gitURL}"]
                       ],
                       workspaceUpdater      : [$class: 'UpdateUpdater']]
                break
            default:
                scm = [$class                           : 'GitSCM',
                       branches                         : [[name: "*/${gitBranch}"]],
                       doGenerateSubmoduleConfigurations: false,
                       extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                            relativeTargetDir: "${checkoutSubfolder}"]],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: "${gitCredentialsID}",
                                                            url          : "${gitURL}"]]]
                break
        }

        scm
    }

    protected final void checkoutProject() {
        String successMessage = 'Project has been checkout successfully'
        String errorMessage = 'FAILED to checkout the project'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: getSCMConfiguration()
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
                                      selectedRegion   : "${s3BucketRegion}",
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
    protected final void getBuildCause() {
        def causes = []
        def buildCauses = script.currentBuild.rawBuild.getCauses()
        def userIdCause = script.currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
        def triggeredBy = (userIdCause) ? userIdCause.getUserName() : ''

        for (cause in buildCauses) {
            if (cause instanceof hudson.model.Cause$UpstreamCause) {
                causes.add('upstream')
            } else if (cause instanceof hudson.model.Cause$RemoteCause) {
                causes.add('remote')
                triggeredBy = 'SCM'
            } else if (cause instanceof hudson.model.Cause$UserIdCause) {
                causes.add('user')
            } else {
                causes.add('unknown')
            }
        }

        if (causes.contains('upstream')) {
            buildCause = 'upstream'
        } else if (causes.contains('user')) {
            buildCause = 'user'
        }

        script.env['TRIGGERED_BY'] = "${triggeredBy}"
    }

    @NonCPS
    protected final void setS3ArtifactURL() {
        String s3ArtifactURL = 'https://' + 's3-' + s3BucketRegion + '.amazonaws.com/' + "${s3BucketName}/${projectName}/${environment}"
        script.env['S3_ARTIFACT_URL'] = s3ArtifactURL
    }

    protected final getArtifacts(extension) {
        def artifactsFiles
        String successMessage = 'Search finished successfully'
        String errorMessage = 'FAILED to search artifacts'

        script.catchErrorCustom(successMessage, errorMessage) {
            /* Dirty workaroud for Windows 10 Phone artifacts >>START */
            if (!isUnixNode) {
                if (script.fileExists("${workspace}\\temp\\${projectName}\\build\\windows10\\Windows10Mobile\\KonyApp\\AppPackages\\ARM\\${projectName}.appx")) {
                script.bat("mkdir ${workspace}\\${projectName}\\binaries\\windows\\windowsphone10\\ARM")
                script.bat("move ${workspace}\\temp\\${projectName}\\build\\windows10\\Windows10Mobile\\KonyApp\\AppPackages\\ARM\\${projectName}.appx ${workspace}\\${projectName}\\binaries\\windows\\windowsphone10\\ARM\\${projectName}.appx")
                }
                if (script.fileExists("${workspace}\\temp\\${projectName}\\build\\windows10\\Windows10Mobile\\KonyApp\\AppPackages\\x86\\${projectName}.appx")) {
                    script.bat("mkdir ${workspace}\\${projectName}\\binaries\\windows\\windowsphone10\\x86")
                    script.bat("move ${workspace}\\temp\\${projectName}\\build\\windows10\\Windows10Mobile\\KonyApp\\AppPackages\\x86\\${projectName}.appx ${workspace}\\${projectName}\\binaries\\windows\\windowsphone10\\x86\\${projectName}.appx")
                }
            }
            /* Dirty workaroud for Windows 10 Phone artifacts <<END */
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
        String artifactTargetName = projectName + '_' + jobBuildNumber + '.' + artifactExtension

        script.catchErrorCustom(successMessage, errorMessage) {
            for (int i=0; i < artifactsList.size(); ++i) {
                String artifactName = artifactsList[i].name
                String artifactPath = artifactsList[i].path
                String targetName = artifactTargetName.replaceFirst('_', getArtifactArchitecture(artifactPath))
                
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

    @NonCPS
    protected static final getArtifactArchitecture(artifactPath) {
        def architecture

        switch (artifactPath) {
            case ~/^.*ARM.*$/:
                architecture = '_ARM_'
                break
            case ~/^.*x86.*$/:
                architecture = '_X86_'
                break
            case ~/^.*x64.*$/:
                architecture = '_X64_'
                break
            default:
                break
        }

        architecture
    }

    @NonCPS
    protected final getChannelPath(channel) {
        def channelPath = channel.tokenize('_').collect() { item ->
            /* Workaround for windows phone jobs */
            if (item.contains('WINDOWSPHONE')) {
                item.replaceAll('WINDOWSPHONE', 'WindowsPhone')
                /* Workaround for SPA jobs */
            } else if (item.contains('SPA')) {
                item
            } else if (item.contains('IOS')) {
                'iOS'
            } else {
                item.toLowerCase().capitalize()
            }
        }.join('/')
        return channelPath
    }
}
