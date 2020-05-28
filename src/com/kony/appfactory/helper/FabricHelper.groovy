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
                if(!script.env.CONSOLE_URL.matches("https://manage.${script.env.CLOUD_DOMAIN}"))
                {
                    fabricCommandOptions.remove('-t')
                    fabricCommandOptions << ['-cu': "\"${script.env.CONSOLE_URL}\"",
                                             '-au': "\"${script.env.IDENTITY_URL}\"",]
                }
                else {
                    // Adding the cloud type only for cloud domains other than kony.com
                    if (script.env.CLOUD_DOMAIN && script.env.CLOUD_DOMAIN.indexOf("-kony.com") > 0){
                        def domainParam = script.env.CLOUD_DOMAIN.substring(0, script.env.CLOUD_DOMAIN.indexOf("-kony.com")+1)
                        fabricCommandOptions['--cloud-type'] = "\"${domainParam}\""
                    }
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
                
               commandOutput = script.shellCustom(shellString, isUnixNode, args)
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
        url.tokenize('/')?.last()?.replaceAll('.git', '')
    }
    
    /**
     * Run maven build for pom.xml files in application's code.
     * @param script a pipeline object.
     * @param isUnixNode environment os info
     */
    private static runMavenBuild(script, boolean isUnixNode, mavenBuildCommand) {
        String successMessage = 'Successfully built Maven project!'
        String errorMessage = 'Failed to build Maven project!'

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
                    def subDirsWithSeparator = script.shellCustom('ls -d */', isUnixNode, [returnStdout:true])
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
}
