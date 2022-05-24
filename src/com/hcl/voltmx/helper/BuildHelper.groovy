package com.hcl.voltmx.helper

import jenkins.model.Jenkins
import groovy.text.SimpleTemplateEngine
/**
 * Implements logic related to channel build process.
 */
class BuildHelper implements Serializable {



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

