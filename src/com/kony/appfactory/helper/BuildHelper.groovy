package com.kony.appfactory.helper

/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {
    protected static checkoutProject(Map args) {
        def script = args.script
        String relativeTargetDir = args.projectRelativePath
        String gitCredentialsID = args.gitCredentialsID
        String gitURL = args.gitURL
        String gitBranch = args.gitBranch

        script.catchErrorCustom('FAILED to checkout the project') {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: getSCMConfiguration(relativeTargetDir, gitCredentialsID, gitURL, gitBranch)
            )
        }
    }

    private static getSCMConfiguration(relativeTargetDir, gitCredentialsID, gitURL, gitBranch) {
        def scm

        switch (gitURL) {
            case ~/^.*svn.*$/:
                scm = [$class                : 'SubversionSCM',
                       additionalCredentials : [],
                       excludedCommitMessages: '',
                       excludedRegions       : '',
                       excludedRevprop       : '',
                       excludedUsers         : '',
                       filterChangelog       : false,
                       ignoreDirPropChanges  : false,
                       includedRegions       : '',
                       locations             : [
                               [credentialsId        : gitCredentialsID,
                                depthOption          : 'infinity',
                                ignoreExternalsOption: true,
                                local                : relativeTargetDir,
                                remote               : gitURL]
                       ],
                       workspaceUpdater      : [$class: 'UpdateUpdater']]
                break
            default:
                scm = [$class                           : 'GitSCM',
                       branches                         : [[name: gitBranch]],
                       doGenerateSubmoduleConfigurations: false,
                       extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                            relativeTargetDir: relativeTargetDir],
                                                           [$class: 'WipeWorkspace']],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: gitCredentialsID,
                                                            url          : gitURL]]]
                break
        }

        scm
    }

    @NonCPS
    private static getRootCause(cause) {
        def causedBy

        if (cause.class.toString().contains('UpstreamCause')) {
            for (upCause in cause.upstreamCauses) {
                causedBy = getRootCause(upCause)
            }
        } else {
            switch (cause.class.toString()) {
                case ~/^.*UserIdCause.*$/:
                    causedBy = cause.getUserName()
                    break
                case ~/^.*SCMTriggerCause.*$/:
                    causedBy = 'SCM'
                    break
                case ~/^.*TimerTriggerCause.*$/:
                    causedBy = 'CRON'
                    break
                case ~/^.*GitHubPushCause.*$/:
                    causedBy = 'GitHub hook'
                    break
                default:
                    causedBy = ''
                    break
            }
        }

        causedBy
    }

    @NonCPS
    protected static getBuildCause(buildCauses) {
        def causedBy

        for (cause in buildCauses) {
            causedBy = getRootCause(cause)
        }

        causedBy
    }

    protected static void checkBuildConfiguration(script, channelSpecificRequiredParams = [], parametersToCheck = [:]) {
        def commonRequiredParams = ['PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID', 'PROJECT_SOURCE_CODE_BRANCH',
                                    'BUILD_MODE', 'CLOUD_CREDENTIALS_ID', 'FABRIC_ENVIRONMENT_NAME',
                                    'PROJECT_NAME', 'PROJECT_GIT_URL', 'BUILD_NUMBER', 'FORM_FACTOR', 'PROJECT_WORKSPACE']
        def buildConfiguration = (parametersToCheck) ?:
                script.params + script.env.getEnvironment() + script.env.getOverriddenEnvironment()
        def requiredParams = (channelSpecificRequiredParams) ?: commonRequiredParams
        script.println "Build P: $buildConfiguration"
        script.println "Required P: $requiredParams"
        def filteredParams = filterItems(buildConfiguration, requiredParams)
        script.println "$filteredParams"
        def emptyParams = checkForNull(filteredParams)
        script.println "NOT N: $emptyParams, ${emptyParams.class}"
        if (emptyParams) {
            String message = 'parameter' + ((emptyParams.size() > 1) ? 's' : '')
            String errorMessage = [emptyParams.keySet().join(', '), message, "can't be null!"].join(' ')
            script.error(errorMessage)
        }
        script.println "$filteredParams"
        def notValidPrams = checkIfValid(filteredParams)
        script.println "NOT V: $notValidPrams"
        if (notValidPrams) {
            String errorMessage = (['Please provide valid values for following parameters:'] + notValidPrams.keySet()).join('\n')
            script.error(errorMessage)
        }
    }

    private static filterItems(items, requiredItems) {
        items?.findAll { item -> requiredItems?.contains(item.key) }
    }

    private static checkForNull(items) {
        items?.findAll { !it.value }
    }

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
                default:
                    regex = /.*/
                    break
            }

            !(item.value ==~ regex)
        }
    }

    /*  Workaround for switching Visualizer dependencies */
    /* --------------------------------------------------- START --------------------------------------------------- */
    private final static parseDependenciesFileContent(script, dependenciesFileContent) {
        (dependenciesFileContent) ?: script.error("File content string can't be null")

        def requiredDependencies = null

        script.catchErrorCustom('FAILED to parse Visualizer dependencies file content') {
            requiredDependencies = script.readJSON(text: dependenciesFileContent)?.visualizer.dependencies
        }

        requiredDependencies
    }

    private final static void switchDependencies(script, isUnixNode, dependencyPath, installationPath) {
        (dependencyPath) ?: script.error("Dependency path can't be null!")
        (installationPath) ?: script.error("Installation path can't be null!")

        if (isUnixNode) {
            script.shellCustom(
                    ['rm', '-rf', installationPath, '&&', 'ln', '-s', dependencyPath, installationPath].join(' '),
                    isUnixNode
            )
        } else {
            script.shellCustom(
                    ['rmdir', '/s', '/q', installationPath, '&&', 'mklink', '/J', installationPath, dependencyPath].join(' '),
                    isUnixNode
            )
        }
    }

    private final static fetchRequiredDependencies(script, visualizerVersion) {
        (visualizerVersion) ?: script.error("Visualizer version couldn't be null!")

        def dependenciesArchive = null
        def dependenciesFileName = 'externaldependencies.json'
        def dependenciesBaseURL = "http://download.kony.com/visualizer_enterprise/citools"
        def dependenciesArchiveFileName = 'visualizer-ci-tool-' + visualizerVersion + '.' + 'zip'
        def dependenciesURL = [
                dependenciesBaseURL, visualizerVersion, dependenciesArchiveFileName
        ].join('/')

        script.catchErrorCustom('FAILED to fetch Visualizer dependencies file!') {
            script.httpRequest url: dependenciesURL, acceptType: 'APPLICATION_ZIP', contentType: 'APPLICATION_ZIP',
                    outputFile: dependenciesArchiveFileName, validResponseCodes: '200'
        }

        script.catchErrorCustom('FAILED to unzip Visualizer dependencies file!') {
            dependenciesArchive = script.unzip zipFile: dependenciesArchiveFileName, read: true
        }

        dependenciesArchive?."$dependenciesFileName"
    }

    protected final static getVisualizerDependencies(script, isUnixNode, separator, visualizerHome, visualizerVersion) {
        (script) ?: script.error("script argument can't be null!")
        (separator) ?: script.error("separator argument can't be null!")
        (visualizerHome) ?: script.error("visualizerHome argument can't be null!")
        (visualizerVersion) ?: script.error("visualizerVersion argument can't be null!")

        def dependencies = []
        def dependenciesFileContent = fetchRequiredDependencies(script, visualizerVersion)
        def visualizerDependencies = (parseDependenciesFileContent(script, dependenciesFileContent)) ?:
                script.error("Visualizer dependencies object can't be null!")
        def getInstallationPath = { toolPath ->
            ([visualizerHome] + toolPath).join(separator)
        }
        def createDependencyObject = { variableName, homePath ->
            [variableName: variableName, homePath: homePath, binPath: [homePath, 'bin'].join(separator)]
        }
        def getToolPath = {
            script.tool([it.name, it.version].join('-'))
        }

        for (dependency in visualizerDependencies) {
            switch (dependency.name) {
                case 'gradle':
                    def installationPath = getInstallationPath([dependency.name])
                    switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('GRADLE_HOME', installationPath))
                    break
                case 'ant':
                    def installationPath = getInstallationPath(['Ant'])
                    switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('ANT_HOME', installationPath))
                    break
                case 'java':
                    def installationPath

                    if(isUnixNode) {
                        script.shellCustom(['mkdir -p', getInstallationPath(["jdk${dependency.version}.jdk", 'Contents'])].join(' '), isUnixNode)
                        installationPath = getInstallationPath(["jdk${dependency.version}.jdk", 'Contents', 'Home'])
                    } else {
                        installationPath = getInstallationPath(['Java', "jdk${dependency.version}"])
                    }
//                    def installationPath = (isUnixNode) ?
//                            getInstallationPath(["jdk${dependency.version}.jdk", 'Contents', 'Home']) :
//                            getInstallationPath(['Java', "jdk${dependency.version}"])
                    switchDependencies(script, isUnixNode, getToolPath(dependency), installationPath)
                    dependencies.add(createDependencyObject('JAVA_HOME', installationPath))
                    break
                case 'android build tools':
                    def installationPath = [script.env.ANDROID_HOME, 'build-tools', dependency.version].join(separator)
                    dependencies.add(createDependencyObject('ANDROID_BUILD_TOOLS', installationPath))
                default:
                    break
            }
        }

        dependencies
    }
    /* ---------------------------------------------------- END ---------------------------------------------------- */
}