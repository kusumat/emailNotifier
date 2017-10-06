package com.kony.appfactory.fabric

class Fabric implements Serializable {
    private script
    private final fabricCliFileName

    Fabric(script) {
        this.script = script
        this.fabricCliFileName = 'mfcli.jar'
    }

    protected fetchFabricCli(String fabricCliVersion = 'latest') {
        String fabricCliUrl = [
//                "http://download.kony.com/onpremise/mobilefabric/mobilefabricCLI",
                'https://s3-eu-west-1.amazonaws.com/konyappfactorydev-ci0001-storage1/configuration/mobilefabricCLI',
                fabricCliVersion,
                fabricCliFileName
        ].join('/')

        script.catchErrorCustom("FAILED to fetch Fabric CLI (version: $fabricCliVersion)") {
            script.httpRequest url: fabricCliUrl, outputFile: fabricCliFileName, validResponseCodes: '200'
        }
    }

    protected fabricCli(String fabricCommand, String cloudCredentialsID, Boolean isUnixNode, fabricCommandOptions = [:]) {
        (fabricCommand) ?: script.error('fabricCommand argument cann\'t be null')
        (cloudCredentialsID) ?: script.error('cloudCredentialsID argument cann\'t be null')

        String errorMessage = ['FAILED to run', fabricCommand, 'command'].join(' ')

        script.catchErrorCustom(errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : cloudCredentialsID,
                     passwordVariable: 'fabricPassword',
                     usernameVariable: 'fabricUsername']
            ]) {
                String fabricUsername = script.env.fabricUsername
                String fabricPassword = script.env.fabricPassword
                String options = fabricCommandOptions?.collect { option, value ->
                    [option, value].join(' ')
                }.join(' ')
                String shellString = ['java -jar', fabricCliFileName, fabricCommand, '-u', fabricUsername,
                                      '-p', fabricPassword, options].join(' ')

                script.shellCustom(shellString, isUnixNode)
            }
        }
    }
}
