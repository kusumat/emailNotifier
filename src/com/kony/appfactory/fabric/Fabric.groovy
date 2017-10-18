package com.kony.appfactory.fabric

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class Fabric implements Serializable {
    private script
    private emailData
    private fabricCommand
    private buildDescriptionItems
    private libraryProperties
    private final String fabricCliFileName
    private final String fabricCliVersion
    private final String nodeLabel
    private final boolean isUnixNode = true
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
            "Automatic backup of Fabric services" + (script.env.BUILD_NUMBER ? ", build-${script.env.BUILD_NUMBER}" : '.')
    private final boolean overwriteExisting = script.params.OVERWRITE_EXISTING
    private final boolean enablePublish = script.params.ENABLE_PUBLISH
    /* Publish build parameters */
    private final String fabricEnvironmentName = script.params.FABRIC_ENVIRONMENT_NAME

    Fabric(script) {
        this.script = script
        libraryProperties = BuildHelper.loadLibraryProperties(this.script, 'com/kony/appfactory/configurations/common.properties')
        fabricCliVersion = libraryProperties. 'fabric.cli.version'
        fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        nodeLabel = libraryProperties.'fabric.node.label'
    }

    protected final fetchFabricCli(fabricCliVersion = 'latest') {
        String fabricCliUrl = [
                libraryProperties.'fabric.cli.fetch.url',
                fabricCliVersion.toString(),
                fabricCliFileName
        ].join('/')

        script.catchErrorCustom("FAILED to fetch Fabric CLI (version: $fabricCliVersion)") {
            script.shellCustom("curl -k -s -S -f -L -o \'${fabricCliFileName}\' \'${fabricCliUrl}\'")
        }
    }

    protected final fabricCli(fabricCommand, cloudCredentialsID, isUnixNode, fabricCommandOptions = [:]) {
        (fabricCommand) ?: script.error("fabricCommand argument can't be null")
        (cloudCredentialsID) ?: script.error("cloudCredentialsID argument can't be null")

        String errorMessage = ['FAILED to run', fabricCommand, 'command'].join(' ')

        script.catchErrorCustom(errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : cloudCredentialsID,
                     passwordVariable: 'fabricPassword',
                     usernameVariable: 'fabricUsername']
            ]) {
                String options = fabricCommandOptions?.collect { option, value ->
                    [option, value].join(' ')
                }?.join(' ')
                String shellString = [
                        'java -jar', fabricCliFileName, fabricCommand,
                        '-u', (isUnixNode) ? '$fabricUsername': '%fabricUsername%',
                        '-p', (isUnixNode) ? '$fabricPassword': '%fabricPassword%',
                        options
                ].join(' ')

                script.shellCustom(shellString, isUnixNode)
            }
        }
    }

    private final boolean fabricAppChanged(args) {
        boolean status = true
        String previousArchivePath = args.previousPath
        String currentArchivePath = args.currentPath
        String errorMessage = 'FAILED to check if exported project has been changed!'

        script.catchErrorCustom(errorMessage) {
            if (script.fileExists(previousArchivePath)) {
                script.unzip zipFile: previousArchivePath, dir: 'previous'
                script.unzip zipFile: currentArchivePath, dir: 'current'

                status = script.shellCustom(
                        'diff -r previous current > /dev/null',
                        isUnixNode,
                        [returnStatus: true]
                ) != 0

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

    private final findJsonFiles(args) {
        def files
        String folderToSearchIn = args.folderToSearchIn
        String errorMessage = 'FAILED to find JSON files'

        script.catchErrorCustom(errorMessage) {
            script.dir(folderToSearchIn) {
                files = script.findFiles(glob: '**/*.json')
            }
        }

        files
    }

    private final prettifyJsonFiles(args) {
        def files = args.files
        String rootFolder = args.rootFolder
        String errorMessage = 'FAILED to prettify JSON files'

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

    private final replaceLast(text, regex, replacement) {
        text.replaceFirst("(?s)"+regex+"(?!.*?"+regex+")", replacement)
    }

    private final getPrettyFilePathName(path) {
        String[] parts = path.split('/')
        String uglyFileName = parts[parts.size()-1]

        int extIndex = uglyFileName.toLowerCase().lastIndexOf(".json")

        String prettyFileName = uglyFileName.substring(0, extIndex) + ".pretty.json"

        replaceLast(path, uglyFileName, prettyFileName)
    }

    private final getGitProjectName(String url) {
        url.tokenize('/')?.last()?.replaceAll('.git', '')
    }

    private final void overwriteFilesInGit(args) {
        String projectPath = args.projectPath
        String exportDir = args.exportDir
        String errorMessage = 'FAILED overwrite exported files'

        script.catchErrorCustom(errorMessage) {
            script.dir("${projectPath}/${exportDir}") {
                script.deleteDir()
            }

            script.shellCustom("mv -f ./${exportDir} ./${projectPath}/")
        }
    }

    private final void storeArtifacts(projectName) {
        String errorMessage = 'FAILED to store artifacts'

        script.catchErrorCustom(errorMessage) {
            script.shellCustom("cp -p ${projectName}.zip ${projectName}_PREV.zip")
        }
    }

    private final void zipProject(projectName, fabricApplicationName) {
        String errorMessage = "FAILED to create zip file for project ${projectName}"

        script.catchErrorCustom(errorMessage) {
            script.shellCustom("zip -r \"${fabricApplicationName}.zip\" \"${projectName}/export/Apps\" -x *.pretty.json")
        }
    }

    private final boolean isGitCodeChanged() {
        boolean status
        String errorMessage = 'FAILED to check if there were changes in local repository'

        script.catchErrorCustom(errorMessage) {
            script.shellCustom('git add .')

            status = script.shellCustom(
                    'git diff --cached --exit-code > /dev/null',
                    isUnixNode,
                    [returnStatus: true]
            ) != 0
        }

        status
    }

    private final void configureLocalGitAccount() {
        String successMessage = 'Local git account configured successfully'
        String errorMessage = 'FAILED configure local git account'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.shellCustom([
                    "git config --local push.default simple",
                    "git config --local user.name \"$commitAuthor\""
            ].join(' && '))

            if (commitAuthor == 'Jenkins') {
                commitMessage += " Used credentials of user \"${script.env.gitUsername}\""
            }

            if (authorEmail != '') {
                script.shellCustom("git config --local user.email \"$authorEmail\"")
            }
        }
    }

    private final void pushChanges() {
        String successMessage = 'All changes were successfully pushed to remote git repository'
        String errorMessage = 'FAILED to push changes to remote git repository'

        script.catchErrorCustom(errorMessage, successMessage) {
            String gitUsername = URLEncoder.encode(script.env.gitUsername)
            String gitPassword = URLEncoder.encode(script.env.gitPassword)
            String pushUrl = exportRepositoryUrl.replaceFirst("//", "//${gitUsername}:${gitPassword}@")
            String checkoutCommand = "git checkout \"$exportRepositoryBranch\""
            String commitCommand = "git commit -m \"$commitMessage\""
            String pushCommand = "git push \"$pushUrl\""

            script.shellCustom(
                    [checkoutCommand, commitCommand, pushCommand].join(' && ')
            )
        }
    }

    private final void credentialsWrapper(closure) {
        script.withCredentials([
                [$class: 'UsernamePasswordMultiBinding',
                 credentialsId: exportRepositoryCredentialsId,
                 passwordVariable: 'gitPassword',
                 usernameVariable: 'gitUsername']
        ]) {
            closure()
        }
    }

    private final void setBuildDescription(itemsToExpose) {
        script.currentBuild.description = """\
            <div id="build-description">
                ${itemsToExpose?.findResults { item -> item.value ? "<p>${item.key}: ${item.value}</p>" : null }?.join('\n')}
            </div>\
            """.stripIndent()
    }

    private final void pipelineWrapper(closure) {
        try {
            closure()
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echo "ERROR: $exceptionMessage"
            script.currentBuild.result = 'FAILURE'
        } finally {
            setBuildDescription(buildDescriptionItems)
            NotificationsHelper.sendEmail(script, fabricCommand.capitalize(), emailData)
        }
    }

    protected final void exportApp() {
        script.timestamps {
            fabricCommand = 'export'
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
            String exportDir = fabricCommand
            String projectName = getGitProjectName(exportRepositoryUrl) ?: script.echo("projectName property can't be null!")

            script.stage('Check provided parameters') {
                def mandatoryParameters = [
                        'CLOUD_ACCOUNT_ID', 'CLOUD_CREDENTIALS_ID', 'FABRIC_APP_NAME', 'PROJECT_EXPORT_REPOSITORY_URL',
                        'PROJECT_EXPORT_BRANCH', 'PROJECT_EXPORT_REPOSITORY_CREDENTIALS_ID', 'AUTHOR_EMAIL'
                ]

                ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
            }

            pipelineWrapper {
                script.node(nodeLabel) {
                    script.stage('Prepare build-node environment') {
                        script.cleanWs deleteDirs: true, patterns: [[pattern: "**/${projectName}_PREV.zip", type: 'EXCLUDE']]
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
                            script.echo 'There were some changes from previous run, proceeding...'
                            script.unzip zipFile: "${projectName}.zip", dir: exportDir
                        } else {
                            script.echo 'There were no changes from previous run'
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
                            def JSonFilesList = findJsonFiles folderToSearchIn: exportDir
                            if (JSonFilesList) {
                                prettifyJsonFiles rootFolder: exportDir, files: JSonFilesList
                                overwriteFilesInGit exportDir: exportDir, projectPath: projectName
                            } else {
                                script.error 'JSON files were not found'
                            }
                        }

                        script.stage('Push changes to remote git repository') {
                            script.dir(projectName) {
                                if (isGitCodeChanged()) {
                                    credentialsWrapper {
                                        configureLocalGitAccount()
                                        pushChanges()
                                    }
                                } else {
                                    script.echo 'There were no any changes in local git repository'
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

    protected final void importApp() {
        script.timestamps {
            fabricCommand = 'import'
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
                    'Application name': fabricAppName,
                    'Published'       : (enablePublish) ? 'yes' : 'no',
                    'Environment'     : (enablePublish) ? fabricEnvironmentName : null
            ]
            String projectName = getGitProjectName(exportRepositoryUrl) ?: script.echo("projectName property can't be null!")

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
                script.node(nodeLabel) {
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

    protected final void publishApp() {
        script.timestamps {
            fabricCommand = 'publish'
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
                script.node(nodeLabel) {
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
