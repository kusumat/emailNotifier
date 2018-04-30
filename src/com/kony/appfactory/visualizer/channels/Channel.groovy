package com.kony.appfactory.visualizer.channels

import java.util.regex.Matcher

import com.kony.appfactory.fabric.Fabric
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper

/**
 * Implements logic for building channels. Contains common methods that are used during the channel build.
 */
class Channel implements Serializable {
    /* Pipeline object */
    protected script
    /*
        List of channel artifacts in format:
            [channelPath: <relative path to the artifact on S3>, name: <artifact file name>, url: <S3 artifact URL>]
     */
    protected artifacts = []
    /*
     List of artifacts to be captured for the must haves to debug
    */
    protected mustHaveArtifacts = []
    protected mustHaves = []
    protected mustHavePath
    protected String upstreamJob = null
    protected isRebuild = false
    protected String s3MustHaveAuthUrl
    /*
        Platform dependent default name-separator character as String.
        For windows, it's '\' and for unix it's '/'.
     */
    protected separator
    /*
        Platform dependent variable for path-separator.
        For example PATH or CLASSPATH variable list of paths separated by ':' in Unix systems and ';' in Windows system.
     */
    protected pathSeparator
    /* Stores Visualizer dependencies list */
    protected visualizerDependencies
    /* Build artifacts */
    protected buildArtifacts
    /*
        Contains instance of Fabric class for publishing Fabric application,
        if PUBLISH_FABRIC_APP build parameter set to true.
     */
    protected fabric
    protected fabricEnvName
    /*
        Flag stores slave OS type, mostly used in shellCustom step,
        or set environment dependent variables, or run OS dependent steps
     */
    protected isUnixNode
    /* Job workspace path */
    protected workspace
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    /*
        If projectRoot value has been provide, than value of this property
        will be set to <job_workspace>/vis_ws/<project_name> otherwise it will be set to <job_workspace>/vis_ws
     */
    protected projectWorkspacePath
    /* Absolute path to the project folder (<job_workspace>/vis_ws/<project_name>[/<project_root>]) */
    protected projectFullPath
    /* Visualizer home folder, slave(build-node) dependent value, fetched from environment variables of the slave */
    protected visualizerHome
    /* Visualizer version */
    protected visualizerVersion
    /*
        Channel relative path on S3, used for storing artifacts on S3 according to agreed bucket structure,
        also used in e-mail notifications.
     */
    protected channelPath
    /*
        Channel build parameter name (exposed as environment variables),
        used for exposing channel to build in HeadlessBuild.properties.
     */
    protected channelVariableName
    /* Channel type: Native or SPA */
    protected channelType
    /* Channel OS type: Android or iOS or Windows or SPA */
    protected channelOs
    /* Temp folder location where to search build artifacts */
    protected artifactsBasePath
    /* Artifact extension, depends on channel type */
    protected artifactExtension
    /* Path for storing artifact on S3 bucket, has following format: Builds/<Fabric environment name>/channelPath */
    protected s3ArtifactPath
    /* Library configuration */
    protected libraryProperties
	/* Domain Param to be used for the SPA channel deployment on non prod environments */
	protected domainParam
    /*
        Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and
        can not be used.
     */
    final projectWorkspaceFolderName
    /* Base path for build resources (plist file template, ivysettings.xml, property.xml, Fastfile, etc.) */
    final resourceBasePath
    /* Common build parameters */
    protected final scmCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    protected final scmBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    protected final cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    protected final buildMode = script.params.BUILD_MODE
    protected final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    protected channelFormFactor = script.params.FORM_FACTOR
    /* Common environment variables */
    protected final projectName = script.env.PROJECT_NAME
    protected final projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')
    protected final scmUrl = script.env.PROJECT_SOURCE_CODE_URL
    protected final jobBuildNumber = script.env.BUILD_NUMBER

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    Channel(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        resourceBasePath = libraryProperties.'project.resources.base.path'
        /*
            Instantiate Fabric object for publishing Fabric apps,
            because of lack of information about publishing native apps,
            this step been moved here to be able to add support for publishing native applications if needed.
         */
        fabric = new Fabric(this.script)
        /* Expose Kony global variables to use them in HeadlessBuild.properties */
        this.script.env['CLOUD_ACCOUNT_ID'] = (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'

        if (this.script.env.CLOUD_DOMAIN && this.script.env.CLOUD_DOMAIN.indexOf("-kony.com") > 0 ){
            this.script.env.domainParam = this.script.env.CLOUD_DOMAIN.substring(0, this.script.env.CLOUD_DOMAIN.indexOf("-kony.com"))
        }
    }

    /**
     * Wraps block of code with required steps for every build pipeline.
     *
     * @param closure block of code that implements build pipeline.
     */
    protected final void pipelineWrapper(closure) {
        /* Expose Fabric configuration */
        if (fabricAppConfig) {
            BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                /* Workaround to fix masking of the values from fabricAppTriplet credentials build parameter,
                to not mask required values during the build we simply need redefine parameter values.
                Also, because of the case, when user didn't provide some not mandatory values we can get null value
                and script.env object returns only String values, been added elvis operator for assigning variable
                value as ''(empty). */
                script.env.FABRIC_APP_NAME = (script.env.FABRIC_APP_NAME) ?: ''
                script.env.FABRIC_ENV_NAME = (script.env.FABRIC_ENV_NAME) ?: ''
            }
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
        projectFullPath = [
                workspace, checkoutRelativeTargetFolder, projectRoot?.join(separator)
        ].findAll().join(separator)
        channelPath = [channelOs, channelFormFactor, channelType].unique().join('/')
        channelVariableName = channelPath.toUpperCase().replaceAll('/', '_')
        /* Expose channel to build to environment variables to use it in HeadlessBuild.properties */
        script.env[channelVariableName] = true
        /* Check FABRIC_ENV_NAME is set for the build or not from optional parameter of FABRIC_APP_CONFIG, if not set use by default '_' value for binaries publish to S3. */
        
        /* fabricEnvName consist default value for fabric env name which is required to construct s3Upload path */ 
        fabricEnvName = (script.env.FABRIC_ENV_NAME) ?: '_'

        s3ArtifactPath = ['Builds', fabricEnvName, channelPath].join('/')
        artifactsBasePath = getArtifactTempPath(projectWorkspacePath, projectName, separator, channelVariableName) ?:
                script.echoCustom('Artifacts base path is missing!','ERROR')
        artifactExtension = getArtifactExtension(channelVariableName) ?:
                script.echoCustom('Artifacts extension is missing!','ERROR')

        try {
            closure()
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage,'ERROR',false)
            script.currentBuild.result = 'FAILURE'
        } finally {
            mustHavePath = [projectFullPath, 'mustHaves'].join(separator)
            if (script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED') {
                upstreamJob = BuildHelper.getUpstreamJobName(script)
                isRebuild = BuildHelper.isRebuildTriggered(script)
                PrepareMustHaves()
            }
            setBuildDescription()

            /* Been agreed to send notification from channel job only if result equals 'FAILURE' */
            if (script.currentBuild.result == 'FAILURE') {
                NotificationsHelper.sendEmail(script, 'buildVisualizerApp')
            }
        }
    }

    /**
     * Wraps code with required environment variables (home paths of dependencies,
     *  updates PATH environment variable, Kony cloud credentials and optionally GOOGLE_MAPS_KEY for Android builds).
     *
     * @param closure block of code.
     */
    protected final void visualizerEnvWrapper(closure) {
        /* Get Visualizer version */
        visualizerVersion = getVisualizerVersion(script.readFile('konyplugins.xml'))
        script.env.visualizerVersion = visualizerVersion
        if (getVisualizerPackVersion(visualizerVersion) >= getVisualizerPackVersion(libraryProperties.'ci.build.support.base.version')) {
            /* Set a property for a reference to check current build is CI or not for any other module */
            script.env.isCIBUILD = "true"
        }
        /* Get Visualizer dependencies */
        visualizerDependencies = (
                BuildHelper.getVisualizerDependencies(script, isUnixNode, separator, visualizerHome,
                        visualizerVersion, libraryProperties.'visualizer.dependencies.file.name',
                        libraryProperties.'visualizer.dependencies.base.url',
                        libraryProperties.'visualizer.dependencies.archive.file.prefix',
                        libraryProperties.'visualizer.dependencies.archive.file.extension')
        ) ?: script.echoCustom('Missing Visualizer dependencies!','ERROR')
        /* Expose tool installation path as environment variable */
        def exposeToolPath = { variableName, homePath ->
            script.env[variableName] = homePath
        }
        /* Collect tool installation paths for PATH environment variables */
        def toolBinPath = visualizerDependencies.collect {
            exposeToolPath(it.variableName, it.homePath)
            it.binPath
        }.join(pathSeparator)

        /* Collect all additional environment variables that required for build */
        def credentialsTypeList = [script.usernamePassword(credentialsId: "${cloudCredentialsID}",
                passwordVariable: 'CLOUD_PASSWORD', usernameVariable: 'CLOUD_USERNAME')]

        if (channelOs == 'Android' && script.params.GOOGLE_MAPS_KEY_ID) {
            credentialsTypeList.add(
                    script.string(credentialsId: "${script.params.GOOGLE_MAPS_KEY_ID}", variable: 'GOOGLE_MAPS_KEY')
            )
        }

        script.withEnv(["PATH+TOOLS=${script.env.NODE_HOME}${pathSeparator}${toolBinPath}"]) {
            script.withCredentials(credentialsTypeList) {
                closure()
            }
        }
    }

    /**
     * Builds the project.
     */
    protected final void build() {
        /* List of required build resources */
        def requiredResources = ['property.xml', 'ivysettings.xml', 'ci-property.xml']

        /* Populate Fabric configuration to appfactory.js file */
        populateFabricAppConfig()

        script.catchErrorCustom('Failed to build the project') {
            script.dir(projectFullPath) {
                mustHaveArtifacts.add([name: "HeadlessBuild.properties", path: projectFullPath])
                mustHaveArtifacts.add([name: ".log", path: [workspace, '.metadata'].join(separator)])
                /* Load required resources and store them in project folder */
                for (int i=0; i < requiredResources.size(); i++) {
                    String resource = script.loadLibraryResource(resourceBasePath + requiredResources[i])
				
					if(requiredResources[i] == 'ivysettings.xml'){
						// Replace the environment domain to the correct domain i.e. qa-kony.com or sit2-kony.com
						resource = resource.replaceAll("\\[CLOUD_DOMAIN\\]", script.env.CLOUD_DOMAIN)
					}
                    script.writeFile file: requiredResources[i], text: resource
                }

                /* Workaround for run.sh files */
                if (isUnixNode) {
                    script.echoCustom("Applying fix for run.sh file...")
                    BuildHelper.fixRunShScript(script, script.pwd() ,'run.sh')
                }

                /* Inject required build environment variables with visualizerEnvWrapper */
                visualizerEnvWrapper() {
                    if (script.env.isCIBUILD){
                        /* Build project using CI tool" */
                        script.shellCustom('ant -buildfile ci-property.xml', isUnixNode)
                        /* Run npm install */
                        script.catchErrorCustom('Something wrong, FAILED to run "npm install" on this project') {
                                def npmBuildScript = "npm install"
                                script.shellCustom(npmBuildScript, isUnixNode)
                        }
                        /* Run node build.js */
                        script.catchErrorCustom('CI build failed for this project') {
                                def nodeBuildScript = 'node build.js'
                                script.shellCustom(nodeBuildScript, isUnixNode)
                        }
                    }
                    else {
                        def windowsResource = libraryProperties.'window.lockable.resource.name'
                        def iosResource = libraryProperties.'ios.lockable.resource.name'

                        def slave= isUnixNode ? iosResource : windowsResource
                        script.lock(slave) {
                            /* Populate HeadlessBuild.properties, HeadlessBuild-Global.properties and download Kony plugins */
                            script.shellCustom('ant -buildfile property.xml', isUnixNode)
                            /* Build project using headless build tool*/
                            script.shellCustom('ant', isUnixNode)
                        }
                    }
                }
            }
        }
    }

    /**
     * Determine which Visualizer version project requires,
     * according to the version that matches first in the order of branding/studioviz/keditor plugin.
     *
     * @param konyPluginsXmlFileContent content of the konyplugins.xml.
     * @return Visualizer version.
     */
    protected final getVisualizerVersion(konyPluginsXmlFileContent) {
	    String visualizerVersion = ''
        def plugins = [
                'Branding': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.ide.paas.branding"/,
                'Studioviz win64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.win64"/,
                'Studioviz mac64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.mac64"/,
                'KEditor': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.pat.tool.keditor"/
        ]

        plugins.find { pluginName, pluginSearchPattern ->
            if (konyPluginsXmlFileContent =~ pluginSearchPattern) {
                visualizerVersion = Matcher.lastMatcher[0][1]
                script.echoCustom("Found ${pluginName} plugin!")
                /* Return true to break the find loop, if at least one much been found */
                return true
            } else {
                script.echoCustom("Could not find ${pluginName} plugin entry... " +
                        "Switching to the next plugin to search...")
            }
        }

        visualizerVersion
    }

    /**
     * Get visualizer version in Number Format
     *
     * @param visualizerVersion  ( with Dots eg. 8.0.0 )
     * @return Visualizer Fix Pack version  (without dots eg. 800)
     */
    protected final getVisualizerPackVersion(visualizerVersion) {
        return visualizerVersion.replace(".", "")
    }

    /**
     * Searches build artifacts.
     *
     * @param artifactExtension artifact extension.
     * @return list with found artifacts.
     */
    protected final getArtifactLocations(artifactExtension) {
        /* Check required arguments */
        (artifactExtension) ?: script.echoCustom("artifactExtension argument can't be null",'ERROR')

        def files = null
        def artifactLocations = []

        script.catchErrorCustom('Failed to search build artifacts!') {
            /* Switch to artifact temp folder */
            script.dir(artifactsBasePath) {
                /* Search for artifacts */
                files = script.findFiles glob: getSearchGlob(artifactExtension)
            }
        }

        /* Generate artifact object with required properties */
        for (file in files) {
            def filePath = (file.path == file.name) ? artifactsBasePath :
                    [artifactsBasePath, file.path.minus(separator + file.name)].join(separator)

            artifactLocations.add([name: file.name, path: filePath, extension: artifactExtension])
        }

        artifactLocations
    }

    /**
     * Renames build artifact, format: <ProjectName>_[<ArtifactArchitecture>].<artifactExtension>
     * @param buildArtifacts build artifacts list.
     * @return list of renamed artifacts.
     */
    protected final renameArtifacts(buildArtifacts) {
        def renamedArtifacts = []
        String shellCommand = (isUnixNode) ? 'mv' : 'rename'

        script.catchErrorCustom('Failed to rename artifacts') {
            for (int i = 0; i < buildArtifacts?.size(); ++i) {
                def artifact = buildArtifacts[i]
                String artifactName = artifact.name
                String artifactPath = artifact.path
                String artifactExtension = artifact.extension
                String artifactTargetName = projectName + '_' +
                        getArtifactArchitecture([artifactPath, artifactName].join(separator)) +
                        jobBuildNumber + '.' + artifactExtension
                String command = [shellCommand, artifactName, artifactTargetName].join(' ')

                /*
                    Workaround for Android binaries, because of there are two build artifacts
                    with debug and release suffixes, working only with required one.
                 */
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

    /**
     * Populates Fabric configuration to appfactory.js file.
     */
    protected final void populateFabricAppConfig() {
        String configFileName = libraryProperties.'fabric.config.file.name'

        /* Check if FABRIC_APP_CONFIG build parameter is set */
        if (fabricAppConfig) {
            script.dir(projectFullPath) {
                script.dir('modules') {
                    /* Check if appfactory.js file exists */
                    if (script.fileExists(configFileName)) {
                        String config = (script.readFile(configFileName)) ?:
                                script.echoCustom("$configFileName content is empty!",'ERROR')
                        String successMessage = 'Fabric app key, secret and service URL were successfully populated'
                        String errorMessage = 'Failed to populate Fabric app key, secret and service URL'

                        script.catchErrorCustom(errorMessage, successMessage) {
                            /* Populate values from Fabric configuration */
                            BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                                String updatedConfig = config.
                                        replaceAll('\\$FABRIC_APP_KEY', "\'${script.env.APP_KEY}\'").
                                        replaceAll('\\$FABRIC_APP_SECRET', "\'${script.env.APP_SECRET}\'").
                                        replaceAll('\\$FABRIC_APP_SERVICE_URL', "\'${script.env.SERVICE_URL}\'")

                                script.writeFile file: configFileName, text: updatedConfig
                            }
                        }
                    } else {
                        script.echoCustom("Skipping population of Fabric app key, secret and service URL, " +
                                "${configFileName} was not found in the project modules folder!")
                    }
                }
            }
        } else {
            script.echoCustom("Skipping population of Fabric app key, secret and service URL, " +
                    "credentials parameter was not provided!")
        }
    }

    /**
     * Returns search glob for build artifacts search.
     *
     * @param artifactExtension artifact extension.
     * @return search glob.
     */
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

    /**
     * Returns artifact path in build temp folder depending on channel.
     *
     * @param projectWorkspacePath project location on job workspace.
     * @param projectName project name.
     * @param separator path separator.
     * @param channelVariableName channel build parameter name.
     * @return path to the artifact.
     */
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
                artifactsTempPath = getPath(
                        ['build', 'luatabrcandroid', 'dist', projectName, 'build', 'outputs', 'apk']
                )
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
     * @param channelName channel build parameter name.
     * @return the artifact extension string
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
     * @return artifact architecture
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

    /**
     * Sets build description at the end of the build.
     */
    protected final void setBuildDescription() {
        String EnvironmentDescription = ""
        String mustHavesDescription = ""
        if(script.env.FABRIC_ENV_NAME) {
            EnvironmentDescription = "<p>Environment: $script.env.FABRIC_ENV_NAME</p>"
        }
        if((upstreamJob == null || isRebuild) && s3MustHaveAuthUrl != null){
            mustHavesDescription = "<p><a href='${s3MustHaveAuthUrl}'>Logs</a></p>"
        }
        script.currentBuild.description = """\
        <div id="build-description">
            ${EnvironmentDescription}
            <p>Channel: $channelVariableName</p>
            ${mustHavesDescription}
        </div>\
        """.stripIndent()
    }

    /**
     * @params Absolute or relative path of a Folder or file
     * Set execute permissions to all shell files
     */
    protected final void setExecutePermissions(source,isDir) {
        if(script.fileExists(source)){
            isDir ? script.shellCustom("chmod -R 755 $source/*.sh",isUnixNode): script.shellCustom("chmod 755 $source",isUnixNode)
        } else{
            script.echoCustom("File or Directory doesn't exist : ${source}",'WARN')
        }
    }

    /**
     * Copies the custom hooks build logs into must haves folder
    */
    protected final void copyCustomHooksBuildLogs() {
        def chLogs = [workspace, projectWorkspaceFolderName, projectName, libraryProperties.'customhooks.buildlog.folder.name'].join("/")
        script.dir(chLogs){
            script.shellCustom("find \"${chLogs}\" -name \"*.log\" -exec cp -f {} \"${mustHavePath}\" \\;", isUnixNode)
        }
    }

    
    /**
     * Sanitizes the sensitive information from the collected information
    */
    protected final void sanitizeFiles() {
        def sanitizableResources = ['HeadlessBuild.properties']
        script.dir(mustHavePath){
            sanitizableResources.each { propertyFileName ->
                if (script.fileExists(propertyFileName)) {
                    String fileContent = script.readFile file: propertyFileName
                    fileContent = fileContent.replaceAll('\\.password=.*','.password=********')
                    script.writeFile file: propertyFileName, text: fileContent
                }
            }
        }
    }

    /**
     * Collect all the information for the musthaves.
     * Copies all the required files from the workspace.
     * Also collected the information about the environment, Input Params, Build Log
    */
    protected final void collectAllInformation() {
        String buildLog = "JenkinsBuild.log"
        script.dir(mustHavePath){
            script.writeFile file: buildLog, text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
            script.writeFile file: "environmentInfo.txt", text: BuildHelper.getEnvironmentInfo(script)
            script.writeFile file: "ParamInputs.txt", text: BuildHelper.getInputParamsAsString(script)
            
            /* APPFACT-858 - Custom hooks will be executed only on MAC Machine. Build will be executed on MAC node
             * if there are custom hooks and also run custom hook is checked. If the run custom hook is checked, but
             * there are no custom hooks defined, then there is a chance that a build will be executed in Windows where
             * must haves collection get failed as the commands we use don't exists. So added a check if we are running
             * the build on non-Windows node.
             */
            if(script.params.RUN_CUSTOM_HOOKS && isUnixNode){
                copyCustomHooksBuildLogs()
            }
            if(mustHaveArtifacts.size() > 0){
                mustHaveArtifacts.each{
                    String sourceFile = [it.path, it.name].join(separator)
                    if(script.fileExists(sourceFile)){
                        script.shellCustom("cp -f \"${sourceFile}\" \"${mustHavePath}\"", isUnixNode)
                    }
                }
            }
        }
        sanitizeFiles()
    }
    /**
     * Prepares all the information for the debugging any build failures.
     * Zips all the files into a single zip file and upload into S3 and give the S3 URL
     * in a map so that Viz job copies later from S3 to create single zip for all builds.
     */
    protected final void PrepareMustHaves() {
        String mustHaveFile = ["MustHaves", channelVariableName, jobBuildNumber].join("_") + ".zip"
        String mustHaveFilePath = [projectFullPath, mustHaveFile].join(separator)
        script.catchErrorCustom("Error while preparing must haves"){
            collectAllInformation()
            script.dir(projectFullPath){
                script.zip dir:mustHavePath, zipFile: mustHaveFile
                script.catchErrorCustom("Failed to create the Zip file") {
                    if(script.fileExists(mustHaveFilePath)){
                        String s3MustHaveUrl = AwsHelper.publishToS3  bucketPath: s3ArtifactPath, sourceFileName: mustHaveFile,
	                                    sourceFilePath: projectFullPath, script
                        s3MustHaveAuthUrl = BuildHelper.createAuthUrl(s3MustHaveUrl, script, false)
                        /* We will be keeping the s3 url of the must haves into the collection only if the 
                         * channel job is triggered by the parent job that is buildVisualiser job.
                         * Handling the case where we rebuild a child job, from an existing job which was
                         * triggered by the buildVisualiser job.
                         */
                        if(upstreamJob != null && !isRebuild) {
                            mustHaves.add([
                                    channelVariableName: channelVariableName, name: mustHaveFile, url: s3MustHaveUrl
                            ])
                            script.env['MUSTHAVE_ARTIFACTS'] = mustHaves?.inspect()
                        }
                    }
                }
            }
        }
    }

}


