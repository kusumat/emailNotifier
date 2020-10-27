package com.kony.appfactory.helper

class FabricHelper implements Serializable {
    
    /**
     * Fetches specified version of Fabric CLI application.
     *
     * @param script Build script object.
     * @param libraryProperties properties object that is loaded with the common.properties file
     * @param fabricCliVersion version of Fabric CLI application.
     */
    protected static final void fetchFabricCli(script, libraryProperties, fabricCliVersion = 'latest') {
        
        String fabricCliFileName = libraryProperties.'fabric.cli.file.name'
        
        String fabricCliUrl = [
                libraryProperties.'fabric.cli.fetch.url'.replaceAll("\\[CLOUD_DOMAIN\\]", script.env.CLOUD_DOMAIN),
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
     * @param script Build script object.
     * @param fabricCommand command name.
     * @param fabricCredentialsID Kony Fabric credentials Id in Jenkins credentials store.
     * @param isUnixNode UNIX node flag.
     * @param fabricCliFileName fabric cli file name that need to executed.
     * @param fabricCommandOptions options for Fabric command.
     * @param args to shellCustom to return status and command output
     *
     * @return returnStatus/returnStdout
     */
    protected static final String fabricCli(script, fabricCommand, fabricCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions = [:], args = [:]) {
        /* Check required arguments */
        (fabricCommand) ?: script.echoCustom("fabricCommand argument can't be null",'ERROR')
        (fabricCredentialsID) ?: script.echoCustom("fabricCredentialsID argument can't be null",'ERROR')
        
        String commandOutput
        String errorMessage = ['Failed to run', fabricCommand, 'command'].join(' ')

        script.catchErrorCustom(errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : fabricCredentialsID,
                     passwordVariable: 'fabricPassword',
                     usernameVariable: 'fabricUsername']
            ]) {
                // Switch command options(removing account id) if Console Url represents OnPrem Fabric.(Visit https://docs.kony.com/konylibrary/konyfabric/kony_fabric_user_guide/Content/CI_MobileFabric.htm for details).
                if(!script.env.CONSOLE_URL.matches(script.kony.FABRIC_CONSOLE_URL))
                {
                    fabricCommandOptions.remove('-t')
                    fabricCommandOptions << ['-cu': "\"${script.env.CONSOLE_URL}\"",
                                             '-au': "\"${script.env.IDENTITY_URL}\"",]
                } else {
                    def cloudUrl = script.kony.FABRIC_CONSOLE_URL
                    fabricCommandOptions['--cloud-url'] = "\"${cloudUrl}\""
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
                
                // if URL_PATH_INFO variable was set, Adding option to authenticate with external-auth
                if(script.env.URL_PATH_INFO)
                    shellString = [shellString, '--external-auth'].join(' ')
                
                if(fabricCommand.equalsIgnoreCase('publish')){
                    String envName = fabricCommandOptions?."-e"
                    commandOutput = sequencePublish(script, envName, shellString, isUnixNode, args)
                } else {
                    commandOutput = script.shellCustom(shellString, isUnixNode, args)
                }
            }
        }
        commandOutput
    }
    
    /**
     * Publishes the application to the fabric environment in the sequential way
     * 
     * @param script Build script object.
     * @param envName Environment name on which the app need to be published.
     * @param shellString complete fabric command.
     * @param isUnixNode UNIX node flag.
     * @param args to shellCustom to return status and command output.
     * 
     * @return mfcli commandOutput
     */
    private static final sequencePublish(script, envName, shellString, isUnixNode, args) {
        def commandOutput
        args.returnStdout = true
        script.lock(envName) {
            int iteration = 0
            commandOutput = script.shellCustom(shellString, isUnixNode, args)
            while(commandOutput.contains('ERROR: Server is busy ') && iteration < 100) {
                script.echoCustom('Server Environment seems busy with another app publish!! We will try to publish after 15 Seconds.', 'INFO')
                script.sleep 15
                commandOutput = script.shellCustom(shellString, isUnixNode, args)
                iteration++
            }
        }
        commandOutput
    }
    
    /** 
     * Get the repository project name
     * @param url valid source control url of the project repository
     * @return repo project name
     */
    private static final getGitProjectName(String url) {
        url?.tokenize('/')?.last()?.replaceAll('.git', '')
    }
    
    /**
     * Run maven build for pom.xml files in application's code.
     * @param script a pipeline object.
     * @param isUnixNode environment os info
     */
    private static runMavenBuild(script, boolean isUnixNode, mavenBuildCommand) {
        String successMessage = 'Successfully built the maven java project!'
        String errorMessage = 'Failed to build the maven java project!'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.shellCustom(mavenBuildCommand, isUnixNode)
        }
    }

    /**
     * Get the fabric app version from fabric app source
     * @param script pipeline object
     * @param fabricAppBasePath Full path to fabric app dir where it contains the 'Apps' folder
     * @param isUnixNode flag for os
     * @return appVersionFromAppMeta version from meta.json
     */
    protected static final getFabricAppVersionFromAppMetaJson(script, fabricAppBasePath, isUnixNode) {
        String appVersionFromAppMeta = null
        def fabricAppsDirList = []
        def appMetaJsonFile
        def separator = getPathSeparatorBasedOnOs(isUnixNode)
        //appNameDir/Apps/appName/meta.json file
        def fabricAppsDirPath = [fabricAppBasePath, 'Apps'].join(separator)
        script.catchErrorCustom("Failed to get fabric app version!") {
            script.dir(fabricAppsDirPath) {
                fabricAppsDirList = FabricHelper.getSubDirectories(script, isUnixNode, fabricAppsDirPath)
                if(fabricAppsDirList.isEmpty())
                    throw new AppFactoryException("The path ${fabricAppsDirPath} does not appear to contain a valid Fabric app.", "ERROR")
                //Find the directory which does not start with _ underscore( will be fabric app name dir)
                fabricAppsDirList.each{ fabricAppNameDir ->
                    if(fabricAppNameDir =~ /^(?!_.*$).*/) {
                        appMetaJsonFile = [fabricAppsDirPath, fabricAppNameDir, 'Meta.json'].join(separator)
                        if (script.fileExists(appMetaJsonFile)) {
                            def metaJson = script.readJSON file: appMetaJsonFile
                            appVersionFromAppMeta = metaJson.version
                        } else {
                            throw new AppFactoryException("Fabric app repository does not not exist ${appMetaJsonFile }!", "ERROR")
                        }
                    }
                }
            }
            
            if(!appVersionFromAppMeta){
                throw new AppFactoryException("App Version can't be null", 'ERROR')
            }
        }
        appVersionFromAppMeta
    }
    
    /**
     * Rename the fabric build artifacts for custom name
     * @param script pipeline object
     * @param buildArtifacts map of build artifacts
     * @param isUnixNode flag for os
     * @return renamedArtifacts: renamed artifacts
     */
    protected static final renameFabricAppZipArtifact(script, buildArtifacts, isUnixNode) {
        String renamedArtifactName = script.env.PROJECT_NAME + ".zip"
        String shellCommand = (isUnixNode) ? 'mv' : 'rename'
        script.catchErrorCustom('Failed to rename artifacts') {
            String artifactName = buildArtifacts.first().name
            String command = [shellCommand, artifactName, renamedArtifactName].join(' ')
            /* Rename artifact */
            script.shellCustom(command, isUnixNode)
        }
        renamedArtifactName
    }
    
    /**
     * Import fabric app
     * @param script
     * @param fabricCliPath, mfcli.jar with absolute path
     * @param fabricAppArtifact
     * @param cloudCredentialsID
     * @param cloudAccountId
     * @param fabricAppName
     * @param fabricAppVersion
     * @param isUnixNode
     * @param importAsNew
     */
    protected static final void importFabricApp(
        script,
        fabricCliPath,
        fabricAppArtifact,
        cloudCredentialsID,
        cloudAccountId,
        fabricAppName,
        fabricAppVersion,
        isUnixNode,
        boolean importAsNew = false) {

        def importCommandOptions = [
            '-t': "\"$cloudAccountId\"",
            '-f': "\"${fabricAppArtifact}\"",
            '-a': "\"$fabricAppName\"",
            '-v': "\"$fabricAppVersion\""
        ]
        
        if(importAsNew)
            importCommandOptions << ['-importAsNew': ""]
        fabricCli(script, 'import', cloudCredentialsID, isUnixNode, fabricCliPath, importCommandOptions)
    }
    
    /**
     * Publish fabric app
     * @param script
     * @param fabricCli, mfcli.jar with absolute path
     * @param cloudCredentialsID
     * @param cloudAccountId
     * @param fabricAppName
     * @param fabricAppVersion
     * @param fabricEnvironmentName
     * @param isUnixNode
     */
    protected static final void publishFabricApp(
        script,
        fabricCliPath,
        cloudCredentialsID,
        cloudAccountId,
        fabricAppName,
        fabricAppVersion,
        fabricEnvironmentName,
        isUnixNode) {
        
        def publishCommandOptions = [
            '-t': "\"$cloudAccountId\"",
            '-a': "\"$fabricAppName\"",
            '-v': "\"$fabricAppVersion\"",
            '-e': "\"$fabricEnvironmentName\""
        ]
        
        fabricCli(script, 'publish', cloudCredentialsID, isUnixNode, fabricCliPath, publishCommandOptions)
    }
    
    /**
     * Get sub-directory list for given base dir path for linux
     * @param script
     * @param isUnixNode
     * @param baseDirPath
     */
    protected static final getSubDirectories(script, isUnixNode, baseDirPath){
        def subDirList = []
        def errorMsg = "Failed to get sub-directory for base dir:[${baseDirPath}]!"
        script.catchErrorCustom(errorMsg) {
            script.dir(baseDirPath) {
                if(isUnixNode) {
                    def subDirsWithSeparator = script.shellCustom('set +x; ls -d */', isUnixNode, [returnStdout:true])
                    def subDirs = subDirsWithSeparator.trim().split("/")
                    subDirs.each { subDir ->
                        subDirList << subDir.trim()
                    }
                } else {
                    // TODO: Not in scope, will add later
                    script.echoCustom("Not supported to get the sub-directories list for Windows!", 'ERROR')
                }
            }
        }
        subDirList
    }
    
    /**
     * Get path separator based on OS
     * @param isUnixNode
     * @return separator
     */
    protected static final getPathSeparatorBasedOnOs(boolean isUnixNode) {
        def separator = isUnixNode ? '/' : '\\'
        separator
    }

    /**
     * To check directory exists or not
     * @param script
     * @param dirPath: complete path upto directory.
     * @param isUnixNode
     * @return boolean true or false
     */
    protected static boolean isDirExist(script, dirPath, isUnixNode ){
        def isDirExist = script.shellCustom("set +x;test -d ${dirPath} && echo 'exist' || echo 'doesNotExist'", isUnixNode, [returnStdout: true])
        if (isDirExist.trim() == 'doesNotExist')
            return false
        return true
    }

    /**
     * Validating the maven goals and options command by allowing (;|&&) operands in -Darguments Or
     * If these operands are followed with mvn command again
     *
     * @param cmd - maven goals and options command
     */
    protected static boolean validateMvnGoalsBuildCommand(cmd) {
        def tokens = cmd.tokenize(' ')
        int totalTokens = tokens.size()
        boolean isValid = true
        if (cmd.contains(';') || cmd.contains('&&')) {
            tokens.eachWithIndex { subCommand, curToken ->
                if (!isValid)
                    return false
                def character = [';', '&&'];
                for (int i = 0; i < character.size(); i++) {
                    if (subCommand.contains(character[i])) {

                        if (!subCommand.contains('="') && !subCommand.contains("='")) {
                            if (subCommand.contains(character[i - 1]) || subCommand.count(character[i]) > 1)
                                isValid = false
                            else if (subCommand.endsWith(character[i]))
                                isValid = (curToken == totalTokens - 1) ? ((character[i] == ';') ? true : false) : tokens[curToken + 1].toLowerCase().startsWith('mvn')
                            else
                                isValid = subCommand.toLowerCase().endsWith('mvn')
                        }
                        else if (subCommand.endsWith(character[i]))
                            isValid = (curToken == totalTokens - 1) ? ((character[i] == ';') ? true : false) : tokens[curToken + 1].toLowerCase().startsWith('mvn')
                        else if (subCommand.startsWith(character[i]))
                            isValid = subCommand.toLowerCase().endsWith('mvn')
                        else
                            isValid = subCommand.startsWith('-D')

                        if (!isValid)
                            break
                    }
                }
            }
        }
        return isValid
    }
    
    /**
     * Run healthcheck for Fabric environment
     * @param script
     * @param fabricCliPath, mfcli.jar with absolute path
     * @param cloudCredentialsID
     * @param cloudAccountId
     * @param fabricEnvironmentName
     * @param isUnixNode
     * @return console output screen of healthcheck
     */
    private static final String runFabricHealthCheck(
        script,
        fabricCliPath,
        cloudCredentialsID,
        cloudAccountId,
        fabricEnvironmentName,
        isUnixNode) {

        def healthCheckCommandOptions = [
            '-t': "\"$cloudAccountId\"",
            '-e': "\"$fabricEnvironmentName\""
        ]
        
        fabricCli(script, 'healthcheck', cloudCredentialsID, isUnixNode, fabricCliPath, healthCheckCommandOptions, [returnStdout: true])
    }
    
    /**
     * Import fabric app service config
     * @param script
     * @param fabricCliPath, mfcli.jar with absolute path
     * @param fabricAppConfigJsonFile
     * @param cloudCredentialsID
     * @param cloudAccountId
     * @param fabricAppName
     * @param fabricAppVersion
     * @param environmentName
     * @param isUnixNode
     */
    protected static final void importFabricAppServiceConfig(
        script,
        fabricCliPath,
        cloudCredentialsID,
        cloudAccountId,
        fabricAppConfigJsonFile,
        fabricAppName,
        fabricAppVersion,
        environmentName,
        isUnixNode) {

        def importAppConfigCmdOptions = [
            '-t': "\"$cloudAccountId\"",
            '-f': "\"${fabricAppConfigJsonFile}\"",
            '-a': "\"$fabricAppName\"",
            '-v': "\"$fabricAppVersion\"",
            '-e': "\"$environmentName\""
        ]
        
        fabricCli(script, 'import-config', cloudCredentialsID, isUnixNode, fabricCliPath, importAppConfigCmdOptions)
    }
    
    /**
     * Get the Fabric Server version through mfcli healthcheck command.
     * @param script
     * @param fabricCliPath
     * @param fabricCredentialsID
     * @param cloudAccountId
     * @param fabricEnvironmentName
     * @param isUnixNode
     */
    protected static void fetchFabricServerVersion(script, fabricCliPath, fabricCredentialsID, cloudAccountId, fabricEnvironmentName, isUnixNode) {
        def fabricServerVersion
        def fabServerVersionFromHealthCheck = ""
        def fabrichealthCheck = runFabricHealthCheck(script, fabricCliPath, fabricCredentialsID, cloudAccountId, fabricEnvironmentName, isUnixNode)
        Map<String,String> fabricHealthCheckMap = new LinkedHashMap<String,String>();
        fabrichealthCheck.split("\n").each { outputLine ->
            if(outputLine.contains(":")) {
                fabricHealthCheckMap.put(outputLine.split(":").first().trim(), outputLine.split(":").last().trim());
            }
        }
        if(!fabricHealthCheckMap.containsKey("Version"))
            throw new AppFactoryException('Failed to get the Fabric Server version! It seems something wrong with health check of Fabric environment.', 'ERROR')
        
        fabServerVersionFromHealthCheck = fabricHealthCheckMap.get("Version")
        /* The DEV build convention of the version is slightly different. So in few of lower Fabric environments, 
         * Fabric Server Version value can be as: '9.2.0.71_DEV' but in actual we have to take value as : '9.2.0.71' */
        if(fabServerVersionFromHealthCheck.contains("_")) {
            fabricServerVersion = fabServerVersionFromHealthCheck.split("_").first()
        } else {
            fabricServerVersion = fabServerVersionFromHealthCheck
        }
        script.env['fabricServerVersion'] = fabricServerVersion
        script.echoCustom("From the given Fabric App Config, found the Fabric Server version: ${fabServerVersionFromHealthCheck}", 'INFO')
    }

}
