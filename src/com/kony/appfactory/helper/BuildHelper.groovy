package com.kony.appfactory.helper

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.jenkins.plugins.lockableresources.LockableResources
import hudson.plugins.timestamper.api.TimestamperAPI
import jenkins.model.Jenkins
import groovy.text.SimpleTemplateEngine
import groovy.json.JsonSlurper
import com.kony.AppFactory.Jenkins.rootactions.AppFactoryVersions
import com.kony.appfactory.helper.ConfigFileHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.AppFactory.fabric.api.oauth1.KonyOauth1Client
import com.kony.AppFactory.fabric.api.oauth1.dto.KonyExternalAuthN
import com.kony.AppFactory.fabric.FabricException;
import com.kony.AppFactory.fabric.FabricUnreachableException
import java.util.stream.Collectors;

/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {

    /**
     * Clones project source based on checkoutType. If checkoutType is scm, clone from the provided git repository, If
     * checkoutType is downloadzip, clones project source from the non-protected zip file download URL
     *
     * @param args for checkoutType scm
     *   script pipeline object.
     *   checkoutType - scm for cloning through git, downloadzip for direct download source from URL
     *   relativeTargetDir path where project should be stored.
     *   scmCredentialsId credentials that will be used to access the repository.
     *   scmUrl URL of the repository.
     *   scmBranch repository branch to clone.
     *
     * @param args for checkoutType downloadzip
     *   script pipeline object.
     *   downloadURL from which the source project should be downloaded
     *   relativeTargetDir path where project should be stored.
     */
    protected static void checkoutProject(Map args) {
        def script = args.script
        def scmVars = null
        def scmMeta = [:]
        String checkoutType = args.checkoutType
        String relativeTargetDir = args.projectRelativePath
        if (checkoutType.equals("scm")) {
            String scmCredentialsId = args.scmCredentialsId
            String scmUrl = args.scmUrl
            String scmBranch = args.scmBranch
            script.catchErrorCustom('Failed to checkout the project') {
                // re-try 3 times in case code checkout fails due to various reasons.
                 script.retry(3) {
                     scmVars = script.checkout(
                             changelog: true,
                             poll: false,
                             scm: getScmConfiguration(relativeTargetDir, scmCredentialsId, scmUrl, scmBranch)
                     )
                 }
            }
            scmMeta = getScmDetails(script, scmBranch, scmVars, scmUrl)
        } else if (checkoutType.equals("downloadzip")) {
            String projectFileName = args.projectFileName
            String downloadURL = args.downloadURL
            script.dir(relativeTargetDir) {
                // download the zip from non-protected url
                downloadFile(script, downloadURL, projectFileName)
                // extract the final downloaded zip
                def zipExtractStatus = script.shellCustom("unzip -q ${projectFileName}", true, [returnStatus: true])
                if (zipExtractStatus) {
                    script.currentBuild.result = "FAILED"
                    throw new AppFactoryException("Failed to extract the downloaded zip", 'ERROR')
                }
            }
        } else {
            throw new AppFactoryException("Unknown checkout source type found!!", "ERROR")
        }
        scmMeta
    }

    /**
     * This function deletes the list of directories passed to it
     * @param script
     * @param directories list of directories which had to be deleted
     */
    public static void deleteDirectories(script, directories) {

        for (directory in directories) {
            if (script.fileExists(directory)) {
                script.dir(directory) {
                    script.deleteDir()
                }
            }
        }
    }


	/**
	 * This method is responsible for downloading file from a pre signed or pre authenticated or an open url
	 * @param script
	 * @param sourceUrl is the target url, from which we want to download the artefact
	 * @param outputFileName is the fileName for the downloaded artefact
	 */
    protected static final void downloadFile(script, String sourceUrl, String outputFileName) {

        try {
            def projectDownload = script.shellCustom("curl -L --silent --show-error --fail -o \'${outputFileName}\' \'${sourceUrl}\'", true, [returnStatus: true, returnStdout: true])
            if (projectDownload) {
                script.currentBuild.result = "FAILED"
            }
        }
        catch (Exception e) {
            throw new AppFactoryException("Failed to download the file", 'ERROR')
        }
    }

    /**
     * Populates provided binding in template.
     *
     * @param text template with template tags.
     * @param binding values to populate, key of the value should match to the key in template (text argument).
     * @return populated template.
     */
    @NonCPS
    private static String populateTemplate(text, binding) {
        SimpleTemplateEngine engine = new SimpleTemplateEngine()
        Writable template = engine.createTemplate(text).make(binding)

        (template) ? template.toString() : null
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
                                                            relativeTargetDir: relativeTargetDir],
                                                           [$class : 'CloneOption',
                                                            timeout: 30]],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: scmCredentialsId,
                                                            url          : scmUrl]]]
                break
        }

        scm
    }

    private static getScmDetails(script, currentBuildBranch, scmVars, scmUrl) {
        List<String> logsList = new ArrayList<String>();
        if (script.currentBuild.getPreviousBuild() == null)
            logsList.add("Previous Build is unavailable, to fetch the diff.")
        else {
            String previousBuildBranch = script.currentBuild.getPreviousBuild().getRawBuild().actions.find { it instanceof ParametersAction }?.parameters.find { it.name == 'PROJECT_SOURCE_CODE_BRANCH' }?.value
            if (!currentBuildBranch.equals(previousBuildBranch))
                logsList.add("Unable to fetch diff, your previous build is on a different branch.")
            else if (script.currentBuild.changeSets.isEmpty())
                logsList.add("No diff is available")
            else {
                def changeLogSets = script.currentBuild.changeSets
                for (entries in changeLogSets) {
                    for (entry in entries) {
                        for (file in entry.affectedFiles) {
                            logsList.add(file.editType.name + ": " + file.path)
                        }
                    }
                }
            }
        }
        return [commitID: scmVars.GIT_COMMIT, scmUrl: scmUrl, commitLogs: logsList]
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
        if (cause instanceof Cause.UpstreamCause) {
            /* checking if the build cause is DeeplyNestedUpstreamCause because of build depth is more than 10 */
            if (cause instanceof Cause.UpstreamCause.DeeplyNestedUpstreamCause) {
                causedBy = ''
            } else {
                Cause.UpstreamCause c = (Cause.UpstreamCause) cause;
                List<Cause> upstreamCauses = c.getUpstreamCauses();
                for (Cause upstreamCause : upstreamCauses)
                    causedBy = getRootCause(upstreamCause)
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
     * Get the build log for a specific build of a specific job
     */
    @NonCPS
    protected static String getBuildLogText(jobFullName, buildNumber, script) {
        String buildLogText = ""
        BufferedReader reader
        Jenkins.instance.getItemByFullName(jobFullName).each { item ->
            Run currentBuild = ((Job) item).getBuild(buildNumber)
            if (currentBuild) {
                try {
                    reader = TimestamperAPI.get().read(currentBuild, "time=HH:mm:ss&appendLog")
                    buildLogText = reader.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Failed to capture the Build Log....'
                    script.echoCustom(exceptionMessage, 'ERROR', false)
                } finally {
                    if (reader != null) {
                        reader.close();
                    }
                }
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
                        applicationVersionVariable: 'FABRIC_APP_VERSION',
                        environmentNameVariable: 'FABRIC_ENV_NAME',
                        fabricAccountIdVariable: 'FABRIC_ACCOUNT_ID',
                        fabricConsoleUrlVariable: 'MF_CONSOLE_URL',
                        fabricIdentityUrlVariable: 'MF_IDENTITY_URL',
                        applicationKeyVariable: 'APP_KEY',
                        applicationSecretVariable: 'APP_SECRET',
                        serviceUrlVariable: 'SERVICE_URL',
                        hostType: 'HOST_TYPE'
                )
        ]) {
            /* Block of code to run */
            if (script.env.HOST_TYPE.equals("KONYCLOUD")) {
                script.env['FABRIC_ACCOUNT_ID'] = script.env.FABRIC_ACCOUNT_ID
                //setting default values for console/identity as we expect only accountId from Fabric triplet incase of KonyCloud host type.
                script.env['CONSOLE_URL'] = script.kony.FABRIC_CONSOLE_URL
                script.env['IDENTITY_URL'] = null
            }
            else {
                script.env['CONSOLE_URL'] = script.env.MF_CONSOLE_URL
                script.env['IDENTITY_URL'] = script.env.MF_IDENTITY_URL
                //setting default value for accountId as we expect only console/identity urls from Fabric triplet incase of non KonyCloud host type.
                script.env['FABRIC_ACCOUNT_ID'] = script.env.CLOUD_ACCOUNT_ID
            }

            closure()
        }
    }

    /**
     * Extract Protected Keys from credential plug-in.
     *
     * @param script pipeline object.
     * @param protectedKeysId protected Keys ID in Jenkins credentials store.
     * @param targetProtectedKeysPath for copying the keys at build workspace
     * @param closure block of code.
     */
    protected final static void extractProtectedKeys(script, protectedKeysId, targetProtectedKeysPath, closure) {
        script.withCredentials([[$class       : 'ProtectedModeTripletBinding',
                                 credentialsId: "${protectedKeysId}",
                                 filePath     : "${targetProtectedKeysPath}"
                                ]]) {
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
        (dependenciesFileContent) ?: script.echoCustom("File content string can't be null", 'ERROR')

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
        (dependencyPath) ?: script.echoCustom("Dependency path can't be null!", 'ERROR')
        (installationPath) ?: script.echoCustom("Installation path can't be null!", 'ERROR')

        if (isUnixNode) {
            script.shellCustom(
                    ['rm -rf', installationPath, '&&', 'ln -s', dependencyPath, installationPath].join(' '),
                    isUnixNode
            )
        } else {
            script.shellCustom(['(if exist', installationPath + '\\', 'fsutil reparsepoint query ' + installationPath,
                                '| ' + 'findstr /C:\"Print Name:\" | find \"' + dependencyPath + '\"  >nul && ' +
                                        'echo symbolic link found ) || rmdir /s /q', installationPath, '&&',
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
        (visualizerVersion) ?: script.echoCustom("Visualizer version couldn't be null!", 'ERROR')

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
        (separator) ?: script.echoCustom("separator argument can't be null!", 'ERROR')
        (visualizerHome) ?: script.echoCustom("visualizerHome argument can't be null!", 'ERROR')
        (visualizerVersion) ?: script.echoCustom("visualizerVersion argument can't be null!", 'ERROR')
        (dependenciesFileName) ?: script.echoCustom("dependenciesFileName argument can't be null!", 'ERROR')
        (dependenciesBaseUrl) ?: script.echoCustom("dependenciesBaseUrl argument can't be null!", 'ERROR')
        (dependenciesArchiveFilePrefix) ?: script.echoCustom("dependenciesArchiveFilePrefix argument can't be null!", 'ERROR')
        (dependenciesArchiveFileExtension) ?: script.echoCustom("dependenciesArchiveFileExtension argument can't be null!", 'ERROR')

        def dependencies = []
        def dependenciesFileContent = fetchRequiredDependencies(script, visualizerVersion, dependenciesFileName,
                dependenciesBaseUrl, dependenciesArchiveFilePrefix, dependenciesArchiveFileExtension)
        def visualizerDependencies = parseDependenciesFileContent(script, dependenciesFileContent)

        if (!visualizerDependencies) {
            throw new AppFactoryException("Visualizer dependencies object can't be null!", 'ERROR')
        }

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
                        script.env.NODE_HOME = [getToolPath(dependency), 'bin'].join(separator)
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

    protected final static void fixRunShScript(script, filePath, fileName) {
        /* Check required arguments */
        (filePath) ?: script.echoCustom("filePath argument can't be null!", 'ERROR')
        (fileName) ?: script.echoCustom("fileName argument can't be null!", 'ERROR')

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
    protected final static getResourcesList() {
        def resources = []
        def resourceList = new LockableResources().resources
        resourceList.each { resource ->
            resources << ['name': resource.getName(), 'status': resource.isLocked()]
        }
        return resources
    }

    /**
     * Check if CustomHooks defined and active. Return false if doesn't.
     *
     * @param ProjectFullPath
     * @param projectName
     * @return boolean
     */
    def static isActiveCustomHookAvailable(String projectFullPath, String projectName) {
        String configFileContent = ConfigFileHelper.getContent(projectFullPath, projectName)
        JsonSlurper jsonSlurper = new JsonSlurper();
        def configFileContentInJson = (configFileContent) ? jsonSlurper.parseText(configFileContent) : null;

        if (configFileContentInJson) {
            def checkAvailableActiveHook = { hookStage ->
                hookStage.any { hookProperties ->
                    hookProperties.status == 'enabled' ? true : false
                }
            }

            if (checkAvailableActiveHook(configFileContentInJson.PRE_BUILD)) return true
            if (checkAvailableActiveHook(configFileContentInJson.POST_BUILD)) return true
        }

        return false
    }

    /**
     * Main function to determine node label for build. This function implements node allocation strategy.
     * 1) CustomHooks always run in Mac/Linux Systems. So if there is any CustomHooks defined, for Android & SPA & IOS build
     *    should run in Mac machines.
     * 2) If CustomHooks not defined. Then there is case of Handling 7.3 Headless and 8.0 CI builds.
     *    Now, CI builds can run in Parallel but Headless builds doesn't support Parallel builds.
     *    This function take cares in any Headless build running in Any agent, other headless build started, it shouldn't run
     *    in same agent. It finds if there is any other Compatible agent is free and allocate that agent to run current build.
     *
     *    For eg. Android and SPA both can runs in both WIN and MAC, So if WIN is occupied, then next build should occupy MAC
     *    and vice versa.
     *    For mac, if multiple headless build got triggered, then only one will occupy MAC and all other headless build will
     *    be in waiting state.
     *
     * @params Resources Status with name and lock status [['name':Resource Name,'status':state]]
     * @params common Library properties object
     * @params Script build instance to log print statements
     * @params isActiveCustomHookAvailable
     *
     * @return NodeLabel
     */
    protected final static getAvailableNode(resourcesStatus, libraryProperties, script, isThisBuildWithCustomHooksRun, channelOs) {
        def iosNodeLabel = libraryProperties.'ios.node.label'
        def winNodeLabel = libraryProperties.'windows.node.label'

        /*
         *  1. Checks if user wants to run CustomHooks
         *  2. If Yes, check if there are any CustomHooks defined and are in active state.
         */

        if (isThisBuildWithCustomHooksRun) {
            script.echoCustom('Found active CustomHooks. Allocating MAC agent.')
            return iosNodeLabel;
        }

        /* return win if no Node in Label 'ios' is alive  */
        if (!isLabelActive(iosNodeLabel, script)) {
            script.echoCustom('All the MAC slaves are down currently. Starting on Windows')
            return winNodeLabel
        }
        /* return ios if no Node in Label 'win' is alive  */
        if (!isLabelActive(winNodeLabel, script)) {
            script.echoCustom('All the Windows slaves are down currently. Starting on Mac')
            return iosNodeLabel
        }

        def winResourceStatus
        def macResourceStatus

        resourcesStatus.each {
            if (it.name == libraryProperties.'window.lockable.resource.name') {
                winResourceStatus = it.status
            }
            if (it.name == libraryProperties.'ios.lockable.resource.name') {
                macResourceStatus = it.status
            }
        }

        if (winResourceStatus == true && macResourceStatus == false) {
            return iosNodeLabel
        } else if (winResourceStatus == false && macResourceStatus == true) {
            return winNodeLabel
        } else {
            return (channelOs == 'Android') ? libraryProperties.'android.node.label' : libraryProperties.'spa.node.label'
        }
    }

    protected final static isLabelActive(label, script) {
        def isActiveNodeAvailable = false

        Jenkins instance = Jenkins.getInstance()
        instance.getLabel(label).getNodes().each { node ->
            if (node.toComputer()?.isOnline()) {
                def nodeName = node.toComputer().getDisplayName()
                def isNodeOnline = node.toComputer().isOnline()
                isActiveNodeAvailable = true
            }
        }
        return isActiveNodeAvailable
    }

    protected final static getEnvironmentInfo(script) {
        String cmd = script.isUnix() ? "env" : "set"
        String environmentInfo = script.shellCustom(cmd, script.isUnix(), [returnStdout: true]).trim()

        return environmentInfo
    }

    protected final static getInputParamsAsString(script) {
        def paramsInfo = StringBuilder.newInstance()
        script.params.each {
            paramsInfo.append "${it.key} = ${it.value}\n"
        }
        return paramsInfo.toString()
    }

    /*
     * This method is used to to create authenticated urls from S3 urls
     * @param artifactUrl is the url which we want to convert as authenticated
     * @param script is default script parameter
     * @param exposeUrl is made as true if we want to display it in the console
     * @param action - (downloads or view): decides whether the url is direct download link or directly view from browser (such as HTML files), default value is "downloads"
     */

    protected final static createAuthUrl(artifactUrl, script, boolean exposeUrl = false, String action = "downloads") {

        def authArtifactUrl = artifactUrl

        if (script.env['CLOUD_ENVIRONMENT_GUID'] && script.env['CLOUD_DOMAIN']) {
            String searchString = [script.env.CLOUD_ACCOUNT_ID, script.env.PROJECT_NAME].join("/")
            //artifactUrl is already encoded but only for space and double quote character. Avoid double encoding for these two special characters.
            def subStringIndex = 0
            if (artifactUrl.indexOf(searchString) > 0)
                subStringIndex = artifactUrl.indexOf(searchString)
            def encodedArtifactUrl = artifactUrl
                    .substring(subStringIndex)
                    .replace('%20', ' ')
                    .replace('%22', '"')
                    .split("/")
                    .collect({ URLEncoder.encode(it, "UTF-8") })
                    .join('/')
                    .replace('+', '%20')
                    .replace('"', '%22')

            def externalAuthID = (script.env['URL_PATH_INFO']) ? "?url_path=" + URLEncoder.encode(script.env['URL_PATH_INFO'], "UTF-8") : ''
            authArtifactUrl = "https://manage." + script.env['CLOUD_DOMAIN'] + "/console/" + externalAuthID + "#/environments/" + script.env['CLOUD_ENVIRONMENT_GUID'] + "/downloads?path=" + encodedArtifactUrl
        } else {
            script.echoCustom("Failed to generate the authenticated URLs. Unable to find the cloud environment guid.", 'WARN')
        }

        if (exposeUrl) {
            script.echoCustom("Artifact URL: ${authArtifactUrl}")
        }

        authArtifactUrl
    }

    /**
     *  Tells whether the current build is rebuilt or not
     *
     * @param script
     * @return true if the current build is rebuilt from previous build, false otherwise
     */
    protected final static isRebuildTriggered(script) {
        boolean isRebuildFlag = false
        script.currentBuild.rawBuild.actions.each { action ->
            if (action.hasProperty("causes")) {
                action.causes.each { cause ->
                    if (cause instanceof com.sonyericsson.rebuild.RebuildCause) {
                        isRebuildFlag = true
                    }
                }
            }
        }
        return isRebuildFlag
    }

    /* This is required as each build can be trigger from IOS Android or SPA.
     *  To give permission to channel jobs workspace we need info about Upstream job
     *
     *  @param script
     *  return upstreamJobName
     * */

    @NonCPS
    protected final static getUpstreamJobName(script) {
        String upstreamJobName = null
        script.currentBuild.rawBuild.actions.each { action ->
            if (action.hasProperty("causes")) {
                action.causes.each { cause ->
                    if (cause instanceof hudson.model.Cause$UpstreamCause && cause.hasProperty("shortDescription") && cause.shortDescription.contains("Started by upstream project")) {
                        upstreamJobName = cause.upstreamRun?.getEnvironment(TaskListener.NULL)?.get("JOB_BASE_NAME")
                    }
                }
            }
        }
        upstreamJobName
    }


    /*  Provides the Upstream Job Build Number.
     *  It is required to keep the S3 upload path of buildresults.html in Cloud Build consistent with Single Tenant.
     *
     *  @param script
     *  return upstreamJobNumber
     * */

    @NonCPS
    protected final static getUpstreamJobNumber(script) {
        String upstreamJobNumber = null
        script.currentBuild.rawBuild.actions.each { action ->
            if (action.hasProperty("causes")) {
                action.causes.each { cause ->
                    if (cause instanceof hudson.model.Cause$UpstreamCause && cause.hasProperty("shortDescription") && cause.shortDescription.contains("Started by upstream project")) {
                        upstreamJobNumber = cause.upstreamRun?.getEnvironment(TaskListener.NULL)?.get("BUILD_NUMBER")
                    }
                }
            }
        }
        upstreamJobNumber
    }

    /**
     * Get the app id type for the native channel
     *
     * @param channelOs
     * @param channelFormFactor
     * @return appIdType.
     */
    protected static getAppIdTypeBasedOnChannleAndFormFactor(channelOs, channelFormFactor) {
        def appIdType = [channelOs, channelFormFactor, "APP_ID"].join('_').toUpperCase()
        return appIdType
    }

    /**
     * Returns the flag to trigger the custom hook based on RUN_CUSTOM_HOOK build parameters flag
     * and available active custom hook for the project
     *
     * @param projectName
     * @param runCustomHook
     * @param libraryProperties
     * @return runCustomHookForBuild
     */
    protected final static isThisBuildWithCustomHooksRun(projectName, buildType, runCustomHook, libraryProperties) {
        def customhooksConfigFolder = [projectName, buildType.toString(), libraryProperties.'customhooks.folder.name'].join('/')
        return (runCustomHook && isActiveCustomHookAvailable(customhooksConfigFolder, projectName))
    }

    /**
     * Collects selected channels to build.
     *
     * @param buildParameters job parameters.
     * @return list of selected channels.
     */
    @NonCPS
    private static getSelectedChannels(buildParameters) {
        /* Creating a list of boolean parameters that are not Target Channels */
        buildParameters.findAll {
            it.value instanceof Boolean && (it.key.matches('^ANDROID_.*_NATIVE$') || it.key.matches('^IOS_.*_NATIVE$')
                    || it.key.matches('^ANDROID_.*_SPA$') || it.key.matches('^IOS_.*_SPA$')
                    || it.key.matches('^DESKTOP_WEB')) && it.value
        }.keySet().collect()
    }

    
    /* Get the param based on DSL job availability */
    @NonCPS
    private static getCurrentParamName(script, newParam, oldParam) {
        def paramName = script.params.containsKey(newParam) ? newParam : oldParam
        return paramName
    }
    
    /* Get the param value based on DSL job param availability */
    @NonCPS
    private static getCurrentParamValue(script, newParam, oldParam) {
        def paramValue = script.params[getCurrentParamName(script, newParam, oldParam)]
        return paramValue
    }
    
    /* Get the param value if exists other wise send the default value that is being passed */
    @NonCPS
    private static getParamValueOrDefault(script, param, defaultValue) {
        def paramValue = script.params.containsKey(param) ? script.params[param] : defaultValue
        return paramValue
    }
    
    /* Get the status for any param existence from probable list of param*/
    @NonCPS
    private static doesAnyParamExistFromProbableParamList(script, paramList = []) {
        boolean isParamFound = false
        for (param in paramList) {
            if(script.params.containsKey(param)) {
                isParamFound = true
                break
            }
        }
        return isParamFound
    }
    
    /* Get the param value if exists other wise send the default value that is being passed */
    @NonCPS
    private static getParamValueOrDefaultFromProbableParamList(script, paramList = [], defaultValue) {
        def paramValue
        boolean isParamFound = false
        for (param in paramList) {
            if(script.params.containsKey(param)) {
                isParamFound = true
                paramValue = script.params[param]
                break
            }
        }
        return isParamFound ?  paramValue : defaultValue
    }

    /**
     * Created a zip with all MustHaves the artifacts, uploads to s3, creates Auth URL and sets the environment variable "MUSTHAVE_ARTIFACTS".
     * @param script current build instance
     * @param projectFullPath The full path of the project for which we are creating the MustHaves
     * @param mustHaveFile The file for which we are going to create a zip
     * @param separator This is used while creating paths
     * @param s3ArtifactPath Path where we are going to publish the S3 artifacts
     * @param channelVariableName The channel for which we are creating the MustHaves
     * @return s3MustHaveAuthUrl The authenticated URL of the S3 url
     */
    protected static def uploadBuildMustHavesToS3 (script, projectFullPath, mustHavePath, mustHaveFile, separator, s3ArtifactPath, channelVariableName) {
        def upstreamJob = BuildHelper.getUpstreamJobName(script)
        def isRebuild = BuildHelper.isRebuildTriggered(script)
        def mustHaves = []
        def s3MustHaveAuthUrl
        String mustHaveFilePath = [projectFullPath, mustHaveFile].join(separator)
        script.dir(projectFullPath) {
            script.catchErrorCustom("Failed to create the zip file") {
                script.zip dir: mustHavePath, zipFile: mustHaveFile
                if (script.fileExists(mustHaveFilePath)) {
                    s3MustHaveAuthUrl = AwsHelper.publishMustHavesToS3(script, s3ArtifactPath, mustHaveFile, projectFullPath, upstreamJob, isRebuild, channelVariableName, mustHaves)
                }
            }
        }
        s3MustHaveAuthUrl
    }

    /**
     * Set the external (third party) authentication login path as URL_PATH_INFO env variable, if enabled for the provided MF Account.
     * @param script
     * @param cloudDomain: The domain of the Kony cloud in which the account is hosted -e.g. kony.com, sit2-kony.com, etc.
     * @param mfApiVersion: The version of the Kony API to be used for authentication.
     * @param environmentGuid: The GUID of the Kony environment (MF_ENVIRONMENT_GUID).
     */
    protected final static void getExternalAuthInfoForCloudBuild(script, cloudDomain, mfApiVersion, environmentGuid) {
        try{
            KonyExternalAuthN externalAuth = KonyOauth1Client.getExternalAuthNConfig(cloudDomain, mfApiVersion, environmentGuid)
            //If external authentication is enabled.
            if (externalAuth != null && externalAuth.urlPath != null && !externalAuth.urlPath.trim().isEmpty()) {
                script.echoCustom("Third party authentication is enabled with url path: ${externalAuth.urlPath}")
                script.env['URL_PATH_INFO'] = externalAuth.urlPath
            }
        }
        catch (FabricUnreachableException e1) {
            throw new AppFactoryException("Unable to reach the Kony Cloud..")
        }
        catch(FabricException e){
            throw new AppFactoryException("Looks like Oauth key is no longer accepted.. Please retry..")
        }
    }

    /**
     * Fetches list of Kony released versions from Visualizer updatesite.
     * @param script pipeline object.
     * @param common Library properties object
     * return releasedVersionsList
     **/
    protected static String getVisualizerReleasedVersions(script, libraryProperties) {
        /* Added the check to use update site link for v9 prod if project version is :9.X.X  */
        def updatesiteVersionInfoUrl = Pattern.matches("^9\\.\\d+\\.\\d+\$", script.env["visualizerVersion"]) ?
            libraryProperties.'visualizer.dependencies.updatesite.v9.versioninfo.base.url' :
            libraryProperties.'visualizer.dependencies.updatesite.versioninfo.base.url'
            
        updatesiteVersionInfoUrl = updatesiteVersionInfoUrl.replaceAll("\\[CLOUD_DOMAIN\\]", script.env.CLOUD_DOMAIN)

        def updatesiteVersionInfoFile = "versionInfo.json"

        downloadFile(script, updatesiteVersionInfoUrl, updatesiteVersionInfoFile)

        def versionInfoFileContent = script.readJSON file: updatesiteVersionInfoFile
        def releaseKeySet = versionInfoFileContent.visualizer_enterprise.releases.version
        releaseKeySet?.findAll { it =~ /(\d+)\.(\d+)\.(\d+)$/ }
    }

    /**
     * Sorts versions passed in ArrayList and returns most recent nth version.
     * Able to sort the versions in two dot and three dot formats, example [8.2.1, 8.4.2.3, 8.5.6] will return 8.5.6 if
     * index passed is -1 and 8.4.2.3 if index passed is -2.
     * By default, it returns most latest version.
     * @param version List
     * @param index value to return (pass negative value to get nth release from latest version)
     * return mostRecentNthVersion
     **/
    @NonCPS
    protected static String getMostRecentNthVersion(versions, index=-1) {
        def sorted = getSortedVersionList(versions)
        def maxIndexExist = -(sorted.size())
        if (maxIndexExist <= index) {
            sorted[index]
        } else {
            sorted[maxIndexExist]
        }
    }
    
    
    /**
     * Get the sorted list of versions
     * @param versionsList list with versions
     * @return sortedVersionsList
     */
    @NonCPS
    protected static getSortedVersionList(versionsList) {
        def sortedVersionsList = versionsList.sort(false) { a, b ->

            List verA = a.tokenize('.')
            List verB = b.tokenize('.')

            def commonIndices = Math.min(verA.size(), verB.size())

            for (int i = 0; i < commonIndices; ++i) {
                def numA = verA[i].toInteger()
                def numB = verB[i].toInteger()

                if (numA != numB) {
                    return numA <=> numB
                }
            }

            // If we got this far then all the common indices are identical, so whichever version is longer must be more recent
            verA.size() <=> verB.size()
        }

        sortedVersionsList
    }

    /**
     * Determine which Visualizer version project requires,
     * according to the version that matches first in the order of branding/studioviz/keditor plugin.
     *
     * @return Visualizer version.
     */
    protected final static getVisualizerVersion(script) {
        if(script.env.IS_STARTER_PROJECT.equals("true")) {
            if(script.env.IS_KONYQUANTUM_APP_BUILD.equalsIgnoreCase("true")){
                return script.env.QUANTUM_CHILDAPP_VIZ_VERSION
            }
            else {
                def projectPropertiesJsonContent = script.readJSON file: 'projectProperties.json'
                return projectPropertiesJsonContent['konyVizVersion']
            }
        }

        def konyPluginsXmlFileContent = script.readFile('konyplugins.xml')

        String visualizerVersion = ''
        def plugins = [
                'Branding'       : /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.ide.paas.branding"/,
                'Studioviz win64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.win64"/,
                'Studioviz mac64': /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.kony.studio.viz.core.mac64"/,
                'KEditor'        : /<pluginInfo version-no="(\d+\.\d+\.\d+)\.\w*" plugin-id="com.pat.tool.keditor"/
        ]

        plugins.find { pluginName, pluginSearchPattern ->
            if (konyPluginsXmlFileContent =~ pluginSearchPattern) {
                visualizerVersion = Matcher.lastMatcher[0][1]
                script.echoCustom("Found ${pluginName} plugin!")
                /* Return true to break the find loop, if at least one match been found */
                return true
            } else {
                script.echoCustom("Could not find ${pluginName} plugin entry... " +
                        "Switching to the next plugin to search...")
            }
        }

        visualizerVersion
    }
    
    /**
     * This wrapper function will create the required environment for packaging the scripts and resolve the dependencies.
     * Jasmine packaging node script, can be called with in this wrapper.
     */
    protected final static jasmineTestEnvWrapper(script, closure) {
        
        /* Project will be considered as Starter Project on below case
         * IS_SOURCE_VISUALIZER parameter is set to true
         * OR
         * if konyplugins.xml doesn't exist
         **/
        def konyPluginExists = script.fileExists file: "konyplugins.xml"
        script.env.IS_STARTER_PROJECT = (ValidationHelper.isValidStringParam(script, 'IS_SOURCE_VISUALIZER') ? script.params.IS_SOURCE_VISUALIZER : false) || !konyPluginExists
        script.env.IS_KONYQUANTUM_APP_BUILD = ValidationHelper.isValidStringParam(script, 'IS_KONYQUANTUM_APP_BUILD') ? script.params.IS_KONYQUANTUM_APP_BUILD : false

        def libraryProperties = loadLibraryProperties(script, 'com/kony/appfactory/configurations/common.properties')
        def visualizerVersion = getVisualizerVersion(script)

        /* Checking the supported version, if Jasmine is selected as framework and
         * either device pool is selected or the desktop web test execution is selected */
        script.env["visualizerVersion"] = visualizerVersion
        def finalParamsJasmineTestsSupport = [:]
        finalParamsJasmineTestsSupport.put('Jasmine', ['featureDisplayName': 'Jasmine Tests Execution'])
        ValidationHelper.checkFeatureSupportExist(script, libraryProperties, finalParamsJasmineTestsSupport, 'tests')

        /* Get Visualizer dependencies */
        def dependenciesFilePath = fetchRequiredDependencies(script, visualizerVersion, libraryProperties.'visualizer.dependencies.file.name',
                        libraryProperties.'visualizer.dependencies.base.url',
                        libraryProperties.'visualizer.dependencies.archive.file.prefix',
                        libraryProperties.'visualizer.dependencies.archive.file.extension')

        def testRunDependencies = parseDependenciesFileContent(script, dependenciesFilePath)
        
        def nodeversion = null
        
        /*
            Get the node version details - This will work if we follow new approach for the tools availability.
         */
        for (dependency in testRunDependencies) {
            switch (dependency.name) {
                case 'node':
                    nodeversion = [dependency.name, dependency.version].join('-')
                    break
                default:
                    break
            }
        }
        
        script.nodejs(nodeversion) {
            closure()
        }
    }

    /**
     * This method handles if there are any spaces in directory and trim extra space
     * @param path indicates path of the file.
     */
    protected static String addQuotesIfRequired(path) {
        return path.trim().contains(" ") ? "\"" + path.trim() + "\"" : path.trim()
    }
    
    /**
     *  Method to run pre build custom hooks
     * @param script Current build script handle
     * @param isCustomHookRunBuild flag that indicates whether to run the hooks or not
     * @param hookHelper object with which we can initiates the hooks execution
     * @param projectName Name of the current project
     * @param hookBuildStage Name of the hook stage
     * @param customHookStage Name of the hook channels.
     */
    protected static final void runPreBuildHook(script, isCustomHookRunBuild, hookHelper, projectName, hookBuildStage, customHookStage) {
        script.stage('Check PreBuild Hook Points') {
            runHooks(script, isCustomHookRunBuild, {
                hookHelper.runCustomHooks(projectName, hookBuildStage, customHookStage)
            })
        }
    }

    /**
     * Method to run post build custom hooks
     * @param script Current build script handle
     * @param isCustomHookRunBuild flag that indicates whether to run the hooks or not
     * @param hookHelper object with which we can initiates the hooks execution
     * @param projectName Name of the current project
     * @param hookBuildStage Name of the hook stage
     * @param customHookStage Name of the hook channels.
     */
    protected static final void runPostBuildHook(script, isCustomHookRunBuild, hookHelper, projectName, hookBuildStage, customHookStage) {
        script.stage('Check PostBuild Hook Points') {
            runHooks(script, isCustomHookRunBuild, {
                if (script.currentBuild.currentResult == 'SUCCESS') {
                    hookHelper.runCustomHooks(projectName, hookBuildStage, customHookStage)
                } else {
                    script.echoCustom('CustomHooks execution is skipped as current build result is NOT SUCCESS.', 'WARN')
                }
            })
        }
    }
    
    /**
     *  Method to run post deploy custom hooks
     * @param script Current build script handle
     * @param isCustomHookRunBuild flag that indicates whether to run the hooks or not
     * @param hookHelper object with which we can initiates the hooks execution
     * @param projectName Name of the current project
     * @param hookBuildStage Name of the hook stage
     * @param customHookStage Name of the hook channels.
     */
    protected static final void runPostDeployHook(script, isCustomHookRunBuild, hookHelper, projectName, hookBuildStage, customHookStage) {
        runHooks(script, isCustomHookRunBuild, {
            if (script.currentBuild.currentResult == 'SUCCESS') {
                hookHelper.runCustomHooks(projectName, hookBuildStage, customHookStage)
            } else {
                script.echoCustom('CustomHooks execution is skipped as current build result is NOT SUCCESS.', 'WARN')
            }
        })
    }

    /**
     *  Method to run custom hooks
     * @param isCustomHookRunBuild flag that indicates whether to run the hooks or not
     * @param closure piece of code that need to executed.
     */
    protected static final void runHooks(script, isCustomHookRunBuild, closure) {
        if (isCustomHookRunBuild) {
            closure()
        } else {
            script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped.', 'WARN')
        }
    }
            
    /**
     * This method returns the difference between the given timestamps.
     * @param startDate
     * @param endDate
     * @return duration
     */

    @NonCPS
    private static long getDuration(Date startDate, Date endDate) {
        use(groovy.time.TimeCategory) {
            long duration = endDate.time - startDate.time
            return duration
        }
    }

    /**
     * Get the AppFactory version information (appfactory plugin version, core plugins versions, Kony Libarary branch information )
     */
    protected static final String getMyAppFactoryVersions() {
        def apver = new AppFactoryVersions()
        def versionInfo = StringBuilder.newInstance()

        versionInfo.append "PipeLine Version : " + apver.getPipelineVersion()
        versionInfo.append "\nDSL Job Version : " + apver.getJobDslVersion()
        versionInfo.append "\nAppFactory Plugin Version : " + apver.getAppFactoryPluginVersion()
        versionInfo.append "\nAppFactory Custom View Plugin Version : " + apver.getCustomViewPluginVersion()
        versionInfo.append "\nAppFactory Build Parameters Plugin Version : " + apver.getBuildParametersPluginVersion()
        versionInfo.append "\nAppFactory Build Listener Plugin Version : " + apver.getBuildListenerPluginVersion()

        def corePlugInVersionInfo = apver.getCorePluginVersions()

        corePlugInVersionInfo.each { pluginName, pluginVersion ->
            versionInfo.append "\n$pluginName : $pluginVersion"
        }

        versionInfo.toString()
    }
    
    /**
     * Prepare mustHave log for debugging
     * @param script
     * @param buildType
     * @param facadeJobMustHavesFolderName
     * @param facadeJobBuildLogFile
     * @param mustHaveArtifacts
     * @return s3MustHaveAuthUrl fabric authenticated auth url to download musthaves
     */
    protected static final String prepareMustHaves(script, buildType, facadeJobMustHavesFolderName, facadeJobBuildLogFile, mustHaveArtifacts) {
        String s3MustHaveAuthUrl = ''
        String separator = script.isUnix() ? '/' : '\\'
        String mustHaveFolderPath = [script.env.WORKSPACE, facadeJobMustHavesFolderName].join(separator)
        String mustHaveFile = [facadeJobMustHavesFolderName, script.env.BUILD_NUMBER].join("_") + ".zip"
        String mustHaveFilePath = [script.env.WORKSPACE, mustHaveFile].join(separator)
        script.cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: "${facadeJobMustHavesFolderName}*", type: 'INCLUDE']]

        script.dir(mustHaveFolderPath) {
            script.writeFile file: facadeJobBuildLogFile, text: getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
            script.writeFile file: "AppFactoryVersionInfo.txt", text: getMyAppFactoryVersions()
            script.writeFile file: "environmentInfo.txt", text: getEnvironmentInfo(script)
            script.writeFile file: "ParamInputs.txt", text: getInputParamsAsString(script)
            if(buildType.toString().equals("Visualizer")) {
                AwsHelper.downloadChildJobMustHavesFromS3(script, mustHaveArtifacts)
            } else {
                mustHaveArtifacts?.each { mustHaveArtifact ->
                    def artifactToBeAddedToMustHave = [mustHaveArtifact.path, mustHaveArtifact.name].join(separator)
                    script.shellCustom("cp \"${artifactToBeAddedToMustHave}\" \"${mustHaveFolderPath}\"", true, [returnStdout:true])
                }
            }
        }

        script.dir(script.env.WORKSPACE) {
            script.zip dir: facadeJobMustHavesFolderName, zipFile: mustHaveFile
            script.catchErrorCustom("Failed to create the Zip file") {
                if (script.fileExists(mustHaveFilePath)) {
                    String s3ArtifactPath = ['Builds', script.env.PROJECT_NAME].join('/')
                    s3MustHaveAuthUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath, sourceFileName: mustHaveFile,
                            sourceFilePath: script.env.WORKSPACE, script
                    s3MustHaveAuthUrl = createAuthUrl(s3MustHaveAuthUrl, script)
                }
            }
        }
        s3MustHaveAuthUrl
    }
    
    /**
     * Sets build description at the end of the build.
     * @param script
     * @param s3MustHaveAuthUrl
     * @param buildArtifactName its optional param
     */
    protected static final void setBuildDescription(script, s3MustHaveAuthUrl, String buildArtifactName = null) {
        String EnvironmentDescription = ""
        String mustHavesDescription = ""
        String buildArtifactDescription = ""
        if (script.env.FABRIC_ENV_NAME && script.env.FABRIC_ENV_NAME != '_') {
            EnvironmentDescription = "<p>Environment: $script.env.FABRIC_ENV_NAME</p>"
        }
        
        if(buildArtifactName)
            buildArtifactDescription = "<p>App Name: $buildArtifactName</p>"

        if(s3MustHaveAuthUrl)
            mustHavesDescription = "<p><a href='${s3MustHaveAuthUrl}'>Logs</a></p>"

        script.currentBuild.description = """\
            <div id="build-description">
                ${EnvironmentDescription}
                ${buildArtifactDescription}
                <p>Rebuild:<a href='${script.env.BUILD_URL}rebuild' class="task-icon-link">
                <img src="/static/b33030df/images/24x24/clock.png" style="width: 24px; height: 24px; margin: 2px;"
                class="icon-clock icon-md"></a>${mustHavesDescription}</p>
            </div>\
            """.stripIndent()
    }
}
