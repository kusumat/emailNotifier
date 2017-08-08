package com.kony.appfactory.helper

/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {
    protected static checkoutProject(Map args) {
        def script = args.script
        def projectName = args.projectName
        def gitCredentialsID = args.gitCredentialsID
        def gitURL = args.gitURL
        def gitBranch = args.gitBranch

        script.catchErrorCustom('FAILED to checkout the project') {
            script.checkout(
                    changelog: false,
                    poll: false,
                    scm: getSCMConfiguration(script, projectName, gitCredentialsID, gitURL, gitBranch)
            )
        }
    }

    private static getSCMConfiguration(script, projectName, gitCredentialsID, gitURL, gitBranch) {
        def scm
        def projectInSubfolder = (script.env.PROJECT_IN_SUBFOLDER?.trim()) ? true : false
        def checkoutSubfolder = (projectInSubfolder) ? '.' : projectName

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
                               [credentialsId        : "${gitCredentialsID}",
                                depthOption          : 'infinity',
                                ignoreExternalsOption: true,
                                local                : "${checkoutSubfolder}",
                                remote               : "${gitURL}"]
                       ],
                       workspaceUpdater      : [$class: 'UpdateUpdater']]
                break
            default:
                scm = [$class                           : 'GitSCM',
                       branches                         : [[name: "*/${gitBranch}"]],
                       doGenerateSubmoduleConfigurations: false,
                       extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                            relativeTargetDir: "${checkoutSubfolder}"]],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: "${gitCredentialsID}",
                                                            url          : "${gitURL}"]]]
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
}