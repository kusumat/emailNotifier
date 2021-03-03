package com.kony.appfactory.helper

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ArtifactHelper
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
        (script) ?: script.echoCustom("script argument can't be null",'ERROR')
        (templateType) ?: script.echoCustom("templateType argument can't be null",'ERROR')
        /* If storeBody is true , expecting the tests results from AWS and using them to set build result. */
        if (storeBody && templateData.isNativeAppTestRun) {
            if(!(templateData.deviceruns.isEmpty())) {
                /* Get test results and set build result accordingly */
                /* We are using JsonSlurperClassic instead of JsonSlurper because JsonSlurperClassic will return an object that can be 
                 * serializable, but the object returned by JsonSlurper is not. Jenkins has issues when we use objects that are not serializable. */
                def jsonSlurper = new JsonSlurperClassic()
                String testResultsToText = JsonOutput.toJson(templateData.deviceruns)
                def testResultsToJson = jsonSlurper.parseText(testResultsToText)
                setbuildResult(script, testResultsToJson)
            }
            else {
                script.currentBuild.result = "FAILURE"
            }
        }
        /* Get data for e-mail notification */
        Map emailData = getEmailData(script, templateType, templateData)

        /* Store e-mail body on workspace, for publishing on ArtifactStorage, if storeBody flag set to true */
        if (storeBody) {
            storeEmailBody(script, emailData.body, templateType, templateData)
        }
        
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
    private static Map getEmailData(script, templateType, templateData) {
        /* Check required arguments */
        (script) ?: script.echoCustom("script argument can't be null",'ERROR')
        (templateType) ?: script.echoCustom("templateType argument can't be null",'ERROR')
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
        String recipients = (script.params.containsKey("RECIPIENTS_LIST")) ? ((script.params.RECIPIENTS_LIST?.trim()) ?: '$DEFAULT_RECIPIENTS') : script.env["RECIPIENTS_LIST"]
        /*
            Subject of the e-mail, is generated from BUILD_TAG(jenkins-${JOB_NAME}-${BUILD_NUMBER}) environment name
            and result status of the job.
         */
        String modifiedBuildTag = modifySubjectOfMail(script, templateType, templateData);

        String subject = modifiedBuildTag + "-${script.currentBuild.currentResult}"

        /* Load base e-mail template from library resources */
        String baseTemplate = script.loadLibraryResource(templatesFolder + '/' + baseTemplateName)
        /* Get template content depending on templateType(job name) */
        
        String templateContent = getTemplateContent(script, templateType, templateData)
        def productName = templateType.equals('cloudBuild') ? 'Build Service' : 'App Factory'
        /* Populate binding values in the base template */
        String body = BuildHelper.populateTemplate(baseTemplate, [title: subject, contentTable: templateContent, productName: productName])
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
        (script) ?: script.echoCustom("script argument can't be null",'ERROR')
        (body) ?: script.echoCustom("body argument can't be null",'ERROR')
        (templateType) ?: script.echoCustom("templateType argument can't be null",'ERROR')

        String buildResult = script.currentBuild.currentResult
        List filesToStore = getFilesToStore(body, buildResult, templateType, templateData)

        /* Iterating through all files that needs to be stored */
        for (fileToStore in filesToStore) {
            /* Store file on workspace */
            script.catchErrorCustom('Failed to store e-mail body!') {
                script.writeFile text: fileToStore.data, file: fileToStore.name
            }

            /* Get sub-folder for ArtifactStorage path */
            String subFolder = (fileToStore.name.contains('build')) ? 'Builds' : 'Tests'
            /* Get build-Number for ArtifactStorage path */
            String baseJobBuildNumber = templateData.buildNumber ?: script.env.BUILD_NUMBER
            String baseJobName = templateData.jobName ?: script.env.JOB_BASE_NAME
            /* Publish file on ArtifactStorage */
            ArtifactHelper.publishArtifact sourceFileName: fileToStore.name,
                    destinationPath: [subFolder, baseJobName, baseJobBuildNumber].join('/'),
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
            case 'ABORTED':
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
     * Creates list of files that will be stored on workspace and published on ArtifactStorage
     *      depending on a templateType (job name).
     *
     * @param body e-mail body with populated values.
     * @param buildResult job build result.
     * @param templateType type(job name) that is used for determining files to store.
     * @param templateData job specific data for the e-mail notification.
     * @return list with the files that will be stored and published on ArtifactStorage.
     */
    private static List getFilesToStore(body, buildResult, templateType, templateData) {
        List filesToStore = []
        /* Get build result suffix for test console */
        String buildResultForTestConsole = getBuildResultForTestConsole(buildResult)

        switch (templateType) {
            case 'cloudBuild':
            case 'buildVisualizerApp':
            case 'fabricBuild':
                filesToStore.add([name: 'buildResults' + buildResultForTestConsole + '.html', data: body])
                break
            case 'runTests':
                /* Convert test run results to JSON */
                String testRunsToJson = JsonOutput.toJson(templateData.deviceruns)
                String testDesktopRunsToJson
                if (templateData.isJasmineEnabled)
                    testDesktopRunsToJson = JsonOutput.toJson(templateData.jasmineruns)
                else
                    testDesktopRunsToJson = JsonOutput.toJson(templateData.desktopruns)

                /* For test console we are storing both HTML and JSON representation of test results */
                if(templateData.isDesktopWebAppTestRun){
                    filesToStore.addAll([
                            [name: 'testResults-'+ templateData.runWebTestsChannelName + buildResultForTestConsole + '.html', data: body],
                            [name: 'testResults-'+ templateData.runWebTestsChannelName + buildResultForTestConsole + '.json', data: testDesktopRunsToJson]
                    ])
                }
                if(templateData.isNativeAppTestRun){
                    filesToStore.addAll([
                            [name: 'testResults-Native' + buildResultForTestConsole + '.html', data: body],
                            [name: 'testResults-Native' + buildResultForTestConsole + '.json', data: testRunsToJson]
                    ])
                }
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
        String modifiedBuildTag = modifySubjectOfMail(script, templateType, templateData)
        Map commonBinding = [
                notificationHeader: modifiedBuildTag,
                triggeredBy       : BuildHelper.getBuildCause(script.currentBuild.rawBuild.getCauses()),
                projectName       : script.env.PROJECT_NAME,
                build             : [
                        duration: script.currentBuild.rawBuild.getTimestampString(),
                        number  : script.currentBuild.number,
                        result  : script.currentBuild.currentResult,
                        url     : script.env.BUILD_URL,

                        started : script.currentBuild.rawBuild.getTime().toLocaleString() + ' ' +
                                System.getProperty('user.timezone').toUpperCase(),
                        log     : script.currentBuild.rawBuild.getLog(100),
                        mode    : script.env.BUILD_MODE
                ]
        ] + templateData

        // Adding specifc DesktopWeb Channel binding keys
        if (script.params.BUILD_MODE == 'release-protected' && script.params.containsKey("PROTECTION_LEVEL")) {
            commonBinding.build += [protectionlevel : script.env.PROTECTION_LEVEL]
        }

        switch (templateType) {
            case 'buildVisualizerApp':
            case 'cloudBuild':
                templateContent = EmailTemplateHelper.createBuildVisualizerAppContent(commonBinding, templateType)
                break
            case 'runTests':
                templateContent = EmailTemplateHelper.createRunTestContent(commonBinding)
                break
            case 'fabric':
                templateContent = EmailTemplateHelper.fabricContent(commonBinding)
                break
            case 'fabricBuild':
                templateContent = EmailTemplateHelper.fabricBuildContent(commonBinding, templateType)
                break
            case 'flyway':
                templateContent = EmailTemplateHelper.createFlywayContent(commonBinding)
                break
            default:
                templateContent = ''
                break
        }

        templateContent
    }

    private static String modifySubjectOfMail(script, templateType, templateData) {
        String modifiedBuildTag = script.env.BUILD_TAG.minus("jenkins-");
        switch (templateType) {
            case 'buildVisualizerApp':
                modifiedBuildTag = (((modifiedBuildTag.minus("-Visualizer")).minus("s-buildVisualizerApp")).minus("s-Channels")).replaceAll("-build", "-")
                if (modifiedBuildTag.contains("Android"))
                    return modifiedBuildTag.replace("Android", "Android-$script.env.FORM_FACTOR")
                if (modifiedBuildTag.contains("Ios"))
                    return modifiedBuildTag.replace("Ios", "Ios-${script.env.FORM_FACTOR}")
                break
            case 'runTests':
                modifiedBuildTag = (modifiedBuildTag.minus("-Visualizer")).minus("-runTests")
                /* To maintain backward compatibility, we are checking whether 'Channels' folder is present under 'Tests' folder or not and then modifying the subject of mail accordingly */
                if (script.env.JOB_NAME.contains("Tests/Channels/")) {
                    modifiedBuildTag = modifiedBuildTag.minus("Tests-Channels-run")
                } else {
                    if (templateData.isDesktopWebAppTestRun) {
                        modifiedBuildTag = modifiedBuildTag.replace("-Tests", "-DesktopWebTests")
                    }
                    if (templateData.isNativeAppTestRun) {
                        modifiedBuildTag = modifiedBuildTag.replace("-Tests", "-NativeTests")
                    }
                }

                if (templateData.isSummaryEmail)
                    modifiedBuildTag = modifiedBuildTag.replace("-DesktopWebTests", "-Tests").replace("-NativeTests", "-Tests")
                break
            case 'cloudBuild':
                modifiedBuildTag = "${script.env.PROJECT_NAME}-Build Service"
                break
            case 'fabricBuild':
                modifiedBuildTag = "${script.env.PROJECT_NAME}-Fabric Build-${script.env.BUILD_NUMBER}"
                break
            case 'fabric':
                break
            case 'flyway':
                modifiedBuildTag = "${script.env.PROJECT_NAME}-Flyway Build-${script.env.BUILD_NUMBER}"
                break
            default:
                modifiedBuildTag = ''
                break
        }
        return modifiedBuildTag
    }
}
