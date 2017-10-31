package com.kony.appfactory.helper

import groovy.json.JsonOutput
import groovy.text.SimpleTemplateEngine

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
    protected static final void sendEmail(script, templateType, templateData = [:], storeBody = false) {
        /* Check required arguments */
        (script) ?: script.error("script argument can't be null")
        (templateType) ?: script.error("templateType argument can't be null")

        /* Get data for e-mail notification */
        Map emailData = getEmailData(script, templateType, templateData)

        /* Store e-mail body on workspace, for publishing on S3, if storeBody flag set to true */
        if (storeBody) {
            storeEmailBody(script, emailData.body, templateType, templateData)
        }

        script.catchErrorCustom('Failed to send e-mail!') {
            /* Send e-mail notification with provided values */
            script.emailext body: emailData.body, subject: emailData.subject, to: emailData.recipients
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
    private static Map getEmailData(script, templateType, templateData) {
        /* Check required arguments */
        (script) ?: script.error("script argument can't be null")
        (templateType) ?: script.error("templateType argument can't be null")

        /* Location of the base template */
        String templatesFolder = 'com/kony/appfactory/email/templates'
        /* Name of the base template */
        String baseTemplateName = 'KonyBase.template'
        /*
            Recipients list, by default will be used values from RECIPIENTS_LIST build parameter,
            if it's empty, than DEFAULT_RECIPIENTS value from global Jenkins configuration will be used.

            Person who is responsible for provisioning of the environment will have a field for providing value for
            DEFAULT_RECIPIENTS property.
         */
        String recipients = (script.params.RECIPIENTS_LIST?.trim()) ?: '$DEFAULT_RECIPIENTS'
        /*
            Subject of the e-mail, is generated from BUILD_TAG(jenkins-${JOB_NAME}-${BUILD_NUMBER}) environment name
            and result status of the job.
         */
        String subject = "${script.env.BUILD_TAG}-${script.currentBuild.currentResult}"

        /* Load base e-mail template from library resources */
        String baseTemplate = script.loadLibraryResource(templatesFolder + '/' + baseTemplateName)
        /* Get template content depending on templateType(job name) */
        String templateContent = getTemplateContent(script, templateType, templateData)
        /* Populate binding values in the base template */
        String body = populateTemplate(baseTemplate, [title: subject, contentTable: templateContent])

        /* Return e-mail data */
        [body: body, subject: subject, recipients: recipients]
    }

    /**
     * Stores content of the e-mail on workspace.
     *
     * @param script pipeline object.
     * @param body e-mail body with populated values.
     * @param templateType type(job name) that is used for determining files to store.
     * @param templateData job specific data for the e-mail notification.
     */
    private static void storeEmailBody(script, body, templateType, templateData) {
        /* Check required arguments */
        (script) ?: script.error("script argument can't be null")
        (body) ?: script.error("body argument can't be null")
        (templateType) ?: script.error("templateType argument can't be null")

        String buildResult = script.currentBuild.currentResult
        List filesToStore = getFilesToStore(body, buildResult, templateType, templateData)

        /* Iterating through all files that needs to be stored */
        for (fileToStore in filesToStore) {
            /* Store file on workspace */
            script.catchErrorCustom('Failed to store e-mail body!') {
                script.writeFile text: fileToStore.data, file: fileToStore.name
            }

            /* Get sub-folder for S3 path */
            String subFolder = (fileToStore.name.contains('build')) ? 'Builds' : 'Tests'
            /* Publish file on S3 */
            AwsHelper.publishToS3 sourceFileName: fileToStore.name,
                    bucketPath: [subFolder, script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/'),
                    sourceFilePath: script.pwd(), script
        }
    }

    /**
     * Modifies build result for test console.
     *
     * @param buildResult job build result.
     * @return build result suffix for test console, suffix will be injected in build result file name.
     */
    private static String getBuildResultForTestConsole(buildResult) {
        String buildResultForTestConsole

        switch (buildResult) {
            case 'SUCCESS':
                buildResultForTestConsole = '-PASS'
                break
            case 'FAILURE':
                buildResultForTestConsole = '-FAIL'
                break
            case 'UNSTABLE':
                buildResultForTestConsole = '-UNSTABLE'
                break
            default:
                buildResultForTestConsole = ''
                break
        }

        buildResultForTestConsole
    }

    /**
     * Creates list of files that will be stored on workspace and published on S3
     *      depending on a templateType (job name).
     *
     * @param body e-mail body with populated values.
     * @param buildResult job build result.
     * @param templateType type(job name) that is used for determining files to store.
     * @param templateData job specific data for the e-mail notification.
     * @return list with the files that will be stored and published on S3.
     */
    private static List getFilesToStore(body, buildResult, templateType, templateData) {
        List filesToStore = []
        /* Get build result suffix for test console */
        String buildResultForTestConsole = getBuildResultForTestConsole(buildResult)

        switch (templateType) {
            case 'buildVisualizerApp':
                filesToStore.add([name: 'buildResults' + buildResultForTestConsole + '.html', data: body])
                break
            case 'runTests':
                /* Convert test run results to JSON */
                String testRunsToJson = JsonOutput.toJson(templateData.runs)

                /* For test console we are storing both HTML and JSON representation of test results */
                filesToStore.addAll([
                        [name: 'testResults' + buildResultForTestConsole + '.html', data: body],
                        [name: 'testResults' + buildResultForTestConsole + '.json', data: testRunsToJson]
                ])
                break
            default:
                filesToStore
                break
        }

        filesToStore
    }

    /**
     * Generates template content depending on a templateType (job name).
     *
     * @param script pipeline object.
     * @param templateType type(job name) that is used for determining correspondent template content.
     * @param templateData job specific data for the e-mail notification.
     * @return generated template content.
     */
    private static String getTemplateContent(script, templateType, templateData = [:]) {
        String templateContent
        /* Common properties for content */
        Map commonBinding = [
                notificationHeader: "${script.env.BUILD_TAG}-${script.currentBuild.currentResult}",
                triggeredBy       : BuildHelper.getBuildCause(script.currentBuild.rawBuild.getCauses()),
                projectName       : script.env.PROJECT_NAME,
                build             : [
                        duration: script.currentBuild.rawBuild.getTimestampString(),
                        number  : script.currentBuild.number,
                        result  : script.currentBuild.currentResult,
                        url     : script.env.BUILD_URL,
                        started : script.currentBuild.rawBuild.getTime().toLocaleString(),
                        log     : script.currentBuild.rawBuild.getLog(50)
                ]
        ] + templateData

        switch (templateType) {
            case 'buildVisualizerApp':
                templateContent = EmailTemplateHelper.createBuildVisualizerAppContent(commonBinding)
                break
            case 'buildTests':
                templateContent = EmailTemplateHelper.createBuildTestsContent(commonBinding)
                break
            case 'runTests':
                templateContent = EmailTemplateHelper.createRunTestContent(commonBinding)
                break
            case 'Export':
            case 'Import':
            case 'Publish':
                templateContent = EmailTemplateHelper.fabricContent(commonBinding)
                break
            default:
                templateContent = ''
                break
        }

        templateContent
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
}
