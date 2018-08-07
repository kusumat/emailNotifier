package com.kony.appfactory.helper

/**
 * Implements build parameters and environment variables validation.
 */
class ValidationHelper implements Serializable {
    /**
     * Validate build parameters and environment variables.
     *
     * @param script pipeline object.
     * @param [parametersToCheck] parameters that need to be validated.
     */
    protected static void checkBuildConfiguration(script, parametersToCheck = [], eitherOrParameters = []) {
        /* List of the parameters that every channel job requires */
        def commonRequiredParams = [
                'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_SOURCE_CODE_BRANCH', 'BUILD_MODE',
                'CLOUD_CREDENTIALS_ID', 'PROJECT_NAME', 'PROJECT_SOURCE_CODE_URL', 'BUILD_NUMBER'
        ]
        /*
            List of the required parameters.
            Please note, that without second(parametersToCheck) argument call - commonRequiredParams list will be used
         */
        def requiredParams = (parametersToCheck) ?: commonRequiredParams
        /* Collect(filter) build parameters and environment variables to check */
        def buildConfiguration = collectEnvironmentVariables(script.env, requiredParams)
        /* Check if there are empty parameters among required parameters */
        def emptyParams = checkForNull(buildConfiguration)

        /* If there are empty parameters */
        if (emptyParams) {
            String message = 'parameter' + ((emptyParams.size() > 1) ? 's' : '')
            String errorMessage = [emptyParams.keySet().join(', '), message, "can't be null!"].join(' ')
            /* Break the build and print all empty parameters */
            script.echoCustom(errorMessage,'ERROR')
        }

        if(eitherOrParameters.size() > 0) {
            eitherOrParameters.each{ paramSet ->
                (script.params[paramSet[0]]?.trim() != "" ^ script.params[paramSet[1]]?.trim() != "") ?: script.echoCustom("Only one of parameters must be selected from ${paramSet[0]}, ${paramSet[1]}.",'ERROR')
            }
        }
        
        /* Validate required parameters */
        def notValidPrams = checkIfValid(buildConfiguration)
        /* If there are not valid parameters */
        if (notValidPrams) {
            String errorMessage = (
                    ['Please provide valid values for following parameters:'] + notValidPrams.keySet()
            ).join('\n')
            /* Defining a suggestion message containing error message for each invalid parameter */
            String suggestionMessage = constructSuggestionMessage(notValidPrams)
            /* Forming final error message by adding suggestion message */
            errorMessage = errorMessage + '\n' + suggestionMessage
            /* Break the build and print all not valid parameters */
            script.echoCustom(errorMessage,'ERROR')
        }
    }

    /**
     * Filters required parameters.
     *
     * @param environment Map<String, String> build parameters and environment variables.
     * @param requiredParams required parameters list.
     * @return filtered parameters.
     */
    private static collectEnvironmentVariables(environment, requiredParams) {
        requiredParams.collectEntries { param ->
            /*
                Check if we have not set parameters. Because item value is String,
                to be able to check if parameter is null (not set), we need to set it explicitly to null
             */
            def paramValue = (environment.getProperty(param) != 'null') ? environment.getProperty(param) : null
            [(param): paramValue]
        }
    }
    
    /**
     * Validate universal application build parameters
     *
     * @param script pipeline object.
     */
    protected static void checkBuildConfigurationForUniversalApp(script) {
        // Checking if both Native and Universal Channels are selected, if so failing the build.
        if ((script.params.ANDROID_UNIVERSAL_NATIVE && (script.params.ANDROID_MOBILE_NATIVE || script.params.ANDROID_TABLET_NATIVE)) ||
            (script.params.IOS_UNIVERSAL_NATIVE && (script.params.IOS_MOBILE_NATIVE || script.params.IOS_TABLET_NATIVE))) {
            script.echoCustom("Build parameters are invalid, We allow to run either universal app or individual Mobile/Tablet native " +
                "channels build. The universal app just works for both channels, you can skip individual Mobile/Tablet native channel selection.",'ERROR')
        }
    }
    
    /**
     * Checks if any of the parameters is empty.
     *
     * @param items parameters to check for null.
     * @return parameters that have empty values.
     */
    private static checkForNull(items) {
        items?.findAll { !it.value }
    }

    /**
     * Validates values of the required parameters.
     *
     * @param items parameters to validate.
     * @return parameters that have not valid values.
     */
    private static checkIfValid(items) {
        items?.findAll { item ->
            String regex

            switch(item.key) {
                case ['ANDROID_MOBILE_APP_ID', 'ANDROID_TABLET_APP_ID', 'IOS_MOBILE_APP_ID', 'IOS_TABLET_APP_ID']:
                    regex = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+[0-9a-zA-Z_]?$/
                    break
                case ['ANDROID_APP_VERSION', 'IOS_BUNDLE_VERSION', 'SPA_APP_VERSION', 'APP_VERSION', 'WEB_APP_VERSION']:
                    regex = /^(\d+\.)?(\d+\.)?(\*|\d+)$/
                    break
                case 'ANDROID_VERSION_CODE':
                    regex = /^\d+$/
                    break
                case 'CLOUD_ACCOUNT_ID':
                    regex = /^\d{9}$/
                    break
                default:
                    regex = /.*/
                    break
            }

            !(item.value ==~ regex)
        }
    }


    /**
     * Constructs Suggestion message for invalid parameters.
     *
     * @param items invalid parameters to create message for.
     * @return suggestion message to be added.
     */

    private static constructSuggestionMessage(items) {
        String message  = "Please refer the following suggestions : \n"
        items.each {
            String parameter_message
            switch(it.key) {
                case ['ANDROID_MOBILE_APP_ID', 'ANDROID_TABLET_APP_ID']:
                    parameter_message = it.key + ' : ' + 'Expecting something like <domain_name>.<org_name>.<app_name>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> Android -> Package Name".' + '\n' + 'For Example : com.konyappfactory.KitchenSink'
                    break
                case ['IOS_MOBILE_APP_ID', 'IOS_TABLET_APP_ID']:
                    parameter_message = it.key + ' : ' + 'Expecting something like <domain_name>.<org_name>.<app_name>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> iPhone/iPad/Watch -> Bundle Identifier".' + '\n' + 'For Example : com.konyappfactory.KitchenSink'
                    break
                case ['ANDROID_APP_VERSION', 'SPA_APP_VERSION', 'APP_VERSION', 'WEB_APP_VERSION']:
                    parameter_message = it.key + ' : ' + 'Expecting standard versioning format like <major>.<minor>.<patch>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Application -> Version".' + '\n' + 'For Example : 1.0.1 '
                    break
                case ['IOS_BUNDLE_VERSION']:
                    parameter_message = it.key + ' : ' + 'Expecting standard versioning format like <major>.<minor>.<patch>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> iPhone/iPad/Watch -> Bundle Version".' + '\n' + 'For Example : 1.0.1 '
                    break
                case 'ANDROID_VERSION_CODE':
                    parameter_message = it.key + ' : ' + 'Expecting an <integer_value>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> Android -> Version Code".' + '\n' + 'For Example : 1'
                    break
                case 'CLOUD_ACCOUNT_ID':
                    parameter_message = it.key + ' : ' + 'You can find this value by logging in to Fabric Cloud. Expecting a nine digit <integer_value>' + '\n' + 'For Example : 100000011 '
                    break
                default:
                    parameter_message = parameter_message = it.key + ' : ' + 'The parameter expects a string value.'
                    break
            }

            message = message + parameter_message + '\n'
        }
        return message
    }
    
    /**
     * Compare two visualizer versions
     *
     * @param two visualizerVersions (eg. 8.0.0, 8.2.0)
     * @return
     *      0 if two versions are equal
     *      1 if first parameter version is higher than second parameter version
     *      -1 if first parameter version is lower than second parameter version
     */
    protected static final int compareVisualizerVersions(String visualizerVersion1, String visualizerVersion2) {
        List<String> verA = visualizerVersion1.tokenize('.')
        List<String> verB = visualizerVersion2.tokenize('.')
        def commonIndices = Math.min(verA.size(), verB.size())
        for (int i = 0; i < commonIndices; ++i) {
            def numA = verA[i].toInteger()
            def numB = verB[i].toInteger()
            if (numA != numB) {
                /* compareTo two indices, return result (1 or -1) */
                return numA <=> numB
            }
        }
        /* If we got this far then all the common indices are identical */
        verA.size() <=> verB.size()
    }
    
    /**
     * Validates Visualizer CI/Headless build support exists for few of new features.
     * If CI/Headless support not available, fails the build.
     */
    protected static void checkFeatureSupportExist(script, libraryProperties, vizFeaturesSupportToCheck, buildType) {
        vizFeaturesSupportToCheck.each { featureKey, featureProperties ->
            def featureKeyInLowers = featureKey.toLowerCase()
            def featureSupportedVersion = libraryProperties."${featureKeyInLowers}.${buildType}.support.base.version"
            if (ValidationHelper.compareVisualizerVersions(script.env["visualizerVersion"], featureSupportedVersion) == -1) {
                script.echoCustom("Sorry, the ${buildType} build for ${featureProperties.featureDisplayName} is not supported for your Visualizer project " +
                        "version. The minimum supported version is ${featureSupportedVersion}. Please upgrade your project to " +
                        "latest version and build the app.", 'ERROR')
            }
        }
    }
    
}
