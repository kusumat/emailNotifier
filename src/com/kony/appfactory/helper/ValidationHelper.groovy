package com.kony.appfactory.helper

/**
 * Implements build parameters and environment variables validation.
 */
class ValidationHelper implements Serializable {
    /**
     * Validate build parameters and environment variables
     * @param script - pipeline object.
     * @param [parametersToCheck] - parameters that need to be validated.
     */
    protected static void checkBuildConfiguration(script, parametersToCheck = []) {
        /* List of the parameters that every channel job requires */
        def commonRequiredParams = [
                'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_SOURCE_CODE_BRANCH', 'BUILD_MODE',
                'CLOUD_CREDENTIALS_ID', 'FABRIC_ENVIRONMENT_NAME', 'PROJECT_NAME', 'PROJECT_GIT_URL', 'BUILD_NUMBER',
                'FORM_FACTOR', 'PROJECT_WORKSPACE'
        ]
        /* List of the build parameters and environment variables to check */
        def buildConfiguration = script.params + script.env.getEnvironment() + script.env.getOverriddenEnvironment()
        /* List of the required parameters, without second(parametersToCheck) argument call -
            commonRequiredParams list will be used */
        def requiredParams = (parametersToCheck) ?: commonRequiredParams
        /* Filter required parameters from all build parameters */
        def filteredParams = filterItems(buildConfiguration, requiredParams)

        /* Check if there are any not set parameters */
        def notSet = checkIfNotSet(filteredParams, requiredParams)
        if (notSet) {
            String message = 'parameter' + ((notSet.size() > 1) ? 's were' : ' was')
            String errorMessage = [notSet.join(', '), message, "not set!"].join(' ')
            /* Break the build and print all empty parameters */
            script.error(errorMessage)
        }

        /* Check if there are empty parameters among required parameters */
        def emptyParams = checkForNull(filteredParams)
        /* If there are empty parameters */
        if (emptyParams) {
            String message = 'parameter' + ((emptyParams.size() > 1) ? 's' : '')
            String errorMessage = [emptyParams.keySet().join(', '), message, "can't be null!"].join(' ')
            /* Break the build and print all empty parameters */
            script.error(errorMessage)
        }

        /* Validate required parameters */
        def notValidPrams = checkIfValid(filteredParams)
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
     * Filter required parameters
     * @param items build configuration (environment variables and build parameters).
     * @param requiredItems required parameter names
     * @return filtered parameters
     */
    private static filterItems(items, requiredItems) {
        items?.subMap(requiredItems)
    }

    /**
     * Check if there is not set parameters
     * @param items build configuration (environment variables and build parameters).
     * @param requiredItems required parameter names
     * @return parameters that have not been set
     */
    private static checkIfNotSet(items, requiredItems) {
        def paramsThatBeenSet = items.keySet()
        /* Get disjunction (opposite of intersection) from required parameters list and parameters
            that been set in the environment, to figure out what parameters were not set. */
        (requiredItems + paramsThatBeenSet) - requiredItems.intersect(paramsThatBeenSet)
    }

    /**
     * Check if any of the parameters is empty
     * @param items parameters to check for null
     * @return parameters that have empty values
     */
    private static checkForNull(items) {
        items?.findAll { !it.value }
    }

    /**
     * Validates values of the required parameters
     * @param items parameters to validate
     * @return parameters that have not valid values
     */
    private static checkIfValid(items) {
        items?.findAll { item ->
            String regex

            switch(item.key) {
                case 'ANDROID_MOBILE_APP_ID':
                case 'ANDROID_TABLET_APP_ID':
                case 'IOS_MOBILE_APP_ID':
                case 'IOS_TABLET_APP_ID':
                    regex = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+[0-9a-zA-Z_]?$/
                    break
                case 'ANDROID_VERSION':
                case 'IOS_BUNDLE_VERSION':
                    regex = /^(\d+\.)?(\d+\.)?(\*|\d+)$/
                    break
                case 'ANDROID_VERSION_CODE':
                    regex = /^\d+$/
                    break
                default:
                    regex = /.*/
                    break
            }

            !(item.value ==~ regex)
        }
    }
}
