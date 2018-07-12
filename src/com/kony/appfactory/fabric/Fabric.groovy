package com.kony.appfactory.fabric

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper
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
    /* Fabric CLI command to run, the same value will be used for e-mail notifications */
    private fabricCommand
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
    private final String exportRepositoryUrl = script.params.PROJECT_EXPORT_REPOSITORY_URL
    private final String exportRepositoryBranch = script.params.PROJECT_EXPORT_BRANCH
    private final String exportRepositoryCredentialsId = script.params.PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID
    private final String cloudAccountId = script.params.CLOUD_ACCOUNT_ID
    private final String cloudCredentialsID = script.params.CLOUD_CREDENTIALS_ID
    private final String fabricAppName = script.params.FABRIC_APP_NAME
    private final String recipientsList = script.params.RECIPIENTS_LIST
    /* Import build parameters */
    private final String commitAuthor = script.params.COMMIT_AUTHOR?.trim() ?: 'Jenkins'
    private final String authorEmail = script.params.AUTHOR_EMAIL
    private String commitMessage = script.params.COMMIT_MESSAGE?.trim() ?:
            "Automatic backup of Fabric services" +
                    (script.env.BUILD_NUMBER ? ", build-${script.env.BUILD_NUMBER}" : '.')
    /* Force Fabric CLI to overwrite existing application on import */
    private final boolean overwriteExisting = script.params.OVERWRITE_EXISTING
    /* Flag for triggering Publish job */
    private final boolean enablePublish = script.params.ENABLE_PUBLISH
    /* Publish build parameters */
    private final String fabricEnvironmentName = script.params.FABRIC_ENVIRONMENT_NAME

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
    }

    /**
     * Fetches specified version of Fabric CLI application.
     *
     * @param fabricCliVersion version of Fabric CLI application.
     */
    protected final void fetchFabricCli(fabricCliVersion = 'latest') {
        String fabricCliUrl = [
                libraryProperties.'fabric.cli.fetch.url',
                fabricCliVersion.toString(),
                fabricCliFileName
        ].join('/')

        script.catchErrorCustom("Failed to fetch Fabric CLI (version: $fabricCliVersion)") {
            /* httpRequest step been used here, to be able to fetch application on any slave (any OS) */
            script.httpRequest url: fabricCliUrl, outputFile: fabricCliFileName, validResponseCodes: '200'
        }
    }

    /**
     * Runs Fabric CLI application with provided arguments.
     *
     * @param fabricCommand command name.
     * @param cloudCredentialsID Kony Cloud credentials Id in Jenkins credentials store.
     * @param isUnixNode UNIX node flag.
     * @param fabricCommandOptions options for Fabric command.
     */
    protected final void fabricCli(fabricCommand, cloudCredentialsID, isUnixNode, fabricCommandOptions = [:]) {
        /* Check required arguments */
        (fabricCommand) ?: script.echoCustom("fabricCommand argument can't be null",'ERROR')
        (cloudCredentialsID) ?: script.echoCustom("cloudCredentialsID argument can't be null",'ERROR')

        String errorMessage = ['Failed to run', fabricCommand, 'command'].join(' ')

        script.catchErrorCustom(errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : cloudCredentialsID,
                     passwordVariable: 'fabricPassword',
                     usernameVariable: 'fabricUsername']
            ]) {

                // Adding the cloud type if the domain contains other than kony.com
                if (script.env.CLOUD_DOMAIN && script.env.CLOUD_DOMAIN.indexOf("-kony.com") > 0 ){
                    def domainParam = script.env.CLOUD_DOMAIN.substring(0, script.env.CLOUD_DOMAIN.indexOf("-kony.com")+1)
                    fabricCommandOptions['--cloud-type'] = "\"${domainParam}\""
                }
                /* Collect Fabric command options */
                String options = fabricCommandOptions?.collect { option, value ->
                    [option, value].join(' ')
                }?.join(' ')
                /* Prepare string with shell script to run */
                String shellString = [
                        'java -jar', fabricCliFileName, fabricCommand,
                        '-u', (isUnixNode) ? '$fabricUsername' : '%fabricUsername%',
                        '-p', (isUnixNode) ? '$fabricPassword' : '%fabricPassword%',
                        options
                ].join(' ')

                script.shellCustom(shellString, isUnixNode)
            }
        }
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
     * Changes name of current export for next build.
     *
     * @param projectName name of the application.
     */
    private final void storeArtifacts(projectName) {
        String errorMessage = 'Failed to store artifacts'

        script.catchErrorCustom(errorMessage) {
            script.shellCustom("cp -p ${projectName}.zip ${projectName}_PREV.zip", isUnixNode)
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
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage,'WARN')
            script.currentBuild.result = 'FAILURE'
        } finally {
            setBuildDescription(buildDescriptionItems)
            NotificationsHelper.sendEmail(script, fabricCommand.capitalize(), emailData)
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
                fabricCommand = 'export'
                /* Data for e-mail notification */
                emailData = [
                        projectName           : fabricAppName,
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        commitAuthor          : commitAuthor,
                        commitMessage         : commitMessage,
                        authorEmail           : authorEmail,
                        commandName           : fabricCommand.capitalize()
                ]
                buildDescriptionItems = [
                        'Application name': fabricAppName
                ]
                boolean appChanged
                /* Folder name for storing exported application */
                String exportFolder = fabricCommand
                String projectName = getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID', 'CLOUD_CREDENTIALS_ID', 'FABRIC_APP_NAME', 'PROJECT_EXPORT_REPOSITORY_URL',
                            'PROJECT_EXPORT_BRANCH', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID', 'AUTHOR_EMAIL'
                    ]

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = (script.isUnix()) ?: script.echoCustom("Slave's OS type for this run is not supported!",'ERROR')
                        script.stage('Prepare build-node environment') {
                            /*
                            Clean workspace, to be sure that we have not any items from previous build,
                            and build environment completely new, but exclude zip file with exported
                            application from previous run.
                         */
                            script.cleanWs deleteDirs: true,
                                    patterns: [[pattern: "**/${projectName}_PREV.zip", type: 'EXCLUDE']]
                            fetchFabricCli(fabricCliVersion)
                        }

                        script.stage('Export project from Fabric') {
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-f': "\"${projectName}.zip\""
                            ]

                            fabricCli(fabricCommand, cloudCredentialsID, isUnixNode, fabricCliOptions)
                        }

                        script.stage('Check if there were changes') {
                            appChanged = fabricAppChanged(
                                    previousPath: "${projectName}_PREV.zip",
                                    currentPath: "${projectName}.zip"
                            )

                            if (appChanged) {
                                script.echoCustom("There were some changes from previous run, proceeding...")
                                script.unzip zipFile: "${projectName}.zip", dir: exportFolder
                            } else {
                                script.echoCustom("There were no changes from previous run")
                            }
                        }

                        if (appChanged) {
                            script.stage('Fetch project from remote git repository') {
                                BuildHelper.checkoutProject script: script,
                                        projectRelativePath: projectName,
                                        scmBranch: exportRepositoryBranch,
                                        scmCredentialsId: exportRepositoryCredentialsId,
                                        scmUrl: exportRepositoryUrl
                            }

                            script.stage('Prettify exported JSON files') {
                                def JSonFilesList = findJsonFiles folderToSearchIn: exportFolder
                                if (JSonFilesList) {
                                    prettifyJsonFiles rootFolder: exportFolder, files: JSonFilesList
                                    overwriteFilesInGit exportFolder: exportFolder, projectPath: projectName
                                } else {
                                    script.echoCustom('JSON files were not found','ERROR')
                                }
                            }

                            script.stage('Push changes to remote git repository') {
                                script.dir(projectName) {
                                    if (isGitCodeChanged()) {
                                        gitCredentialsWrapper {
                                            configureLocalGitAccount()
                                            pushChanges()
                                        }
                                    } else {
                                        script.echoCustom("There were no any changes in local git repository")
                                    }
                                }
                            }

                            script.stage("Store exported project on workspace") {
                                storeArtifacts(projectName)
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
                fabricCommand = 'import'
                /* Data for e-mail notification */
                emailData = [
                        projectName           : fabricAppName,
                        exportRepositoryUrl   : exportRepositoryUrl,
                        exportRepositoryBranch: exportRepositoryBranch,
                        overwriteExisting     : overwriteExisting,
                        publishApp            : enablePublish,
                        commandName           : fabricCommand.capitalize(),
                        fabricEnvironmentName : fabricEnvironmentName
                ]
                 buildDescriptionItems = [
                        'Application name' : fabricAppName,
                        'Published'        : enablePublish ? fabricEnvironmentName : 'No'
                ]
                
                String projectName = getGitProjectName(exportRepositoryUrl) ?:
                        script.echoCustom("projectName property can't be null!",'WARN')

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID', 'CLOUD_CREDENTIALS_ID', 'FABRIC_APP_NAME', 'PROJECT_EXPORT_REPOSITORY_URL',
                            'PROJECT_EXPORT_BRANCH', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID'
                    ]

                    if (enablePublish) {
                        mandatoryParameters.add('FABRIC_ENVIRONMENT_NAME')
                    }

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = (script.isUnix()) ?: script.echoCustom("Slave's OS type for this run is not supported!",'ERROR')
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        script.stage('Prepare build-node environment') {
                            fetchFabricCli(fabricCliVersion)
                        }

                        script.stage('Fetch project from remote git repository') {
                            BuildHelper.checkoutProject script: script,
                                    projectRelativePath: projectName,
                                    scmBranch: exportRepositoryBranch,
                                    scmCredentialsId: exportRepositoryCredentialsId,
                                    scmUrl: exportRepositoryUrl
                        }

                        script.stage("Create zip archive of the project") {
                            zipProject(projectName, fabricAppName)
                        }

                        script.stage('Import project to Fabric') {
                            def commonOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-f': "\"${fabricAppName}.zip\""
                            ]
                            def fabricCliOptions = (overwriteExisting) ? commonOptions + ['-a': "\"$fabricAppName\""] :
                                    commonOptions

                            fabricCli(fabricCommand, cloudCredentialsID, isUnixNode, fabricCliOptions)
                        }

                        if (enablePublish) {
                            script.stage('Trigger Publish job') {
                                script.build job: "Publish", parameters: [
                                        script.string(name: 'CLOUD_CREDENTIALS_ID', value: cloudCredentialsID),
                                        script.string(name: 'CLOUD_ACCOUNT_ID', value: cloudAccountId),
                                        script.string(name: 'FABRIC_APP_NAME', value: fabricAppName),
                                        script.string(name: 'FABRIC_ENVIRONMENT_NAME', value: fabricEnvironmentName),
                                        script.string(name: 'RECIPIENTS_LIST', value: recipientsList)
                                ]
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
                fabricCommand = 'publish'
                /* Data for e-mail notification */
                emailData = [
                        projectName          : fabricAppName,
                        fabricEnvironmentName: fabricEnvironmentName,
                        commandName          : fabricCommand.capitalize()
                ]
                buildDescriptionItems = [
                        'Application name': fabricAppName,
                        'Environment'     : fabricEnvironmentName
                ]

                script.stage('Check provided parameters') {
                    def mandatoryParameters = [
                            'CLOUD_ACCOUNT_ID', 'CLOUD_CREDENTIALS_ID', 'FABRIC_APP_NAME', 'FABRIC_ENVIRONMENT_NAME'
                    ]

                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }

                pipelineWrapper {
                    /* Allocate a slave for the run */
                    script.node(nodeLabel) {
                        isUnixNode = (script.isUnix()) ?: script.echoCustom("Slave's OS type for this run is not supported!",'ERROR')
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        script.stage('Prepare build-node environment') {
                            fetchFabricCli(fabricCliVersion)
                        }

                        script.stage('Publish project on Fabric') {
                            def fabricCliOptions = [
                                    '-t': "\"$cloudAccountId\"",
                                    '-a': "\"$fabricAppName\"",
                                    '-e': "\"$fabricEnvironmentName\""
                            ]

                            fabricCli(fabricCommand, cloudCredentialsID, isUnixNode, fabricCliOptions)
                        }
                    }
                }
            }
        }
    }
}
