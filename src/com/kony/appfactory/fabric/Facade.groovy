package com.kony.appfactory.fabric

import java.io.Serializable
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.FabricHelper
import com.kony.appfactory.helper.ArtifactHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.enums.BuildType

class Facade implements Serializable{
    
    //Common build parameters
    private script
    /* CustomHookHelper object */
    protected hookHelper
    /* CustomHooks build Parameters*/
    protected final runCustomHook = script.params.RUN_CUSTOM_HOOKS
    private final projectName = script.env.PROJECT_NAME
    private final projectSourceCodeBranch = (script.params.SCM_BRANCH)?.trim()
    private final projectSourceCodeRepositoryCredentialsId
    private final fabricAppDir
    private final fabricAppVersionToPickFrom = script.params.IMPORT_FABRIC_APP_VERSION
    private final fabricAppVersionInputParam = (script.params.FABRIC_APP_VERSION)?.trim()
    private final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    private final fabricCredentialsID = script.params.FABRIC_CREDENTIALS
    private final boolean isBuildWithImport = script.params.IMPORT
    private final boolean isBuildWithPublish = script.params.PUBLISH
    private final fabricJavaProjectsDir = (script.params.JAVA_PROJECTS_DIR)?.trim()
    private final mvnBuildCmdInput = (script.params.MVN_GOALS_AND_OPTIONS)?.trim()
    private boolean isBuildWithJavaAssets = script.params.BUILD_JAVA_ASSETS
    private final boolean isBuildWithCleanJavaAssets = script.params.CLEAN_JAVA_ASSETS
    private final serviceConfigPath = (script.params.SERVICE_CONFIG_PATH)?.trim()
    private final nodeLabel
    protected isUnixNode
    protected separator
    protected libraryProperties
    protected projectWorkspaceFolderName
    protected fabricAppBasePath
    protected projectNameZip
    
    //Fabric config variable
    protected fabricAppName
    protected fabricAppVersion
    protected fabricEnvironmentName
    protected fabricCloudAccountId

    //mfcli.jar variables
    protected fabricCliVersion
    protected fabricCliFileName
    protected fabricCliFilePath

    //Flag to import as new app or version
    protected boolean importAsNew = false
    
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    Facade(script) {
        this.script = script
        this.hookHelper = new CustomHookHelper(script, BuildType.Fabric)
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        nodeLabel = libraryProperties.'fabric.node.label'
        fabricCliVersion = libraryProperties.'fabric.cli.version'
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        projectWorkspaceFolderName = libraryProperties.'fabric.project.workspace.folder.name'
        projectNameZip = projectName + ".zip"
        /* Set the fabric project settings values to the corresponding fabric environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Fabric')
        fabricAppDir = (script.params.FABRIC_DIR) ? (script.params.FABRIC_DIR).trim() : ((script.env.FABRIC_APP_ROOT_FOLDER) ? script.env.FABRIC_APP_ROOT_FOLDER.trim() : "")
        projectSourceCodeRepositoryCredentialsId = script.env.SCM_CREDENTIALS
        this.script.env['CLOUD_ACCOUNT_ID'] = (script.params.MF_ACCOUNT_ID) ?: (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (script.params.MF_ENVIRONMENT_GUID) ?: (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
    }
    
    protected final void createPipeline() {
        //SCM variables
        def final projectRepositoryUrl = script.env.PROJECT_SOURCE_CODE_URL_FOR_FABRIC
        def fabricScmMeta = [:]
        
        /* Absolute path to the project folder (<job_workspace>/fab_ws/<project_name>[/<project_root>]) */
        def projectFullPath
        
        //Maven build variables
        def pomFileName = "pom.xml"
        def mavenBuildCommand
        
        //Fabric repo assets path variables
        def javaAssetBasePath
        def javaServiceDirList = []
        def fabricAppJarsRelativePath
        def fabricAppJarsAbsolutePath

        def javaAssetsBinariesReleasePath
        def appBinariesReleasePath
        /*
         List of fabric app artifacts in format:
             [fabricAppPath: <relative path to the artifact on ArtifactStorage>, name: <artifact file name>, url: <ArtifactStorage URL for the artifact>]
        */
        def fabricBuildArtifacts = []
        def javaServiceBuildTargetFolderName = "target"
        
        //MustHave variables
        def mustHaveArtifacts = []
        def mustHaveAuthUrl = ''
        
        //Build stats variable
        def fabricStats = [:]
        
        /* Allocate a slave for the run */
        script.node(nodeLabel) {
            fabricStats.put('fabtsstart', new Date().time)
            script.ansiColor('xterm') {
                script.properties([[$class: 'CopyArtifactPermissionProperty', projectNames: '/*']])
                def workspace = script.env.WORKSPACE
                isUnixNode = script.isUnix()
                separator = FabricHelper.getPathSeparatorBasedOnOs(isUnixNode)
                def defaultReleaseAppBundleDir = ['release', 'apps'].join(separator)
                def defaultReleaseAppBinariesDir = ['release', 'binaries'].join(separator)
                def checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
                def projectWorkspacePath = [workspace, projectWorkspaceFolderName].join(separator)
                
                script.env['FABRIC_PROJECT_WORKSPACE'] = projectWorkspacePath
                projectFullPath = [workspace, checkoutRelativeTargetFolder].join(separator)
                def isCustomHookRunBuild = BuildHelper.isThisBuildWithCustomHooksRun(this.projectName, BuildType.Fabric, runCustomHook, libraryProperties)
                if(!isUnixNode){
                    throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                }
                
                try {
                    script.timestamps {
                        script.stage('Validate build params') {

                            def mandatoryParameters = [
                                'SCM_BRANCH',
                                'SCM_CREDENTIALS',
                                'FABRIC_APP_CONFIG'
                            ]

                            if (isBuildWithImport)
                                mandatoryParameters.add('FABRIC_CREDENTIALS')
                            
                            if(isBuildWithJavaAssets)
                                mandatoryParameters.add('JAVA_PROJECTS_DIR')
                            
                            if(fabricAppVersionToPickFrom == 'Other')
                                ValidationHelper.checkValidParamValue(script, 'FABRIC_APP_VERSION', "Invalid value for FABRIC_APP_VERSION. The version must be in the format <major>.<minor>.",
                                    "Since you have chosen to type it in manually Fabric app version.")
                            
                            ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                        }
                        
                        script.cleanWs deleteDirs: true
                                                
                        script.stage('Source checkout') {
                            
                            fabricScmMeta = BuildHelper.checkoutProject script: script,
                                        checkoutType: "scm",
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: projectSourceCodeBranch,
                                        scmCredentialsId: projectSourceCodeRepositoryCredentialsId,
                                        scmUrl: projectRepositoryUrl
                        }

                        BuildHelper.runPreBuildHook(script, isCustomHookRunBuild, hookHelper, this.projectName, libraryProperties.'customhooks.prebuild.name', 'ALL')
                        
                        script.stage("Prepare build environment") {
                            if(isBuildWithImport)
                                FabricHelper.fetchFabricCli(script, libraryProperties, fabricCliVersion)
                                fabricCliFilePath = [workspace, fabricCliFileName].join(separator)

                            // "fabricAppDir" is path upto fabric app's where it contains 'Apps' directory relative to repo root dir
                            fabricAppBasePath = [projectFullPath, fabricAppDir].join(separator)

                            fabricAppJarsRelativePath = (fabricAppDir.isEmpty()) ? ['Apps', '_JARs'].join(separator) : [fabricAppDir, 'Apps', '_JARs'].join(separator)
                            fabricAppJarsAbsolutePath = [projectFullPath, fabricAppJarsRelativePath].join(separator)

                            appBinariesReleasePath = [projectFullPath, defaultReleaseAppBundleDir].join(separator)
                            javaAssetsBinariesReleasePath = [projectFullPath, defaultReleaseAppBinariesDir].join(separator)
                            // "fabricJavaProjectsDir" is path upto parent of java projects relative to repo root path
                            javaAssetBasePath = [projectFullPath, fabricJavaProjectsDir].join(separator)
                            def fabricAppsDirPath = (fabricAppDir.isEmpty()) ? 'Apps' : [fabricAppDir, 'Apps'].join(separator)

                            // If the fabric App does not exist then failing the build
                            def isFabricDirExist = FabricHelper.isDirExist(script, fabricAppBasePath, isUnixNode)
                            if (!isFabricDirExist)
                                throw new AppFactoryException("The path [${fabricAppsDirPath}] in revision [${projectSourceCodeBranch}] of repository [${projectRepositoryUrl}] " +
                                    "does not exist.", 'ERROR')

                            def versionJsonFile = [projectFullPath, fabricAppsDirPath, 'Version.json'].join(separator)
                            if (!script.fileExists(versionJsonFile))
                                throw new AppFactoryException("The path [${fabricAppsDirPath}] in revision [${projectSourceCodeBranch}] of repository [${projectRepositoryUrl}] " +
                                    "does not appear to contain a Fabric app or service.", 'ERROR')

                            // If the given java asset path does not exist then failing the build
                            if(isBuildWithJavaAssets && fabricJavaProjectsDir) {
                                def isPathExistForJavaAssest = FabricHelper.isDirExist(script, javaAssetBasePath, isUnixNode)
                                if(!isPathExistForJavaAssest)
                                    throw new AppFactoryException("The path [${fabricJavaProjectsDir}] in revision [${projectSourceCodeBranch}] of repository [${projectRepositoryUrl}] " +
                                        "does not appear to contain any Java projects.", 'ERROR')
                            }
                            
                            // Creating the fabric app's '_JARs' dir under fabric app 'Apps' path( i.e Apps/_JARs) if it is missing by default.
                            script.shellCustom("set +x;mkdir -p ${fabricAppJarsAbsolutePath}", isUnixNode)
                            
                            // Creating the fabric app's release 'apps' dir under fabric app release path( i.e release/apps)
                            script.shellCustom("set +x;mkdir -p ${appBinariesReleasePath}", isUnixNode)
                            
                            // Creating the fabric app's release 'binaries' dir under fabric app release path( i.e release/binaries)
                            script.shellCustom("set +x;mkdir -p ${javaAssetsBinariesReleasePath}", isUnixNode)
                            
                            // Clean up app's zip release path if already existing
                            script.shellCustom("set +x;rm -f ${appBinariesReleasePath}/*.zip", isUnixNode)
                            
                            // Clean up app's binaries release path if already existing
                            script.shellCustom("set +x;rm -f ${javaAssetsBinariesReleasePath}/*.jar", isUnixNode)
                            
                            // Clean up app's jars at Apps/_JARs path if isBuildWithCleanJavaAssets is TRUE
                            if(isBuildWithCleanJavaAssets) {
                                script.echoCustom("Deleting all contents under '${fabricAppJarsRelativePath}' path as per CLEAN_JAVA_ASSETS build option.", 'INFO')
                                script.shellCustom("set +x;rm -f ${fabricAppJarsAbsolutePath}/*.jar", isUnixNode)
                            }
                            
                            // Exposing the fabric app config variable
                            BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                                script.env['FABRIC_ENV_NAME'] = script.env.FABRIC_ENV_NAME
                                fabricEnvironmentName = script.env.FABRIC_ENV_NAME
                                fabricAppName = script.env.FABRIC_APP_NAME
                                fabricCloudAccountId = script.env.FABRIC_ACCOUNT_ID
                                fabricAppVersion = getFabricApplicationVersion()
                            }
                            
                            // Setting the maven command to run and with local .m2 folder set to current build workspace.
                            def mvnLocalRepoPath = [workspace, projectWorkspaceFolderName, ".m2"].join(separator)
                            def defaultMavenGoalsAndOptions = "mvn -Dmaven.repo.local=${mvnLocalRepoPath} --batch-mode clean package"
                            mavenBuildCommand = (mvnBuildCmdInput) ? ("mvn -Dmaven.repo.local=${mvnLocalRepoPath} --batch-mode " + mvnBuildCmdInput.trim()) : defaultMavenGoalsAndOptions
                            def isValidCmd = FabricHelper.validateMvnGoalsBuildCommand(mavenBuildCommand)
                            if(!isValidCmd)
                                throw new AppFactoryException("Looks like multiple commands are passed in MVN_GOALS_AND_OPTIONS build option? We allow to run only mvn commands that has goals and options!!", 'ERROR')
                        }
                        
                        script.stage('Build java assets') {
                            boolean isAnAggregatorPom = false
                            def rootPOMGroupID, rootPOMArtifactID, rootPOMVersion

                            if(isBuildWithJavaAssets) {
                                script.dir(javaAssetBasePath) {
                                    // If POM exist at root of java folder, then execute first.
                                    if(script.fileExists(pomFileName)) {
                                        script.echoCustom("Found POM file at '${fabricJavaProjectsDir}' root path, running the maven build..", 'INFO')
                                        FabricHelper.runMavenBuild(script, isUnixNode, mavenBuildCommand)
                                        def pomFileContent = script.readMavenPom file: pomFileName
                                        // Check if this pom serving multi-module project.
                                        // Please note, for multi-module project build served through Aggregator pom, mvn build at root will fire all internal module builds automatically.
                                        if (pomFileContent.modules) {
                                            isAnAggregatorPom = true
                                            // Collect all modules to enter into respective target folders for finding artifacts.
                                            pomFileContent.modules?.each { mavenModule ->
                                                javaServiceDirList << mavenModule
                                            }
                                        }
                                        // If no modules found in POM, then it must be a parent POM that is inherited by all child java projects. So, it's not actually an aggregator pom.
                                        // In this case, lets gather parent POM co-ordinates.
                                        else {
                                            rootPOMGroupID = pomFileContent.getGroupId()
                                            rootPOMArtifactID = pomFileContent.getArtifactId()
                                            rootPOMVersion = pomFileContent.getVersion()
                                        }
                                    }

                                    // If there is no POM exist at root of java folder, build each java sub-folder that has POM
                                    else {
                                        def javaAssetSubDirList = BuildHelper.getSubDirectories(script, isUnixNode, javaAssetBasePath)
                                        javaAssetSubDirList?.each { javaServiceDir ->
                                            if (script.fileExists(javaServiceDir + '/' + pomFileName)) {
                                                script.dir(javaServiceDir) {
                                                        script.echoCustom("Found POM file at '${fabricJavaProjectsDir}/${javaServiceDir}' path, running the maven build " +
                                                                "for this java project..", 'INFO')
                                                        FabricHelper.runMavenBuild(script, isUnixNode, mavenBuildCommand)
                                                        javaServiceDirList << javaServiceDir
                                                }
                                            }
                                        }
                                    }

                                    //If POM exist at root of java folder, but it is not an aggregator POM, build each java sub-folder that is inheriting parent POM.
                                    if( script.fileExists(pomFileName) && !isAnAggregatorPom) {
                                        def javaAssetSubDirList = BuildHelper.getSubDirectories(script, isUnixNode, javaAssetBasePath)
                                        javaAssetSubDirList?.each { javaServiceDir ->
                                            if (script.fileExists(javaServiceDir + '/' + pomFileName)) {
                                                def pomFileContent = script.readMavenPom file: pomFileName

                                                if(pomFileContent.getGroupId().equals(rootPOMGroupID) &&
                                                        pomFileContent.getArtifactId().equals(rootPOMArtifactID) &&
                                                        pomFileContent.getVersion().equals(rootPOMVersion))
                                                {
                                                    script.dir(javaServiceDir) {
                                                        script.echoCustom("Found POM file at '${fabricJavaProjectsDir}/${javaServiceDir}' path that is inheriting Parent POM, running the maven build " +
                                                                "for this java project..", 'INFO')
                                                        FabricHelper.runMavenBuild(script, isUnixNode, mavenBuildCommand)
                                                        javaServiceDirList << javaServiceDir
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                script.echoCustom("Skipping building of java assets, since you have not selected 'BUILD_JAVA_ASSETS' build option!", 'INFO')
                            }
                        }

                        BuildHelper.runPostBuildHook(script, isCustomHookRunBuild, hookHelper, this.projectName, libraryProperties.'customhooks.postbuild.name', 'ALL')

                        script.stage('Bundle fabric app') {
                            script.echoCustom("Generating the Fabric App Bundle ${projectNameZip}..", "INFO")
                            if(isBuildWithJavaAssets) {
                                script.dir(javaAssetBasePath) {
                                    javaServiceDirList?.each { javaServiceDir ->
                                        script.dir(javaServiceDir) {
                                            def pomFileContent = script.readMavenPom file: pomFileName
                                            def artifactId = pomFileContent.getArtifactId()
                                            def artifactVersion = pomFileContent.getVersion()
                                            // Check whether pom.xml has any entry with 'finalName' key for referring the same for build artifactId, if not use default mvn artifactId that it generates.
                                            def javaServiceArtifact = ((pomFileContent.build?.finalName) ? pomFileContent.build.finalName : artifactId + '-' + artifactVersion) + '.jar'
                                            script.dir(javaServiceBuildTargetFolderName) {
                                                // Listing the contents of the target directory before copying the generated JAR's
                                                script.echoCustom("The list of contents in ${fabricJavaProjectsDir}/${javaServiceDir}/target directory", "INFO")
                                                script.shellCustom("set +ex;ls -lAh", isUnixNode)

                                                if(script.fileExists(javaServiceArtifact)) {
                                                    // Clean if any jar containing artifactID as starting name.
                                                    script.shellCustom("set +x;rm -f ${fabricAppJarsAbsolutePath}/${javaServiceArtifact}", isUnixNode)
                                                    script.echoCustom("Copying ${javaServiceArtifact} to '${fabricAppJarsRelativePath}' path..", "INFO")
                                                    script.shellCustom("set +x;cp ${javaServiceArtifact} ${javaAssetsBinariesReleasePath}", isUnixNode, [returnStdout:true])
                                                    script.shellCustom("set +x;cp ${javaServiceArtifact} ${fabricAppJarsAbsolutePath}", isUnixNode, [returnStdout:true])
                                                } else {
                                                    def targetFolderContent = script.shellCustom("ls", isUnixNode, [returnStdout:true])
                                                    targetFolderContent = (targetFolderContent) ?: " Seem to be empty? No content available."
                                                    throw new AppFactoryException("Maven build is successful for '${fabricJavaProjectsDir}/${javaServiceDir}' java project, " +
                                                            "but did not find any artifact with name '${javaServiceArtifact}' in '${fabricJavaProjectsDir}/${javaServiceDir}/${javaServiceBuildTargetFolderName}' " +
                                                            "path. \nThe '${javaServiceBuildTargetFolderName}' path has below content:\n${targetFolderContent}" +
                                                        "\nNote: Please cross verify your POM file for the project is having proper groupId, artifactId, version keys defined or not. OR the 'finalName' " +
                                                            "key is properly defined in the POM file or not. We look for the exact artifact generated by maven either in one of these forms!!", 'ERROR')
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // Create the app zip for fabric app and place the zip at path "/release/apps" with file name projectName.zip
                            script.dir(fabricAppBasePath) {
                                script.shellCustom("set +ex;zip --recurse-paths --display-bytes ${projectNameZip} \"Apps\" --exclude *.DS_Store *thumbs.db *.desktop.ini.", isUnixNode)
                                script.echoCustom("Successfully generated the Fabric App Bundle ${projectNameZip} at Fabric dir path!", "INFO")
                                script.shellCustom("set +x;mv -f ${projectNameZip} ${appBinariesReleasePath}", isUnixNode)
                                script.echoCustom("Successfully copied the Fabric App Bundle ${projectNameZip} to '${defaultReleaseAppBundleDir}' path!", "INFO")
                                mustHaveArtifacts.add([name: projectNameZip, path: appBinariesReleasePath])
                            }
                        }
                        script.stage('Import and Publish app') {
                            if(isBuildWithImport) {
                                try {
                                    script.dir(appBinariesReleasePath) {
                                        
                                        FabricHelper.fetchFabricConsoleVersion(script, fabricCliFilePath, fabricCredentialsID, fabricCloudAccountId, fabricEnvironmentName, isUnixNode)
                                        FabricHelper.importFabricApp(
                                                script,
                                                fabricCliFilePath,
                                                projectNameZip,
                                                fabricCredentialsID,
                                                fabricCloudAccountId,
                                                fabricAppName,
                                                fabricAppVersion,
                                                isUnixNode,
                                                importAsNew)
                                        
                                        if (isBuildWithPublish) {
                                            /* Check to import fabric app service config*/
                                            if(script.params.containsKey("SERVICE_CONFIG_PATH")) {
                                                def featuresSupportToCheckInFabric = [:]
                                                featuresSupportToCheckInFabric.put('app_service_config', ['featureDisplayName': 'App Service Config'])
                                                /* If 'SERVICE_CONFIG_PATH' param value is not empty, Refer the path provided to import service config if its valid */
                                                if(!serviceConfigPath.isEmpty()) {
                                                    def appServiceConfigFilePath = [projectFullPath, serviceConfigPath].join(separator)
                                                    def serviceConfigFileName = appServiceConfigFilePath.substring(appServiceConfigFilePath.lastIndexOf("/") + 1)
                                                    
                                                    /* Check the service config file type and name */
                                                    if(!serviceConfigFileName.endsWith(".json") || serviceConfigFileName.contains(" ")) {
                                                        throw new AppFactoryException("Invalid file name or type given for service config file! Service config import is supported with '.json' file and should not contains spaces in file name.", "ERROR")
                                                    }
                                                    if (script.fileExists(appServiceConfigFilePath)) {
                                                        script.echoCustom("Service config file found for Fabric environment is '${serviceConfigPath}', will be importing to Fabric app!", "INFO")
                                                        if(FabricHelper.checkFabricFeatureSupportExist(script, libraryProperties, featuresSupportToCheckInFabric)) {
                                                            FabricHelper.importFabricAppServiceConfig(
                                                                script,
                                                                fabricCliFilePath,
                                                                fabricCredentialsID,
                                                                fabricCloudAccountId,
                                                                appServiceConfigFilePath,
                                                                fabricAppName,
                                                                fabricAppVersion,
                                                                fabricEnvironmentName,
                                                                isUnixNode)
                                                        }
                                                    } else {
                                                        throw new AppFactoryException("The path [${serviceConfigPath}] in revision [${projectSourceCodeBranch}] of repository [${projectRepositoryUrl}] " +
                                                            "does not exist.", 'ERROR')
                                                    }
                                                } else {
                                                    script.echoCustom("Skipping to update Fabric app service config, since you have not provided value for 'SERVICE_CONFIG_PATH' param!", "INFO")
                                                }
                                            }
                                            
                                            Date publishStart = new Date()
                                            FabricHelper.publishFabricApp(
                                                script,
                                                fabricCliFilePath,
                                                fabricCredentialsID,
                                                fabricCloudAccountId,
                                                fabricAppName,
                                                fabricAppVersion,
                                                fabricEnvironmentName,
                                                isUnixNode)
                                        
                                        fabricStats.put('fabpubdur', BuildHelper.getDuration(publishStart, new Date()))
                                        
                                        } else {
                                            script.echoCustom("Skipping the App Publish, since you have not selected 'PUBLISH' build option!", 'INFO')
                                        }
                                    }
                                }
                                catch(Exception e){
                                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                                    fabricStats.put('faberrmsg', exceptionMessage)
                                    fabricStats.put('faberrstack', e.getStackTrace().toString())
                                    script.currentBuild.result = "FAILURE"
                                    throw new AppFactoryException(exceptionMessage, 'ERROR')
                                }
                            }
                            else {
                                script.echoCustom("Skipping the App Import, since you have not selected 'IMPORT' build option!", 'INFO')
                            }
                        }
                        
                        script.stage('Check PostDeploy Hook Points') {
                            if(isBuildWithPublish) {
                                BuildHelper.runPostDeployHook(script, isCustomHookRunBuild, hookHelper, this.projectName, libraryProperties.'customhooks.postdeploy.name', 'ALL')
                            }
                        }
                        
                        script.stage('Publish build artifacts') {

                            script.echoCustom("Publishing build artifacts to artifact storage..", 'INFO')
                            def fabricArtifactPath = ['Builds', fabricEnvironmentName, 'Fabric', fabricAppName, script.env.BUILD_NUMBER].join('/')

                            String fabricAppArtifactUrl = ArtifactHelper.publishArtifact sourceFileName: projectNameZip,
                                    sourceFilePath: appBinariesReleasePath, destinationPath: fabricArtifactPath, script
                            String authenticatedFabricAppArtifactUrl = ArtifactHelper.createAuthUrl(fabricAppArtifactUrl, script, true)
                            fabricBuildArtifacts.add([fabricAppName: fabricAppName, name: projectNameZip, fabricArtifactUrl: fabricAppArtifactUrl,
                                            authurl: authenticatedFabricAppArtifactUrl])

                            script.dir(javaAssetsBinariesReleasePath) {
                                def jarFiles = script.findFiles(glob: '*.jar')
                                jarFiles?.each { jarFile ->
                                    String javaAssestArtifactUrl = ArtifactHelper.publishArtifact sourceFileName: jarFile.name,
                                            sourceFilePath: ".", destinationPath: fabricArtifactPath, script
                                        
                                    String authenticatedJavaAssestArtifactUrl = ArtifactHelper.createAuthUrl(javaAssestArtifactUrl, script, true)
                                    
                                    fabricBuildArtifacts.add([fabricAppName: fabricAppName, name: jarFile.name, fabricArtifactUrl: javaAssestArtifactUrl,
                                        authurl: authenticatedJavaAssestArtifactUrl])
                                }
                            }
                        }
                    }
                } catch(Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong ...'
                    script.echoCustom(exceptionMessage, 'ERROR', false)
                    fabricStats.put('faberrmsg', exceptionMessage)
                    fabricStats.put('faberrstack', e.getStackTrace().toString())
                    script.currentBuild.result = "FAILURE"
                    
                } finally {
                    // Collecting metrics data
                    def fabricTask = isBuildWithPublish ? "publish" : isBuildWithImport ? "import" : "build"
                    fabricStats.put('projname', script.env.PROJECT_NAME)
                    fabricStats.put('fabaname', fabricAppName)
                    fabricStats.put('fabaver', fabricAppVersion)
                    fabricStats.put('fabtask', fabricTask)
                    fabricStats.put('fabsrcurl', projectRepositoryUrl)
                    fabricStats.put('fabsrcbrch', projectSourceCodeBranch)
                    fabricStats.put('fabsrccmtid', fabricScmMeta?.commitID)
                    fabricStats.put('buildemlrecipients', script.env.RECIPIENTS_LIST)
                    fabricStats.put('buildtype', "Fabric")
                    
                    // Pushing stats data
                    script.statspublish fabricStats.inspect()
                    
                    def abortMsg = ""
                    if(!script.currentBuild.rawBuild.getActions(jenkins.model.InterruptedBuildAction.class).isEmpty()) {
                        abortMsg = "BUILD ABORTED!!"
                        script.echoCustom(abortMsg, 'ERROR', false)
                    }
                    
                    if(abortMsg?.trim()) {
                        script.currentBuild.result = 'ABORTED'
                    }
                    
                    // Collecting MustHaves
                    try {
                        if(script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED') {
                            def fabricJobMustHavesFolderName = "fabricMustHaves"
                            def fabricJobBuildLogFile = "fabricBuildlog.log"
                            mustHaveAuthUrl = BuildHelper.prepareMustHaves(script, BuildType.Fabric, fabricJobMustHavesFolderName, fabricJobBuildLogFile, libraryProperties, mustHaveArtifacts)
                        }
                        
                    } catch (Exception Ex) {
                        String exceptionMessage = (Ex.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage, 'ERROR', false)
                        script.currentBuild.result = "UNSTABLE"
                    }
                    
                    BuildHelper.setBuildDescription(script, mustHaveAuthUrl, fabricAppName)
                    script.echoCustom("Notifying build results..", 'INFO')
                    NotificationsHelper.sendEmail(script, 'fabricBuild',
                        [
                            fabricBuildArtifacts        : fabricBuildArtifacts,
                            projectSourceCodeBranch     : projectSourceCodeBranch,
                            appVersion                  : fabricAppVersion,
                            importApp                   : isBuildWithImport,
                            publishApp                  : isBuildWithPublish,
                            fabricEnvironmentName       : fabricEnvironmentName,
                            isBuildWithCleanJavaAssets  : isBuildWithCleanJavaAssets,
                            isBuildWithJavaAssets       : isBuildWithJavaAssets,
                            fabricScmMeta               : fabricScmMeta
                        ],
                        true)
                }
            }
        }
    }
    
    // Get the Fabric app version based on source to pick from app version
    private String getFabricApplicationVersion() {
        def fabricApplicationVersion
        if(fabricAppVersionToPickFrom == 'PICK_FROM_FABRIC_APP_META_JSON') {
            fabricApplicationVersion = FabricHelper.getFabricAppVersionFromAppMetaJson(script, fabricAppBasePath, isUnixNode)
        } else if(fabricAppVersionToPickFrom == 'PICK_FROM_FABRIC_APP_CONFIG') {
            fabricApplicationVersion = (script.env.FABRIC_APP_VERSION) ?: "1.0"
        } else {
            fabricApplicationVersion = (fabricAppVersionInputParam) ?: "1.0"
            importAsNew = true
        }
        fabricApplicationVersion
    }
}
