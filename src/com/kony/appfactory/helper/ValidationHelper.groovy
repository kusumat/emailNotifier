package com.kony.appfactory.helper


/**
 * Implements build parameters and environment variables validation.
 */
class ValidationHelper implements Serializable {
    /**
     * Validate project settings, job build parameters and environment variables.
     *
     * @param script pipeline object.
     * @param [parametersToCheck] parameters that need to be validated.
     * @param [eitherOrParameters] List of parameters where either one among the parameters needs to exist
     */
    protected static void checkBuildConfiguration(script, parametersToCheck = [], eitherOrParameters = []) {
        /* Validate project settings parameters */
        checkProjectSettingsConfiguration(script)
        /*Validate job build parameters and environment variables.*/
        checkParamsConfiguration(script, parametersToCheck, eitherOrParameters)
    }

    /**
     * Validate job build parameters and environment variables.
     *
     * @param script pipeline object.
     * @param [parametersToCheck] parameters that need to be validated.
     * @param [eitherOrParameters] List of parameters where either one among the parameters needs to exist
     */
    protected static void checkParamsConfiguration(script, parametersToCheck = [], eitherOrParameters = []) {

        def fabricCredentialsParamName = BuildHelper.getCurrentParamName(script, 'CLOUD_CREDENTIALS_ID', 'FABRIC_CREDENTIALS_ID')
        /* List of the parameters that every channel job requires */
        def commonRequiredParams
        if(script.params.IS_SOURCE_VISUALIZER) {
            commonRequiredParams = [
                    'BUILD_MODE', 'PROJECT_NAME', 'PROJECT_SOURCE_URL', 'BUILD_STATUS_PATH', 'MF_ACCOUNT_ID', 'MF_TOKEN'
            ]
        }
        else {
            commonRequiredParams = [
                    'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_SOURCE_CODE_BRANCH', 'BUILD_MODE',
                    fabricCredentialsParamName, 'PROJECT_NAME', 'PROJECT_SOURCE_CODE_URL', 'BUILD_NUMBER'
            ]
        }

        /*
            We are checking for the explicit null string check, since in the case of previous (< 8.3) appfactory projects,
            buildviz job is going to send the newly added param value with the null string.
        */
        if(eitherOrParameters.size() > 0) {
            eitherOrParameters.each{ paramSet ->
                boolean isParam1Valid = (script.params.containsKey(paramSet[0]) && script.params[paramSet[0]] != null && script.params[paramSet[0]] != "null" && script.params[paramSet[0]]?.trim() != "")
                boolean isParam2Valid = (script.params.containsKey(paramSet[1]) && script.params[paramSet[1]] != null && script.params[paramSet[1]] != "null" && script.params[paramSet[1]]?.trim() != "")
                if(!(isParam1Valid ^ isParam2Valid)){
                    String errorMessage = "One of the parameters ${paramSet[0]} or ${paramSet[1]} is mandatory. So please choose appropriate parameter."
                    throw new AppFactoryException("${errorMessage}",'ERROR')
                }
            }
        }
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
            throw new AppFactoryException(errorMessage, 'ERROR')
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
            throw new AppFactoryException(errorMessage, 'ERROR')
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
            String errorMessage = "Build parameters are invalid, We allow to run either universal app or individual Mobile/Tablet native " +
                "channels build. The universal app just works for both channels, you can skip individual Mobile/Tablet native channel selection."
            throw new AppFactoryException(errorMessage, 'ERROR')
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
                case ['ANDROID_MOBILE_APP_ID', 'ANDROID_TABLET_APP_ID', 'ANDROID_UNIVERSAL_APP_ID', 'IOS_MOBILE_APP_ID', 'IOS_TABLET_APP_ID', 'IOS_UNIVERSAL_APP_ID']:
                    regex = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+[0-9a-zA-Z_]?$/
                    break
                case ['ANDROID_APP_VERSION', 'IOS_APP_VERSION', 'IOS_BUNDLE_VERSION', 'SPA_APP_VERSION', 'APP_VERSION', 'WEB_APP_VERSION']:
                    regex = /^(\d+\.)(\d+\.)(\*|\d+)$/
                    break
                case 'ANDROID_VERSION_CODE':
                    regex = /^\d+$/
                    break
                case 'CLOUD_ACCOUNT_ID':
                    regex = /^\d{9}$/
                    break
                case 'FABRIC_APP_VERSION':
                    regex = /^[1-9]{1,3}\.[0-9]{1,2}$/
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
                case ['ANDROID_MOBILE_APP_ID', 'ANDROID_TABLET_APP_ID', 'ANDROID_UNIVERSAL_APP_ID']:
                    parameter_message = it.key + ' : ' + 'Expecting something like <domain_name>.<org_name>.<app_name>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> Android -> Package Name".' + '\n' + 'For Example : com.konyappfactory.KitchenSink'
                    break
                case ['IOS_MOBILE_APP_ID', 'IOS_TABLET_APP_ID', 'IOS_UNIVERSAL_APP_ID']:
                    parameter_message = it.key + ' : ' + 'Expecting something like <domain_name>.<org_name>.<app_name>' + '\n' + 'It is the value you generally enter in build UI mode at "Project Settings -> Native -> iPhone/iPad/Watch -> Bundle Identifier".' + '\n' + 'For Example : com.konyappfactory.KitchenSink'
                    break
                case ['ANDROID_APP_VERSION', 'IOS_APP_VERSION', 'SPA_APP_VERSION', 'APP_VERSION', 'WEB_APP_VERSION']:
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
                case 'FABRIC_APP_VERSION':
                    parameter_message = it.key + ' : ' + 'Expecting App Version in the format allowed on Fabric, like <major>.<minor>' + '\n' + 'where major and minor are numeric, and major is between 1 and 999, and  minor is between 0 and 99.' + '\n' + 'For Example.: 1.0 or 999.99'
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
     * Compare two versions
     *
     * @param two versions (eg. 8.0.0, 8.2.0)
     * @return
     *      0 if two versions are equal
     *      1 if first parameter version is higher than second parameter version
     *      -1 if first parameter version is lower than second parameter version
     */
    protected static final int compareVersions(String version1, String version2) {
        List<String> verA = version1.tokenize('.')
        List<String> verB = version2.tokenize('.')
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
            if (ValidationHelper.compareVersions(script.env["visualizerVersion"], featureSupportedVersion) == -1) {
                String errorMessage = "Sorry, the ${buildType} build for ${featureProperties.featureDisplayName} is not supported for your " +
                        "Visualizer project version. The minimum supported version is ${featureSupportedVersion}. Please upgrade your project to " +
                        "latest version and build the app."
                throw new AppFactoryException(errorMessage, "ERROR")
            }
        }
    }
    
    /**
     * Validates the proper string param exists in the build parameters list or not
     *
     * @param script
     * @param paramName
     *
     * @return true/false
     */
    @NonCPS
    protected static isValidStringParam(script, paramName) {
        return (script.params.containsKey(paramName) && script.params[paramName] != "null")
    }
    
    /**
     * Check the parameters for running DesktopWeb tests build
     * @param script pipeline object
     * @param libraryProperties
     */
    protected static void checkBuildConfigurationForDesktopWebTest(script, libraryProperties) {
        if(!script.params.PUBLISH_FABRIC_APP && !script.params.PUBLISH_WEB_APP) {
            throw new AppFactoryException("If you want to run DesktopWeb tests, please make sure to select PUBLISH_WEB_APP/PUBLISH_FABRIC_APP build parameter.", 'ERROR')
        }
    }
    
    /**
     * Check the valid values for a choice parameter from the list of possible values
     * 
     * @param script pipeline object.
     * @param paramName parameters that value need to be validated.
     * @param [expectedValues] the valid possible values for the parameter
     */
    protected static void checkValidValueForParam(script, paramName, expectedValues = [] ) {
        if(script.params.containsKey(paramName)) {
            def currentParamValue = script.params[paramName].trim()
            if(!expectedValues.contains(currentParamValue)) {
                throw new AppFactoryException("Invalid input is given for ${paramName} parameter! Allowed values are : ${expectedValues.join(',')}", 'ERROR')
            }
        }
    }
    
    /**
     * Check valid value passed for given param matching with regex
     * @param script
     * @param paramName
     * @param customMessage
     * @param errorMessage
     * @return throw error message if invalid
     */
    protected static checkValidParamValue(script, paramName, errorMessage, customMessage) {
        //check for null
        def paramValue = script.params[paramName].trim()
        if(!paramValue)
            throw new AppFactoryException("${paramName} param value can't be null! " + "${customMessage}", 'ERROR')
            
        //check for invalid
        def parameterWithValue = [:]
        parameterWithValue.put(paramName, paramValue)
        def notValidPram = checkIfValid(parameterWithValue)
        if(notValidPram) {
            String suggestionMessage = constructSuggestionMessage(notValidPram)
            /* Forming final error message by adding suggestion message */
            errorMessage = errorMessage + '\n' + suggestionMessage
            throw new AppFactoryException(errorMessage, 'ERROR')
        }
    }

    /**
     * Validate project settings parameters.
     *
     * @param script pipeline object.
     */
    private static void checkProjectSettingsConfiguration(script) {
        if (BuildHelper.getProjectVersion(script.env.PROJECT_NAME)) {
            def projectSettingsParamsMap = ["PROJECT_SOURCE_CODE_URL": 'Repository URL', 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID': "SCM Credentials"]
            def emptyProjSettingsParams = []
            /* Collect(filter) build parameters and environment variables to check */
            def buildConfiguration = collectEnvironmentVariables(script.env, projectSettingsParamsMap.keySet())
            /* Check if there are empty parameters among required parameters */
            def emptyParams = checkForNull(buildConfiguration)
            emptyParams.keySet().each { param ->
                emptyProjSettingsParams.add(projectSettingsParamsMap[param])
            }

            /* If there are empty parameters */
            if (emptyParams) {
                String message = 'Project Settings parameter' + ((emptyParams.size() > 1) ? "'s" : '')
                String requiredParamsErrorMessage = [emptyProjSettingsParams.join(', '), message, "can't be null!"].join(' ')

                /* Redirect to project settings page to fill the required params */
                String projectSettingsUrl = script.env.JENKINS_URL + "job/" + script.env.PROJECT_NAME + "/" + "projsettings"
                String errorMessage = requiredParamsErrorMessage + ("\nPlease go to Project Settings to fill the mandatory parameters: ${projectSettingsUrl}")
                /* Break the build and print all empty parameters */
                throw new AppFactoryException(errorMessage, 'ERROR')
            }
        }
    }
}
