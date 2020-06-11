package com.kony.appfactory.fabric

import java.io.Serializable
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.FabricHelper
import com.kony.appfactory.helper.AwsHelper
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
    private final projectSourceCodeBranch = (script.params.PROJECT_SOURCE_CODE_BRANCH)?.trim()
    private final projectSourceCodeRepositoryCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private final fabricAppVersionToPickFrom = script.params.IMPORT_FABRIC_APP_VERSION
    private final fabricAppVersionInputParam = (script.params.FABRIC_APP_VERSION)?.trim()
    private final fabricAppConfig = script.params.FABRIC_APP_CONFIG
    private final fabricCredentialsID = script.params.FABRIC_CREDENTIALS_ID
    private final boolean isBuildWithImport = script.params.IMPORT
    private final boolean isBuildWithPublish = script.params.PUBLISH
    private final fabricAppDir = (script.params.FABRIC_DIR)?.trim()
    private final fabricJavaProjectsDir = (script.params.JAVA_PROJECTS_DIR)?.trim()
    private final mvnBuildCmdInput = (script.params.MVN_GOALS_AND_OPTIONS)?.trim()
    private boolean isBuildWithJavaAssets = script.params.BUILD_JAVA_ASSETS
    private final boolean isBuildWithCleanJavaAssets = script.params.CLEAN_JAVA_ASSETS
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
        boolean isBuildWithAggregatorPom = false
        def mavenBuildCommand
        
        //Fabric repo assets path variables
        def javaAssetBasePath
        def javaServiceDirList = []
        def fabricAppJarsPath
        def javaAssetsBinariesReleasePath
        def appBinariesReleasePath
        /*
         List of fabric app artifacts in format:
             [fabricAppPath: <relative path to the artifact on S3>, name: <artifact file name>, url: <S3 artifact URL>]
        */
        def fabricBuildArtifacts = []
        def javaServiceBuildTargetFolderName = "target"
        
        //MustHave variables
        def mustHaveArtifacts = []
        def s3MustHaveAuthUrl = ''
        
        //Build stats variable
        def fabricStats = [:]
        
        /* Allocate a slave for the run */
        script.node(nodeLabel) {
            fabricStats.put('fabtsstart', new Date().time)
            script.ansiColor('xterm') {
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
                                'PROJECT_SOURCE_CODE_BRANCH',
                                'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                                'FABRIC_APP_CONFIG'
                            ]
                            
                            if (isBuildWithImport) {
                                mandatoryParameters.add('FABRIC_CREDENTIALS_ID')
                            }
                            
                            if(isBuildWithJavaAssets) {
                                mandatoryParameters.add('JAVA_PROJECTS_DIR')
                            }
                            
                            if(fabricAppVersionToPickFrom == 'Other') {
                                mandatoryParameters.add('FABRIC_APP_VERSION')
                            }
                            
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
                            FabricHelper.fetchFabricCli(script, libraryProperties, fabricCliVersion)
                            fabricCliFilePath = [workspace, fabricCliFileName].join(separator)
                            
                            // "fabricAppDir" is path upto fabric app's where it contains 'Apps' directory relative to repo root dir
                            fabricAppBasePath = [projectFullPath, fabricAppDir].join(separator)
                            fabricAppJarsPath = [fabricAppBasePath, 'Apps', '_JARs'].join(separator)

                            // If the fabric App does not exist then failing the build
                            def isFabricDirExist = FabricHelper.isDirExist(script, fabricAppBasePath, isUnixNode)
                            if (!isFabricDirExist)
                                throw new AppFactoryException("The path ${fabricAppBasePath} does not exist.", 'ERROR')
                            def fabricAppsDirPath = [fabricAppBasePath, 'Apps'].join(separator)
                            script.dir(fabricAppsDirPath) {
                                def versionJsonFile = [fabricAppsDirPath, 'Version.json'].join(separator)
                                if (!script.fileExists(versionJsonFile))
                                    throw new AppFactoryException("The path ${fabricAppsDirPath} in repository ${projectRepositoryUrl} branch ${projectSourceCodeBranch} does not appear to contain a Fabric app.", 'ERROR')
                            }
                          
                            appBinariesReleasePath = [projectFullPath, defaultReleaseAppBundleDir].join(separator)
                            javaAssetsBinariesReleasePath = [projectFullPath, defaultReleaseAppBinariesDir].join(separator)
                            
                            // "fabricJavaProjectsDir" is path upto parent of java projects relative to repo root path
                            javaAssetBasePath = [projectFullPath, fabricJavaProjectsDir].join(separator)
                            
                            // If the given java asset path does not exist then failing the build
                            if(fabricJavaProjectsDir) {
                                def isPathExistForJavaAssest = FabricHelper.isDirExist(script, javaAssetBasePath, isUnixNode)
                                if(!isPathExistForJavaAssest)
                                    throw new AppFactoryException("The path [${fabricJavaProjectsDir}] does not contain any Java projects.", 'ERROR')
                            }
                            
                            // Creating the fabric app's '_JARs' dir under fabric app 'Apps' path( i.e Apps/_JARs) if it is missing by default.
                            script.shellCustom("set +x;mkdir -p ${fabricAppJarsPath}", isUnixNode)
                            
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
                                script.shellCustom("set +x;rm -f ${fabricAppJarsPath}/*.jar", isUnixNode)
                            }
                            
                            // Exposing the fabric app config variable
                            BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                                script.env['FABRIC_ENV_NAME'] = script.env.FABRIC_ENV_NAME
                                fabricEnvironmentName = script.env.FABRIC_ENV_NAME
                                fabricAppName = script.env.FABRIC_APP_NAME
                                fabricCloudAccountId = script.env.FABRIC_ACCOUNT_ID
                                fabricAppVersion = getFabricApplicationVersion()
                            }
                            
                            // Setting the custom folder for maven repo path
                            def mvnLocalRepoPath = [workspace, projectWorkspaceFolderName, ".m2"].join(separator)
                            def defaultMavenGoalsAndOptions = "mvn -Dmaven.repo.local=${mvnLocalRepoPath} clean package"
                            mavenBuildCommand = (mvnBuildCmdInput) ? ("mvn -Dmaven.repo.local=${mvnLocalRepoPath} " + mvnBuildCmdInput.trim()) : defaultMavenGoalsAndOptions
                        }
                        
                        script.stage('Build java assets') {
                            if(isBuildWithJavaAssets) {
                                script.catchErrorCustom('Failed to build the fabric app') {
                                    script.dir(javaAssetBasePath) {
                                        if(script.fileExists(pomFileName)) {
                                            isBuildWithAggregatorPom = true
                                            FabricHelper.runMavenBuild(script, isUnixNode, mavenBuildCommand)
                                        } else {
                                            // If aggregator pom is not found looking for pom.xml available in each java sub-folders
                                            def javaAssetSubDirList = FabricHelper.getSubDirectories(script, isUnixNode, javaAssetBasePath)
                                            javaAssetSubDirList?.each { javaServiceDir ->
                                                if(script.fileExists(javaServiceDir + '/' + pomFileName)) {
                                                    script.dir(javaServiceDir) {
                                                        FabricHelper.runMavenBuild(script, isUnixNode, mavenBuildCommand)
                                                        script.dir(javaServiceBuildTargetFolderName) {
                                                            def buildArtifactsInfo = script.shellCustom('ls', isUnixNode, [returnStdout:true])
                                                            script.echoCustom("Maven build artifacts generated for [${javaServiceDir}] at [${javaServiceDir}/${javaServiceBuildTargetFolderName}] are:\n${buildArtifactsInfo}", "INFO")
                                                        }
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
                            if(!isBuildWithAggregatorPom && isBuildWithJavaAssets) {
                                script.dir(javaAssetBasePath) {
                                    javaServiceDirList?.each { javaServiceDir ->
                                        script.dir(javaServiceDir) {
                                            def pomFileContent = script.readMavenPom file: pomFileName
                                            def artifactId = pomFileContent.getArtifactId()
                                            def artifactVersion = pomFileContent.getVersion()
                                            // Check whether pom.xml has any entry with 'finalName' key for referring the same for build artifactId, if not use default mvn artifactId that it generates.
                                            def javaServiceArtifact = (pomFileContent.build.finalName) ?: artifactId + '-' + artifactVersion + '.jar'
                                            script.dir(javaServiceBuildTargetFolderName) {
                                                if(script.fileExists(javaServiceArtifact)) {
                                                    // Clean if any jar containing artifactID as starting name.
                                                    script.shellCustom("set +x;rm -f ${fabricAppJarsPath}/${artifactId}*.jar", isUnixNode)
                                                    script.shellCustom("cp ${javaServiceArtifact} ${javaAssetsBinariesReleasePath}", isUnixNode, [returnStdout:true])
                                                    script.shellCustom("cp ${javaServiceArtifact} ${fabricAppJarsPath}", isUnixNode, [returnStdout:true])
                                                } else {
                                                    throw new AppFactoryException("Maven build is successful, but did not find built asset with name ${javaServiceArtifact} in ${javaServiceBuildTargetFolderName} folder!" +
                                                        "\nPlease cross-check, have you properly defined groupId, artifactId, version or 'finalName' property in the build pom.xml file?", 'ERROR')
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // Create the app zip for fabric app and place the zip at path "/release/apps" with file name projectName.zip
                            script.dir(appBinariesReleasePath) {
                                def fabricAppZipArtifacts
                                if(isBuildWithAggregatorPom) {
                                    fabricAppZipArtifacts = script.findFiles(glob: '*.zip')
                                    if(fabricAppZipArtifacts) {
                                        FabricHelper.renameFabricAppZipArtifact(script, fabricAppZipArtifacts, isUnixNode)
                                    } else {
                                        throw new AppFactoryException("Failed to find the Fabric app bundle artifact!", 'ERROR')
                                    }
                                } else {
                                    def fabricApplicationAppsDir = [fabricAppBasePath, 'Apps'].join(separator)
                                    script.dir(fabricApplicationAppsDir) {
                                        def appZipAssetsInfo = script.shellCustom('ls', isUnixNode, [returnStdout:true])
                                        script.echoCustom("Fabric app's bundle zip should contain below assets:\n${appZipAssetsInfo}", "INFO")
                                    }
                                    script.dir(javaAssetsBinariesReleasePath) {
                                        def appBinariesAssetsInfo = script.shellCustom('ls', isUnixNode, [returnStdout:true])
                                        script.echoCustom("Copying Fabric app's binaries at path '${defaultReleaseAppBinariesDir}' and containing below assets:\n${appBinariesAssetsInfo}", "INFO")
                                    }
                                    script.zip dir: fabricAppBasePath, zipFile: projectNameZip
                                    script.dir(appBinariesReleasePath) {
                                        def appZipDirAssetsInfo = script.shellCustom('ls', isUnixNode, [returnStdout:true])
                                        script.echoCustom("Copying Fabric app's bundle zip <${projectNameZip}> to '${defaultReleaseAppBundleDir}' and containing below assets:\n${appZipDirAssetsInfo}", "INFO")
                                    }
                                }
                                // Add MustHaves Artifacts
                                fabricAppZipArtifacts = script.findFiles(glob: '*.zip')
                                fabricAppZipArtifacts?.each { fabricAppZipArtifact ->
                                    mustHaveArtifacts.add([name: fabricAppZipArtifact.name, path: appBinariesReleasePath])
                                }
                            }
                        }
                        script.stage('Import and Publish app') {
                            if(isBuildWithImport) {
                                try {
                                    script.dir(appBinariesReleasePath) {
                                        
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
                                    script.echoCustom(exceptionMessage, 'ERROR', false)
                                    fabricStats.put('faberrmsg', exceptionMessage)
                                    fabricStats.put('faberrstack', e.getStackTrace().toString())
                                    script.currentBuild.result = "FAILURE"
                                }
                            }
                        }
                        
                        script.stage('Check PostDeploy Hook Points') {
                            if(isBuildWithPublish) {
                                BuildHelper.runPostDeployHook(script, isCustomHookRunBuild, hookHelper, this.projectName, libraryProperties.'customhooks.postdeploy.name', 'ALL')
                            }
                        }
                        
                        script.stage('Archive build artifacts') {
                            def s3FabricArtifactPath = ['Builds', fabricEnvironmentName, 'Fabric', fabricAppName, script.env.BUILD_NUMBER].join('/')
                            
                            script.dir(appBinariesReleasePath) {
                                def fabricAppArtifacts = script.findFiles(glob: projectName + '*.zip')
                                if(fabricAppArtifacts) {
                                    fabricAppArtifacts.each { fabricAppArtifact ->
                                        String fabricAppArtifactUrl = AwsHelper.publishToS3 bucketPath: s3FabricArtifactPath,
                                            sourceFileName: fabricAppArtifact.name, sourceFilePath: ".", script
    
                                        String authenticatedFabricAppArtifactUrl = BuildHelper.createAuthUrl(fabricAppArtifactUrl, script, true)
                                        
                                        fabricBuildArtifacts.add([fabricAppName: fabricAppName, name: fabricAppArtifact.name, fabricArtifactUrl: fabricAppArtifactUrl,
                                            authurl: authenticatedFabricAppArtifactUrl])
                                    }
                                } else {
                                    throw new AppFactoryException("Failed to find Fabric App (${projectName}) artifact!", 'ERROR')
                                }
                            }
                            
                            script.dir(javaAssetsBinariesReleasePath) {
                                def jarFiles = script.findFiles(glob: '*.jar')
                                jarFiles?.each { jarFile ->
                                    String javaAssestArtifactUrl = AwsHelper.publishToS3 bucketPath: s3FabricArtifactPath,
                                        sourceFileName: jarFile.name, sourceFilePath: ".", script
                                        
                                    String authenticatedJavaAssestArtifactUrl = BuildHelper.createAuthUrl(javaAssestArtifactUrl, script, true)
                                    
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
                            s3MustHaveAuthUrl = BuildHelper.prepareMustHaves(script, BuildType.Fabric, fabricJobMustHavesFolderName, fabricJobBuildLogFile, mustHaveArtifacts)
                        }
                        
                    } catch (Exception Ex) {
                        String exceptionMessage = (Ex.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage, 'ERROR', false)
                        script.currentBuild.result = "UNSTABLE"
                    }
                    
                    BuildHelper.setBuildDescription(script, s3MustHaveAuthUrl, fabricAppName)
                    NotificationsHelper.sendEmail(script, 'fabricBuild',
                        [
                            fabricBuildArtifacts        : fabricBuildArtifacts,
                            appVersion                  : fabricAppVersion,
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
