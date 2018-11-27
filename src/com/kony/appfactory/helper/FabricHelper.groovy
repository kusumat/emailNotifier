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
     * @param script Build script object.
     * @param fabricCommand command name.
     * @param cloudCredentialsID Kony Cloud credentials Id in Jenkins credentials store.
     * @param isUnixNode UNIX node flag.
     * @param fabricCliFileName fabric cli file name that need to executed.
     * @param fabricCommandOptions options for Fabric command.
     * @param args to shellCustom to return status and command output
     *
     * @return returnStatus/returnStdout
     */
    protected static final String fabricCli(script, fabricCommand, cloudCredentialsID, isUnixNode, fabricCliFileName, fabricCommandOptions = [:], args = [:]) {
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

                script.shellCustom(shellString, isUnixNode, args)
            }
        }
    }


}
