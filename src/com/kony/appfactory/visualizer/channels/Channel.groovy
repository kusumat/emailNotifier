package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper

class Channel implements Serializable {
    protected script
    protected artifacts
    protected separator
    protected pathSeparator
    protected visualizerDependencies
    protected buildArtifacts
    protected boolean isUnixNode
    protected String workspace
    protected String projectFullPath
    protected String visualizerVersion
    protected String channelPath
    protected String channelVariableName
    protected String channelOs
    protected String channelFormFactor
    protected String channelType
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
    protected String mobileFabricAppConfig = script.env.FABRIC_APP_CONFIG

    Channel(script) {
        this.script = script
        channelOs = (script.env.CHANNEL_OS) ?: script.env.JOB_BASE_NAME - 'build'
        channelType = (channelOs.contains('Spa')) ? 'SPA' : 'Native'
        script.println channelType
        if (channelType != 'SPA') {
            channelFormFactor = script.env.CHANNEL_FORMFACTOR
            channelPath = [channelOs, channelFormFactor, channelType].join('/')
            channelVariableName = channelPath.toUpperCase().replaceAll('/','_')
            script.println channelVariableName
            script.env[channelVariableName] = true // Exposing environment variable with channel to build
            artifactExtension = getArtifactExtension(channelVariableName)
            script.println artifactExtension
            s3ArtifactPath = ['Builds', environment, channelPath].join('/')
        } else {
            artifactExtension = 'war'
            s3ArtifactPath = ['Builds', environment, channelType].join('/')
        }
        script.println s3ArtifactPath
    }

    protected final void pipelineWrapper(closure) {
        /* Set environment-dependent variables */
        isUnixNode = script.isUnix()
        separator = (isUnixNode) ? '/' : '\\'
        pathSeparator = ((isUnixNode) ? ':' : ';')
        workspace = script.env.WORKSPACE
        projectFullPath = [workspace, projectName].join(separator)
        artifactsBasePath = (
                (channelType == 'SPA') ?
                        getArtifactTempPath(workspace, projectName, separator, channelType):
                        getArtifactTempPath(workspace, projectName, separator, channelVariableName)
        ) ?: script.error('Artifacts path is missing!')
        script.println artifactsBasePath

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

    protected final getArtifactLocations(artifactExtension) {
        (artifactExtension) ?: script.error("artifactExtension argument can't be null")

        def files = null
        def artifactLocations = []

        script.catchErrorCustom('FAILED to search build artifacts!') {
            script.dir(artifactsBasePath) {
                files = script.findFiles glob: getSearchGlob(artifactExtension)
            }
        }

        for (file in files) {
            def filePath = (file.path == file.name) ? artifactsBasePath :
                    [artifactsBasePath, file.path.minus(separator + file.name)].join(separator)

            artifactLocations.add([name: file.name, path: filePath, extension: artifactExtension])
        }

        artifactLocations
    }

    protected final renameArtifacts(buildArtifacts) {
        def renamedArtifacts = []
        String shellCommand = (isUnixNode) ? 'mv' : 'rename'

        script.catchErrorCustom('FAILED to rename artifacts') {
            for (int i = 0; i < buildArtifacts.size(); ++i) {
                def artifact = buildArtifacts[i]
                String artifactName = artifact.name
                String artifactPath = artifact.path
                String artifactExtension = artifact.extension
                String artifactTargetName = projectName + '_' +
                        getArtifactArchitecture([artifactPath, artifactName].join(separator)) +
                        jobBuildNumber + '.' + artifactExtension
                String command = [shellCommand, artifactName, artifactTargetName].join(' ')

                // Workaround for Android binaries
                if (channelVariableName?.contains('ANDROID') && !artifactName.contains(buildMode)) {
                    continue
                }

                /* Rename artifact */
                script.dir(artifactPath) {
                    script.shellCustom(command, isUnixNode)
                }

                renamedArtifacts.add([name: artifactTargetName, path: artifactPath])
            }
        }

        renamedArtifacts
    }

    protected final populateMobileFabricAppConfig(configFileName) {
        String successMessage = 'MobileFabric app key, secret and service URL were populated successfully'
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
            script.println "Skipping population of MobileFabric app key, secret and service URL, " +
                    "credentials parameter was not provided!"
        }
    }

    protected final getSearchGlob(artifactExtension) {
        def searchGlob

        switch (artifactExtension) {
            case ~/^.*apk.*$/:
                searchGlob = '**/' + projectName + '-' + buildMode + '*.' + artifactExtension
                break
            case ~/^.*KAR.*$/:
                searchGlob = '**/*.' + artifactExtension
                break
            case ~/^.*xap.*$/:
                searchGlob = '**/WindowsPhone*.' + artifactExtension
                break
            case ~/^.*appx.*$/:
            case ~/^.*war.*$/:
                searchGlob = '**/' + projectName + '.' + artifactExtension
                break
            default:
                searchGlob = '**/*.' + artifactExtension
                break
        }

        searchGlob
    }

    protected final getArtifactTempPath(workspace, projectName, separator, channelVariableName) {
        def artifactsTempPath

        def getPath = {
            def tempBasePath = [workspace, 'temp', projectName]
            (tempBasePath + it).join(separator)
        }

        switch (channelVariableName) {
            case 'ANDROID_MOBILE_NATIVE':
                artifactsTempPath = getPath(['build', 'luaandroid', 'dist', projectName, 'build', 'outputs', 'apk'])
                break
            case 'ANDROID_TABLET_NATIVE':
                artifactsTempPath = getPath(['build', 'luatabrcandroid', 'dist', projectName, 'build', 'outputs', 'apk'])
                break
            case 'IOS_MOBILE_NATIVE':
                artifactsTempPath = getPath(['build', 'server', 'iphonekbf'])
                break
            case 'IOS_TABLET_NATIVE':
                artifactsTempPath = getPath(['build', 'server', 'ipadkbf'])
                break
            case 'WINDOWS_MOBILE_WINDOWSPHONE8':
            case 'WINDOWS_MOBILE_WINDOWSPHONE81S':
                artifactsTempPath = getPath(['build', 'winphone8'])
                break
            case 'WINDOWS_MOBILE_WINDOWSPHONE10':
            case 'WINDOWS_TABLET_WINDOWS10':
                artifactsTempPath = getPath(['build', 'windows10'])
                break
            case 'WINDOWS_TABLET_WINDOWS81':
                artifactsTempPath = getPath(['build', 'windows8'])
                break
            case ~/^.*SPA.*$/:
                artifactsTempPath = getPath(['middleware_mobileweb'])
                break
            default:
                artifactsTempPath = ''
                break
        }

        artifactsTempPath
    }

    /**
     * Returns an artifact file extension depending on channel name.
     * Method's result is used as an input parameter for build artifacts search.
     *
     * @param channelName channel name string
     * @return            the artifact extension string
     */
    @NonCPS
    protected final getArtifactExtension(channelVariableName) {
        def artifactExtension

        switch (channelVariableName) {
            case ~/^.*SPA.*$/:
                artifactExtension = 'war'
                break
            case ~/^.*ANDROID.*$/:
                artifactExtension = 'apk'
                break
            case ~/^.*IOS.*$/:
                artifactExtension = 'KAR'
                break
            case ~/^.*WINDOWS8_MOBILE.*$/:
                artifactExtension = 'xap'
                break
            case ~/^.*WINDOWS.*$/:
                artifactExtension = 'appx'
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
