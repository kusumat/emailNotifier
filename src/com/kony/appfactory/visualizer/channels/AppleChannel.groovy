package com.kony.appfactory.visualizer.channels

class AppleChannel extends Channel {
    private bundleID
    private karFile = [:]
    private plistFileName

    /* Build parameters */
    private String matchType = script.params.APPLE_DEVELOPER_PROFILE_TYPE
    private String appleID = script.params.APPLE_ID
    private String matchGitURL = script.env.MATCH_GIT_URL

    AppleChannel(script) {
        super(script)
        nodeLabel = 'mac'
        plistFileName = "${projectName}_${mainBuildNumber}.plist"
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

            script.dir("${workspace}/KonyiOSWorkspace") {
                if (script.fileExists('iOS-plugin/iOS-GA-*.zip')) {
                    script.sh 'unzip iOS-GA-plugin/iOS-GA-*.zip'
                } else {
                    script.sh "cp ${visualizerDropinsPath}/com.kony.ios_*.jar iOS-plugin.zip"
                    script.sh 'unzip iOS-plugin.zip -d iOS-plugin'
                    script.sh 'unzip iOS-plugin/iOS-GA-*.zip'
                }
            }

            script.dir("${workspace}/KonyiOSWorkspace/VMAppWithKonylib/gen") {
                script.sh """
                    cp ${karFile.path}/${karFile.name} .
                    perl extract.pl ${karFile.name} sqd
                """
            }

            script.dir("${workspace}/KonyiOSWorkspace/VMAppWithKonylib") {
                fastLaneEnvWrapper() {
                    script.dir('fastlane') {
                        def fastFileName = 'Fastfile'
                        def fastFileContent = script.loadLibraryResource(resourceBasePath + fastFileName)
                        script.writeFile file: fastFileName, text: fastFileContent
                    }
                    script.sh '$HOME/.fastlane/bin/fastlane wildcard_build_' + fastLaneBuildCommand
                }
            }
        }
    }

    private final void createPlist() {
        String successMessage = 'PLIST file created successfully'
        String errorMessage = 'FAILED to create PLIST file'
        String plistResourcesFileName = 'apple_orig.plist'
        String plistPathTagValue = script.env.S3_ARTIFACT_URL

        script.catchErrorCustom(successMessage, errorMessage) {
            script.dir(artifacts[0].path) {
                /* Load property list file template */
                String plist = script.loadLibraryResource(resourceBasePath + plistResourcesFileName)

                /* Substitute required values */
                String plistUpdated = plist.replaceAll('\\$path', plistPathTagValue)
                        .replaceAll('\\$bundleIdentifier', bundleID)

                /* Write updated property list file to current working directory */
                script.writeFile file: plistFileName, text: plistUpdated
            }
        }
    }

    private final void fastLaneEnvWrapper(closure) {
        String codeSignIdentity = (matchType == 'development') ? 'iPhone Developer' : 'iPhone Distribution'

        script.withCredentials([
                script.usernamePassword(credentialsId: "${appleID}",
                        passwordVariable: 'FASTLANE_PASSWORD',
                        usernameVariable: 'MATCH_USERNAME'
                )
        ]) {
            script.withEnv([
                    "FASTLANE_DONT_STORE_PASSWORD=true",
                    "MATCH_APP_IDENTIFIER=${bundleID}",
                    "MATCH_GIT_URL=https://${script.env.MATCH_GIT_TOKEN}@${(matchGitURL - 'https://')}",
                    "GYM_CODE_SIGNING_IDENTITY=${codeSignIdentity}",
                    "GYM_OUTPUT_DIRECTORY=${karFile.path}",
                    "GYM_OUTPUT_NAME=${projectName}",
                    "FL_UPDATE_PLIST_DISPLAY_NAME=${projectName}",
                    "FL_PROJECT_SIGNING_PROJECT_PATH=${workspace}/KonyiOSWorkspace/VMAppWithKonylib/VMAppWithKonylib.xcodeproj",
                    "MATCH_TYPE=${matchType}"
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
            workspace = script.env.WORKSPACE
            projectFullPath = workspace + '/' + projectName
            artifactsBasePath = projectFullPath + '/binaries'

            try {
                script.deleteDir()

                script.stage('Checkout') {
                    checkoutProject()
                }

                script.stage('Build') {
                    build()
                    if (artifactExtension == 'war') {
                        /* Search for build artifacts */
                        def foundArtifacts = getArtifacts(artifactExtension)
                        /* Rename artifacts for publishing */
                        artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) : script.error('FAILED build artifacts are missing!')
                    } else {
                        /* Get KAR file name and path */
                        def transitArtifacts = getArtifacts('KAR')
                        karFile.name = transitArtifacts[0].name
                        karFile.path = artifactsBasePath + '/' + transitArtifacts[0].path.minus('/' + transitArtifacts[0].name)
                    }
                }

                /* Check to not sign artifacts if SPA chosen */
                if (artifactExtension != 'war') {
                    script.stage('Generate IPA file') {
                        createIPA()
                        /* Search for build artifacts */
                        def foundArtifacts = getArtifacts(artifactExtension)
                        /* Rename artifacts for publishing */
                        artifacts = (foundArtifacts) ? renameArtifacts(foundArtifacts) : script.error('FAILED build artifacts are missing!')
                    }

                    script.stage("Generate property list file") {
                        createPlist()
                        /* Get plist artifact */
                        artifacts.add([name: plistFileName, path: "${karFile.path}"])
                    }
                }

                script.stage("Publish artifact to S3") {
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
