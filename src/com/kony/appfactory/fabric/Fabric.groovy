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
    private emailData = []
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
    private String cloudAccountId = script.params.CLOUD_ACCOUNT_ID
    private fabricCredentialsProbableParamNames = ['CLOUD_CREDENTIALS_ID', 'FABRIC_CREDENTIALS_ID', 'IMPORT_CLOUD_CREDENTIALS_ID', 'IMPORT_FABRIC_CREDENTIALS_ID']
    private final fabricCredentialsParamName = BuildHelper.getParamNameOrDefaultFromProbableParamList(script, fabricCredentialsProbableParamNames, 'FABRIC_CREDENTIALS')
    private final String fabricCredentialsID = script.params[fabricCredentialsParamName]
    private String fabricAppName = script.params.FABRIC_APP_NAME
    private final String recipientsList
    private String fabricAppVersion
    private final serviceConfigPath = (script.params.SERVICE_CONFIG_PATH)?.trim()
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
    /* Migrate specific build parameters for backward compatibility */
    private importfabricAppConfig = script.params.IMPORT_FABRIC_APP_CONFIG?:null
    private exportfabricAppConfig = script.params.EXPORT_FABRIC_APP_CONFIG?:null

    private String importCloudAccountId
    private String importFabricCredentialsID
    private String exportCloudAccountId
    private String exportFabricCredentialsID
    /* Flag for triggering Publish job */
    private final boolean enablePublish = script.params.ENABLE_PUBLISH
    /* Publish build parameters */
    private String fabricEnvironmentName = script.params.FABRIC_ENVIRONMENT_NAME
    private final boolean setDefaultVersion = script.params.SET_DEFAULT_VERSION
    /* OnPrem Fabric parameters */
    private String consoleUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_CONSOLE_URL', script.kony.FABRIC_CONSOLE_URL)
    private String identityUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_IDENTITY_URL', script.kony.FABRIC_CONSOLE_URL)
    private fabricAppConfig = script.params.FABRIC_APP_CONFIG?:null
    def appConfigParameter = BuildHelper.getCurrentParamName(script, 'FABRIC_APP_CONFIG', 'CLOUD_ACCOUNT_ID')

    /*scm meta info like commitID ,commitLogs */
    protected scmMeta = [:]

    /* Build Stats */
    def publishJob
    private fabricStats = [:]
    private fabricRunListStats = [:]
    private fabricTask = ""
    
    /* As part of V9SP1GA, We removed the redundant field SCM url like: "PROJECT_SOURCE_CODE_REPOSITORY_URL"
     * parameter from fabric job level to 'Create AppFactory Project" seeder job. So the parameter existence 
     * for backward compatibility for older project.*/
    private boolean isScmUrlParamExistInCurrentProject
    private final appUnzipTempDir = "appUnzipTempDir"

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
        /* The PROJECT_NAME environment variable is not available for fabric jobs for AppFactory projects below 9.0 Version .So retrieving the project name from the JOB_NAME environment variable */
        this.script.env.PROJECT_NAME = (this.script.env.PROJECT_NAME) ?: (this.script.env.JOB_NAME.substring(0, this.script.env.JOB_NAME.indexOf('/')))
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        overwriteExisting = BuildHelper.getParamValueOrDefault(this.script, 'OVERWRITE_EXISTING', true)
        /* Set the fabric project settings values to the corresponding fabric environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Fabric')

        recipientsList = script.env.RECIPIENTS_LIST
         /* To maintain Fabric triggers jobs backward compatibility */
        def fabricScmUrlProbableParams = ['PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL']
        exportRepositoryUrl = BuildHelper.getParamValueOrDefaultFromProbableParamList(
                this.script, fabricScmUrlProbableParams, this.script.env.PROJECT_SOURCE_CODE_URL_FOR_FABRIC)

        def sourceCodeRepoBranchParamName = BuildHelper.getCurrentParamName(this.script, 'SCM_BRANCH', BuildHelper.getCurrentParamName(
                this.script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'))
        exportRepositoryBranch = this.script.params[sourceCodeRepoBranchParamName]

        def sourceCodeRepoCredentialParamName = BuildHelper.getCurrentParamName(this.script, BuildHelper.getCurrentParamName(
                this.script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID'), 'SCM_CREDENTIALS')
        exportRepositoryCredentialsId = this.script.env[sourceCodeRepoCredentialParamName]
        isScmUrlParamExistInCurrentProject = BuildHelper.doesAnyParamExistFromProbableParamList(this.script, fabricScmUrlProbableParams)
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
        boolean ignoreJarsForExport = args.ignoreJarsForExport
        String errorMessage = 'Failed to check if exported project has been changed!'

        script.catchErrorCustom(errorMessage) {
            if (script.fileExists(previousArchivePath)) {
                script.unzip zipFile: previousArchivePath, dir: 'previous'
                script.unzip zipFile: currentArchivePath, dir: 'current'

                /* Check if previous and current code are equal */
                def diffCmd = "set +ex;diff -r previous current > /dev/null"
                status = script.shellCustom(
                        diffCmd,
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

                script.shellCustom("rm -f ${previousArchivePath}", true)
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
     * Updates application JSON files.
     *
     * @param args method named arguments.
     *      projectPath path to the application folder in workspace.
     *      exportFolder path to the export folder.
     */
    private final void overwriteFilesInGit(args) {
        String projectPath = args.projectPath
        String localAppsDirPath = args.exportFolder
        String appUnzipDir = args.appUnzipTempDir
        String errorMessage = 'Failed to overwrite exported files!'

        script.catchErrorCustom(errorMessage) {
            script.dir("${projectPath}/${localAppsDirPath}") {
                script.shellCustom("rm -rf Apps", isUnixNode)
            }
            
            script.shellCustom("set +x;mkdir -p ${script.env.WORKSPACE}/${projectPath}/${localAppsDirPath}", isUnixNode)
            script.shellCustom("set +x;mv -f ./${appUnzipDir}/* ./${projectPath}/${localAppsDirPath}", isUnixNode)
        }
    }

    /**
     * Creates application zip file for export
     *
     * @param projectName name of the application.
     * @param exportFolder is the folder where app is exported.
     * @param fabricApplicationName name of the application on Fabric.
     */
    private final void zipProjectForExport(projectName, fabricAppDir) {
        String errorMessage = "Failed to create zip file for project ${projectName}"
        def localCheckedOutAppDir = (fabricAppDir.isEmpty()) ? projectName : [projectName, fabricAppDir].join("/")
        def localExportZipName = "${script.env.WORKSPACE}/${projectName}_PREV.zip"

        script.catchErrorCustom(errorMessage) {
            script.dir(localCheckedOutAppDir) {
                script.shellCustom("zip -r $localExportZipName Apps -x *.pretty.json", isUnixNode)
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
    private final void pushChanges(exportFolder, ignoreJarsForExport) {
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
            String commitCommand = "git commit -m \"$commitMessage\" || error=true"
            String pushCommand = "git push \"$pushUrl\" || error=true"

            if(ignoreJarsForExport) {
                String jarsDirPath = (exportFolder?.isEmpty()) ? "Apps/_JARs/*" : "${exportFolder}/Apps/_JARs/*"
                checkoutCommand = [checkoutCommand, "git add \"$jarsDirPath\"", "git reset HEAD \"$jarsDirPath\""].join(' && ')
            }

            script.shellCustom(
                    [hideShellOutput + checkoutCommand].join(' && '),
                    isUnixNode
            )

            script.shellCustom(
                    commitCommand,
                    isUnixNode
            )

            script.shellCustom(
                    pushCommand,
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
        String descriptionItems = itemsToExpose ? itemsToExpose.findResults {
            item -> if(item.value) "<p>${item.key}: ${item.value}</p>"
        }?.join('\n') : ""

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
            switch (appConfigParameter){
                case 'FABRIC_APP_CONFIG':
                    BuildHelper.fabricConfigEnvWrapper(script, fabricAppConfig) {
                        script.env.FABRIC_ENV_NAME = (script.env.FABRIC_ENV_NAME) ?:
                                script.echoCustom("Fabric environment value can't be null", 'ERROR')
                        fabricEnvironmentName = script.env.FABRIC_ENV_NAME
                        cloudAccountId = script.env.FABRIC_ACCOUNT_ID
                        fabricAppName = script.env.FABRIC_APP_NAME
                        consoleUrl = script.env.CONSOLE_URL
                        identityUrl = script.env.IDENTITY_URL
                        fabricAppVersion = (script.params.FABRIC_APP_VERSION && (script.params.FABRIC_APP_VERSION)?.trim() != '') ? script.params.FABRIC_APP_VERSION : script.env.FABRIC_APP_VERSION
                    }
                    break
                case 'CLOUD_ACCOUNT_ID':
                    script.env['CONSOLE_URL'] = consoleUrl
                    script.env['IDENTITY_URL'] = identityUrl
                    fabricAppVersion = script.params.FABRIC_APP_VERSION
                    break
            }
            
            emailData = [
                fabricEnvironmentName : fabricEnvironmentName,
                fabricAppName         : fabricAppName,
                fabricAppVersion      : fabricAppVersion
            ] + emailData

            buildDescriptionItems = [
                    'App Version'   : fabricAppVersion
            ]
            buildDescriptionItems = [ 'App Name': fabricAppName ] + buildDescriptionItems
                
            closure()
        } catch (AppFactoryException e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, e.getErrorType(), false)
            fabricStats.put('faberrmsg', exceptionMessage)
            fabricStats.put('faberrstack', e.getStackTrace().toString())
            script.currentBuild.result = 'FAILURE'
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage,'WARN')
            fabricStats.put('faberrmsg', exceptionMessage)
            fabricStats.put('faberrstack', e.getStackTrace().toString())
            script.currentBuild.result = 'FAILURE'
        } finally {
            fabricStats.put("projname", script.env.PROJECT_NAME)
            fabricStats.put('fabaname', fabricAppName)
            fabricStats.put('fabaver', fabricAppVersion)
            fabricStats.put('fabtask', fabricTask)
            fabricStats.put('fabsrcurl', exportRepositoryUrl)
            fabricStats.put('fabsrcbrch', exportRepositoryBranch)
            fabricStats.put('buildemlrecipients', script.env.RECIPIENTS_LIST)
            fabricStats.put('buildtype', "Fabric")

            if (publishJob?.number) {
                fabricRunListStats.put(publishJob.fullProjectName + "/" + publishJob.number, publishJob.fullProjectName)
                fabricStats.put("pipeline-run-jobs", fabricRunListStats)
            }
            // Publish fabric metrics keys to build Stats Action class.
            script.statspublish fabricStats.inspect()
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
                def overwriteExistingScmBranchOrValidateVersionParamName = BuildHelper.getCurrentParamName(script, 'VALIDATE_VERSION', 'OVERWRITE_EXISTING_SCM_BRANCH')
                overwriteExisting = BuildHelper.getParamValueOrDefault(script, overwriteExistingScmBranchOrValidateVersionParamName, overwriteExisting)

                /* After renaming the overwriteExistingScmBranch to ValidateVersion which are inverse to each other, So added a backward compatability check*/
                def isNewExportJob = (overwriteExistingScmBranchOrValidateVersionParamName.equals("VALIDATE_VERSION"))
                if(isNewExportJob)
                    overwriteExisting = !overwriteExisting

                /* Data for e-mail notification */
                emailData = [
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        exportCloudAccountId  : exportCloudAccountId,
                        authorEmail           : authorEmail,
                        commitAuthor          : commitAuthor,
                        commitMessage         : commitMessage,
                        commandName           : 'EXPORT'
                ]

                /* Folder name for storing exported application. Set default export folder for projects where FABRIC_DIR param not exist in Export Job.
                 * If Fabric_DIR param exist and its value is empty, then set root directory as FABRIC_DIR.
                 */
                String fabricAppDir = BuildHelper.getParamValueOrDefault(script, 'FABRIC_DIR', "export")
                boolean ignoreJarsForExport = BuildHelper.getParamValueOrDefault(script, 'IGNORE_JARS', false)
                
                String projectName = script.env.PROJECT_NAME
                fabricTask = "export"

                script.stage('Check provided parameters') {

                    def sourceCodeRepoCredentialParamName = BuildHelper.getCurrentParamName(script, BuildHelper.getCurrentParamName(
                            script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID'), 'SCM_CREDENTIALS')
                    def sourceCodeRepoBranchParamName = BuildHelper.getCurrentParamName(script, 'SCM_BRANCH', BuildHelper.getCurrentParamName(
                            script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'))

                    def mandatoryParameters = [
                            fabricCredentialsParamName,
                            sourceCodeRepoBranchParamName,
                            sourceCodeRepoCredentialParamName,
                            'AUTHOR_EMAIL'
                    ]
                    if(isScmUrlParamExistInCurrentProject) {
                        mandatoryParameters.add(BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL'))
                    }
                    mandatoryParameters = checkCompatibility(mandatoryParameters, 'export')
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                /* Allocate a slave for the run */
                script.node(nodeLabel) {
                    pipelineWrapper {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode){
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        def separator = FabricHelper.getPathSeparatorBasedOnOs(isUnixNode)
                        
                        def workspace = script.env.WORKSPACE
                        def checkoutRelativeTargetFolder = projectName
                        def projectFullPath = [workspace, checkoutRelativeTargetFolder].join(separator)
                        def fabricAppsDirRelativePath = (fabricAppDir.isEmpty()) ? 'Apps' : [fabricAppDir, 'Apps'].join(separator)
                        def fabricAppBasePath = (fabricAppDir.isEmpty()) ? projectFullPath: [projectFullPath, fabricAppDir].join(separator)
                        
                        script.stage('Prepare build-node environment') {
                            prepareBuildEnvironment()
                        }
                        script.stage('Export project from Fabric') {
                            exportProjectFromFabric(cloudAccountId, fabricCredentialsID, projectName)
                        }
                        
                        script.stage('Fetch project from remote git repository') {
                            checkoutProjectFromRepo(checkoutRelativeTargetFolder)
                        }
                        
                        /* If 'SERVICE_CONFIG_PATH' param exist and value is not empty, Refer the path provided to export service config if its valid */
                        if(script.params.containsKey("SERVICE_CONFIG_PATH")) {
                            /* If 'SERVICE_CONFIG_PATH' param is not empty, Refer the path given to export the app service config and commit */
                            if(!serviceConfigPath.isEmpty()) {
                                def appServiceConfigFilePath = [workspace, projectName, serviceConfigPath].join(separator)
                                def appServiceConfigDirPath = appServiceConfigFilePath.substring(0, appServiceConfigFilePath.lastIndexOf("/"))
                                def serviceConfigFileName = appServiceConfigFilePath.substring(appServiceConfigFilePath.lastIndexOf("/") + 1)
                                
                                /* Check the service config file type and name */
                                if(!serviceConfigFileName.endsWith(".json") || serviceConfigFileName.contains(" ")) {
                                    throw new AppFactoryException("Invalid file name or type given for service config file! Service config export is supported with '.json' file and should not contains spaces in file name.", "ERROR")
                                }
                                
                                /* Health check call to get the Fabric Server version for environment*/
                                FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, fabricCredentialsID, cloudAccountId, fabricEnvironmentName, isUnixNode)
                                
                                /* Check the Fabric Server Version supported for Export of App Service config */
                                def featuresSupportToCheckInFabric = [:]
                                featuresSupportToCheckInFabric.put('app_service_config', ['featureDisplayName': 'App Service Config'])
                                if(FabricHelper.checkFabricFeatureSupportExist(script, libraryProperties, featuresSupportToCheckInFabric)) {
                                    
                                    /* Create the Fabric App's service configuration dir if not available at given repo's root path */
                                    script.shellCustom("set +x;mkdir -p ${appServiceConfigDirPath}", isUnixNode)
                                    script.shellCustom("set +x;rm -f ${appServiceConfigFilePath}", isUnixNode)
                                    
                                    script.echoCustom("Fabric app service config will be exported at Fabric App's repo path '${serviceConfigPath}'", "INFO")
                                    FabricHelper.exportFabricAppServiceConfig(
                                        script,
                                        fabricCliFileName,
                                        fabricCredentialsID,
                                        cloudAccountId,
                                        appServiceConfigFilePath,
                                        fabricAppName,
                                        fabricAppVersion,
                                        fabricEnvironmentName,
                                        isUnixNode)
                                }
                            } else {
                                script.echoCustom("Skipping to export Fabric app service config, since you have not provided value for 'SERVICE_CONFIG_PATH' param!", "INFO")
                            }
                        }

                        script.stage("Validate the Local Fabric App") {

                            def invalidVersionError = "Repository contains a different version of the fabric app." +
                                    " Selected version '${fabricAppVersion}' and version in ${fabricAppsDirRelativePath}/${fabricAppName}/Meta.json file is mis matching." +
                                    " Please select an appropriate branch to export the app."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app." +
                                    " ${fabricAppsDirRelativePath}/${fabricAppName} is not existing on the branch you have selected."
                            if (!isNewExportJob) {
                                invalidVersionError = invalidVersionError + " OR \n Select OVERWRITE_EXISTING parameter to force push the exported " +
                                        "app to SCM irrespective of what branch is containing.."
                                invalidFabricAppError = invalidFabricAppError + "\n Select OVERWRITE_EXISTING parameter to force push the exported app to SCM."
                            }
                            if (!overwriteExisting) {
                                validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError, fabricAppDir)
                            }
                        }
                        
                        script.stage('Find App Changes') {
                            findAppChanges(overwriteExisting, projectName, fabricAppDir, ignoreJarsForExport)
                            
                            /* If user selects Ignore Jars flag as true but if '_JARs' dir doesn't exist in fabric app set the flag as false */
                            if(ignoreJarsForExport) {
                                def appJarsDirAbsolutePath = [workspace, appUnzipTempDir, 'Apps', '_JARs'].join(separator)
                                boolean isPathExistForAppJars = FabricHelper.isDirExist(script, appJarsDirAbsolutePath, isUnixNode)
                                if(!isPathExistForAppJars) {
                                    script.echoCustom("${fabricAppName}(${fabricAppVersion}) does not contain '_JARs' dir, so by default export jars will be skipped.")
                                    ignoreJarsForExport = false
                                }
                            }
                        }
                        
                        script.stage('Push changes to remote git repository') {
                            if (appChanged) {
                                pushAppChangesToRepo(projectName, fabricAppDir, ignoreJarsForExport)
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
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        overwriteExisting     : overwriteExisting,
                        publishApp            : enablePublish,
                        commandName           : 'IMPORT',
                ]
                
                String projectName = FabricHelper.getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')

                fabricTask = "import"

                script.stage('Check provided parameters') {
                    def sourceCodeRepoCredentialParamName = BuildHelper.getCurrentParamName(script, BuildHelper.getCurrentParamName(
                            script, 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID'), 'SCM_CREDENTIALS')
                    def sourceCodeRepoBranchParamName = BuildHelper.getCurrentParamName(script, 'SCM_BRANCH', BuildHelper.getCurrentParamName(
                            script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'))

                    def mandatoryParameters = [
                            fabricCredentialsParamName,
                            sourceCodeRepoBranchParamName,
                            sourceCodeRepoCredentialParamName
                    ]
                    if(isScmUrlParamExistInCurrentProject) {
                        mandatoryParameters.add(BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL'))
                    }
                    mandatoryParameters = checkCompatibility(mandatoryParameters, 'import')
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                /* Allocate a slave for the run */
                script.node(nodeLabel) {
                    pipelineWrapper {
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
                                    " Please select the appropriate branch."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app."
                            validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                        }

                        script.stage("Create zip archive of the project") {
                            zipProject(projectName, fabricAppName)
                        }
                        
                        script.stage('Import project to Fabric') {
                            FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, fabricCredentialsID, cloudAccountId, fabricEnvironmentName, isUnixNode)
                            importProjectToFabric(cloudAccountId, fabricCredentialsID, overwriteExisting)
                        }
                        
                        script.stage('Trigger Publish job') {
                            if (enablePublish) {
                                appPublish(cloudAccountId, fabricCredentialsID, appConfigParameter, consoleUrl, identityUrl)
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
                        commandName          : 'PUBLISH'
                ]

                fabricTask = "publish"

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            fabricCredentialsParamName
                    ]
                    mandatoryParameters = checkCompatibility(mandatoryParameters, 'publish')
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                /* Allocate a slave for the run */
                script.node(nodeLabel) {
                    pipelineWrapper {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode){
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        script.stage('Prepare environment for Publish task') {
                            prepareBuildEnvironment()
                        }
                        script.stage('Publish project on Fabric') {
                            FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, fabricCredentialsID, cloudAccountId, fabricEnvironmentName, isUnixNode)
                            
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-e': "\"$fabricEnvironmentName\"",
                                    '-v': "\"$fabricAppVersion\""
                            ]

                            FabricHelper.fabricCli(script, 'publish', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
                        }

                        if (setDefaultVersion) {
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-e': "\"$fabricEnvironmentName\"",
                                    '-v': "\"$fabricAppVersion\""
                            ]

                            FabricHelper.fabricCli(script, 'set-appversion', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
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
                def exportFabricCredsParamName = BuildHelper.getCurrentParamName(script, 'EXPORT_CLOUD_CREDENTIALS_ID', 'EXPORT_FABRIC_CREDENTIALS_ID')
                exportFabricCredentialsID = script.params[exportFabricCredsParamName]
                importCloudAccountId = script.params.IMPORT_CLOUD_ACCOUNT_ID
                def importFabricCredsParamName = BuildHelper.getCurrentParamName(script, 'IMPORT_CLOUD_CREDENTIALS_ID', 'IMPORT_FABRIC_CREDENTIALS_ID')
                importFabricCredentialsID = script.params[importFabricCredsParamName]
                def exportConsoleUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_EXPORT_CONSOLE_URL', script.kony.FABRIC_CONSOLE_URL)
                def exportIdentityUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_EXPORT_IDENTITY_URL', script.kony.FABRIC_CONSOLE_URL)
                def importConsoleUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_IMPORT_CONSOLE_URL', script.kony.FABRIC_CONSOLE_URL)
                def importIdentityUrl = BuildHelper.getParamValueOrDefault(script, 'FABRIC_IMPORT_IDENTITY_URL', script.kony.FABRIC_CONSOLE_URL)
                appConfigParameter = BuildHelper.getCurrentParamName(script, 'EXPORT_FABRIC_APP_CONFIG', 'EXPORT_CLOUD_ACCOUNT_ID')

                /* Data for e-mail notification to be specified*/
                emailData = [
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        exportCloudAccountId  : exportCloudAccountId,
                        importCloudAccountId  : importCloudAccountId,
                        overwriteExisting     : overwriteExistingScmBranch,
                        publishApp            : enablePublish,
                        commandName           : 'MIGRATE'
                ]

                /* Folder name for storing exported application */
                String exportFolder = 'export'
                String projectName = FabricHelper.getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!", 'WARN')

                fabricTask = "migrate"

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            exportFabricCredsParamName,
                            importFabricCredsParamName,
                            BuildHelper.getCurrentParamName(script, 'SCM_BRANCH', 'PROJECT_SOURCE_CODE_BRANCH'),
                            BuildHelper.getCurrentParamName(script, 'SCM_CREDENTIALS', 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID'),
                            'AUTHOR_EMAIL'
                    ]
                    if(isScmUrlParamExistInCurrentProject) {
                        mandatoryParameters.add(BuildHelper.getCurrentParamName(script, 'PROJECT_SOURCE_CODE_REPOSITORY_URL', 'PROJECT_EXPORT_REPOSITORY_URL'))
                    }
                    switch (appConfigParameter) {
                        case 'EXPORT_FABRIC_APP_CONFIG':
                            mandatoryParameters << 'EXPORT_FABRIC_APP_CONFIG' << 'IMPORT_FABRIC_APP_CONFIG'
                            BuildHelper.fabricConfigEnvWrapper(script, exportfabricAppConfig) {
                                exportCloudAccountId = script.env.FABRIC_ACCOUNT_ID
                                fabricAppName = script.env.FABRIC_APP_NAME
                                fabricAppVersion = BuildHelper.getParamValueOrDefault(script, 'FABRIC_APP_VERSION', script.env.FABRIC_APP_VERSION)
                            }
                            BuildHelper.fabricConfigEnvWrapper(script, importfabricAppConfig) {
                                fabricEnvironmentName = script.env.FABRIC_ENV_NAME
                                importCloudAccountId = script.env.FABRIC_ACCOUNT_ID
                                fabricAppVersion = BuildHelper.getParamValueOrDefault(script, 'FABRIC_APP_VERSION', script.env.FABRIC_APP_VERSION)
                            }
                            break
                        case 'EXPORT_CLOUD_ACCOUNT_ID':
                            if (enablePublish) {
                                mandatoryParameters.add('FABRIC_ENVIRONMENT_NAME')
                            }
                            mandatoryParameters << ['FABRIC_APP_NAME']
                            if ((!script.params.FABRIC_EXPORT_CONSOLE_URL && !script.params.EXPORT_CLOUD_ACCOUNT_ID) || (!script.params.FABRIC_IMPORT_CONSOLE_URL && !script.params.IMPORT_CLOUD_ACCOUNT_ID)) {
                                throw new AppFactoryException('One of the parameters among (EXPORT_CLOUD_ACCOUNT_ID, FABRIC_EXPORT_CONSOLE_URL) and (IMPORT_CLOUD_ACCOUNT_ID, FABRIC_IMPORT_CONSOLE_URL) is mandatory.')
                            }
                            if (script.params.containsKey('FABRIC_EXPORT_CONSOLE_URL') && script.params.FABRIC_EXPORT_CONSOLE_URL) mandatoryParameters << ['FABRIC_EXPORT_IDENTITY_URL']
                            if (script.params.containsKey('FABRIC_IMPORT_CONSOLE_URL') && script.params.FABRIC_IMPORT_CONSOLE_URL) mandatoryParameters << ['FABRIC_IMPORT_IDENTITY_URL']
                            fabricAppVersion = script.params.FABRIC_APP_VERSION
                            break
                    }

                    emailData = [
                            importCloudAccountId : importCloudAccountId,
                            exportCloudAccountId : exportCloudAccountId
                    ] + emailData

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                /* Allocate a slave for the run */
                script.node(nodeLabel) {
                    pipelineWrapper {
                        isUnixNode = script.isUnix()
                        if(!isUnixNode) {
                            throw new AppFactoryException("Slave's OS type for this run is not supported!", 'ERROR')
                        }
                        /* Steps for exporting fabric app configuration for migrate task */
                        script.stage('Prepare environment for Migrate task') {
                            prepareBuildEnvironment()
                        }
                        script.stage('Export project from Fabric') {
                            script.echoCustom("Exporting the ${fabricAppName} app from Fabric..", 'INFO')
                            exportProjectFromFabric(exportCloudAccountId, exportFabricCredentialsID, projectName, exportConsoleUrl, exportIdentityUrl)
                            script.echoCustom("Exported the ${fabricAppName} app from Fabric successfully.", 'INFO')
                        }

                        script.stage('Fetch project from remote git repository') {
                            script.echoCustom("Source code checkout from GIT repository for validating the changes..", 'INFO')
                            checkoutProjectFromRepo(projectName)
                        }

                        script.stage("Validate the Local Fabric App") {
                            def invalidVersionError = "Repository contains a different version of fabric app." +
                                    " Please select an appropriate branch OR \n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app." +
                                    "\n Select OVERWRITE_EXISTING parameter to use force push to SCM."
                            if (!overwriteExistingScmBranch) {
                                validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                            }
                        }

                        script.stage('Find App Changes') {
                            script.echoCustom("Finding the exported app changes with GIT repository code content..", 'INFO')
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
                                    " Please select the appropriate branch."
                            def invalidFabricAppError = "Repository doesn't contain valid fabric app."
                            validateLocalFabricApp(projectName, invalidVersionError, invalidFabricAppError)
                        }

                        script.stage("Create zip archive of the project") {
                            zipProject(projectName, fabricAppName)
                        }
                        
                        script.stage('Import project to Fabric') {
                            FabricHelper.fetchFabricConsoleVersion(script, fabricCliFileName, importFabricCredentialsID, importCloudAccountId, fabricEnvironmentName, isUnixNode)
                            importProjectToFabric(importCloudAccountId, importFabricCredentialsID, overwriteExistingAppVersion, importConsoleUrl,importIdentityUrl)
                        }

                        script.stage('Trigger Publish job') {
                            if (enablePublish) {
                                fabricAppConfig = importfabricAppConfig
                                appPublish(importCloudAccountId, importFabricCredentialsID, appConfigParameter, importConsoleUrl, importIdentityUrl)
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
     * @param checkoutRelativeTargetFolder
     */
    private void checkoutProjectFromRepo(String checkoutRelativeTargetFolder) {
        scmMeta = BuildHelper.checkoutProject script: script,
                checkoutType: "scm",
                projectRelativePath: checkoutRelativeTargetFolder,
                scmBranch: exportRepositoryBranch,
                scmCredentialsId: exportRepositoryCredentialsId,
                scmUrl: exportRepositoryUrl
        fabricStats.put('fabsrccmtid', scmMeta['commitID'])
    }
    
    /**
     * To export project from fabric
     * @param cloudAccountId
     * @param fabricCredentialsID
     * @param projectName
     */
    private void exportProjectFromFabric(String cloudAccountId, String fabricCredentialsID, String projectName, String consoleUrl = null, String identityUrl = null) {
        def fabricCliOptions = [
            '-t': "\"$cloudAccountId\"",
            '-a': "\"$fabricAppName\"",
            '-f': "\"${projectName}.zip\"",
            '-v': "\"$fabricAppVersion\""
    ]
        if (consoleUrl) {
            script.env.CONSOLE_URL = consoleUrl
            script.env.IDENTITY_URL = identityUrl
        }
    FabricHelper.fabricCli(script, 'export', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
    }
    
    /**
     * To import project to fabric
     * @param cloudAccountId
     * @param fabricCredentialsID
     * @param overwriteExisting
     */
    private void importProjectToFabric(String cloudAccountId, String fabricCredentialsID, boolean overwriteExisting, String consoleUrl = null, String identityUrl = null) {
        def commonOptions = [
            '-t': "\"$cloudAccountId\"",
            '-f': "\"${fabricAppName}.zip\"",
            '-v': "\"$fabricAppVersion\""
        ]
        if (consoleUrl) {
            script.env.CONSOLE_URL = consoleUrl
            script.env.IDENTITY_URL = identityUrl
        }
        def fabricCliOptions = (overwriteExisting) ? commonOptions + ['-a': "\"$fabricAppName\""] :
            commonOptions
        FabricHelper.fabricCli(script, 'import', fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCliOptions)
    }
    
    /**
     * To validate the local fabric App for import/export
     * @param projectName
     * @param invalidVersionError
     * @param invalidFabricAppError
     */
    private void validateLocalFabricApp(String projectName, String invalidVersionError, String invalidFabricAppError, String exportFolder="/export/") {
        def metaFileLocation = (exportFolder.isEmpty()) ? [projectName, "Apps/$fabricAppName/Meta.json"].join(separator) : [projectName, exportFolder, "Apps/$fabricAppName/Meta.json"].join("/")
        def fileExist = script.fileExists file: metaFileLocation
        if (fileExist) {
            if (!validateLocalFabricAppVersion(fabricAppVersion, metaFileLocation)) {
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
    private void findAppChanges(boolean overwriteExisting, String projectName, String fabricAppDir, boolean ignoreJarsForExport = false) {
        if (overwriteExisting) {
            def fabricAppsDirRelativePath = (fabricAppDir.isEmpty()) ? 'Apps' : [fabricAppDir, 'Apps'].join("/")
            script.echoCustom("Force Push ${fabricAppName}(${fabricAppVersion}) is selected, exporting updates to '${fabricAppsDirRelativePath}' path in '${exportRepositoryBranch}' branch.")
            
            /* If Override Existing is selected, setting flag appChanged as true, in this case will delete all the files from previous version and check in the code. */
            script.dir(appUnzipTempDir) {
                script.deleteDir()
            }
            script.unzip zipFile: "${projectName}.zip", dir: appUnzipTempDir
            appChanged = true
        } else {
            zipProjectForExport(projectName, fabricAppDir)
            appChanged = fabricAppChanged(previousPath: "${script.env.WORKSPACE}/${projectName}_PREV.zip", currentPath: "${script.env.WORKSPACE}/${projectName}.zip", ignoreJarsForExport: ignoreJarsForExport)
            if (appChanged) {
                script.echoCustom("Found updates on Fabric for ${fabricAppName}(${fabricAppVersion}), exporting updates to ${exportRepositoryBranch} branch.")
                script.unzip zipFile: "${projectName}.zip", dir: appUnzipTempDir
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
    private void pushAppChangesToRepo(String projectName, String exportFolder, boolean ignoreJarsForExport=false) {
        def JSonFilesList = findJsonFiles folderToSearchIn: appUnzipTempDir
        if (JSonFilesList) {
            prettifyJsonFiles rootFolder: appUnzipTempDir, files: JSonFilesList
            overwriteFilesInGit exportFolder: exportFolder, projectPath: projectName, appUnzipTempDir: appUnzipTempDir
        } else {
            throw new AppFactoryException('JSON files were not found', 'ERROR')
        }
        script.dir(projectName) {
            if (isGitCodeChanged()) {
                gitCredentialsWrapper {
                    configureLocalGitAccount()
                    pushChanges(exportFolder, ignoreJarsForExport)
                }
            } else {
                script.echoCustom("${fabricAppName}(${fabricAppVersion}) has no updates in Fabric.")
            }
        }
    }
    
     /**
     * To trigger publish job
     * @param cloudAccountId
     * @param fabricCredentialsID
     */
    private void appPublish(String cloudAccountId, String fabricCredentialsID, def appConfigParam, String consoleUrl = null, String identityUrl = null) {
        def publishJobParameters = [
                script.string(name: 'FABRIC_APP_VERSION', value: fabricAppVersion),
                script.booleanParam(name: "SET_DEFAULT_VERSION", value: setDefaultVersion),
                script.string(name: fabricCredentialsParamName, value: fabricCredentialsID),
                script.string(name: 'CLOUD_ACCOUNT_ID', value: cloudAccountId),
                script.string(name: 'FABRIC_APP_NAME', value: fabricAppName),
                script.string(name: 'FABRIC_ENVIRONMENT_NAME', value: fabricEnvironmentName),
                script.string(name: 'RECIPIENTS_LIST', value: recipientsList)
        ]
        if (appConfigParam.matches("(.*)_APP_CONFIG")) {
            publishJobParameters << script.credentials(name: 'FABRIC_APP_CONFIG', value: fabricAppConfig)
        }
        if (script.params.containsKey('FABRIC_CONSOLE_URL') && script.params.containsKey('FABRIC_IDENTITY_URL')) {
            publishJobParameters << script.string(name: 'FABRIC_CONSOLE_URL', value: consoleUrl) <<
                    script.string(name: 'FABRIC_IDENTITY_URL', value: identityUrl)
        }

        publishJob = script.build job: "Publish", parameters: publishJobParameters
    }

    /**
     * To implement few backward compatibility checks for FABRIC_CONSOLE_URL
     */
    private final checkCompatibility(def mandatoryParameters, String jobName) {
        switch (appConfigParameter) {
            case 'FABRIC_APP_CONFIG':
                mandatoryParameters << ['FABRIC_APP_CONFIG']
                break
            case 'CLOUD_ACCOUNT_ID':
                mandatoryParameters << ['FABRIC_APP_NAME']
                if (!script.params.FABRIC_CONSOLE_URL && !script.params.CLOUD_ACCOUNT_ID) {
                    throw new AppFactoryException('Please enter one of CLOUD_ACCOUNT_ID or FABRIC_CONSOLE_URL parameters.')
                }
                if (script.params.containsKey('FABRIC_CONSOLE_URL') && script.params.FABRIC_CONSOLE_URL) mandatoryParameters << ['FABRIC_IDENTITY_URL']
                if (enablePublish || jobName == 'publish') {
                    mandatoryParameters.add('FABRIC_ENVIRONMENT_NAME')
                }
                break
        }

        return mandatoryParameters
    }
}
