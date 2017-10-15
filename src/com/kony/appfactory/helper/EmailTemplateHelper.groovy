package com.kony.appfactory.helper

import groovy.xml.MarkupBuilder

/**
 * Implements logic of creation content for job result notifications.
 * This class uses groovy.xml.MarkupBuilder for creation HTML content for specific job.
 *
 * Because of the logic in methods of this class and Jenkins pipeline plugin requirements,
 * @NonCPS annotation been added for every method.
 */
class EmailTemplateHelper implements Serializable {
    /**
     * Wrapper for creation of HTML content
     */
    private static markupBuilderWrapper = { closure ->
        Writer writer = new StringWriter()
        MarkupBuilder htmlBuilder = new MarkupBuilder(writer)

        /* HTML markup closure call, passing MarkupBuild object to the closure */
        closure(htmlBuilder)

        /* Return created HTML markup as a string */
        writer.toString()
    }

    /**
     * Creates HTML content for buildVisualizerApp job
     *
     * @param binding provides data for HTML
     * @return HTML content as a string
     */
    @NonCPS
    protected static String createBuildVisualizerAppContent(Map binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
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
                        table(style: "width:100%", class: "text-color table-border cell-spacing") {
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
        }
    }

    /**
     * Creates HTML content for runTests job, build of the test binaries part.
     *
     * @param binding provides data for HTML
     * @return HTML content as a string
     */
    @NonCPS
    protected static String createBuildTestsContent(Map binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
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
                        table(style: "width:100%", class: "text-color table-border cell-spacing") {
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
        }
    }

    /**
     * Creates HTML content for runTests job, tests run part.
     *
     * @param binding provides data for HTML
     * @return HTML content as a string
     */
    @NonCPS
    protected static String createRunTestContent(Map binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
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
        }
    }

    /**
     * Creates HTML content for Fabric jobs(Export, Import, Publish).
     *
     * @param binding provides data for HTML
     * @return HTML content as a string
     */
    @NonCPS
    protected static String fabricContent(binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
            htmlBuilder.table(style: "width:100%") {
                tr {
                    td(style: "text-align:center", class: "text-color") {
                        h2 binding.notificationHeader
                    }
                }

                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4 "AppID: ${binding.projectName}"
                    }
                }

                tr {
                    td {
                        table(style: "width:100%;text-align:left", class: "text-color table-border") {
                            thead {
                                tr {
                                    th 'Input Params'
                                    th 'Value'
                                }
                            }
                            tbody {
                                if (binding.triggeredBy) {
                                    tr {
                                        td 'Triggered by'
                                        td(class: "table-value", binding.triggeredBy)
                                    }
                                }
                                if (binding.gitURL) {
                                    tr {
                                        td 'Repository URL'
                                        td {
                                            a(href: binding.gitURL, "${binding.exportRepositoryUrl}")
                                        }
                                    }
                                }
                                if (binding.gitBranch) {
                                    tr {
                                        td 'Repository Branch'
                                        td(class: "table-value", binding.exportRepositoryBranch)
                                    }
                                }
                                if (binding.commitAuthor) {
                                    tr {
                                        td 'Author'
                                        td(class: "table-value", binding.commitAuthor)
                                    }
                                }
                                if (binding.authorEmail) {
                                    tr {
                                        td 'Author Email'
                                        td {
                                            a(href: "mailto:${binding.authorEmail}", "${binding.authorEmail}")
                                        }
                                    }
                                }
                                if (binding.commitMessage) {
                                    tr {
                                        td 'Message'
                                        td(class: "table-value", binding.commitMessage)
                                    }
                                }
                                if (binding.overwriteExisting) {
                                    tr {
                                        td 'Overwrite Existing'
                                        td(class: "table-value", binding.overwriteExisting)
                                    }
                                }
                                if (binding.publishApp) {
                                    tr {
                                        td 'Enable Publish'
                                        td(class: "table-value", binding.publishApp)
                                    }
                                }
                                if (binding.fabricEnvironmentName) {
                                    tr {
                                        td 'Environment'
                                        td(class: "table-value", binding.fabricEnvironmentName)
                                    }
                                }
                            }
                        }
                    }
                }

                tr {
                    td(style: "text-align:left;padding:15px 20px 0", class: "text-color") {
                        h4(style: "margin-bottom:0", "${binding.commandName} Details")
                        p {
                            mkp.yield "${binding.commandName} of Fabric app ${binding.projectName} is: "
                            strong binding.build.result
                            mkp.yield '.'
                        }
                    }
                }
            }
        }
    }
}
