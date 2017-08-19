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
            def fileList = getFileNameList(templateType, data.body, templateData)
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

        script.catchErrorCustom('FAILED to send email') {
            script.emailext body: data.body, subject: data.subject, to: data.recipients
        }
    }

    private static getData(script, templateType, templateData = [:]) {
        def data = [:]
        def templatesFolder = 'com/kony/appfactory/email/templates'
        def baseTemplateName = 'KonyBase.template'
        def recipients = (script.env.RECIPIENT_LIST?.trim()) ?: '$DEFAULT_RECIPIENTS'
        def subject = "${script.env.BUILD_TAG} - ${script.currentBuild.currentResult}"
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

    private static getFileNameList(templateType, body, templateData) {
        def fileNameList = []

        switch (templateType) {
            case 'buildVisualizerApp':
                fileNameList.add([
                        name: 'buildResults.html',
                        data: body
                ])
                break
            case 'runTests':
                def runs = templateData.runs
                def jsonString = JsonOutput.toJson(runs)

                fileNameList.add([
                        name: 'testResults.html',
                        data: body
                ])

                fileNameList.add([
                        name: 'testResults.json',
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
                notificationHeader: "${script.env.BUILD_TAG} - ${script.currentBuild.currentResult}",
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
                emailContent = createBuildVisualizerAppContent(commonBinding)
                break
            case 'buildTests':
                emailContent = createBuildTestsContent(commonBinding)
                break
            case 'runTests':
                emailContent = createRunTestContent(commonBinding)
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

    @NonCPS
    private static createBuildVisualizerAppContent(binding) {
        Writer writer = new StringWriter()
        MarkupBuilder htmlBuilder = new MarkupBuilder(writer)

        htmlBuilder.table(style: "width:100%") {
            tr {
                td(style: "text-align:center", class: "text-color") {
                    h2 binding.notificationHeader
                }
            }

            tr {
                td(style: "text-align:left", class: "text-color") {
                    h4(class: "subheading", "Build Details")
                }
            }

            tr {
                td {
                    table(style:"width:100%", class: "text-color table-border cell-spacing") {
                        if (binding.triggeredBy) {
                            tr {
                                td(style: "width:22%;text-align:right", 'Triggered by:')
                                td(class: "table-value", binding.triggeredBy)
                            }
                        }

                        tr {
                            td(style: "width:22%;text-align:right", 'Build URL:')
                            td {
                                a(href: binding.build.url, "${binding.build.url}")
                            }
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Project:')
                            td(class: "table-value", binding.projectName)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Build number:')
                            td(class: "table-value", binding.build.number)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Date of build:')
                            td(class: "table-value", binding.build.started)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Build duration:')
                            td(class: "table-value", binding.build.duration)
                        }
                    }
                }
            }

            if (binding.build.result != 'FAILURE') {
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", 'Build Information')
                    }
                }
                tr {
                    td {
                        table(style: "width:100%;text-align:left", class: "text-color table-border") {
                            thead {
                                tr {
                                    th('Installer')
                                    th('URL')
                                }
                            }
                            tbody {
                                for (artifact in binding.artifacts) {
                                    tr {
                                        td(artifact.channelPath.replaceAll('/', ' '))
                                        td {
                                            if (artifact.name) {
                                                a(href: artifact.url, artifact.name)
                                            } else {
                                                mkp.yield 'Build failed'
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                tr {
                    td(style: "text-align:left;padding:15px 20px 0", class: "text-color") {
                        h4(style: "margin-bottom:0", 'Console Output')
                        binding.build.log.each { line ->
                            p(line)
                        }
                    }
                }
            }
        }

        writer.toString()
    }

    @NonCPS
    private static createBuildTestsContent(binding) {
        Writer writer = new StringWriter()
        MarkupBuilder htmlBuilder = new MarkupBuilder(writer)

        htmlBuilder.table(style: "width:100%") {
            tr {
                td(style: "text-align:center", class: "text-color") {
                    h2 binding.notificationHeader
                }
            }

            tr {
                td(style: "text-align:left", class: "text-color") {
                    h4(class: "subheading", "Build Details")
                }
            }

            tr {
                td {
                    table(style:"width:100%", class: "text-color table-border cell-spacing") {
                        if (binding.triggeredBy) {
                            tr {
                                td(style: "width:22%;text-align:right", 'Triggered by:')
                                td(class: "table-value", binding.triggeredBy)
                            }
                        }

                        tr {
                            td(style: "width:22%;text-align:right", 'Build URL:')
                            td {
                                a(href: binding.build.url, "${binding.build.url}")
                            }
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Project:')
                            td(class: "table-value", binding.projectName)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Build number:')
                            td(class: "table-value", binding.build.number)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Date of build:')
                            td(class: "table-value", binding.build.started)
                        }
                        tr {
                            td(style: "width:22%;text-align:right", 'Build duration:')
                            td(class: "table-value", binding.build.duration)
                        }
                    }
                }
            }

            if (binding.build.result == 'SUCCESS') {
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", 'Build Information')
                        p("Build of Test Automation scripts for ${binding.projectName} finished successfully.")
                    }
                }
            } else {
                tr {
                    td(style: "text-align:left;padding:15px 20px 0", class: "text-color") {
                        h4(style: "margin-bottom:0", 'Console Output')
                        binding.build.log.each { line ->
                            p(line)
                        }
                    }
                }
            }
        }

        writer.toString()
    }

    @NonCPS
    private static createRunTestContent(binding) {
        Writer writer = new StringWriter()
        MarkupBuilder htmlBuilder = new MarkupBuilder(writer)

        htmlBuilder.table(style: "width:100%") {
            tr {
                td(style: "text-align:center", class: "text-color") {
                    h2 binding.notificationHeader
                }
            }

            tr {
                td(style: "text-align:left", class: "text-color") {
                    h4 "Project Name: ${binding.projectName}"
                    p { strong "Selected Device Pools: ${binding.devicePoolName}" }
                    p { strong "Devices not available in pool: ${(binding.missingDevices) ?: 'None'}" }
                }
            }

            for (runResult in binding.runs) {
                for (prop in runResult) {
                    if (prop.key == 'device') {
                        def projectArtifactKey = ((prop.value.platform.toLowerCase() + '_' +
                                prop.value.formFactor.toLowerCase()).contains('phone')) ?
                                (prop.value.platform.toLowerCase() + '_' +
                                        prop.value.formFactor.toLowerCase()).replaceAll('phone', 'mobile') :
                                prop.value.platform.toLowerCase() + '_' + prop.value.formFactor.toLowerCase()
                        def binaryName = binding.binaryName.find {
                            it.key.toLowerCase() == projectArtifactKey
                        }.value
                        tr {
                            td {
                                p(style: "margin:30px 0 10px;font-weight:bold", 'Device: ' + prop.value.name +
                                        ', FormFactor: ' + prop.value.formFactor +
                                        ', Platform: ' + prop.value.platform +
                                        ', OS Version: ' + prop.value.os +
                                        ', Binary Name: ' + binaryName)
                            }
                        }
                    }

                    if (prop.key == 'suites') {
                        tr {
                            td {
                                table(style: "width:100%;text-align:left", class: "text-color table-border") {
                                    tr {
                                        th('Name')
                                        th('URL')
                                        th(style: "width:25%", 'Status')
                                    }
                                    for (suite in prop.value) {
                                        tr {
                                            th(colspan: "2", class: "table-value", style: "padding:10px;text-transform:none", 'Suite Name: ' + suite.name)
                                            th(class: "table-value", style: "padding:10px;text-transform:none", 'Total tests: ' + suite.totalTests)
                                        }
                                        for (test in suite.tests) {
                                            tr {
                                                th(colspan: "2", class: "table-value", style: "padding:10px;text-transform:none", 'Test Name: ' + test.name)
                                                th(class: "table-value", style: "padding:10px;text-transform:none", test.result)
                                            }
                                            for (artifact in test.artifacts) {
                                                tr {
                                                    td artifact.name + '.' + artifact.extension
                                                    td {
                                                        a(href: artifact.url, 'Download File')
                                                    }
                                                    td(style: "padding:10px") {
                                                        mkp.yieldUnescaped('&nbsp;')
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        writer.toString()
    }
}

