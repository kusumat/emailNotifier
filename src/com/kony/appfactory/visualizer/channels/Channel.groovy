package com.kony.appfactory.visualizer.channels

import com.kony.appfactory.fabric.Fabric
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import java.util.regex.Matcher

class Channel implements Serializable {
    protected script
    protected artifacts = []
    protected separator
    protected pathSeparator
    protected visualizerDependencies
    protected buildArtifacts
    protected fabric
    protected isUnixNode
    protected workspace
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    /* If projectRoot value has been provide, than value of this property will be set to <job_workspace>/vis_ws/<project_name>
     * otherwise it will be set to <job_workspace>/vis_ws */
    protected projectWorkspacePath
    /* Absolute path to the project folder (<job_workspace>/vis_ws/<project_name>[/<project_root>]) */
    protected projectFullPath
    /* Visualizer home folder, slave(build-node) dependent value, fetched from environment variables of the slave */
    protected visualizerHome
    protected visualizerVersion
    protected channelPath
    protected channelVariableName
    protected channelType
    protected channelOs
    protected artifactsBasePath
    protected artifactExtension
    protected s3ArtifactPath
    protected libraryProperties
    /* Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and can not be used */
    final projectWorkspaceFolderName
    final resourceBasePath
    /* Common build parameters */
    protected final gitCredentialsID = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    protected final gitBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    protected final cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    protected final buildMode = script.params.BUILD_MODE
    protected final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    protected channelFormFactor = script.params.FORM_FACTOR
    /* Common environment variables */
    protected final projectName = script.env.PROJECT_NAME
    protected final projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')
    protected final gitURL = script.env.PROJECT_GIT_URL
    protected final jobBuildNumber = script.env.BUILD_NUMBER

    Channel(script) {
        this.script = script
        libraryProperties = BuildHelper.loadLibraryProperties(this.script, 'com/kony/appfactory/configurations/common.properties')
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        resourceBasePath = libraryProperties.'project.resources.base.path'
        fabric = new Fabric(this.script)
        /* Expose Kony global variables to use them in HeadlessBuild.properties */
        this.script.env['CLOUD_ACCOUNT_ID'] = (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.println libraryProperties
    }

    protected final void pipelineWrapper(closure) {
        /* Expose Fabric configuration */
        BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
            /* Workaround to fix masking of the values from fabricAppTriplet credentials build parameter,
                to not mask required values during the build we simply need redefine parameter values.
                Also, because of the case, when user didn't provide some not mandatory values we can get null value
                and script.env object returns only String values, been added elvis operator for assigning variable value
                as ''(empty). */
            script.env.FABRIC_APP_NAME = (script.env.FABRIC_APP_NAME) ?: ''
            script.env.FABRIC_ENV_NAME = (script.env.FABRIC_ENV_NAME) ?: ''
        }
        /* Set environment-dependent variables */
        isUnixNode = script.isUnix()
        separator = isUnixNode ? '/' : '\\'
        pathSeparator = isUnixNode ? ':' : ';'
        workspace = script.env.WORKSPACE
        visualizerHome = script.env.VISUALIZER_HOME
        checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
        projectWorkspacePath = (projectRoot) ?
                ([workspace, checkoutRelativeTargetFolder] + projectRoot.dropRight(1))?.join(separator) :
                [workspace, projectWorkspaceFolderName]?.join(separator)
        /* Expose Visualizer workspace to environment variables to use it in HeadlessBuild.properties */
        script.env['PROJECT_WORKSPACE'] = projectWorkspacePath
        projectFullPath = [workspace, checkoutRelativeTargetFolder, projectRoot?.join(separator)].findAll().join(separator)
        channelPath = [channelOs, channelFormFactor, channelType].unique().join('/')
        channelVariableName = channelPath.toUpperCase().replaceAll('/', '_')
        /* Expose channel to build to environment variables to use it in HeadlessBuild.properties */
        script.env[channelVariableName] = true
        s3ArtifactPath = ['Builds', script.env.FABRIC_ENV_NAME, channelPath].join('/')
        artifactsBasePath = getArtifactTempPath(projectWorkspacePath, projectName, separator, channelVariableName) ?:
                script.error('Artifacts base path is missing!')
        artifactExtension = getArtifactExtension(channelVariableName) ?:
                script.error('Artifacts extension is missing!')

        try {
            closure()
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echo "ERROR: $exceptionMessage"
            script.currentBuild.result = 'FAILURE'
        } finally {
            setBuildDescription()

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
                BuildHelper.getVisualizerDependencies(script, isUnixNode, separator, visualizerHome, visualizerVersion)
        ) ?: script.error('Missing Visualizer dependencies!')
        def exposeToolPath = { variableName, homePath ->
            script.env[variableName] = homePath
        }
        def toolBinPath = visualizerDependencies.collect {
            exposeToolPath(it.variableName, it.homePath)
            it.binPath
        }.join(pathSeparator)

        def credentialsTypeList = [script.usernamePassword(credentialsId: "${cloudCredentialsID}",
                passwordVariable: 'CLOUD_PASSWORD', usernameVariable: 'CLOUD_USERNAME')]

        if (channelOs == 'Android' && script.params.GOOGLE_MAPS_KEY_ID) {
            credentialsTypeList.add(
                    script.string(credentialsId: "${script.params.GOOGLE_MAPS_KEY_ID}", variable: 'GOOGLE_MAPS_KEY')
            )
        }

        script.withEnv(["PATH+TOOLS=${toolBinPath}"]) {
            script.withCredentials(credentialsTypeList) {
                closure()
            }
        }
    }

    protected final void build() {
        def requiredResources = ['property.xml', 'ivysettings.xml']

        // Populate Fabric configuration to appfactory.js file
        populateFabricAppConfig()

        script.catchErrorCustom('FAILED to build the project') {
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

    /*
        Determine which Visualizer version project requires,
        according to the version that matches first in the order of branding/studioviz/keditor plugin
    */
    protected final getVisualizerVersion(text) {
	    String visualizerVersion = ''
        def plugins = [
                'Branding': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.ide.paas.branding"/,
                'Studioviz win64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.win64"/,
                'Studioviz mac64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.mac64"/,
                'KEditor': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.pat.tool.keditor"/
        ]

        plugins.find { pluginName, pluginSearchPattern ->
            if (text =~ pluginSearchPattern) {
                script.echo "Found $pluginName plugin!"
                visualizerVersion = Matcher.lastMatcher[0][1]
                /* Return true to break the find loop, if at least one much been found */
                return true
            } else {
                script.echo "Could not find $pluginName plugin entry... Switching to the next plugin to search..."
            }
        }

        visualizerVersion
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
            for (int i = 0; i < buildArtifacts?.size(); ++i) {
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

    protected final void populateFabricAppConfig() {
        String configFileName = libraryProperties.'fabric.config.file.name'
        String successMessage = 'Fabric app key, secret and service URL were successfully populated'
        String errorMessage = 'FAILED to populate Fabric app key, secret and service URL'

        if (fabricAppConfig) {
            script.dir(projectFullPath) {
                script.dir('modules') {
                    def updatedConfig = ''
                    def config = (script.fileExists(configFileName)) ?
                            script.readFile(configFileName) :
                            script.error("FAILED ${configFileName} not found!")

                    script.catchErrorCustom(errorMessage, successMessage) {
                        BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                            updatedConfig = config.
                                    replaceAll('\\$FABRIC_APP_KEY', "\'${script.env.APP_KEY}\'").
                                    replaceAll('\\$FABRIC_APP_SECRET', "\'${script.env.APP_SECRET}\'").
                                    replaceAll('\\$FABRIC_APP_SERVICE_URL', "\'${script.env.SERVICE_URL}\'")

                            script.writeFile file: configFileName, text: updatedConfig
                        }
                    }
                }
            }
        } else {
            script.echo "Skipping population of Fabric app key, secret and service URL, " +
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

    protected final getArtifactTempPath(projectWorkspacePath, projectName, separator, channelVariableName) {
        def artifactsTempPath

        def getPath = {
            def tempBasePath = [projectWorkspacePath, 'temp', projectName]
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
            case 'WINDOWS81_MOBILE_NATIVE':
                artifactsTempPath = getPath(['build', 'winphone8'])
                break
            case 'WINDOWS10_MOBILE_NATIVE':
            case 'WINDOWS10_TABLET_NATIVE':
                artifactsTempPath = getPath(['build', 'windows10'])
                break
            case 'WINDOWS81_TABLET_NATIVE':
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
            case ~/^.*WINDOWS81_MOBILE.*$/:
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

    protected final void setBuildDescription() {
        script.currentBuild.description = """\
        <div id="build-description">
            <p>Environment: ${script.env.FABRIC_ENV_NAME}</p>
            <p>Channel: $channelVariableName</p>
        </div>\
        """.stripIndent()
    }
}
