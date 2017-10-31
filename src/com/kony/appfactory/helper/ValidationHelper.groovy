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
    protected static void checkBuildConfiguration(script, parametersToCheck = []) {
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
            script.error(errorMessage)
        }

        /* Validate required parameters */
        def notValidPrams = checkIfValid(buildConfiguration)
        /* If there are not valid parameters */
        if (notValidPrams) {
            String errorMessage = (
                    ['Please provide valid values for following parameters:'] + notValidPrams.keySet()
            ).join('\n')
            /* Break the build and print all not valid parameters */
            script.error(errorMessage)
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
                case ['ANDROID_APP_VERSION', 'IOS_BUNDLE_VERSION', 'SPA_APP_VERSION', 'APP_VERSION']:
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
}
