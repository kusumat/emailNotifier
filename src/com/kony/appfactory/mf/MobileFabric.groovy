package com.kony.appfactory.mf

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class MobileFabric implements Serializable {
    private script
    private String mfCommand

    private String gitProtocol
    private String gitDomain
    private String gitProject
    private String organizationName

    /* Common build parameters */
    private final String gitCredentialsID = script.params.GIT_CREDENTIALS
    private final String gitURL = script.params.EXPORT_REPO_URL
    private final String gitBranch = script.params.EXPORT_REPO_BRANCH
    private final String mfAccountID = script.params.MOBILE_FABRIC_ACCOUNT_ID
    private final String mfCredentialsID = script.params.MF_CREDENTIALS
    private final String mfAppID = script.params.MOBILE_FABRIC_APP_ID
    private final String mfCLILocation = script.params.MF_CLI_LOCATION
    private final String recipientList = script.params.RECIPIENT_LIST

    /* Publish build parameters */
    private final String mfEnv = script.params.MOBILE_FABRIC_ENVIRONMENT

    private final String nodeLabel = 'master'

    MobileFabric(script) {
        this.script = script
    }

    private final catchErrorCustom(successMsg, errorMsg, closure) {
        try {
            closure()
            script.echo successMsg
        } catch(Exception e) {
            script.error errorMsg
        }
    }

    private final parseS3URL(url) {
        def s3Params = [:]
        String successMessage = 'Mobile Fabric CLI URL(s3) parsed successfully'
        String errorMessage = "FAILED to parse ${url} url"

        catchErrorCustom(successMessage, errorMessage) {
            URI s3URL = new URI(url)

            if (s3URL.getHost() && s3URL.getPath()) {
                s3Params.bucket = s3URL.getHost()
                s3Params.path = s3URL.getPath().minus('/')
            } else {
               throw new Exception()
            }
        }

        s3Params
    }

    private final mfCLI(args) {
        String command = args.command
        String options = args.options
        String mfCredID = args.mfCredID
        String successMessage = String.valueOf(mfCommand.capitalize()) + ' finished successfully'
        String errorMessage = 'FAILED to run ' + String.valueOf(mfCommand) + ' command'

        catchErrorCustom(successMessage, errorMessage) {
            script.withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                     credentialsId   : mfCredID,
                                     passwordVariable: 'mfPassword',
                                     usernameVariable: 'mfUser']]) {
                script.customShell "java -jar mfcli.jar \"${command}\" \
                    -u \"${script.env.mfUser}\" \
                    -p \"${script.env.mfPassword}\" \
                    ${options}"
            }
        }
    }

    private final fetchMFCLI(args) {
        String url = args.url
        String location = args.location
        String successMessage = 'Mobile Fabric CLI fetched successfully'
        String errorMessage = "FAILED to fetch Mobile Fabric CLI from ${url}"

        catchErrorCustom(successMessage, errorMessage) {
            if(url.startsWith('s3')) {
                def s3Params = parseS3URL(url)
                
                script.s3Download file: location + '/' + 'mfcli.jar',
                        bucket: s3Params.bucket,
                        path: s3Params.path,
                        force: true
            } else {
                script.customShell "curl -s -f -o ${location}/mfcli.jar ${url}"
            }
        }
    }

    private final boolean mfAppChanged(args) {
        boolean status
        String previousArchivePath = args.previousPath
        String currentArchivePath = args.currentPath
        String successMessage = 'Export verification finished successfully'
        String errorMessage = 'FAILED to verify changes between the previous and current exports'

        catchErrorCustom(successMessage, errorMessage) {
            if (script.fileExists(previousArchivePath)) {
                script.unzip zipFile: previousArchivePath, dir: 'previous'
                script.unzip zipFile: currentArchivePath, dir: 'current'
                
                status = script.sh (
                        script: "set +x; diff -r previous current > /dev/null",
                        returnStatus: true
                ) != 0
                
                script.dir('previous') {
                    script.deleteDir()
                }
                
                script.dir('current') {
                    script.deleteDir()
                }
            } else {
                status = true
            }
        }

        status
    }

    private final findJSONFiles(args) {
        def files
        String folderToSearchIn = args.folderToSearchIn
        String successMessage = 'JSON files were found successfully'
        String errorMessage = 'FAILED to find JSON files'
        
        catchErrorCustom(successMessage, errorMessage) {
            script.dir(folderToSearchIn) {
                files = script.findFiles(glob: '**/*.json')
            }
        }

        files
    }

    private final prettifyJSONFiles(args) {
        def files = args.files
        def rootFolder = args.rootFolder
        String successMessage = 'JSON files were prettified successfully'
        String errorMessage = 'FAILED to prettify JSON files'

        catchErrorCustom(successMessage, errorMessage) {
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
    private final prettify(String fileContent) {
        def slurper = new JsonSlurper()
        def parsedJSON = slurper.parseText(fileContent)

        if (parsedJSON instanceof Map) {
            if (parsedJSON.config && parsedJSON.config instanceof String) {
                def config = parsedJSON.config

                parsedJSON.config = slurper.parseText(config)
            }

            if (parsedJSON.policyConfig && parsedJSON.policyConfig instanceof String) {
                def policyConfig = parsedJSON.policyConfig

                parsedJSON.policyConfig = slurper.parseText(policyConfig)
            }
        }

        String updatedJSON = JsonOutput.toJson(parsedJSON)

        def pretty = JsonOutput.prettyPrint(updatedJSON)

        pretty
    }

    private final replaceLast(text, regex, replacement) {
        return text.replaceFirst("(?s)"+regex+"(?!.*?"+regex+")", replacement)
    }

    private final getPrettyFilePathName(path) {
        String[] parts = path.split('/')
        String uglyFileName = parts[parts.size()-1]

        int extIndex = uglyFileName.toLowerCase().lastIndexOf(".json")

        String prettyFileName = uglyFileName.substring(0, extIndex) + ".pretty.json"

        String newPath = replaceLast(path, uglyFileName, prettyFileName)

        newPath
    }

    private final checkEnvironment() {
        if (!script.isUnix()) {
            script.error 'FAILED to run scripts on non-Linux node'
        }
    }

    private final parseExportURL() {
        def successMessage = 'Export URL parsed successfully'
        def errorMessage = "FAILED to parse ${gitURL} url"

        catchErrorCustom(successMessage, errorMessage) {
            def gitParams = gitURL.split('/')
            gitProtocol = gitParams[0]
            gitDomain = gitParams[2]
            organizationName = gitParams[3]
            gitProject = gitParams[4].split('\\.')[0]
        }
    }

    private final boolean ifWereChangesInGit() {
        boolean status
        String successMessage = 'Examination of changes in git completed successfully'
        String errorMessage = 'FAILED to examine if there were changes in git'

        catchErrorCustom(successMessage, errorMessage) {
            script.dir(gitProject) {
                script.customShell 'git add .'

                status = script.sh(
                        script: "set +x; git diff --cached --exit-code > /dev/null",
                        returnStatus: true
                ) != 0
            }
        }

        status
    }

    private final String loadLibraryResource(resourcePath) {
        def resource = ''
        String successMessage = 'Resource loading finished successfully'
        String errorMessage = 'FAILED to load resource'

        catchErrorCustom(successMessage, errorMessage) {
            resource = script.libraryResource resourcePath
        }

        resource
    }

    private final void sendNotification(fillMailClosure) {
        String buildDetails = String.valueOf(mfCommand.capitalize()) +
                ' of Mobile Fabric app ' + String.valueOf(mfAppID) +
                ' is: ' + String.valueOf(script.currentBuild.currentResult)
        String mailTemplate = loadLibraryResource "com/kony/appfactory/mf/${mfCommand}/email-notif-templ.html"
        String content = fillMailClosure(buildDetails, mailTemplate)

        script.emailext(
            body: content,
            mimeType: 'text/html',
            subject: "${script.env.JOB_NAME} - #${script.env.BUILD_NUMBER} - ${script.currentBuild.currentResult}",
            to: recipientList
        )
    }

    private final void cloneProject() {
        String successMessage = 'Mobile Fabric application cloned successfully'
        String errorMessage = 'FAILED to clone Mobile Fabric application from git'

        catchErrorCustom(successMessage, errorMessage) {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: [$class                           : 'GitSCM',
                          branches                         : [[name: "*/${gitBranch}"]],
                          doGenerateSubmoduleConfigurations: false,
                          extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                               relativeTargetDir: "${gitProject}"]],
                          submoduleCfg                     : [],
                          userRemoteConfigs                : [[credentialsId: "${gitCredentialsID}",
                                                               url          : "${gitURL}"]]]
            )
        }

    }

    private final void overwriteFilesInGit(args) {
        String exportDir = args.exportDir
        String successMessage = 'Exported files were overwritten successfully'
        String errorMessage = 'FAILED overwrite exported files'

        catchErrorCustom(successMessage, errorMessage) {
            script.dir("${gitProject}/${exportDir}") {
                script.deleteDir()
            }

            script.customShell "mv -f ./${exportDir} ./${gitProject}/"
        }
    }

    private final void storeArtifacts() {
        String successMessage = 'Artifacts were stored successfully'
        String errorMessage = 'FAILED to store artifacts'

        catchErrorCustom(successMessage, errorMessage) {
            script.customShell "cp -p ${gitProject}.zip ${gitProject}_PREV.zip"
        }
    }

    private final void unarchiveArtifacts() {
        String successMessage = 'Artifacts unarchived successfully'
        String errorMessage = 'There were no any artifacts from previous run'
        /* Workaround taken from here https://issues.jenkins-ci.org/browse/JENKINS-27916 */
        String prevFileName = "${gitProject}_PREV.zip".toString()

        catchErrorCustom(successMessage, errorMessage) {
            script.unarchive mapping: [(prevFileName): '.']
        }
}

    private final void zipProject() {
        String successMessage = 'Zip file created successfully'
        String errorMessage = "FAILED to create zip file for project ${gitProject}"

        catchErrorCustom(successMessage, errorMessage) {
            script.customShell "zip -r \"${mfAppID}.zip\" \"${gitProject}/export/Apps\" -x *.pretty.json"
        }
    }

    protected final void exportApp() {
        mfCommand = 'export'
        final boolean appChanged
        final String exportDir = 'export'

        /* Export build parameters */
        final String commitAuthor = (script.params.COMMIT_AUTHOR?.trim()) ? script.params.COMMIT_AUTHOR : 'Jenkins'
        final String authorEmail = (script.params.AUTHOR_EMAIL?.trim()) ? script.params.AUTHOR_EMAIL : ''
        final String commitMessage = script.params.COMMIT_MESSAGE?.trim() ? script.params.COMMIT_MESSAGE :
                'Automatic backup of MobileFabric services.'

        final fillMailContentPlaceholders = { buildDetails, template ->
            template.replaceAll('mfAppID', mfAppID)
                    .replaceAll('buildDetails', buildDetails)
                    .replaceAll('gitURL', gitURL)
                    .replaceAll('gitBranch', gitBranch)
                    .replaceAll('commitAuthor', commitAuthor)
                    .replaceAll('authorEmail', authorEmail)
                    .replaceAll('commitMessage', commitMessage)
        }

        if (!(gitCredentialsID &&
                gitURL &&
                gitBranch &&
                mfAccountID &&
                mfCredentialsID &&
                mfAppID &&
                mfCLILocation &&
                recipientList &&
                commitAuthor &&
                authorEmail &&
                commitMessage)) {
            script.error 'Please check build parameters'
        }

        script.node(nodeLabel) {
            try {
                script.stage('Prepare environment') {
                    checkEnvironment()
                    parseExportURL()
                    /* Commenting out this because unarchive failed to restore artifacts from prev build */
//                    unarchiveArtifacts()
                    fetchMFCLI url: mfCLILocation, location: script.pwd()
                }

                script.stage('Export Mobile Fabric application') {
                    String exportOptions = "-t \"${mfAccountID}\" -a \"${mfAppID}\" -f \"${gitProject}.zip\""

                    mfCLI command: mfCommand, options: exportOptions, mfCredID: mfCredentialsID
                }

                script.stage('Check if there were changes') {
                    appChanged = mfAppChanged previousPath: "${gitProject}_PREV.zip", currentPath: "${gitProject}.zip"

                    if (appChanged) {
                        script.echo 'There were some changes from previous run, proceeding...'
                        script.unzip zipFile: "${gitProject}.zip", dir: exportDir
                    } else {
                        script.echo 'There were no changes from previous run'
                    }
                }

                if (appChanged) {
                    script.stage('Clone Mobile Fabric application from git') {
                        cloneProject()
                    }

                    script.stage('Configure local git account') {
                        try {
                            script.withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                                     credentialsId   : gitCredentialsID,
                                                     passwordVariable: 'gitPassword',
                                                     usernameVariable: 'gitUser']]) {
                                script.dir(gitProject) {
                                    script.customShell(
                                            "git config --local push.default simple" + ' && ' +
                                            "git config --local user.name '${commitAuthor}'"
                                    )

                                    if (commitAuthor == 'Jenkins') {
                                        commitMessage += " Used credentials of user ${gitUser}"
                                    }

                                    if (authorEmail != '') {
                                        script.customShell "git config --local user.email ${authorEmail}"
                                    }
                                }
                            }
                            script.echo 'Git local account configuration finished successfully'
                        } catch (Exception e) {
                            script.error 'FAILED configure local git account'
                        }
                    }

                    script.stage('Prettify exported JSON files and move them to SCM') {
                        def JSONFilesList = findJSONFiles folderToSearchIn: exportDir
                        if (JSONFilesList) {
                            prettifyJSONFiles rootFolder: exportDir, files: JSONFilesList
                            overwriteFilesInGit 'exportDir': exportDir
                        } else {
                            script.error 'JSON files were not found'
                        }
                    }

                    script.stage('Push changes to remote') {
                        if (ifWereChangesInGit()) {
                            try {
                                script.withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                                         credentialsId   : gitCredentialsID,
                                                         passwordVariable: 'gitPassword',
                                                         usernameVariable: 'gitUser']]) {
                                    def gitPassword = script.env.gitPassword.contains("@") ?
                                            URLEncoder.encode(script.env.gitPassword) :
                                            script.env.gitPassword

                                    script.dir(gitProject) {
                                        script.customShell """git checkout '${gitBranch} && git commit -m ${
                                            commitMessage
                                        } && git push ${gitProtocol}${script.env.gitUser}:${gitPassword}@${gitDomain}${
                                            organizationName
                                        }${gitProject}.git"""
                                    }
                                }
                                script.echo 'All changes were pushed to remote git repository successfully'
                            } catch (Exception e) {
                                script.error 'FAILED to push changes to remote git repository'
                            }
                        } else {
                            script.echo 'There were no any changes in local project repository'
                        }
                    }

                    script.stage("Storing artifacts") {
                        storeArtifacts()
                    }
                }
            } catch(Exception e) {
                script.echo e.getMessage()

                script.currentBuild.result = 'FAILURE'
            } finally {
                /* Commenting out this while unarchive step not working */
//                script.archiveArtifacts artifacts: "**/${gitProject}_PREV.zip", onlyIfSuccessful: true

                script.step([$class    : 'WsCleanup',
                             deleteDirs: true,
                             patterns  : [[pattern: "**/${gitProject}_PREV.zip", type: 'EXCLUDE']]])

                sendNotification(fillMailContentPlaceholders)
            }
        }
    }

    protected final void importApp() {
        mfCommand = 'import'

        /* Import build parameters */
        final String gitTagID = script.params.TAG_ID
        final boolean overwriteExisting = script.params.OVERWRITE_EXISTING
        final boolean publishApp = script.params.ENABLE_PUBLISH

        final fillMailContentPlaceholders = { buildDetails, template ->
            template.replaceAll('mfAppID', mfAppID)
                    .replaceAll('buildDetails', buildDetails)
                    .replaceAll('gitURL', gitURL)
                    .replaceAll('gitBranch', gitBranch)
                    .replaceAll('gitTagID', gitTagID)
                    .replaceAll('overwriteExisting', overwriteExisting.toString())
                    .replaceAll('publishApp', publishApp.toString())
        }

        if (!(gitCredentialsID &&
                gitURL &&
                gitBranch &&
                mfAccountID &&
                mfCredentialsID &&
                mfAppID &&
                mfCLILocation &&
                recipientList &&
                gitTagID &&
                mfEnv)) {
            script.error 'Please check build parameters'
        }

        script.node(nodeLabel) {
            try {
                script.stage('Prepare environment') {
                    checkEnvironment()
                    parseExportURL()
                    fetchMFCLI url: mfCLILocation, location: script.pwd()
                }

                script.stage('Clone Mobile Fabric application from git') {
                    cloneProject()
                }

                script.stage("Create zip file of Mobile Fabric git project") {
                    zipProject()
                }

                script.stage('Import Mobile Fabric application') {
                    String commonImportOptions = "-t \"${mfAccountID}\" -f \"${mfAppID}.zip\""
                    String importOptions = (overwriteExisting) ?
                            "${commonImportOptions} -a \"${mfAppID}\"" :
                            "${commonImportOptions}"

                    mfCLI command: mfCommand, options: importOptions, mfCredID: mfCredentialsID
                }

                if (publishApp) {
                    script.stage('Trigger Publish Mobile Fabric application job') {
                        script.build job: 'MF/News_Weather_Sample_MobileFabric_Publish', parameters: [
                                script.string(name: 'MOBILE_FABRIC_ACCOUNT_ID', value: mfAccountID),
                                script.string(name: 'MOBILE_FABRIC_APP_ID', value: mfAppID),
                                script.string(name: 'MF_CREDENTIALS', value: mfCredentialsID),
                                script.string(name: 'MF_CLI_LOCATION', value: mfCLILocation),
                                script.string(name: 'RECIPIENT_LIST', value: recipientList),
                                script.string(name: 'MOBILE_FABRIC_ENVIRONMENT', value: mfEnv)]
                    }
                }
            } catch(Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                script.step([$class: 'WsCleanup', deleteDirs: true])
                sendNotification(fillMailContentPlaceholders)
            }
        }
    }

    protected final void publishApp() {
        mfCommand = 'publish'

        final fillMailContentPlaceholders = { buildDetails, template ->
            template.replaceAll('mfAppID', mfAppID)
                    .replaceAll('buildDetails', buildDetails)
                    .replaceAll('mfEnv', mfEnv)
        }

        if (!(mfAccountID &&
                mfCredentialsID &&
                mfAppID &&
                mfCLILocation &&
                recipientList &&
                mfEnv)) {
            script.error 'Please check build parameters'
        }

        script.node(nodeLabel) {
            try {
                script.stage('Prepare environment') {
                    checkEnvironment()
                    fetchMFCLI url: mfCLILocation, location: script.pwd()
                }

                script.stage('Publish Mobile Fabric application'){
                    String publishOptions = "-t \"${mfAccountID}\" -a \"${mfAppID}\" -e \"${mfEnv}\""

                    mfCLI command: mfCommand, options: publishOptions, mfCredID: mfCredentialsID
                }
            } catch(Exception e) {
                script.echo e.getMessage()
                script.currentBuild.result = 'FAILURE'
            } finally {
                script.step([$class: 'WsCleanup', deleteDirs: true])
                sendNotification(fillMailContentPlaceholders)
            }
        }
    }
}
