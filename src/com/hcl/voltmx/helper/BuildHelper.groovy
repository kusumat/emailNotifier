package com.hcl.voltmx.helper

import jenkins.model.Jenkins
import groovy.text.SimpleTemplateEngine

/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {


    /**
     * Returns scm configuration depending on scm type.
     *
     * @param relativeTargetDir path where project should be stored.
     * @param scmCredentialsId credentials that will be used to access the repository.
     * @param scmUrl URL of the repository.
     * @param scmBranch repository branch to clone.
     * @return scm configuration.
     */
    private static getScmConfiguration(relativeTargetDir, scmCredentialsId, scmUrl, scmBranch) {
        def scm

        /*
            There was a request to be able to support different type of source control management systems.
            That is why switch statement below returns different scm configurations depending on scmUrl.
            Please note that svn scm configuration type was not tested yet.
         */
        switch (scmUrl) {
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
                               [credentialsId        : scmCredentialsId,
                                depthOption          : 'infinity',
                                ignoreExternalsOption: true,
                                local                : relativeTargetDir,
                                remote               : scmUrl]
                       ],
                       workspaceUpdater      : [$class: 'UpdateUpdater']]
                break
            default:
                scm = [$class                           : 'GitSCM',
                       branches                         : [[name: scmBranch]],
                       doGenerateSubmoduleConfigurations: false,
                       extensions                       : [[$class           : 'RelativeTargetDirectory',
                                                            relativeTargetDir: relativeTargetDir],
                                                           [$class: 'SubmoduleOption', disableSubmodules: false,
                                                            parentCredentials: true, recursiveSubmodules: true,
                                                            reference: '', timeout: 30, trackingSubmodules: true],
                                                           [$class : 'CloneOption',
                                                            timeout: 30]],
                       submoduleCfg                     : [],
                       userRemoteConfigs                : [[credentialsId: scmCredentialsId,
                                                            url          : scmUrl]]]
                break
        }

        scm
    }
/**
 * Clones project source based on checkoutType. If checkoutType is scm, clone from the provided git repository, If
 * checkoutType is downloadzip, clones project source from the non-protected zip file download URL. If the checkout Type
 * is s3download, downloads the project source from the s3 bucket associated with the appfactory instance.
 *
 * @param args for checkoutType scm
 *   script pipeline object.
 *   checkoutType - scm for cloning through git, downloadzip for direct download source from URL
 *   relativeTargetDir path where project should be stored.
 *   scmCredentialsId credentials that will be used to access the repository.
 *   scmUrl URL of the repository.
 *   scmBranch repository branch to clone.
 *
 * @param args for checkoutType downloadzip
 *   script pipeline object.
 *   downloadURL from which the source project should be downloaded
 *   relativeTargetDir path where project should be stored.
 *
 * @param args for checkoutType s3download
 *   script pipeline object.
 *   projectFileName name of the file which is in the s3 path.
 *   filePath path of the file that need to be downloaded from s3 bucket associated with this appfactory instance.
 */
    protected static void checkoutProject(Map args) {
        def script = args.script
        def scmVars = null
        def scmMeta = [:]
        String relativeTargetDir = args.projectRelativePath
            String scmCredentialsId = args.scmCredentialsId
            String scmUrl = args.scmUrl
            String scmBranch = args.scmBranch
        script.echoCustom("scmBranch  is $scmBranch",'INFO')
            script.catchErrorCustom('Failed to checkout the project') {
                // re-try 3 times in case code checkout fails due to various reasons.
                script.retry(3) {
                    scmVars = script.checkout(
                            changelog: true,
                            poll: false,
                            scm: getScmConfiguration(relativeTargetDir, scmCredentialsId, scmUrl, scmBranch)
                    )
                }
            }
         //   scmMeta = getScmDetails(script, scmVars)
        script.echoCustom("$scmMeta",'INFO')
        scmMeta
    }

    private static getScmInfo(script, scmVars) {

        def sourceCodeBranchParamName = scmVars.GIT_BRANCH - 'origin/'
        script.echoCustom("")
        def branch = scmVars.GIT_BRANCH
        script.echoCustom("sourceCodeBranchParamName  is $sourceCodeBranchParamName",'INFO')
        List<String> logsList = new ArrayList<String>();
        String COMMIT = scmVars.GIT_COMMIT
                def changeLogSets = script.currentBuild.rawBuild.changeSets
                for (entries in changeLogSets) {
                    for (entry in entries) {
                        for (file in entry.affectedFiles) {
                            if(COMMIT.equals(entry.commitId)) {
                                logsList.add(file.editType.name + ": " + file.path)
                                script.echoCustom("file path $file.path", 'INFO')
                            }
                        }
                    }
                }
//
        if(logsList.size() == 0)
            logsList.add("No changes")
        return [commitID: scmVars.GIT_COMMIT, scmUrl: scmVars.GIT_URL, commitLogs: logsList]
    }


    protected static void prepareScmDetails(Map args) {
        args.script.echoCustom("args details $args",'INFO')
        def script = args.script
        def scmMeta = [:]
        def scmvars  = [:]
        scmvars = args.scmVars
        script.echoCustom("scmvars ${scmvars}",'INFO')
        scmvars.each {key,value -> script.echoCustom("$key,$value")}
        scmMeta = getScmInfo(script, scmvars)
        script.echoCustom("scmmeta ${scmMeta}",'INFO')
        scmMeta
    }
    @NonCPS
    private static getCurrentParamName(script, newParam, oldParam) {
        def paramName = script.params.containsKey(newParam) ? newParam : oldParam
        return paramName
    }
    @NonCPS
    private static getParamNameOrDefaultFromProbableParamList(script, paramList = [], defaultParam) {
        for (param in paramList) {
            if (script.params.containsKey(param)) {
                return param
            }
        }
        return defaultParam
    }
    private static getScmDetails(script, currentBuildBranch, scmVars, scmUrl) {
        def sourceCodeBranchParamName = 'BRANCH_NAME'
        script.echoCustom("sourceCodeBranchParamName  is $sourceCodeBranchParamName",'INFO')
                //getCurrentParamName(script, 'SCM_BRANCH', getCurrentParamName(script, 'PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_EXPORT_BRANCH'))
        List<String> logsList = new ArrayList<String>();
        if (script.currentBuild.getPreviousBuild() == null)
            logsList.add("Previous Build is unavailable, to fetch the diff.")
        else {
            String previousBuildBranch = script.currentBuild.getPreviousBuild().getRawBuild().actions.find { it instanceof ParametersAction }?.parameters.find { it.name == sourceCodeBranchParamName }?.value
            script.echoCustom("previousBuildBranch $previousBuildBranch",'INFO')
            if (!currentBuildBranch.equals(previousBuildBranch))
                logsList.add("Unable to fetch diff, your previous build is on a different branch.")
            else if (script.currentBuild.changeSets.isEmpty())
                logsList.add("No diff is available")
            else {
                def changeLogSets = script.currentBuild.rawBuild.changeSets
                for (entries in changeLogSets) {
                    for (entry in entries) {
                        for (file in entry.affectedFiles) {
                            logsList.add(file.editType.name + ": " + file.path)
                            script.echoCustom("file path $file.path",'INFO')
                        }
                    }
                }
            }
        }
        return [commitID: scmVars.GIT_COMMIT, scmUrl: scmUrl, commitLogs: logsList]
    }

    /**
     * Populates provided binding in template.
     *
     * @param text template with template tags.
     * @param binding values to populate, key of the value should match to the key in template (text argument).
     * @return populated template.
     */
    @NonCPS
    private static String populateTemplate(text, binding) {
        SimpleTemplateEngine engine = new SimpleTemplateEngine()
        Writable template = engine.createTemplate(text).make(binding)

        (template) ? template.toString() : null
    }


//    @NonCPS
//    protected static String getBranchName(){
//      return ${BRANCH_NAME}
//    }

    /**
     * Gets the root cause of the build.
     * There several build causes in Jenkins, most of them been covered by this method.
     *
     * @param cause object of the cause.
     * @return string with the human-readable form of the cause.
     */
    @NonCPS
    private static getRootCause(cause) {
        def causedBy

        switch (cause.class.toString()) {
            case ~/^.*UserIdCause.*$/:
                causedBy = cause.getUserId()
                break
            case ~/^.*SCMTriggerCause.*$/:
                causedBy = "SCM"
                break
            case ~/^.*TimerTriggerCause.*$/:
                causedBy = "CRON"
                break
            case ~/^.*GitHubPRCause.*$/:
                causedBy = "GitHub Pullrequest"
                break
            case ~/^.*GitHubPushCause.*$/:
                causedBy = "GitHub Hook"
                break
            case ~/^.*UpstreamCause.*$/:
            case ~/^.*RebuildCause.*$/:
                def upstreamJob = Jenkins.getInstance().getItemByFullName (cause.getUpstreamProject(), hudson.model.Job.class)
                if (upstreamJob) {
                    def upstreamBuild = upstreamJob.getBuildByNumber(cause.getUpstreamBuild())
                    if (upstreamBuild) {
                        for (upstreamCause in upstreamBuild.getCauses()) {
                            causedBy = getRootCause(upstreamCause)
                            if (causedBy)
                                break
                        }
                    }
                }
                break
            default:
                causedBy = ''
                break
        }

        causedBy
    }

    /**
     * Gets the cause of the build and format it to human-readable form.
     *
     * @param buildCauses list of build causes
     * @return string with the human-readable form of the cause.
     */
    @NonCPS
    protected static getBuildCause(buildCauses) {
        def causedBy

        for (cause in buildCauses) {
            causedBy = getRootCause(cause)
        }

        causedBy
    }



}

