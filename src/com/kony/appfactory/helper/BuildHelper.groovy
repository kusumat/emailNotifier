package com.kony.appfactory.helper
import org.jenkins.plugins.lockableresources.LockableResources
import jenkins.model.Jenkins

/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {
    /**
     * Clones project from the provided git repository.
     *
     * @param args
     *   script pipeline object.
     *   relativeTargetDir path where project should be stored.
     *   scmCredentialsId credentials that will be used to access the repository.
     *   scmUrl URL of the repository.
     *   scmBranch repository branch to clone.
     */
    protected static void checkoutProject(Map args) {
        def script = args.script
        String relativeTargetDir = args.projectRelativePath
        String scmCredentialsId = args.scmCredentialsId
        String scmUrl = args.scmUrl
        String scmBranch = args.scmBranch

        script.catchErrorCustom('Failed to checkout the project') {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: getScmConfiguration(relativeTargetDir, scmCredentialsId, scmUrl, scmBranch)
            )
        }
    }

    /**
     * Returns scm configuration depending on scm type.
     *
     * @param relativeTargetDir path where project should be stored.
     * @param scmCredentialsId credentials that will be used to access the repository.
     * @param scmUrl URL of the repository.
     * @param scmBranch repository branch to clone.
     * @return scm configuration.
     */
    private static getScmConfiguration(relativeTargetDir, scmCredentialsId, scmUrl, scmBranch) {
        def scm

        /*
            There was a request to be able to support different type of source control management systems.
            That is why switch statement below returns different scm configurations depending on scmUrl.
            Please note that svn scm configuration type was not tested yet.
         */
        switch (scmUrl) {
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
                               [credentialsId        : scmCredentialsId,
                                depthOption          : 'infinity',
                                ignoreExternalsOption: true,
                                local                : relativeTargetDir,
                                remote               : scmUrl]
                       ],
                       workspaceUpdater      : [$class: 'UpdateUpdater']]
                break
            default:
                scm = [$class                           : 'GitSCM',
                       branches                         : [[name: scmBranch]],
                       doGenerateSubmoduleConfigurations: false,
                       extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                            relativeTargetDir: relativeTargetDir]],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: scmCredentialsId,
                                                            url          : scmUrl]]]
                break
        }

        scm
    }
    /**
     * Gets the root cause of the build.
     * There several build causes in Jenkins, most of them been covered by this method.
     *
     * @param cause object of the cause.
     * @return string with the human-readable form of the cause.
     */
    @NonCPS
    private static getRootCause(cause) {
        def causedBy

        /* If build been triggered by Upstream job */
        if (cause.class.toString().contains('UpstreamCause')) {
            /* Than we need to call getRootCause recursively to get the root cause */
            for (upCause in cause.upstreamCauses) {
                causedBy = getRootCause(upCause)
            }
        } else {
            switch (cause.class.toString()) {
                case ~/^.*UserIdCause.*$/:
                    causedBy = cause.getUserName()
                    break
                case ~/^.*SCMTriggerCause.*$/:
                    causedBy = 'SCM'
                    break
                case ~/^.*TimerTriggerCause.*$/:
                    causedBy = 'CRON'
                    break
                case ~/^.*GitHubPushCause.*$/:
                    causedBy = 'GitHub hook'
                    break
                default:
                    causedBy = ''
                    break
            }
        }

        causedBy
    }

    /**
     * Gets the cause of the build and format it to human-readable form.
     *
     * @param buildCauses list of build causes
     * @return string with the human-readable form of the cause.
     */
    @NonCPS
    protected static getBuildCause(buildCauses) {
        def causedBy

        for (cause in buildCauses) {
            causedBy = getRootCause(cause)
        }

        causedBy
    }

    /**
     * Get the build log for a build of a job
     */
    @NonCPS
    protected static String getBuildLogText(script) {
        String buildLogText
        Jenkins.instance.getItemByFullName(script.env.JOB_NAME).each{ item->
            Run currentBuild = ((Job)item).getBuild(script.env.BUILD_ID)
            if(currentBuild){
                File file = currentBuild.getLogFile()
                buildLogText = file.getText()
            }
        }
        buildLogText
    }
	
    /**
     * Wraps code with Fabric environment variables.
     *
     * @param script pipeline object.
     * @param fabricAppConfigId Fabric config ID in Jenkins credentials store.
     * @param closure block of code.
     */
    protected final static void fabricConfigEnvWrapper(script, fabricAppConfigId, closure) {
        script.withCredentials([
                script.fabricAppTriplet(
                        credentialsId: "$fabricAppConfigId",
                        applicationNameVariable: 'FABRIC_APP_NAME',
                        environmentNameVariable: 'FABRIC_ENV_NAME',
                        applicationKeyVariable: 'APP_KEY',
                        applicationSecretVariable: 'APP_SECRET',
                        serviceUrlVariable: 'SERVICE_URL'
                )
        ]) {
            /* Block of code to run */
            closure()
        }
    }

    /**
     * Loads library configuration properties.
     * Properties can be places anywhere in the library, but most appropriate place is under:
     *      kony-common/resources/com/kony/appfactory/configurations
     * Purpose of this properties to store some static configuration of the library.
     * Because of this method return Properties object generic method for Java Properties objects could be applied here,
     *      which means that there is a way to redefine and merge different properties depending on needs, with the
     *      help of putAll or put, etc methods.
     *
     * @param script pipeline object.
     * @param resourcePath path to resource that needs to be loaded.
     * @return loaded library configuration properties.
     */
    @NonCPS
    protected final static Properties loadLibraryProperties(script, String resourcePath) {
        String propertyFileContent = script.libraryResource(resourcePath)

        Properties libraryProperties = new Properties()
        InputStream inputStream = new ByteArrayInputStream(propertyFileContent.getBytes())

        libraryProperties.load(inputStream)

        if (!libraryProperties) {
            throw new NullPointerException('Failed to load library configuration!')
        }

        libraryProperties
    }

    /*  Workaround for switching Visualizer dependencies */
    /* --------------------------------------------------- START --------------------------------------------------- */

    /**
     * Parses dependencies file to fetch list of required dependencies for specific Visualizer version.
     *
     * @param script pipeline object.
     * @param dependenciesFileContent JSON string with the list of the dependencies for specific Visualizer version.
     * @return Map object with required dependencies.
     */
    private final static parseDependenciesFileContent(script, dependenciesFileContent) {
        /* Check required arguments */
        (dependenciesFileContent) ?: script.echoCustom("File content string can't be null",'ERROR')

        def requiredDependencies = null

        script.catchErrorCustom('Failed to parse Visualizer dependencies file content') {
            requiredDependencies = script.readJSON(text: dependenciesFileContent)?.visualizer.dependencies
        }

        requiredDependencies
    }

    /**
     * Changes the dependency inside Visualizer's folder (drops existing folder with dependencies and adds symbolic
     *      links to the required dependency.
     * Been agreed that all required dependencies will be installed by chef during the slave bootstrap.
     * Path to the dependency on slave will be exposed on Jenkins as property for Global Tools Plugin.
     * Global Tools Plugin gives us ability to fetch a tool path in pipeline scripts.
     *
     * @param script pipeline object.
     * @param isUnixNode
     * @param dependencyPath
     * @param installationPath
     */
    private final static void switchDependencies(script, isUnixNode, dependencyPath, installationPath) {
        /* Check required arguments */
        (dependencyPath) ?: script.echoCustom("Dependency path can't be null!",'ERROR')
        (installationPath) ?: script.echoCustom("Installation path can't be null!",'ERROR')

        if (isUnixNode) {
            script.shellCustom(
                    ['rm -rf', installationPath, '&&', 'ln -s', dependencyPath, installationPath].join(' '),
                    isUnixNode
            )
        } else {
            script.shellCustom(['(if exist',installationPath + '\\','fsutil reparsepoint query ' + installationPath,
                                '| ' + 'findstr /C:\"Print Name:\" | find \"' + dependencyPath + '\"  >nul && ' +
                                'echo symbolic link found ) || rmdir /s /q',installationPath,'&&',
                                'mklink /J', installationPath, dependencyPath].join(' '), isUnixNode)
        }
    }

    /**
     * Fetches dependencies file from provided URL.
     *
     * @param script pipeline object.
     * @param visualizerVersion version of Visualizer
     * @param dependenciesFileName dependencies file name.
     * @param dependenciesBaseUrl URL for fetch.
     * @param dependenciesArchiveFilePrefix dependencies file prefix.
     * @param dependenciesArchiveFileExtension dependencies file extension.
     * @return dependencies file content.
     */
    private final static fetchRequiredDependencies(
            script, visualizerVersion, dependenciesFileName, dependenciesBaseUrl, dependenciesArchiveFilePrefix,
            dependenciesArchiveFileExtension
    ) {
        /* Check required arguments */
        (visualizerVersion) ?: script.echoCustom("Visualizer version couldn't be null!",'ERROR')

        def dependenciesArchive = null
        def dependenciesArchiveFileName = dependenciesArchiveFilePrefix + visualizerVersion +
                dependenciesArchiveFileExtension
        
		dependenciesBaseUrl = dependenciesBaseUrl.replaceAll("\\[CLOUD_DOMAIN\\]", script.env.CLOUD_DOMAIN)
		
		/* Composing URL for fetch */
        def dependenciesURL = [
                dependenciesBaseUrl, visualizerVersion, dependenciesArchiveFileName
        ].join('/')

        script.catchErrorCustom('Failed to fetch Visualizer dependencies file!') {
            script.httpRequest url: dependenciesURL, acceptType: 'APPLICATION_ZIP', contentType: 'APPLICATION_ZIP',
                    outputFile: dependenciesArchiveFileName, validResponseCodes: '200'
        }
        script.catchErrorCustom('Failed to unzip Visualizer dependencies file!') {
            /* Unarchive dependencies file */
            dependenciesArchive = script.unzip zipFile: dependenciesArchiveFileName, read: true
            script.unzip zipFile: dependenciesArchiveFileName
        }
        /* Return the content of the dependencies file */
        dependenciesArchive?."$dependenciesFileName"
    }

    /**
     * Main method for shuffling Visualizer dependencies.
     * Prepers data for static methods that required for shuffling dependencies.
     *
     * @param script pipeline object.
     * @param isUnixNode flag that is used for appropriate shell call (depending on OS).
     * @param separator path separator.
     * @param visualizerHome Visualizer home folder.
     * @param visualizerVersion Visualizer version.
     * @param dependenciesFileName Dependencies file name.
     * @param dependenciesBaseUrl Dependencies URL.
     * @param dependenciesArchiveFilePrefix dependencies file prefix.
     * @param dependenciesArchiveFileExtension dependencies file extension.
     * @return Map object with dependencies, every item in map has following structure:
     *      [variableName: <tool's environment variable name>, homePath: <home path of the tool>,
     *          binPath: <path to the tool binaries>]
     */
    protected final static getVisualizerDependencies(
            script, isUnixNode, separator, visualizerHome, visualizerVersion, dependenciesFileName, dependenciesBaseUrl,
            dependenciesArchiveFilePrefix, dependenciesArchiveFileExtension
    ) {
        /* Check required arguments */
        (separator) ?: script.echoCustom("separator argument can't be null!",'ERROR')
        (visualizerHome) ?: script.echoCustom("visualizerHome argument can't be null!",'ERROR')
        (visualizerVersion) ?: script.echoCustom("visualizerVersion argument can't be null!",'ERROR')
        (dependenciesFileName) ?: script.echoCustom("dependenciesFileName argument can't be null!",'ERROR')
        (dependenciesBaseUrl) ?: script.echoCustom("dependenciesBaseUrl argument can't be null!",'ERROR')
        (dependenciesArchiveFilePrefix) ?: script.echoCustom("dependenciesArchiveFilePrefix argument can't be null!",'ERROR')
        (dependenciesArchiveFileExtension) ?: script.echoCustom("dependenciesArchiveFileExtension argument can't be null!",'ERROR')

        def dependencies = []
        def dependenciesFileContent = fetchRequiredDependencies(script, visualizerVersion, dependenciesFileName,
                dependenciesBaseUrl, dependenciesArchiveFilePrefix, dependenciesArchiveFileExtension)
        def visualizerDependencies = (parseDependenciesFileContent(script, dependenciesFileContent)) ?:
                script.echoCustom("Visualizer dependencies object can't be null!",'ERROR')
        /* Construct installation path */
        def getInstallationPath = { toolPath ->
            ([visualizerHome] + toolPath).join(separator)
        }
        /* Create dependency object skeleton */
        def createDependencyObject = { variableName, homePath ->
            [variableName: variableName, homePath: homePath, binPath: [homePath, 'bin'].join(separator)]
        }
        /* Get tool path from Glogal Tools Configuration */
        def getToolPath = {
            script.tool([it.name, it.version].join('-'))
        }
        /*
            Iterate over dependencies, filter required one, get installation path for them on slave,
            switch dependency and  generate dependency object.
         */
        for (dependency in visualizerDependencies) {
            switch (dependency.name) {
                case 'gradle':
                    def installationPath = getInstallationPath([dependency.name])
                    script.env.isCIBUILD ?: switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('GRADLE_HOME', getToolPath(dependency)))
                    break
                case 'ant':
                    def installationPath = getInstallationPath(['Ant'])
                    script.env.isCIBUILD ?: switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('ANT_HOME', getToolPath(dependency)))
                    break
                case 'java':
                    def installationPath

                    if (isUnixNode) {
                        script.env.isCIBUILD ?: script.shellCustom(['mkdir -p', getInstallationPath(
                                ["jdk${dependency.version}.jdk", 'Contents'])].join(' '), isUnixNode
                        )
                        installationPath = getInstallationPath(["jdk${dependency.version}.jdk", 'Contents', 'Home'])
                    } else {
                        installationPath = getInstallationPath(['Java', "jdk${dependency.version}"])
                    }
                    script.env.isCIBUILD ?: switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('JAVA_HOME', getToolPath(dependency)))
                    break
                case 'node':
                    if (isUnixNode) {
                        script.env.NODE_HOME = [getToolPath(dependency),'bin'].join(separator)
                    } else {
                        script.env.NODE_HOME = getToolPath(dependency)
                    }
                    break
                case 'android build tools':
                    def installationPath = [script.env.ANDROID_HOME, 'build-tools', dependency.version].join(separator)
                    dependencies.add(createDependencyObject('ANDROID_BUILD_TOOLS', installationPath))
                default:
                    break
            }
        }

        /* Return list of generated dependency objects */
        dependencies
    }
    /* ---------------------------------------------------- END ---------------------------------------------------- */

    protected final static void fixRunShScript (script, filePath, fileName) {
        /* Check required arguments */
        (filePath) ?: script.echoCustom("filePath argument can't be null!",'ERROR')
        (fileName) ?: script.echoCustom("fileName argument can't be null!",'ERROR')

        script.dir(filePath) {
            if (script.fileExists(fileName)) {
                def updatedFileContent
                /* Missing statement */
                String statementToAdd = 'export PATH="$PATH:/usr/local/bin"'
                def listOfFileContentLines = script.readFile(fileName)?.trim()?.readLines()

                /* Check if file has missing statement */
                if (listOfFileContentLines?.contains(statementToAdd)) {
                    /* If statement already exists skipping fix */
                    updatedFileContent = listOfFileContentLines
                } else {
                    /* If not that injecting missing statement after existing export statement */
                    def exportStatementPosition = listOfFileContentLines?.
                            findIndexOf { it =~ 'export PATH="\\$PATH:\\$5"' }
                    updatedFileContent = listOfFileContentLines.plus(exportStatementPosition + 1, statementToAdd)
                }

                /* Collect updated string lines and inject new line character after 'exit $?' */
                String fileContent = updatedFileContent.join('\n')?.plus('\n')

                script.writeFile file: fileName, text: fileContent, encoding: 'UTF-8'
            }
        }
    }

    /**
     * Fetch lockable resources list from Jenkins
     *
     * @returns resouces : [['name':ResouceName,'status':state]]
     */
    protected final static getResoursesList(){
        def resources = []
        def resourceList = new  LockableResources().resources
        resourceList.each { resource ->
            resources << ['name': resource.getName(),'status':resource.isLocked()]
        }
        return resources
    }

    /**
     * Main function to determine node label for build
     *
     * @params ResoucesStatus with name and lock status [['name':ResouceName,'status':state]]
     * @params common Library properties object
     * @params Script build instance to log print statements
     *
     * @return NodeLabel
    */
    protected final static getAvailableNode(resoucesStatus,libraryProperties,script){
        def iosNodeLabel = libraryProperties.'ios.node.label'
        def winNodeLabel = libraryProperties.'windows.node.label'


        /* return win if no Node in Label 'ios' is alive  */
        if(!isLabelActive(iosNodeLabel, script)){
            script.echoCustom("All the MAC slaves are down currently. Starting on Windows")
            return winNodeLabel
        }
        /* return ios if no Node in Label 'win' is alive  */
        if(!isLabelActive(winNodeLabel, script)) {
            script.echoCustom("All the Windows slaves are down currently. Starting on Mac")
            return iosNodeLabel
        }

        def winResourceStatus
        def macResourceStatus

        resoucesStatus.each{
            if(it.name == libraryProperties.'window.lockable.resource.name') winResourceStatus=it.status
            if(it.name == libraryProperties.'ios.lockable.resource.name') macResourceStatus=it.status
        }

        if(winResourceStatus == true && macResourceStatus == false){
            return iosNodeLabel
        } else if(winResourceStatus == false && macResourceStatus == true){
            return winNodeLabel
        } else {
            return winNodeLabel + " || " + iosNodeLabel
        }
    }

    protected final static isLabelActive(label,script){
        def isActiveNodeAvailable = false

        Jenkins instance = Jenkins.getInstance()
        instance.getLabel(label).getNodes().each { node->
            if(node.toComputer()?.isOnline()){
                def nodeName = node.toComputer().getDisplayName()
                def isNodeOnline = node.toComputer().isOnline()
                isActiveNodeAvailable=true
            }
        }
        return isActiveNodeAvailable
    }
	
    protected final static getEnvironmentInfo(script){
        String cmd = script.isUnix() ? "env" : "set"
        String environmentInfo = script.shellCustom(cmd, script.isUnix(),[returnStdout: true]).trim()

        return environmentInfo
    }

    protected final static getInputParamsAsString(script){
        def paramsInfo = StringBuilder.newInstance()
        script.params.each{
            paramsInfo.append "${it.key} = ${it.value}\n"
        }
        return paramsInfo.toString()
    }
	
    protected final static createAuthUrl(artifactUrl, script, boolean exposeUrl = false) {
		
        def authArtifactUrl = "";
		
        if (script.env['CLOUD_ENVIRONMENT_GUID'] != "" && script.env['CLOUD_DOMAIN'] != ""){
            artifactUrl = artifactUrl.substring(artifactUrl.indexOf(script.env.PROJECT_NAME));
            authArtifactUrl = "https://manage." + script.env['CLOUD_DOMAIN'] + "/console/#/environments/" + script.env['CLOUD_ENVIRONMENT_GUID'] + "/downloads?path=" + artifactUrl
        }
        else {
            script.echoCustom("Failed to generate the authenticated URLs. " +
                    "Unable to find the cloud environment guid. ",'WARN')
            authArtifactUrl = artifactUrl
        }
		
        if (exposeUrl) {
            script.echoCustom("Artifact URL: ${authArtifactUrl}")
        }

        authArtifactUrl
    }
}