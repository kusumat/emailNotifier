package com.hcl.voltmx.helper

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import com.hcl.voltmx.helper.BuildHelper

/**
 * Implements logic required for sending notifications.
 */
class NotificationsHelper implements Serializable {
    /**
     * Main method for sending e-mail notifications with job results.
     *
     * @param script pipeline object.
     * @param templateType type(job name) that is used for determining correspondent template properties.
     * @param templateData job specific data for the e-mail notification.
     * @param storeBody flag that used for storing content of the e-mail on workspace.
     */
    protected static final void sendEmail(script, templateData = [:]) {
        /* Check required arguments */
        (script) ?: script.echoCustom("script argument can't be null",'ERROR')

        /* Get data for e-mail notification */
        Map emailData = getEmailData(script, templateData)

        
        script.catchErrorCustom('Failed to send e-mail!') {
            /* Send e-mail notification with provided values */
            script.emailext body: emailData.body, subject: emailData.subject, to: emailData.recipients
        }
    }

    /**
     * Modifies build result and set it for runtests.
     *
     * @param Json Output of the test results.
     * @param Script pipeline object.
     */
    private static setbuildResult(script, testResultsJson) {
        if ( testResultsJson ) {
            script.echoCustom("Results from AWS Device Farm say: ${testResultsJson[0].result}")
            switch (testResultsJson[0].result) {
                case "FAILED":
                    script.currentBuild.result = "UNSTABLE"
                    break
                case "PASSED":
                    script.currentBuild.result = "SUCCESS"
                    break
                default:
                    script.echoCustom("This will cause build failure. Please check the build notification mail for more details.",'WARN')
                    script.currentBuild.result = "FAILURE"
                    break
            }
        }
        else {
            script.currentBuild.result = "FAILURE"
            script.echoCustom("Received unexpected or no data from AWS!",'WARN')
        }
    }

    /**
     * Generates data for e-mail notification.
     *
     * @param script pipeline object.
     * @param templateType type(job name) that is used for determining correspondent template content.
     * @param templateData job specific data for the e-mail notification.
     * @return e-mail data(body, title, recipients).
     */
    private static Map getEmailData(script, templateData) {
        /* Check required arguments */
        (script) ?: script.echoCustom("script argument can't be null",'ERROR')
        /* Location of the base template */
        String templatesFolder = 'com/hcl/voltmx/email/templates'
        /* Name of the base template */
        String baseTemplateName = 'VoltMXBase.template'
        /*
            Recipients list, by default will be used values from RECIPIENTS_LIST build parameter,
            if it's empty, than DEFAULT_RECIPIENTS value from global Jenkins configuration will be used.

            Person who is responsible for provisioning of the environment will have a field for providing value for
            DEFAULT_RECIPIENTS property.
         */
        String recipients = script.env.RECIPIENTS_LIST
        script.echoCustom("RECIPIENTS_LIST is $recipients",'INFO')
        //String recipients = (script.params.containsKey("RECIPIENTS_LIST")) ? ((script.params.RECIPIENTS_LIST?.trim()) ?: '$DEFAULT_RECIPIENTS') : script.env["RECIPIENTS_LIST"]
        /*
            Subject of the e-mail, is generated from BUILD_TAG(jenkins-${JOB_NAME}-${BUILD_NUMBER}) environment name
            and result status of the job.
         */
        String modifiedBuildTag = script.env.BUILD_TAG.minus("jenkins-");

        String subject = modifiedBuildTag + "-${script.currentBuild.currentResult}"

        /* Load base e-mail template from library resources */
        String baseTemplate = script.loadLibraryResource(templatesFolder + '/' + baseTemplateName)
        /* Get template content depending on templateType(job name) */
        
        String templateContent = getTemplateContent(script, templateData)
        def productName = "${script.env.JOB_NAME}"
        /* Populate binding values in the base template */
        String body = BuildHelper.populateTemplate(baseTemplate, [title: subject, contentTable: templateContent, productName: productName])
        /* Return e-mail data */
        [body: body, subject: subject, recipients: recipients]
    }

    /**
     * Generates template content depending on a templateType (job name).
     *
     * @param script pipeline object.
     * @param templateType type(job name) that is used for determining correspondent template content.
     * @param templateData job specific data for the e-mail notification.
     * @return generated template content.
     */
    private static String getTemplateContent(script, templateData = [:]) {
        String templateContent
        /* Common properties for content */
        String modifiedBuildTag = script.env.BUILD_TAG.minus("jenkins-");
        String branchName = script.params.BRANCH_NAME;
        script.echoCustom("Branch name is : ${branchName}  branchName");
        def filename, msg
        def artifactUrl = script.env.BUILD_URL + "artifact/"
        msg += "\n **Artifacts:**\n"
        script.currentBuild.rawBuild.getArtifacts().each {
            filename = it.getFileName()
            msg += "- [${filename}](${artifactUrl}${it.getFileName()})\n"
        }
        script.echoCustom("artifacts : ${msg} ");

        Map commonBinding = [
                notificationHeader: modifiedBuildTag,
                triggeredBy       : BuildHelper.getBuildCause(script.currentBuild.rawBuild.getCauses()),
                projectName       : script.env.JOB_NAME,
                build             : [
                        duration: script.currentBuild.rawBuild.getTimestampString(),
                        number  : script.currentBuild.number,
                        result  : script.currentBuild.currentResult,
                        url     : script.env.BUILD_URL,
                        branch  : branchName,
                        started : script.currentBuild.rawBuild.getTime().toLocaleString() + ' ' +
                                System.getProperty('user.timezone').toUpperCase(),
                        log     : script.currentBuild.rawBuild.getLog(100),
                        artifact     : msg
                ]
        ] + templateData
        templateContent = EmailTemplateHelper.emailContent(commonBinding)
        templateContent
    }

}
