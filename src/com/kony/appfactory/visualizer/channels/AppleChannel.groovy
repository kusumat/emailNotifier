package com.kony.appfactory.visualizer.channels

class AppleChannel extends Channel {
    private bundleID
    private String nodeLabel = 'mac'
    private String artifactExtension = 'ipa'

    /* Build parameters */
    private String matchType = script.params.MATCH_TYPE
    private String appleID = script.params.APPLE_ID
    private String matchPassword = script.params.MATCH_PASSWORD
    private String matchGitToken = script.params.MATCH_GIT_TOKEN
    private String matchGitURL = script.params.MATCH_GIT_URL

    private String artifactFileName = projectName + '_' + mainBuildNumber + '.' + artifactExtension

    AppleChannel(script) {
        super(script)
        setBuildParameters()
    }

    @NonCPS
    private final void setBuildParameters() {
        script.properties([
                script.parameters([
                        script.stringParam(name: 'PROJECT_NAME', defaultValue: '', description: 'Project Name'),
                        script.stringParam(name: 'GIT_URL', defaultValue: '', description: 'Project Git URL'),
                        script.stringParam(name: 'GIT_BRANCH', defaultValue: '', description: 'Project Git Branch'),
                        [$class: 'CredentialsParameterDefinition', name: 'GIT_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'GitHub.com Credentials', required: true],
                        script.stringParam(name: 'MAIN_BUILD_NUMBER', defaultValue: '', description: 'Build Number for artifact'),
                        script.choice(name: 'BUILD_MODE', choices: "debug\nrelease", defaultValue: '', description: 'Choose build mode (debug or release)'),
                        script.choice(name: 'ENVIRONMENT', choices: "dev\nqa\nrelease", defaultValue: 'dev', description: 'Define target environment'),
                        [$class: 'CredentialsParameterDefinition', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Apple ID credentials', name: 'APPLE_ID', required: true],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'The Encryption password', name: 'MATCH_PASSWORD', required: false],
                        [$class: 'CredentialsParameterDefinition', credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: '', description: 'GitHub access token', name: 'MATCH_GIT_TOKEN', required: false],
                        script.stringParam(name: 'VIZ_VERSION', defaultValue: '7.2.1', description: 'Kony Vizualizer version'),
                        [$class: 'CredentialsParameterDefinition', name: 'CLOUD_CREDENTIALS_ID', credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl', defaultValue: '', description: 'Cloud Mode credentials (Applicable only for cloud)', required: true],
                        script.stringParam(name: 'S3_BUCKET_NAME', defaultValue: '', description: 'S3 Bucket Name'),
                        script.stringParam(name: 'MATCH_GIT_URL', defaultValue: '', description: 'URL to the git repo containing all the certificates (On-premises only!)'),
                        script.choice(name: 'MATCH_TYPE', choices: "appstore\nadhoc\ndevelopment\nenterprise", defaultValue: 'development', description: 'Define the signing profile type')
                ])
        ])
    }

    private final void createIPA() {
        String successMessage = 'IPA file created successfully'
        String errorMessage = 'FAILED to create IPA file'
        String fastLaneBuildCommand = (buildMode == 'release') ? 'release' : 'debug'
        String visualizerDropinsPath = '/Jenkins/KonyVisualizerEnterprise' +
                visualizerVersion + '/Kony_Visualizer_Enterprise/dropins'

        script.catchErrorCustom(successMessage, errorMessage) {
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
                    cp ${artifactPath}/konyappiphone.KAR .
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

            script.dir(artifactPath) {
                script.sh "mv ${projectName}.${artifactExtension} ${artifactFileName}"
            }
        }
    }

    private final void createPlist() {
        String successMessage = 'PLIST file created successfully'
        String errorMessage = 'FAILED to create PLIST file'
        String awsRegion = 'us-west-1'
        String plistFileName = 'apple_orig.plist'
        String plistPathTagValue = 'https://s3-' + awsRegion + '.amazonaws.com/' + s3BucketName + '/' +
                projectName + '/' + environment + '/' + 'iphone' + '/' + projectName + '_' +
                mainBuildNumber + '.ipa'

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifactPath) {
                /* Load property list file template */
                String plist = script.loadLibraryResource(resourceBasePath + plistFileName)

                /* Substitute required values */
                String plistUpdated = plist.replaceAll('\\$path', plistPathTagValue)
                        .replaceAll('\\$bundleIdentifier', bundleID)

                /* Write updated property list file to current working directory */
                script.writeFile file: "${projectName}_${mainBuildNumber}.plist", text: plistUpdated
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
                    "GYM_OUTPUT_DIRECTORY=${artifactPath}",
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

    protected final void createWorkflow() {
        script.node(nodeLabel) {
            /* Set environment-dependent variables */
            isUnixNode = script.isUnix()
            workSpace = script.env.WORKSPACE
            projectFullPath = workSpace + '/' + projectName
            artifactPath = projectFullPath + '/binaries/iphone'

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                }

                script.stage('Generate IPA file') {
                    createIPA()
                }

                script.stage("Generate property list file") {
                    createPlist()
                }

                script.stage("Publish artifact to S3") {
                    publishToS3 artifact: artifactFileName
                    publishToS3 artifact: projectName + '_' + mainBuildNumber + '.plist'
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
