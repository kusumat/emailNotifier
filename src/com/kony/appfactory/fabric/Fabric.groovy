package com.kony.appfactory.fabric

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.FabricHelper

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Implements logic for base Fabric commands and some additional method (fetch Fabric CLI, prettify JSON files, etc).
 */
class Fabric implements Serializable {
    /* Pipeline object */
    private script
    /* Data that should we sent in e-mail notification */
    private emailData
    /* Stores data for app change flag */
    private boolean appChanged
    /* Stores data that should be provided in build description section */
    private buildDescriptionItems
    /* Library configuration */
    private libraryProperties
    /* All properties below will be loaded from library configuration */
    private final String fabricCliFileName
    private final String fabricCliVersion
    private final String nodeLabel
    /*
        Currently all scripts in this class been written with the thought that they(scripts) will be executed
        on *nix machine, for future improvements OS type check been added to every command(export, import, publish).
     */
    private boolean isUnixNode
    /* Common build parameters */
    private final String exportRepositoryUrl
    private final String exportRepositoryBranch
    private final String exportRepositoryCredentialsId
    private final String cloudAccountId = script.params.CLOUD_ACCOUNT_ID
    private final String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private final String fabricAppName = script.params.FABRIC_APP_NAME
    private final String recipientsList = script.params.RECIPIENTS_LIST
    private final String fabricAppVersion
    
    /* Import build parameters */
    private final String commitAuthor = script.params.COMMIT_AUTHOR?.trim() ?: 'Jenkins'
    private final String authorEmail = script.params.AUTHOR_EMAIL
    private String commitMessage = script.params.COMMIT_MESSAGE?.trim() ?:
            "Automatic backup of Fabric services" +
                    (script.env.BUILD_NUMBER ? ", build-${script.env.BUILD_NUMBER}" : '.')

    /* Force Fabric CLI to overwrite existing application on import */
    private boolean overwriteExisting
    private boolean overwriteExistingScmBranch
    private boolean overwriteExistingAppVersion
    /* Migrate specific build parameters for backward compatibility*/
    private String importCloudAccountId
    private String importCloudCredentialsID
    private String exportCloudAccountId
    private String exportCloudCredentialsID
    /* Flag for triggering Publish job */
    private final boolean enablePublish = script.params.ENABLE_PUBLISH
    /* Publish build parameters */
    private final String fabricEnvironmentName = script.params.FABRIC_ENVIRONMENT_NAME
    private final boolean setDefaultVersion = script.params.SET_DEFAULT_VERSION

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    Fabric(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        fabricCliVersion = libraryProperties.'fabric.cli.version'
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        nodeLabel = libraryProperties.'fabric.node.label'
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'

        /*
            To maintain backward compatibility, checking if param exist, if it doesn't then
            setting the value to default 1.0 which is fabric base version for any app
            that you submit before SP2.
        */
        this.script.env['FABRIC_APP_VERSION'] = BuildHelper.getParamValueOrDefault(
                this.script, 'FABRIC_APP_VERSION', '1.0')
        fabricAppVersion = this.script.env['FABRIC_APP_VERSION']
        
        overwriteExisting = BuildHelper.getParamValueOrDefault(this.script, 'OVERWRITE_EXISTING', true)
        
         /* To maintain Fabric triggers jobs backward compatibility */
        exportRepositoryUrl = BuildHelper.getCurrentParamValue(
                this.script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL')
        exportRepositoryBranch = BuildHelper.getCurrentParamValue(
                this.script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH')
        exportRepositoryCredentialsId = BuildHelper.getCurrentParamValue(
                this.script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID')
    }

    /**
     * Validates if a local copy of fabric app is having the same version which user has selected.
     *
     *  Meta.js File content at location ${GitRepositoryName}/export/Apps/${APP_NAME}/Meta.json
     *  <pre>
     *  {@code
     *      "subType":"customapp",
     *      "description":"MyWork",
     *      "version":"1.0"
     *  }
     *  </pre>
     *
     * @param fabricAppVersion
     * @param metaFileLocation
     * @return boolean : return true if version is same else false.
     */
    private final boolean validateLocalFabricAppVersion(String fabricAppVersion, String metaFileLocation) {
        def metaJson = script.readJSON file: metaFileLocation
        return (fabricAppVersion == metaJson.version)
    }

    /**
     * Checks if there were changes in application code from previous export (build).
     *
     * @param args method named arguments.
     *      previousArchivePath path to the zip archive from previous build.
     *      currentArchivePath path to the zip archive for this build.
     * @return result of check if application code was changed.
     */
    private final boolean fabricAppChanged(args) {
        boolean status = true
        String previousArchivePath = args.previousPath
        String currentArchivePath = args.currentPath
        String errorMessage = 'Failed to check if exported project has been changed!'

        script.catchErrorCustom(errorMessage) {
            if (script.fileExists(previousArchivePath)) {
                script.unzip zipFile: previousArchivePath, dir: 'previous'
                script.unzip zipFile: currentArchivePath, dir: 'current'

                /* Check if previous and current code are equal */
                status = script.shellCustom(
                        'diff -r previous current > /dev/null',
                        isUnixNode,
                        [returnStatus: true]
                ) != 0

                /* Clean up tmp folders */
                script.dir('previous') {
                    script.deleteDir()
                }

                script.dir('current') {
                    script.deleteDir()
                }
            }
        }

        status
    }

    /**
     * Searches for JSON files in application's code.
     *
     * @param args method named arguments.
     *      folderToSearchIn path to the application folder in workspace.
     * @return found files.
     */
    private final findJsonFiles(args) {
        def files
        String folderToSearchIn = args.folderToSearchIn
        String errorMessage = 'Failed to find JSON files'

        script.catchErrorCustom(errorMessage) {
            script.dir(folderToSearchIn) {
                files = script.findFiles(glob: '**/*.json')
            }
        }

        files
    }

    /**
     * Prettifies application's JSON files.
     *
     * @param args method named arguments.
     *      rootFolder path to the application folder in workspace.
     */
    private final void prettifyJsonFiles(args) {
        def files = args.files
        String rootFolder = args.rootFolder
        String errorMessage = 'Failed to prettify JSON files'

        script.catchErrorCustom(errorMessage) {
            script.dir(rootFolder) {
                for (int i = 0; i < files.size(); i++) {
                    def JSONPath = files[i].path
                    def prettifiedFile = prettify(script.readFile(JSONPath))
                    def prettifiedJSONPath = getPrettyFilePathName(JSONPath)

                    script.writeFile file: prettifiedJSONPath, text: prettifiedFile
                }
            }
        }
    }

    /**
     * Prettifies provided JSON string.
     *
     * @param fileContent content of the JSON file to prettify.
     * @return prettified JSON file content.
     */
    @NonCPS
    private final prettify(fileContent) {
        def slurper = new JsonSlurper()
        def parsedJson = slurper.parseText(fileContent)

        if (parsedJson instanceof Map) {
            if (parsedJson.config && parsedJson.config instanceof String) {
                String config = parsedJson.config
                parsedJson.config = slurper.parseText(config)
            }

            if (parsedJson.policyConfig && parsedJson.policyConfig instanceof String) {
                String policyConfig = parsedJson.policyConfig
                parsedJson.policyConfig = slurper.parseText(policyConfig)
            }
        }

        JsonOutput.prettyPrint(JsonOutput.toJson(parsedJson))
    }

    /**
     * Changes file name after prettifying.
     *
     * @param text JSON file path.
     * @param regex file name part to replace.
     * @param replacement new value.
     * @return new file name.
     */
    private final replaceLast(text, regex, replacement) {
        text.replaceFirst("(?s)" + regex + "(?!.*?" + regex + ")", replacement)
    }

    /**
     * Prettifies file names.
     *
     * @param path file path.
     * @return prettifies file name.
     */
    private final getPrettyFilePathName(path) {
        String[] parts = path.split('/')
        String uglyFileName = parts[parts.size() - 1]

        int extIndex = uglyFileName.toLowerCase().lastIndexOf(".json")

        String prettyFileName = uglyFileName.substring(0, extIndex) + ".pretty.json"

        replaceLast(path, uglyFileName, prettyFileName)
    }

    /**
     * Fetches application name from provide git URL.
     *
     * @param url application git URL.
     * @return application name.
     */
    private final getGitProjectName(String url) {
        url.tokenize('/')?.last()?.replaceAll('.git', '')
    }

    /**
     * Updates application JSON files.
     *
     * @param args method named arguments.
     *      projectPath path to the application folder in workspace.
     *      exportFolder path to the export folder.
     */
    private final void overwriteFilesInGit(args) {
        String projectPath = args.projectPath
        String exportFolder = args.exportFolder
        String errorMessage = 'Failed overwrite exported files'

        script.catchErrorCustom(errorMessage) {
            script.dir("${projectPath}/${exportFolder}") {
                script.deleteDir()
            }

            script.shellCustom("mv -f ./${exportFolder} ./${projectPath}/", isUnixNode)
        }
    }

    /**
     * Creates application zip file for export
     *
     * @param projectName name of the application.
     * @param fabricApplicationName name of the application on Fabric.
     */
    private final void zipProjectForExport(projectName) {
        String errorMessage = "Failed to create zip file for project ${projectName}"

        def exportAppDir = projectName + "/export"
        def exportZipName = "${projectName}_PREV.zip"

        script.catchErrorCustom(errorMessage) {
            script.dir(exportAppDir) {
                script.shellCustom("zip -r $exportZipName Apps -x *.pretty.json", isUnixNode)
                script.shellCustom("cp $exportZipName ../../", isUnixNode)
            }

        }
    }


    /**
     * Creates application zip file for import.
     *
     * @param projectName name of the application.
     * @param fabricApplicationName name of the application on Fabric.
     */
    private final void zipProject(projectName, fabricApplicationName) {
        String errorMessage = "Failed to create zip file for project ${projectName}"

        script.catchErrorCustom(errorMessage) {
            script.shellCustom(
                    "zip -r \"${fabricApplicationName}.zip\" \"${projectName}/export/Apps\" -x *.pretty.json",
                    isUnixNode
            )
        }
    }

    /**
     * Checks if there is a difference between code in git and exported one.
     *
     * @return result of check if application code was changed.
     */
    private final boolean isGitCodeChanged() {
        boolean status
        String errorMessage = 'Failed to check if there were changes in local repository'

        script.catchErrorCustom(errorMessage) {
            script.shellCustom('git add .', isUnixNode)

            status = script.shellCustom(
                    'git diff --cached --exit-code > /dev/null',
                    isUnixNode,
                    [returnStatus: true]
            ) != 0
        }

        status
    }

    /**
     * Configs local git account, to be able to push changes from export to git.
     */
    private final void configureLocalGitAccount() {
        String successMessage = 'Local git account configured successfully'
        String errorMessage = 'Failed configure local git account'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.shellCustom([
                    "git config --local push.default simple",
                    "git config --local user.name \"$commitAuthor\""
            ].join(' && '), isUnixNode)

            if (commitAuthor == 'Jenkins') {
                commitMessage += " Used credentials of user \"${script.env.gitUsername}\""
            }

            if (authorEmail != '') {
                script.shellCustom("git config --local user.email \"$authorEmail\"", isUnixNode)
            }
        }
    }

    /**
     * Pushes changes to remote git repository.
     */
    private final void pushChanges() {
        String successMessage = 'All changes were successfully pushed to remote git repository'
        String errorMessage = 'Failed to push changes to remote git repository'

        script.catchErrorCustom(errorMessage, successMessage) {
            /* Escape special characters in git username and password, to be able to use them in push step */
            String gitUsername = URLEncoder.encode(script.env.GIT_USERNAME)
            String gitPassword = URLEncoder.encode(script.env.GIT_PASSWORD)
            String pushUrl = exportRepositoryUrl.replaceFirst("//", "//${gitUsername}:${gitPassword}@")
            /*
                Because of need to escape special characters in git username and password,
                we have URLEncoder.encode() call which modifies the values from credentials parameter,
                and Jenkins will not hide them, the shell debug mode for push command should be disabled
                to not expose the credentials to console output.
            */
            String hideShellOutput = '#!/bin/sh -e\n'
            String checkoutCommand = "git checkout \"$exportRepositoryBranch\""
            String commitCommand = "git commit -m \"$commitMessage\""
            String pushCommand = "git push \"$pushUrl\""

            script.shellCustom(
                    [hideShellOutput + checkoutCommand, commitCommand, pushCommand].join(' && '),
                    isUnixNode
            )
        }
    }

    /**
     * Wraps code with Git username and password environment variables via provided credentials ID.
     *
     * @param closure block of code.
     */
    private final void gitCredentialsWrapper(closure) {
        script.withCredentials([
                [$class          : 'UsernamePasswordMultiBinding',
                 credentialsId   : exportRepositoryCredentialsId,
                 passwordVariable: 'GIT_PASSWORD',
                 usernameVariable: 'GIT_USERNAME']
        ]) {
            closure()
        }
    }

    /**
     * Sets build description at the end of the build.
     *
     * @param itemsToExpose list of the items to expose.
     */
    private final void setBuildDescription(itemsToExpose) {
        String descriptionItems = itemsToExpose?.findResults {
            item -> item.value ? "<p>${item.key}: ${item.value}</p>" : null
        }?.join('\n')
        script.currentBuild.description = """\
            <div id="build-description">
                ${descriptionItems}
            </div>\
            """.stripIndent()
    }

    /**
     * Wraps code with try/catch/finally block to reduce code duplication.
     *
     * @param closure block of code.
     */
    private final void pipelineWrapper(closure) {
        try {
            closure()
        } catch (AppFactoryException e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, e.getErrorType(), false)
            script.currentBuild.result = 'FAILURE'
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage,'WARN')
            script.currentBuild.result = 'FAILURE'
        } finally {
            setBuildDescription(buildDescriptionItems)
            NotificationsHelper.sendEmail(script, 'fabric', emailData)
        }
    }

    /**
     * Exports Fabric application.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void exportApp() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                overwriteExisting = BuildHelper.getParamValueOrDefault(script, 'OVERWRITE_EXISTING_SCM_BRANCH', overwriteExisting)
                /* Data for e-mail notification */
                emailData = [
                        fabricAppName         : fabricAppName,
                        fabricAppVersion      : fabricAppVersion,
                        fabricEnvironmentName : fabricEnvironmentName,
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        authorEmail           : authorEmail,
                        commitAuthor          : commitAuthor,
                        commitMessage         : commitMessage,
                        commandName           : 'EXPORT'

                ]

                buildDescriptionItems = [
                        'App Name'      : fabricAppName,
                        'App Version'   : fabricAppVersion
                ]

                /* Folder name for storing exported application */
                String exportFolder = 'export'
                String projectName = getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID',
                            'CLOUD_CREDENTIALS_ID',
                            'FABRIC_APP_NAME',
                            'FABRIC_APP_VERSION',
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'),
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL'),
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID'), 
                            'AUTHOR_EMAIL'
                    ]

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode){
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        script.stage('Prepare build-node environment') {
                            prepareBuildEnvironment()
                        }

                        script.stage('Export project from Fabric') {
                            exportProjectFromFabric(cloudAccountId, cloudCredentialsID, projectName)
                        }

                        script.stage('Fetch project from remote git repository') {
                            checkoutProjectFromRepo(projectName)
                        }

                        script.stage("Validate the Local Fabric App") {
                            
                            def invalidVersionError = "Repository contains a different version of fabric app." +
                                    "Please select an appropriate branch OR \n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app." +
                                    "\n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            if (!overwriteExisting) {
                                validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                            }
                        }
                        
                        script.stage('Find App Changes') {
                            findAppChanges(overwriteExisting, projectName, exportFolder)
                        }
                        
                        script.stage('Push changes to remote git repository') {
                            if (appChanged) {
                                pushAppChangesToRepo(projectName, exportFolder)
                            } else {
                                script.echoCustom("No changes found, Skipping to push changes to SCM.")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Imports Fabric application.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void importApp() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                overwriteExisting = BuildHelper.getParamValueOrDefault(script, 'OVERWRITE_EXISTING_APP_VERSION', overwriteExisting)
                /* Data for e-mail notification */
                emailData = [
                        fabricAppName         : fabricAppName,
                        fabricAppVersion      : fabricAppVersion,
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        overwriteExisting     : overwriteExisting,
                        publishApp            : enablePublish,
                        commandName           : 'IMPORT',
                        fabricEnvironmentName : fabricEnvironmentName
                ]

                buildDescriptionItems = [
                        'App Name'      : fabricAppName,
                        'App Version'   : fabricAppVersion
                ]

                String projectName = getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID',
                            'CLOUD_CREDENTIALS_ID',
                            'FABRIC_APP_NAME',
                            'FABRIC_APP_VERSION',
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'),
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL'),
                            BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID')
                    ]

                    if (enablePublish) {
                        mandatoryParameters.add('FABRIC_ENVIRONMENT_NAME')
                    }

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode){
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        script.stage('Prepare build-node environment') {
                            prepareBuildEnvironment()
                        }
                        
                        script.stage('Fetch project from remote git repository') {
                            checkoutProjectFromRepo(projectName)
                        }

                        script.stage("Validate the Local Fabric App") {
                            def invalidVersionError = "This repository contains a different version of Fabric app." +
                                    "Please select the appropriate branch."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app."
                            validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                        }

                        script.stage("Create zip archive of the project") {
                            zipProject(projectName, fabricAppName)
                        }
                        
                        script.stage('Import project to Fabric') {
                            importProjectToFabric(cloudAccountId, cloudCredentialsID, overwriteExisting)
                        }
                        
                        script.stage('Trigger Publish job') {
                            if (enablePublish) {
                                appPublish(cloudAccountId, cloudCredentialsID )
                            } else {
                                script.echoCustom("Publish is not selected, Skipping the publish stage execution.")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Publishes Fabric application.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void publishApp() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                /* Data for e-mail notification */
                emailData = [
                        fabricAppName        : fabricAppName,
                        fabricAppVersion     : fabricAppVersion,
                        fabricEnvironmentName: fabricEnvironmentName,
                        commandName          : 'PUBLISH'
                ]

                buildDescriptionItems = [
                        'App Name'      : fabricAppName,
                        'App Version'   : fabricAppVersion
                ]

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID',
                            'CLOUD_CREDENTIALS_ID',
                            'FABRIC_APP_NAME',
                            'FABRIC_APP_VERSION',
                            'FABRIC_ENVIRONMENT_NAME'

                    ]

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode){
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        script.stage('Prepare environment for Publish task') {
                            prepareBuildEnvironment()
                        }

                        script.stage('Publish project on Fabric') {
                            
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-e': "\"$fabricEnvironmentName\"",
                                    '-v': "\"$fabricAppVersion\""
                            ]

                            FabricHelper.fabricCli(script, 'publish', cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
                        }

                        if (setDefaultVersion) {
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-e': "\"$fabricEnvironmentName\"",
                                    '-v': "\"$fabricAppVersion\""
                            ]

                            FabricHelper.fabricCli(script, 'set-appversion', cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Migrate method is called from the job and contains whole job's pipeline logic.
     * This will migrate Fabric application from one environment to other.
     */
    protected final void migrateApp() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                overwriteExistingScmBranch = script.params.OVERWRITE_EXISTING_SCM_BRANCH
                exportCloudAccountId = script.params.EXPORT_CLOUD_ACCOUNT_ID
                exportCloudCredentialsID = script.params.EXPORT_CLOUD_CREDENTIALS_ID
                importCloudAccountId = script.params.IMPORT_CLOUD_ACCOUNT_ID
                importCloudCredentialsID = script.params.IMPORT_CLOUD_CREDENTIALS_ID
                /* Data for e-mail notification to be specified*/
                emailData = [
                    fabricAppName         : fabricAppName,
                    fabricAppVersion      : fabricAppVersion,
                    exportRepositoryUrl   : exportRepositoryUrl,
                    exportRepositoryBranch: exportRepositoryBranch,
                    exportCloudAccountId  : exportCloudAccountId,
                    importCloudAccountId  : importCloudAccountId,
                    overwriteExisting     : overwriteExistingScmBranch,
                    publishApp            : enablePublish,
                    fabricEnvironmentName : fabricEnvironmentName,
                    commandName           : 'MIGRATE'
                ]

                buildDescriptionItems = [
                        'App Name'      : fabricAppName,
                        'App Version'   : fabricAppVersion
                ]
                
                /* Folder name for storing exported application */
                String exportFolder = 'export'
                String projectName = getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')
                        
                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'EXPORT_CLOUD_ACCOUNT_ID',
                            'EXPORT_CLOUD_CREDENTIALS_ID',
                            'FABRIC_APP_NAME',
                            'FABRIC_APP_VERSION',
                            'IMPORT_CLOUD_ACCOUNT_ID',
                            'IMPORT_CLOUD_CREDENTIALS_ID',
                            'PROJECT_SOURCE_CODE_BRANCH',
                            'PROJECT_SOURCE_CODE_REPOSITORY_URL',
                            'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                            'AUTHOR_EMAIL'
                    ]
                    
                    if (enablePublish) {
                        mandatoryParameters.add('FABRIC_ENVIRONMENT_NAME')
                    }

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode) {
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        
                        /* Steps for exporting fabric app configuration for migrate task */
                        script.stage('Prepare environment for Migrate task') {
                            prepareBuildEnvironment()
                        }
                        
                        script.stage('Export project from Fabric') {
                            exportProjectFromFabric(exportCloudAccountId, exportCloudCredentialsID, projectName)
                        }
                        
                        script.stage('Fetch project from remote git repository') {
                            checkoutProjectFromRepo(projectName)
                        }
                        
                        script.stage("Validate the Local Fabric App") {
                            def invalidVersionError = "Repository contains a different version of fabric app." +
                                    "Please select an appropriate branch OR \n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app." +
                                    "\n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            if (!overwriteExistingScmBranch) {
                                validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                            }
                        }
                        
                        script.stage('Find App Changes') {
                            findAppChanges(overwriteExistingScmBranch, projectName, exportFolder)
                        }
                        
                        script.stage('Push changes to remote git repository') {
                            if (appChanged) {
                                pushAppChangesToRepo(projectName, exportFolder)
                            } else {
                                script.echoCustom("No changes found, Skipping to push changes to SCM.")
                            }
                        }
                        
                        /* Steps for importing fabric app configuration for migrate task */
                        overwriteExistingAppVersion = script.params.OVERWRITE_EXISTING_APP_VERSION
                        
                        script.stage("Validate the Local Fabric App for Import") {
                            def invalidVersionError = "This repository contains a different version of Fabric app." +
                                    "Please select the appropriate branch."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app."
                            validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                        }
                        
                        script.stage("Create zip archive of the project") {
                            zipProject(projectName, fabricAppName)
                        }
                        
                        script.stage('Import project to Fabric') {
                            importProjectToFabric(importCloudAccountId, importCloudCredentialsID, overwriteExistingAppVersion)
                        }
                        
                        script.stage('Trigger Publish job') {
                            if (enablePublish) {
                                appPublish(importCloudAccountId, importCloudCredentialsID)
                            } else {
                                script.echoCustom("Publish is not selected, Skipping the publish stage execution.")
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * To setup fabric build environment , it cleans workspace, to be sure that we have not any items from previous build,
     * and build environment completely new and setup pre-requisite.
     */
    private void prepareBuildEnvironment() {
        script.cleanWs deleteDirs: true
        FabricHelper.fetchFabricCli(script, libraryProperties, fabricCliVersion)
    }
    
    /**
     * To checkout source code from SCM repo
     * @param projectName
     */
    private void checkoutProjectFromRepo(String projectName) {
        BuildHelper.checkoutProject script: script,
                checkoutType: "scm",
                projectRelativePath: projectName,
                scmBranch: exportRepositoryBranch,
                scmCredentialsId: exportRepositoryCredentialsId,
                scmUrl: exportRepositoryUrl
    }
    
    /**
     * To export project from fabric
     * @param cloudAccountId
     * @param cloudCredentialsID
     * @param projectName
     */
    private void exportProjectFromFabric(String cloudAccountId, String cloudCredentialsID, String projectName) {
        def fabricCliOptions = [
            '-t': "\"$cloudAccountId\"",
            '-a': "\"$fabricAppName\"",
            '-f': "\"${projectName}.zip\"",
            '-v': "\"$fabricAppVersion\""
    ]
    FabricHelper.fabricCli(script, 'export', cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
    }
    
    /**
     * To import project to fabric
     * @param cloudAccountId
     * @param cloudCredentialsID
     * @param overwriteExisting
     */
    private void importProjectToFabric(String cloudAccountId, String cloudCredentialsID, boolean overwriteExisting) {
        def commonOptions = [
            '-t': "\"$cloudAccountId\"",
            '-f': "\"${fabricAppName}.zip\"",
            '-v': "\"$fabricAppVersion\""
        ]
        def fabricCliOptions = (overwriteExisting) ? commonOptions + ['-a': "\"$fabricAppName\""] :
            commonOptions
        FabricHelper.fabricCli(script, 'import', cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
    }
    
    /**
     * To validate the local fabric App for import/export
     * @param projectName
     * @param invalidVersionError
     * @param invalidFabricAppError
     */
    private void validateLocalFabricApp(String projectName, String invalidVersionError, String invalidFabricAppError) {
        def metaFileLocation = projectName + '/export/' + "Apps/$fabricAppName/Meta.json"
        def fileExist = script.fileExists file: metaFileLocation
        if (fileExist) {
            if(!validateLocalFabricAppVersion(fabricAppVersion, metaFileLocation)){
                throw new AppFactoryException("$invalidVersionError", "ERROR")
            }
        } else {
            throw new AppFactoryException("$invalidFabricAppError", "ERROR")
        }
    }
    
    /**
     * To find the app changes
     * @param overwriteExisting
     * @param projectName
     * @param exportFolder
     */
    private void findAppChanges(boolean overwriteExisting, String projectName, String exportFolder) {
        if (overwriteExisting) {
            script.echoCustom("Force Push ${fabricAppName}(${fabricAppVersion}) is selected, exporting updates to ${exportRepositoryBranch} Branch.")

            /* If Override Existing is selected, delete all the files from previous version and check in the code. */
            script.dir(exportFolder) {
                script.deleteDir()
            }
            script.unzip zipFile: "${projectName}.zip", dir: exportFolder
            appChanged = true
        } else {
            zipProjectForExport(projectName)
            appChanged = fabricAppChanged(previousPath: "${projectName}_PREV.zip", currentPath: "${projectName}.zip")

            if (appChanged) {
                script.echoCustom("Found updates on Fabric for ${fabricAppName}(${fabricAppVersion}), exporting updates to ${exportRepositoryBranch} Branch.")
                script.unzip zipFile: "${projectName}.zip", dir: exportFolder
            } else {
                script.echoCustom("${fabricAppName}(${fabricAppVersion}) has no updates in Fabric.")
            }
        }
    }
    
    /**
     * To push app changes to repo
     * @param projectName
     * @param exportFolder
     */
    private void pushAppChangesToRepo(String projectName, String exportFolder) {
        def JSonFilesList = findJsonFiles folderToSearchIn: exportFolder
        if (JSonFilesList) {
            prettifyJsonFiles rootFolder: exportFolder, files: JSonFilesList
            overwriteFilesInGit exportFolder: exportFolder, projectPath: projectName
        } else {
            throw new AppFactoryException('JSON files were not found', 'ERROR')
        }
        script.dir(projectName) {
            if (isGitCodeChanged()) {
                gitCredentialsWrapper {
                    configureLocalGitAccount()
                    pushChanges()
                }
            } else {
                script.echoCustom("${fabricAppName}(${fabricAppVersion}) has no updates in Fabric.")
            }
        }
    }
    
     /**
     * To trigger publish job
     * @param cloudAccountId
     * @param cloudCredentialsID
     */
    private void appPublish(String cloudAccountId, String cloudCredentialsID) {
         script.build job: "Publish", parameters: [
             script.string(name: 'FABRIC_APP_VERSION', value: fabricAppVersion),
             script.booleanParam(name: "SET_DEFAULT_VERSION", value: setDefaultVersion),
             script.string(name: 'CLOUD_CREDENTIALS_ID', value: cloudCredentialsID),
             script.string(name: 'CLOUD_ACCOUNT_ID', value: cloudAccountId),
             script.string(name: 'FABRIC_APP_NAME', value: fabricAppName),
             script.string(name: 'FABRIC_ENVIRONMENT_NAME', value: fabricEnvironmentName),
             script.string(name: 'RECIPIENTS_LIST', value: recipientsList)
        ]
     }
}
