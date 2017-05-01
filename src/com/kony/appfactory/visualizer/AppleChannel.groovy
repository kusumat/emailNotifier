package com.kony.appfactory.visualizer

class AppleChannel implements Serializable {
    private script
    private bundleID
    private boolean isUnixNode
    private String workSpace
    private String projectFullPath
    private String nodeLabel = 'mac'
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
    private String matchType = script.params.MATCH_TYPE
    private String appleID = script.params.APPLE_ID
    private String matchPassword = script.params.MATCH_PASSWORD
    private String matchGitToken = script.params.MATCH_GIT_TOKEN
    private String matchGitURL = script.params.MATCH_GIT_URL
    private String s3BucketName = script.params.S3_BUCKET_NAME
    private String environment = script.params.ENVIRONMENT

    private String artifactFileName = projectName + '_' + mainBuildNumber + '.ipa'

    AppleChannel(script) {
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
                        [$class: 'CredentialsParameterDefinition', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Apple ID credentials', name: 'APPLE_ID', required: true],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The Encryption password', name: 'MATCH_PASSWORD', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'GitHub access token', name: 'MATCH_GIT_TOKEN', required: false],
                        script.stringParam( name: 'VIZ_VERSION', defaultValue: '7.2.1', description: 'Kony Vizualizer version' ),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam( name: 'S3_BUCKET_NAME', defaultValue: '', description: 'S3 Bucket Name' ),
                        script.stringParam( name: 'MATCH_GIT_URL', defaultValue: '', description: 'URL to the git repo containing all the certificates (On-premises only!)' ),
                        script.choice( name: 'MATCH_TYPE', choices: "appstore\nadhoc\ndevelopment\nenterprise", defaultValue: 'development', description: 'Define the signing profile type' ),
                        script.booleanParam( name: 'IPHONE', defaultValue: true, description: 'Select the box if your build is for Apple iPhone' )
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

    private final void createIPA() {
        String successMessage = 'IPA file created successfully'
        String errorMessage = 'FAILED to create IPA file'
        String fastLaneBuildCommand = (buildMode == 'release') ? 'release' : 'debug'
        String visualizerDropinsPath = '/Jenkins/KonyVisualizerEnterprise' +
                visualizerVersion + '/Kony_Visualizer_Enterprise/dropins'

        catchErrorCustom(successMessage, errorMessage) {
            /* Get bundle identifier */
            script.dir(projectFullPath) {
                bundleID = bundleIdentifier(script.readFile('projectprop.xml'))
            }

            script.dir("${workSpace}/KonyiOSWorkspace") {
                if (script.fileExists('iOS-plugin/iOS-GA-*.zip')) {
                    script.sh 'unzip iOS-GA-plugin/iOS-GA-*.zip'
                } else {
                    script.sh "cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip"
                    script.sh 'unzip iOS-plugin.zip -d iOS-plugin'
                    script.sh 'unzip iOS-plugin/iOS-GA-*.zip'
                }
            }

            script.dir("${workSpace}/KonyiOSWorkspace/VMAppWithKonylib/gen") {
                script.sh """
                    cp ${workSpace}/${projectName}/binaries/iphone/konyappiphone.KAR .
                    perl extract.pl konyappiphone.KAR sqd
                """
            }

            script.dir("${workSpace}/KonyiOSWorkspace/VMAppWithKonylib") {
                fastLaneEnvWrapper() {
                    script.dir('fastlane') {
                        script.sh 'cp -f $HOME/fastlane/Fastfile .'
                    }
                    script.sh '$HOME/.fastlane/bin/fastlane wildcard_build_' + fastLaneBuildCommand
                }
            }
        }
    }

    private final void fastLaneEnvWrapper(closure) {
        String codeSignIdentity = (matchType == 'development') ? 'iPhone Developer' : 'iPhone Distribution'

        script.withCredentials([
                script.usernamePassword(credentialsId: "${appleID}",
                        passwordVariable: 'FASTLANE_PASSWORD',
                        usernameVariable: 'MATCH_USERNAME'
                ),
                script.string(credentialsId: "${matchPassword}", variable: 'MATCH_PASSWORD'),
                script.string(credentialsId: "${matchGitToken}", variable: 'MATCH_GIT_TOKEN')
        ]) {
            script.withEnv([
                    "FASTLANE_DONT_STORE_PASSWORD=true",
                    "MATCH_APP_IDENTIFIER=${bundleID}",
                    "MATCH_GIT_URL=https://${script.env.MATCH_GIT_TOKEN}@${(matchGitURL - 'https://')}",
                    "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                    "GYM_OUTPUT_DIRECTORY=${projectFullPath + 'binaries/iphone'}",
                    "GYM_OUTPUT_NAME=${projectName}",
                    "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}",
                    "FL_PROJECT_SIGNING_PROJECT_PATH=${workSpace}/KonyiOSWorkspace/VMAppWithKonylib/VMAppWithKonylib.xcodeproj"
            ]) {
                closure()
            }
        }
    }

    @NonCPS
    private final bundleIdentifier(text) {
        def matcher = text =~ '<attributes name="iphonebundleidentifierkey" value="(.+)"/>'
        return matcher ? matcher[0][1] : null
    }

    private final void publishToS3() {
        String successMessage = 'Artifact published successfully'
        String errorMessage = 'FAILED to publish artifact'

        catchErrorCustom(successMessage, errorMessage) {
            script.dir("${projectFullPath + 'binaries/iphone'}") {
                script.sh "mv ${projectName}.ipa ${artifactFileName}"

                script.step([$class                              : 'S3BucketPublisher',
                             consoleLogLevel                     : 'INFO',
                             dontWaitForConcurrentBuildCompletion: false,
                             entries                             : [
                                     [bucket           : "${s3BucketName}/${projectName}/${environment}/iphone",
                                      flatten          : true,
                                      keepForever      : true,
                                      managedArtifacts : false,
                                      noUploadOnFailure: true,
                                      selectedRegion   : 'eu-west-1',
                                      sourceFile       : "${artifactFileName}"]
                             ],
                             pluginFailureResultConstraint       : 'FAILURE'])
            }
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
        String emailTemplateFolder = 'email/templates/'
        String emailTemplateName = 'Kony_OTA_Installers.jelly'
        String emailBody = '${JELLY_SCRIPT, template="' + emailTemplateName + '"}'
        String emailSubject = "${script.currentBuild.currentResult}: ${projectName} (${script.env.JOB_NAME}-#${script.env.BUILD_NUMBER})"
        String emailRecipientsList = 'KonyAppFactoryTeam@softserveinc.com'

        /* Load email template */
        String emailTemplate = loadLibraryResource(resourceBasePath + emailTemplateFolder + emailTemplateName)
        script.writeFile file: emailTemplateName, text: emailTemplate

        /* Sending email */
        script.emailext body: emailBody, subject: emailSubject, to: emailRecipientsList
    }

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = workSpace + '/' + projectName + '/'

            try {
                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                }

                script.stage('Make an IPA') {
                    createIPA()
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
