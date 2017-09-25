package com.kony.appfactory.fabric

class Fabric implements Serializable {
    def script
    final fabricCliFileName
    final isUnixNode

    Fabric(script, isUnixNode) {
        this.script = script
        this.isUnixNode = isUnixNode
        this.fabricCliFileName = 'mfcli.jar'
    }

    protected fetchFabricCli(String fabricCliVersion = 'latest') {
        String fabricCliUrl = [
                "http://download.kony.com/onpremise/mobilefabric/mobilefabricCLI",
                fabricCliVersion,
                fabricCliFileName
        ].join('/')

        script.catchErrorCustom("FAILED to fetch Fabric CLI (version: $fabricCliVersion)") {
            script.httpRequest url: fabricCliUrl, outputFile: fabricCliFileName, validResponseCodes: '200'
        }
    }

    protected fabricCli(String fabricCommand, String fabricCredentialsId, fabricCommandOptions = [:]) {
        (fabricCommand) ?: script.error('fabricCommand argument cann\'t be null')
        (fabricCredentialsId) ?: script.error('fabricCredentialsId argument cann\'t be null')

        String successMessage = [fabricCommand.capitalize(), 'finished successfully'].join(' ')
        String errorMessage = ['FAILED to run', fabricCommand, 'command'].join(' ')

        script.catchErrorCustom(successMessage, errorMessage) {
            script.withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : fabricCredentialsId,
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
