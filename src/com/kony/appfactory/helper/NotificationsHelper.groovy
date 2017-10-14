package com.kony.appfactory.helper

import groovy.json.JsonOutput
import groovy.xml.MarkupBuilder

/**
 * Implements logic required for sending emails.
 */
class NotificationsHelper implements Serializable {
    protected static final sendEmail(script, templateType, templateData = [:], storeBody = false) {
        def data = getData(script, templateType, templateData)

        if (storeBody) {
            storeEmailBody(script, templateType, data.body, templateData)
        }

        script.catchErrorCustom('FAILED to send email') {
            script.emailext body: data.body, subject: data.subject, to: data.recipients
        }
    }

    private static storeEmailBody(script, templateType, body, templateData) {
        def buildResult = script.currentBuild.currentResult
        def fileList = getFileNameList(templateType, body, templateData, buildResult)
        for (fileName in fileList) {
            script.writeFile text: fileName.data, file: fileName.name
            def subFolder = (fileName.name.contains('build')) ? 'Builds' : 'Tests'
            AWSHelper.publishToS3 script: script, sourceFileName: fileName.name,
                    bucketPath: [
                            subFolder,
                            script.env.JOB_BASE_NAME,
                            script.env.BUILD_NUMBER
                    ].join('/'),
                    sourceFilePath: script.pwd()
        }
    }

    private static getBuildResultForTestConsole(buildResult) {
        def buildResultForTestConsole

        switch(buildResult) {
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

    private static getData(script, templateType, templateData = [:]) {
        def data = [:]
        def templatesFolder = 'com/kony/appfactory/email/templates'
        def baseTemplateName = 'KonyBase.template'
        def recipients = (script.env.RECIPIENTS_LIST?.trim()) ?: '$DEFAULT_RECIPIENTS'
        def subject = "${script.env.BUILD_TAG}-${script.currentBuild.currentResult}"
        /* Load base email template from library resources */
        def baseTemplate = script.loadLibraryResource(templatesFolder + '/' + baseTemplateName)
        def templateContent = getTemplateContent(script, templateType, templateData)
        def bodyBinding = [title: subject, contentTable: templateContent]
        def body = populateTemplate text: baseTemplate, binding: bodyBinding

        data.body = body
        data.subject = subject
        data.recipients = recipients

        data
    }

    private static getFileNameList(templateType, body, templateData, buildResult) {
        def fileNameList = []
        String buildResultForTestConsole = getBuildResultForTestConsole(buildResult)

        switch (templateType) {
            case 'buildVisualizerApp':
                fileNameList.add([
                        name: 'buildResults' + buildResultForTestConsole + '.html',
                        data: body
                ])
                break
            case 'runTests':
                def runs = templateData.runs
                def jsonString = JsonOutput.toJson(runs)

                fileNameList.add([
                        name: 'testResults' + buildResultForTestConsole + '.html',
                        data: body
                ])

                fileNameList.add([
                        name: 'testResults' + buildResultForTestConsole + '.json',
                        data: jsonString
                ])
                break
            default:
                fileNameList
                break
        }

        fileNameList
    }

    private static getTemplateContent(script, templateType, templateData = [:]) {
        def emailContent
        def commonBinding = [
                notificationHeader: "${script.env.BUILD_TAG}-${script.currentBuild.currentResult}",
                triggeredBy: BuildHelper.getBuildCause(script.currentBuild.rawBuild.getCauses()),
                projectName: script.env.PROJECT_NAME,
                build: [duration: script.currentBuild.rawBuild.getTimestampString(),
                        number  : script.currentBuild.number,
                        result  : script.currentBuild.currentResult,
                        url     : script.env.BUILD_URL,
                        started : script.currentBuild.rawBuild.getTime().toLocaleString(),
                        log: script.currentBuild.rawBuild.getLog(50)
                ]
        ] + templateData

        switch (templateType) {
            case 'buildVisualizerApp':
                emailContent = EmailTemplateHelper.createBuildVisualizerAppContent(commonBinding)
                break
            case 'buildTests':
                emailContent = EmailTemplateHelper.createBuildTestsContent(commonBinding)
                break
            case 'runTests':
                emailContent = EmailTemplateHelper.createRunTestContent(commonBinding)
                break
            case 'Export':
            case 'Import':
            case 'Publish':
                emailContent = EmailTemplateHelper.fabricContent(commonBinding)
                break
            default:
                emailContent
                break
        }

        emailContent
    }

    @NonCPS
    private static populateTemplate(Map args) {
        def binding = args.binding
        def text = args.text

        def engine = new groovy.text.SimpleTemplateEngine()
        def template = engine.createTemplate(text).make(binding)

        return (template) ? template.toString() : null
    }
}
