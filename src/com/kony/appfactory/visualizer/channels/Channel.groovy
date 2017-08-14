package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper

class Channel implements Serializable {
    protected script
    protected artifacts
    protected separator
    protected visualizerDependencies
    protected boolean isUnixNode
    protected String workspace
    protected String projectFullPath
    protected String visualizerVersion
    protected String channelPath
    protected String channelName
    protected String artifactsBasePath
    protected String artifactExtension
    protected String s3ArtifactPath
    protected String nodeLabel
    protected final String resourceBasePath = 'com/kony/appfactory/visualizer/'

    /* Common build parameters */
    protected String projectName = script.env.PROJECT_NAME
    protected String gitCredentialsID = script.env.GIT_CREDENTIALS_ID
    protected String gitURL = script.env.PROJECT_GIT_URL
    protected String gitBranch = script.env.GIT_BRANCH
    protected String environment = script.env.ENVIRONMENT
    protected String cloudCredentialsID = script.env.CLOUD_CREDENTIALS_ID
    protected String jobBuildNumber = script.env.BUILD_NUMBER
    protected String buildMode = script.env.BUILD_MODE
    protected String mobileFabricAppConfig = script.env.MOBILE_FABRIC_APP_CONFIG

    Channel(script) {
        this.script = script
        channelPath = (this.script.env.JOB_NAME - 'Visualizer/Builds/' - "${projectName}/" - "${environment}/")
        channelName = channelPath.toUpperCase().replaceAll('/','_')
        this.script.env[channelName] = true // Exposing environment variable with channel to build
        artifactExtension = getArtifactExtension(channelName)
        s3ArtifactPath = ['Builds', environment, channelPath].join('/')
    }

    protected final void pipelineWrapper(closure) {
        /* Set environment-dependent variables */
        isUnixNode = script.isUnix()
        separator = (isUnixNode) ? '/' : '\\'
        workspace = script.env.WORKSPACE
        projectFullPath = [workspace, projectName].join(separator)
        artifactsBasePath = [projectFullPath, 'binaries'].join(separator)

        try {
            closure()
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echo "ERROR: $exceptionMessage"
            script.currentBuild.result = 'FAILURE'
        } finally {
            if (script.currentBuild.result == 'FAILURE') {
                NotificationsHelper.sendEmail(script, 'buildVisualizerApp')
            }
        }
    }

    protected final void visualizerEnvWrapper(closure) {
        /* Get Visualizer version */
        visualizerVersion = getVisualizerVersion(script.readFile('konyplugins.xml'))
        /* Get Visualizer dependencies */
        visualizerDependencies = (
                BuildHelper.getVisualizerDependencies(script, isUnixNode, separator, visualizerVersion)
        ) ?: script.error('Missing Visualizer dependencies!')
        def pathSeparator = ((isUnixNode) ? ':' : ';')
        def exposeToolPath = { variableName, homePath ->
            script.env[variableName] = homePath
        }
        def toolBinPath = visualizerDependencies.collect {
            exposeToolPath(it.variableName, it.homePath)
            it.binPath
        }.join(pathSeparator)

        script.withEnv(["PATH+TOOLS=${toolBinPath}"]) {
            script.withCredentials([script.usernamePassword(credentialsId: "${cloudCredentialsID}",
                    passwordVariable: 'CLOUD_PASS', usernameVariable: 'CLOUD_NAME')]) {
                closure()
            }
        }
    }

    protected final void build() {
        def requiredResources = ['property.xml', 'ivysettings.xml']

        script.catchErrorCustom('FAILED to build the project') {
            // Populate MobileFabric configuration to appfactory.js file
            populateMobileFabricAppConfig('appfactory.js')

            script.dir(projectFullPath) {
                /* Load required resources and store them in project folder */
                for (int i=0; i < requiredResources.size(); i++) {
                    String resource = script.loadLibraryResource(resourceBasePath + requiredResources[i])
                    script.writeFile file: requiredResources[i], text: resource
                }

                /* This wrapper responsible for adding ANT_HOME, JAVA_HOME and Kony Cloud credentials */
                visualizerEnvWrapper() {
                    script.shellCustom('ant -buildfile property.xml', isUnixNode)
                    script.shellCustom('ant', isUnixNode)
                }
            }
        }
    }

    /* Determine which Visualizer version project requires, according to the version of the keditor plugin */
    protected final getVisualizerVersion(text) {
        def matcher = text =~ '<pluginInfo version-no="(\\d+\\.\\d+\\.\\d+)\\.\\w*" plugin-id="com.pat.tool.keditor"'
        return matcher ? matcher[0][1] : null
    }

    protected final getArtifacts(extension) {
        def artifactsFiles

        script.catchErrorCustom('FAILED to search artifacts') {
            /* Dirty workaround for Windows 10 Phone artifacts >>START */
            if (!isUnixNode) {
                def platforms = ['x86', 'ARM']
                def targetFolderBasePath = [
                        workspace,
                        projectName,
                        'binaries',
                        'windows',
                        'windowsphone10'
                ].join(separator)
                def tempFolderBasePath = [
                        workspace,
                        'temp',
                        projectName,
                        'build',
                        'windows10',
                        'Windows10Mobile',
                        'KonyApp',
                        'AppPackages'
                ].join(separator)

                for (platform in platforms) {
                    def filePath = [tempFolderBasePath, platform, "${projectName}.appx"].join(separator)
                    def targetFolder = [targetFolderBasePath, platform].join(separator)

                    if (script.fileExists(filePath)) {
                        script.bat(['mkdir', targetFolder].join(' '))
                        script.bat(['move', filePath, targetFolder].join(' '))
                    }
                }
            }
            /* Dirty workaround for Windows 10 Phone artifacts <<END */

            script.dir(artifactsBasePath) {
                artifactsFiles = script.findFiles(glob: "**/*.${extension}")
            }
        }

        artifactsFiles
    }

    protected final renameArtifacts(artifactsList) {
        def renamedArtifacts = []
        String shellCommand = (isUnixNode) ? 'mv' : 'rename'

        script.catchErrorCustom('FAILED to rename artifacts') {
            for (int i=0; i < artifactsList.size(); ++i) {
                String artifactName = artifactsList[i].name
                String artifactPath = artifactsList[i].path
                String artifactTargetName = projectName + '_' + getArtifactArchitecture(artifactPath) +
                        jobBuildNumber + '.' + artifactExtension
                String targetArtifactFolder = artifactsBasePath +
                        separator +
                        (artifactsList[i].path.minus(separator + "${artifactsList[i].name}"))
                String command = "${shellCommand} ${artifactName} ${artifactTargetName}"

                /* Rename artifact */
                script.dir(targetArtifactFolder) {
                    script.shellCustom(command, isUnixNode)
                }

                renamedArtifacts.add([name: artifactTargetName, path: targetArtifactFolder])
            }
        }

        renamedArtifacts
    }

    protected final populateMobileFabricAppConfig(configFileName) {
        String successMessage = 'Fabric app key, secret and service URL were populated successfully'
        String errorMessage = 'FAILED to populate MobileFabric app key, secret and service URL'

        if (mobileFabricAppConfig) {
            script.dir(projectFullPath) {
                script.dir('modules') {
                    def updatedConfig = ''
                    def config = (script.fileExists(configFileName)) ?
                            script.readFile(configFileName) :
                            script.error("FAILED ${configFileName} not found!")

                    script.catchErrorCustom(errorMessage, successMessage) {
                        script.withCredentials([
                                script.fabricAppTriplet(
                                        credentialsId: mobileFabricAppConfig,
                                        applicationKeyVariable: 'APP_KEY',
                                        applicationSecretVariable: 'APP_SECRET',
                                        serviceUrlVariable: 'SERVICE_URL'
                                )
                        ]) {
                            updatedConfig = config.
                                    replaceAll('\\$FABRIC_APP_KEY', "\'${script.env.APP_KEY}\'").
                                    replaceAll('\\$FABRIC_APP_SECRET', "\'${script.env.APP_SECRET}\'").
                                    replaceAll('\\$FABRIC_APP_SERVICE_URL', "\'${script.env.SERVICE_URL}\'")
                        }

                        script.writeFile file: configFileName, text: updatedConfig
                    }
                }
            }
        } else {
            script.println "Skipping population of Fabric app key, secret and service URL, " +
                    "credentials parameter was not provided!"
        }
    }

    /**
     * Returns an artifact file extension depending on channel name.
     * Method's result is used as an input parameter for build artifacts search.
     *
     * @param channelName channel name string
     * @return            the artifact extension string
     */
    @NonCPS
    protected final getArtifactExtension(channelName) {
        def artifactExtension

        switch (channelName) {
            case ~/^.*SPA.*$/:
                artifactExtension = 'war'
                break
            case ~/^.*WINDOWS_TABLET.*$/:
            case ~/^.*WINDOWS_MOBILE.*$/:
                artifactExtension = 'appx'
                break
            case ~/^.*WINDOWS.*$/:
                artifactExtension = 'xap'
                break
            case ~/^.*IOS.*$/:
                artifactExtension = 'ipa'
                break
            case ~/^.*ANDROID.*$/:
                artifactExtension = 'apk'
                break
            default:
                artifactExtension = ''
                break
        }

        artifactExtension
    }

    /**
     * Returns an artifact architecture depending on location of the artifact after the build.
     * Method's result is substituted to the artifact name.
     *
     * @param artifactPath location of the artifact after the build
     * @return             the artifact architecture string
     */
    @NonCPS
    protected final getArtifactArchitecture(artifactPath) {
        def artifactArchitecture

        switch (artifactPath) {
            case ~/^.*ARM.*$/:
                artifactArchitecture = 'ARM_'
                break
            case ~/^.*x86.*$/:
                artifactArchitecture = 'x86_'
                break
            case ~/^.*x64.*$/:
                artifactArchitecture = 'x64_'
                break
            default:
                artifactArchitecture = ''
                break
        }

        artifactArchitecture
    }
}
