package com.kony.appfactory.helper

import groovy.xml.MarkupBuilder
import org.apache.commons.lang.StringUtils

/**
 * Implements logic of creation content for job result notifications.
 * This class uses groovy.xml.MarkupBuilder for creation HTML content for specific job.
 *
 * Because of the logic in methods of this class and Jenkins pipeline plugin requirements,
 * @NonCPS annotation been added for every method.
 */
class EmailTemplateHelper implements Serializable {
    /**
     * Wrapper for creation of HTML content.
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
     * Creates HTML content for buildVisualizerApp job.
     *
     * @param binding provides data for HTML.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static String createBuildVisualizerAppContent(Map binding, templateType) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
            htmlBuilder.table(style: "width:100%") {
                EmailBuilder.addNotificationHeaderRow(htmlBuilder, binding.notificationHeader)
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", "Build Details")
                    }
                }
                tr {
                    td {
                        table(role :"presentation", cellspacing :"0", cellpadding :"0", style: "width:100%", class: "text-color table-border cell-spacing") {
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project:', binding.projectName)
                            if (binding.triggeredBy)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Triggered by:', binding.triggeredBy)

                            if(templateType.equals('buildVisualizerApp')) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project Branch:', binding.projectSourceCodeBranch)
                                EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Build URL:', binding.build.url, binding.build.number)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build number:', "#" + binding.build.number)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build Mode:', binding.build.mode)
                            }

                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Date of build:', binding.build.started)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build duration:', binding.build.duration)

                            if (binding.fabricEnvironmentName)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Fabric Environment Name:', binding.fabricEnvironmentName)
                        }
                    }
                }

                if (binding.build.result == 'FAILURE' && templateType.equals('buildVisualizerApp')) {
                    tr {
                        td(style: "text-align:left;padding:15px 20px 0", class: "text-color") {
                            h4(style: "margin-bottom:0", 'Console Output')
                            binding.build.log.each { line ->
                                p(line)
                            }
                        }
                    }
                } else {
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            h4(class: "subheading", 'Build Information')
                        }
                    }
                    tr {
                        td {
                            if(binding.artifacts != null && !binding.artifacts.isEmpty()) {
                                table(role :"presentation", cellspacing :"0", cellpadding :"0", style: "width:100%;text-align:left", class: "text-color table-border-channels") {
                                    thead(class:"table-border-channels") {
                                        tr {
                                            th(style: "text-align:center", 'CHANNEL')
                                            th(style: "text-align:center", colspan: "2", 'INSTALLER')
                                            th(style: "text-align:center",'APP VERSION')
                                        }
                                    }
                                    tbody(class:"table-border-channels") {
                                        prepareMailBody(htmlBuilder, binding.artifacts, binding.artifactMeta)
                                    }
                                }
                            }
                        }
                    }
                    if (templateType.equals('cloudBuild'))
                        EmailBuilder.addBuildConsoleLogFields(htmlBuilder, [consolelogs: binding.consolelogs])
                }
            }
            if (!(binding.build.result == 'FAILURE') && templateType.equals('buildVisualizerApp')) {
                htmlBuilder.br()
                htmlBuilder.table(style: "width:100%") {
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            h4(class: "subheading", 'Source Code Details')
                        }
                    }
                    tr {
                        td {
                            if (binding.scmMeta != null && !binding.scmMeta.isEmpty()) {
                                table(role: "presentation", cellspacing: "0", cellpadding: "0", style: "width:100%;text-align:left", class: "text-color table-border-channels") {
                                    thead(class: "table-border-channels") {
                                        tr {
                                            th(style: "text-align:center", width: "25%", 'CHANNEL')
                                            th(style: "text-align:center", width: "20%", 'COMMIT ID')
                                            th(style: "text-align:center", width: "55%", 'COMMIT LOGS')
                                        }
                                    }
                                    tbody(class: "table-border-channels") {
                                        EmailBuilder.addScmTableRow(htmlBuilder, binding.scmMeta)
                                    }
                                }
                            } else {
                                p(style: "font-size:14px;", "ERROR!! Failed at checkout or pre-checkout stage. Please refer to the log.")
                            }
                        }
                    }
                }
            }
        }
    }

    @NonCPS
    static void prepareMailBody(htmlBuilder, artifacts, artifactsMeta) {
        for (artifact in artifacts) {
            if (artifact.name) {
                /* iOS */
                if (artifact.otaurl) {
                    def map = [
                            channelPath      : artifact.channelPath,
                            artifacts        : [
                                    [
                                            name     : artifact.name,
                                            url      : artifact.otaurl,
                                            extension: 'OTA',
                                    ],
                                    [
                                            name     : artifact.ipaName,
                                            url      : artifact.ipaAuthUrl,
                                            extension: 'IPA',
                                    ]
                            ]
                    ] + artifactsMeta

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                } else if (artifact.karAuthUrl) {
                    def map = [
                            channelPath      : artifact.channelPath,
                            artifacts        : [
                                    [
                                            //for karAuthUrl, there won't be any ipa.name and ipa.authUrl
                                            extension: 'IPA'
                                    ],
                                    [
                                            name     : artifact.name,
                                            url      : artifact.karAuthUrl,
                                            extension: 'KAR',
                                    ]
                            ]
                    ] + artifactsMeta

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                }
                /* Android */
                else if (artifact.channelPath.toUpperCase().contains('ANDROID')) {
                    def map = [
                            channelPath: artifact.channelPath,
                            artifacts  : artifact.androidArtifacts
                    ] + artifactsMeta

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                }
                /* Web */
                else if (artifact.webAppUrl) {
                    def artifactNameUpperCase = (artifact.name).toUpperCase()
                    def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)
                    def map = [
                            channelPath      : artifact.channelPath,
                            artifacts        : [
                                    [
                                            name     : artifact.name,
                                            url      : artifact.authurl,
                                            extension: artifactExtension,
                                    ],
                                    [
                                            name     : artifact.webAppUrl,
                                            url      : artifact.webAppUrl,
                                            extension: 'APP URL',
                                    ]
                            ]
                    ] + artifactsMeta

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                }
                /* Channels - SPA/DesktopWeb/Web without publish enabled */
                else {
                    def artifactNameUpperCase = (artifact.name).toUpperCase()
                    def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)
                    def map = [
                            name             : artifact.name,
                            extension        : artifactExtension,
                            url              : artifact.authurl,
                            channelPath      : artifact.channelPath
                    ] + artifactsMeta
                    EmailBuilder.addSimpleArtifactTableRowSuccess(htmlBuilder, map)
                }
            } else {
                def map = [
                        extension  : artifact.extensionType,
                        channelPath: artifact.channelPath
                ] + artifactsMeta
                EmailBuilder.addSimpleArtifactTableRowFailed(htmlBuilder, map)
            }
        }
    }

    /**
     * Creates HTML content for runTests job, tests run part.
     *
     * @param binding provides data for HTML.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static String createRunTestContent(Map binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
            htmlBuilder.table(style: "width:100%") {
                EmailBuilder.addNotificationHeaderRow(htmlBuilder, binding.notificationHeader)
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", "Build Details")
                    }
                }

                tr {
                    td {
                        table(role :"presentation", cellspacing :"0", cellpadding :"0", style: "width:100%", class: "text-color table-border cell-spacing") {
                            if (binding.triggeredBy) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Triggered by:', binding.triggeredBy)
                            }

                            EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Build URL:', binding.build.url, binding.build.number)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project:', binding.projectName)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build number:', "#" + binding.build.number)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Date of build:', binding.build.started)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build duration:', binding.build.duration)
                            if(binding.isJasmineEnabled) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Test Framework:', binding.testFramework)
                                if (binding.isDesktopWebAppTestRun)
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Test Plan:', binding.jasmineWebTestPlan)
                                if (binding.isNativeAppTestRun)
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Test Plan:', binding.jasmineNativeTestPlan)
                            }
                            if (binding.isNativeAppTestRun) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Selected Device Pools: ', binding.devicePoolName)
                                if (binding.missingDevices) {
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Devices not available in pool: ', binding.missingDevices)
                                } else {
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Devices not available in pool: ', 'None')
                                }

                                if (binding.appiumVersion) {
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Appium Version: ', binding.appiumVersion)
                                }
                                if (binding.runInCustomTestEnvironment) {
                                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Run in Custom AWS Environment: ', binding.runInCustomTestEnvironment)
                                }
                            }
                        }
                    }
                }

                if (binding.isNativeAppTestRun && binding.deviceruns) {
                    displayBriefSummaryOfNativeTestResults(htmlBuilder, binding)

                    /*
                     * Check to see if this is the summary email which has to be sent on completion of runTests(parent) job or not.
                     * If this is the summary email, then we display only brief summary and not detailed summary of test results.
                     */
                    if(!binding.isSummaryEmail)
                        displayDetailedSummaryOfNativeTestResults(htmlBuilder, binding)
                }
                if (binding.isDesktopWebAppTestRun && binding.desktopruns) {

                    def suiteList = binding.desktopruns["suiteName"]
                    def testNameMap = binding.desktopruns["testName"]
                    def classNameMap = binding.desktopruns["className"]
                    def testMethodNameMap = binding.desktopruns["testMethod"]
                    def statusOfTestsMap = binding.desktopruns["testStatusMap"]
                    def suiteWiseSummary = [:]

                    getdesktopTestInfoTable(htmlBuilder, binding.desktopruns)

                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            br()
                            p(style: "font-weight:bold", "Brief Summary of DesktopWeb Test Results:")
                        }
                    }

                    prepareContentForDesktopWebTestsSummaryTable(suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap, suiteWiseSummary)
                    displayBriefSummaryOfDesktopWebTestResults(htmlBuilder, binding, suiteWiseSummary)

                    /*
                     * Check to see if this is the summary mail which has to be sent on completion of runTests(parent) job or not.
                     * If this is the summary email, then we display only brief summary and not detailed summary of test results.
                     */
                    if(!binding.isSummaryEmail)
                        displayDetailedSummaryOfDesktopWebTestResults(htmlBuilder, binding, suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap)
                }
                if (binding.isDesktopWebAppTestRun && binding.jasmineruns) {
                    getdesktopTestInfoTable(htmlBuilder, binding.jasmineruns)
                    prepareJasmineTestsSummaryTable(htmlBuilder, binding.jasmineruns.summary, binding.listofLogFiles)
                }

                if (!binding.deviceruns && !binding.desktopruns && !binding.jasmineruns) {
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
     * Creates HTML content related to the browser info.
     *
     * @param htmlBuilder that contains the details of the email.
     * @param testRunInfo that contains the browser information details.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static void getdesktopTestInfoTable(htmlBuilder, testRunInfo) {
        htmlBuilder.tr {
            td {
                table(style: "width:100%", class: "text-color table-border cell-spacing") {
                    EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Selected Browser: ', testRunInfo["browserVersion"])
                }
            }
        }
    }

    /**
     * Creates HTML content related to the browser info.
     *
     * @param htmlBuilder that contains the details of the email.
     * @param summary that contains the summary map of the test results.
     * @param logFiles contains the list of log files that need to be added as a new table in the email.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static void prepareJasmineTestsSummaryTable(htmlBuilder, summary, logFiles) {
        int totalPassed = 0
        int totalFailed = 0
        int totalTests = 0
        int totalDuration = 0
        htmlBuilder.table {
            tr{
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        p(style: "font-weight:bold", "Brief Summary of Desktop Test Results:")
                    }
                }
                td {
                    table(style: "width:100%;text-align:center", class: "text-color table-border") {
                        tr {
                            td(
                                    class: "testresults",
                                    'Suite Name'
                            )
                            td(
                                    class: "testresults",
                                    'Total'
                            )
                            td(
                                    class: "testresults",
                                    'Passed'
                            )
                            td(
                                    class: "testresults",
                                    colspan: "5",
                                    'Failures'
                            )
                            td(
                                    class: "testresults",
                                    'Duration (sec)'
                            )
                        }
                        tr {
                            td("")
                            td("")
                            td("")
                            td(class: "fail", "Failed")
                            td(class: "skip", "Skipped")
                            td(class: "warn", "Warned")
                            td(class: "stop", "Stopped")
                            td(class: "error", "Errored")
                            td("")
                        }
                        summary.each { suiteName, suiteSummary ->
                            totalPassed = totalPassed + suiteSummary["totalPassed"]
                            totalFailed = totalFailed + suiteSummary["totalFailed"]
                            totalTests = totalTests + suiteSummary["totalTests"]
                            totalDuration = totalDuration + suiteSummary["duration"]
                            tr {
                                th(
                                        class: "testresults",
                                        suiteName
                                )
                                th(
                                        class: "testresults",
                                        suiteSummary["totalTests"]
                                )
                                th(
                                        class: "testresults",
                                        suiteSummary["totalPassed"]
                                )
                                th(
                                        class: "testresults",
                                        suiteSummary["totalFailed"]
                                )
                                th(
                                        class: "testresults",
                                        "NA"
                                )
                                th(
                                        class: "testresults",
                                        "NA"
                                )
                                th(
                                        class: "testresults",
                                        "NA"
                                )
                                th(
                                        class: "testresults",
                                        "NA"
                                )
                                th(
                                        class: "testresults",
                                        (suiteSummary["duration"]/1000)
                                )
                            }
                        }
                        // Adding the high level totals
                        tr(style: "font-weight:bold;background-color:#B0C4DE") {
                            td(
                                    class: "testresults",
                                    'Total'
                            )
                            td(
                                    class: "testresults",
                                    totalTests
                            )
                            td(
                                    class: "testresults",
                                    totalPassed
                            )
                            td(
                                    class: "testresults",
                                    totalFailed
                            )
                            td(
                                    class: "testresults",
                                    "NA"
                            )
                            td(
                                    class: "testresults",
                                    "NA"
                            )
                            td(
                                    class: "testresults",
                                    "NA"
                            )
                            td(
                                    class: "testresults",
                                    "NA"
                            )
                            td(
                                    class: "testresults",
                                    (totalDuration/1000)
                            )
                        }
                    }
                    br()
                    p(style: "margin:30px 0 10px;font-weight:bold", "Test Logs : ")
                    table(style: "width:100%", class: "text-color table-border cell-spacing") {
                        tr {
                            td(
                                    class: "testresults",
                                    'Test Report/Log File'
                            )
                            td(
                                    class: "testresults-left-aligned",
                                    'Link'
                            )
                        }
                        logFiles.each { logFileName, logLink ->
                            EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, logFileName, logLink, logFileName)
                        }
                    }
                    br()
                }
            }
        }
    }

    /*
     *This method is used to parse each suite and collect suite-wise tests results
     */

    @NonCPS
    protected static void prepareContentForDesktopWebTestsSummaryTable(suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap, suiteWiseSummary) {
        suiteList.each {
            def mapForTestCaseAndStatus = [:]
            def suite_name = it.value
            // Loop for each test of suite
            testNameMap.each { testNameMapVar ->
                if (testNameMapVar.value.equalsIgnoreCase(suite_name.join(""))) {

                    // This loop is for traversing each class of specific test of a suite
                    classNameMap.each { classNameMapVar ->
                        if (classNameMapVar.value == testNameMapVar.key) {
                            // For each method, put the corresponding status in a map called 'mapForTestCaseAndStatus'
                            testMethodNameMap.each { testMethodNameMapVar ->
                                if (testMethodNameMapVar.value == classNameMapVar.key) {
                                    mapForTestCaseAndStatus.put(testMethodNameMapVar.key, statusOfTestsMap.get(testMethodNameMapVar.key))
                                }
                            }
                            //Create a map with passed, failed, skipped, total test cases correponding to each suite
                            def passedTestCases = 0, failedTestCases = 0, skippedTestCases = 0
                            passedTestCases = mapForTestCaseAndStatus.count { mapForTestCaseAndStatusVar -> mapForTestCaseAndStatusVar.value == "PASS" }
                            failedTestCases = mapForTestCaseAndStatus.count { mapForTestCaseAndStatusVar -> mapForTestCaseAndStatusVar.value == "FAIL" }
                            skippedTestCases = mapForTestCaseAndStatus.size() - passedTestCases - failedTestCases
                            suiteWiseSummary.put(testNameMapVar.key, [mapForTestCaseAndStatus.size(), passedTestCases, failedTestCases, skippedTestCases])
                        }
                    }
                }
            }
        }
    }

    /*
     *Using the content that got generated in "prepareContentForDesktopWebTestsSummaryTable" method, we will be displaying the Brief summary of DesktopWeb tests results in tabular format
     */

    @NonCPS
    protected static void displayBriefSummaryOfDesktopWebTestResults(htmlBuilder, binding, suiteWiseSummary) {
        htmlBuilder.table(style: "width:100%") {
            tr {
                td {
                    table(style: "width:100%;text-align:center", class: "text-color table-border") {
                        def surefireReportshtml = binding.desktopruns["surefireReportshtml"]
                        def testName = binding.desktopruns["testName"]
                        def testNameKeys = testName.keySet()
                        def totalTestCases = 0, totalPassedTestCases = 0, totalFailedTestCases = 0, totalSkippedTestCases = 0

                        //This is to create first row with headings 'Test Name', 'Passed', 'Failed', 'Skipped', 'Total'
                        tr {
                            td(
                                    class: "testresults",
                                    'Test Name'
                            )
                            if(binding.testArtifact) {
                                td(
                                        class: "testresults",
                                        'Installer'
                                )
                            }
                            td(
                                    class: "testresults",
                                    'Passed'
                            )
                            td(
                                    class: "testresults",
                                    'Failed'
                            )
                            td(
                                    class: "testresults",
                                    'Skipped'
                            )
                            td(
                                    class: "testresults",
                                    'Total'
                            )
                        }

                        // This loop is for creating a row for each test of each suite along with the passed, failed, skipped, total tests specific to test of suite
                        for (int testNameVar = 0; testNameVar < testName.size(); testNameVar++) {
                            tr {
                                th(
                                        class: "testresults-left-aligned",
                                        {
                                            a(href: surefireReportshtml[testNameVar], target: '_blank', testNameKeys[testNameVar])
                                        }
                                )
                                if(binding.testArtifact) {
                                    th(
                                            class: "testresults",
                                            {
                                                binding.testArtifact.url ? a(href: binding.testArtifact.url, target: '_blank', binding.testArtifact.extension.toUpperCase()) : ""
                                            }
                                    )
                                }
                                suiteWiseSummary.each {
                                    if (testNameKeys[testNameVar].equalsIgnoreCase(it.key)) {
                                        def summaryList = it.value
                                        totalTestCases += summaryList[0]
                                        totalPassedTestCases += summaryList[1]
                                        totalFailedTestCases += summaryList[2]
                                        totalSkippedTestCases += summaryList[3]

                                        th(
                                                class: "testresults",
                                                summaryList[1]
                                        )
                                        th(
                                                class: "testresults",
                                                summaryList[2]
                                        )
                                        th(
                                                class: "testresults",
                                                summaryList[3]
                                        )
                                        th(
                                                class: "testresults",
                                                summaryList[0]
                                        )
                                    }
                                }
                            }
                        }

                        // This is the last row to print the total number of passed, failed. skipped and total tests across all suites
                        tr(style: "font-weight:bold;background-color:#B0C4DE") {
                            td(
                                    class: "testresults",
                                    'Total'
                            )
                            if(binding.testArtifact) {
                                td(
                                        class: "testresults",
                                        ''
                                )
                            }
                            td(
                                    class: "testresults",
                                    totalPassedTestCases
                            )
                            td(
                                    class: "testresults",
                                    totalFailedTestCases
                            )
                            td(
                                    class: "testresults",
                                    totalSkippedTestCases
                            )
                            td(
                                    class: "testresults",
                                    totalTestCases
                            )
                        }
                    }
                }
            }
        }
    }

    /*
     *This method is used to display the desktopweb test results in detail in tabular format
     * First we parse each list and prepare the content, then we will display it suite-wise
     */

    @NonCPS
    protected static displayDetailedSummaryOfDesktopWebTestResults(htmlBuilder, binding, suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap) {
        htmlBuilder.table(style: "width:100%") {
            tr {
                td {
                    br()
                    p(style: "margin:30px 0 30px;font-weight:bold", "Detailed Summary of Test Results:")
                    table(style: "width:100%;text-align:left", class: "text-color table-border") {
                        //Parse each suite and get status of each test case
                        suiteList.each {
                            def mapForTestCaseAndStatus = [:]
                            def suite_name = it.value
                            // Parse each test of a suite
                            testNameMap.each { testNameMapVar ->
                                if (testNameMapVar.value.equalsIgnoreCase(suite_name.join(""))) {
                                    // Parse each class of a test of a suite
                                    classNameMap.each { classNameMapVar ->
                                        if (classNameMapVar.value == testNameMapVar.key) {
                                            // Put the status of each test corresponding to it in a map called mapForTestCasesAndStatus
                                            testMethodNameMap.each { testMethodNameMapVar ->
                                                if (testMethodNameMapVar.value == classNameMapVar.key) {
                                                    mapForTestCaseAndStatus.put(testMethodNameMapVar.key, statusOfTestsMap.get(testMethodNameMapVar.key))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (mapForTestCaseAndStatus.size() != 0) {
                                tr {
                                    def passedTestCases = 0, failedTestCases = 0, skippedTestCases = 0
                                    // Get the total count of passed, failed, skipped and total test cases
                                    mapForTestCaseAndStatus.each {
                                        (mapForTestCaseAndStatus.value == "PASS") ? passedTestCases++ : (mapForTestCaseAndStatus.value == "FAIL") ? failedTestCases++ : skippedTestCases++
                                    }

                                    td(
                                            class: "testresults-left-aligned",
                                            'Suite Name: ' + suite_name
                                    )
                                    td(
                                            class: "testresults-left-aligned",
                                            'Total test cases: ' + mapForTestCaseAndStatus.size()
                                    )
                                }
                                //For each test case, create one row and for each artifact related to this test case, create one row below the test case
                                mapForTestCaseAndStatus.each { mapForTestCaseAndStatusVar ->
                                    if (mapForTestCaseAndStatusVar.key) {
                                        tr {
                                            th(
                                                    class: "testresults-left-aligned-font-bold",
                                                    'Test Name: ' + mapForTestCaseAndStatusVar.key
                                            )
                                            th(
                                                    class: "testresults-left-aligned-font-bold",
                                                    mapForTestCaseAndStatusVar.value
                                            )
                                        }
                                        tr {
                                            if(binding.listofLogFiles.containsKey(mapForTestCaseAndStatusVar.key)){
                                                th(
                                                        class: "testresults-left-aligned",
                                                        mapForTestCaseAndStatusVar.key + ".log"
                                                )
                                                th(
                                                        class: "testresults-left-aligned",
                                                        {
                                                            a(href: binding.listofLogFiles[mapForTestCaseAndStatusVar.key], target: '_blank', 'Download File')
                                                        }
                                                )}
                                        }
                                        tr {
                                            if(binding.listofLogFiles.containsKey(mapForTestCaseAndStatusVar.key)) {
                                                th(
                                                        class: "testresults-left-aligned",
                                                        mapForTestCaseAndStatusVar.key + ".jpg"
                                                )
                                                th(
                                                        class: "testresults-left-aligned",
                                                        {
                                                            a(href: binding.listofScreenshots[mapForTestCaseAndStatusVar.key], target: '_blank', 'Download File')
                                                        }
                                                )}
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
    /*
    *Using the content that got generated in "prepareContentForDesktopWebTestsSummaryTable" method, we will be displaying the Brief summary of Native tests results in tabular format
    */

    @NonCPS
    protected static void displayBriefSummaryOfNativeTestResults(htmlBuilder, binding) {
        htmlBuilder.table(style: "width:100%") {
            tr {
                td(style: "text-align:left", class: "text-color") {
                    br()
                    p(style: "font-weight:bold", "Brief Summary of Native Test Results:")
                }
            }
            def deviceFarmTimeLimitExceeded = false
            table(style: "width:100%;text-align:center", class: "text-color table-border") {
                tr {
                    td(
                            class: "testresults",
                            'DEVICE'
                    )
                    td(
                            class: "testresults",
                            'INSTALLER'
                    )
                    td(
                            class: "testresults",
                            'TOTAL'
                    )
                    td(
                            class: "testresults",
                            'PASSED'
                    )
                    td(
                            class: "testresults",
                            colspan: "5",
                            'FAILURES'
                    )
                    td(
                            class: "testresults",
                            'DURATION'
                    )
                    if(binding.runInCustomTestEnvironment) {
                        td(
                                class: "testresults",
                                'RESULTS'
                        )
                    }
                }
                tr {
                    td("")
                    td("")
                    td("")
                    td("")
                    td(class: "fail", "Failed")
                    td(class: "skip", "Skipped")
                    td(class: "warn", "Warned")
                    td(class: "stop", "Stopped")
                    td(class: "error", "Errored")
                    td("")
                }
                def testsSummary = [:], testRunDuration
                testsSummary.putAll(binding.summaryofResults)
                def keys = testsSummary?.keySet()
                def vals = testsSummary?.values()
                testsSummary.each { deviceName, resultsObject ->
                    use(groovy.time.TimeCategory) {
                        testRunDuration = changeTimeFormat(resultsObject.getTestDuration())
                    }
                    if(resultsObject.getTestDuration() > binding.defaultDeviceFarmTimeLimit)
                        deviceFarmTimeLimitExceeded = true

                    tr {
                        th(class: "testresults", deviceName)
                        
                        th(class: "testresults",
                            {
                                (resultsObject.getBinaryURL().trim().length() > 0) ? a(href: resultsObject.getBinaryURL(), target: '_blank', (resultsObject.getBinaryExt()).toUpperCase()) : 'NOT AVAILABLE'
                            }
                        )

                        th(class: "testresults", resultsObject.getResultsCount().getTotal() ?: 0)

                                th(class: "testresults", resultsObject.getResultsCount().getPassed() ?: 0)
                                th(
                                        class: "testresults",
                                        resultsObject.getResultsCount().getFailed() ?: 0
                                        )
                                th(
                                        class: "testresults",
                                        resultsObject.getResultsCount().getSkipped() ?: 0
                                        )
                                th(
                                        class: "testresults",
                                        resultsObject.getResultsCount().getWarned() ?: 0
                                        )
                                th(
                                        class: "testresults",
                                        resultsObject.getResultsCount().getStopped() ?: 0
                                        )
                                th(
                                        class: "testresults",
                                        resultsObject.getResultsCount().getErrored() ?: 0
                                        )
                                th(
                                        class: "testresults",
                                        testRunDuration
                                        )
                                if(binding.runInCustomTestEnvironment)
                                    (resultsObject.getResultsLink()) ? th(class: "testresults", { a(href: resultsObject.getResultsLink(), target: '_blank', "Test Report")}) : th(class: "testresults", 'Not Found')
                }
                }
            }
            if(deviceFarmTimeLimitExceeded)
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        p(style: "font-size:10px;font-weight:normal", '** Tests got skipped. Device Farm public fleet default time limit of ' + (binding.defaultDeviceFarmTimeLimit/60000) + ' minutes exceeded.')
                    }
                }
        }
    }

    @NonCPS
    protected static void displayDetailedSummaryOfNativeTestResults(htmlBuilder, binding) {
        def suppressedArtifacts = ['Customer Artifacts Log', 'Test spec shell script', 'Test spec file']
        htmlBuilder.table(style: "width:100%") {
            tr {
                td(style: "text-align:left", class: "text-color") {
                    br()
                    p(style: "font-weight:bold", "Detailed Summary of Test Results:")
                }
            }
            for (runResult in binding.deviceruns) {
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
                                    for (suite in prop.value) {
                                        tr {
                                            td(
                                                    class: "testresults-left-aligned",
                                                    'Suite Name: ' + suite.name
                                            )
                                            td(
                                                    class: "testresults-left-aligned",
                                                    'Total test cases: ' + suite.totalTests
                                            )
                                        }
                                        for (test in suite.tests) {
                                            tr {
                                                th(
                                                        class: "testresults-left-aligned-font-bold",
                                                        'Test Name: ' + test.name
                                                )
                                                th(
                                                        class: "testresults-left-aligned-font-bold",
                                                        test.result
                                                )
                                            }
                                            for (artifact in test.artifacts.sort { a, b -> a.name <=> b.name }) {
                                                if (!suppressedArtifacts.contains(artifact.name)) {
                                                    tr {
                                                        th(
                                                                class: "testresults-left-aligned",
                                                                artifact.name + '.' + artifact.extension
                                                        )
                                                        th(
                                                                class: "testresults-left-aligned",
                                                                {
                                                                    a(href: artifact.authurl, target: '_blank', 'Download File')
                                                                }
                                                        )
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
     * @param binding provides data for HTML.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static String fabricContent(binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
            htmlBuilder.table(style: "width:100%") {
                EmailBuilder.addNotificationHeaderRow(htmlBuilder, binding.notificationHeader)
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", "Build Details")
                    }
                }

                tr {
                    td {
                        table(style: "width:100%", class: "text-color table-border cell-spacing") {
                            if (binding.triggeredBy) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Triggered by:', binding.triggeredBy)
                            }
                            EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Build URL:', binding.build.url, binding.build.number)

                            if (binding.gitURL) {
                                EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Repository URL:', binding.gitURL, binding.exportRepositoryUrl)
                            }
                            if (binding.gitBranch) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Repository Branch:', binding.exportRepositoryBranch)
                            }
                            if (binding.commitAuthor) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Author:', binding.commitAuthor)
                            }
                            if (binding.authorEmail) {
                                EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Author Email:', "mailto:" + binding.authorEmail, binding.authorEmail)
                            }
                            if (binding.commitMessage) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Commit Message:', binding.commitMessage)
                            }
                            if (binding.overwriteExisting) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Overwrite Existing:', binding.overwriteExisting)
                            }
                            if (binding.publishApp) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Enable Publish:', binding.publishApp)
                            }
                            if (binding.fabricEnvironmentName) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Published On Environment:', binding.fabricEnvironmentName)
                            }
                            if (binding.exportCloudAccountId) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Exported From:', binding.exportCloudAccountId)
                            }
                            if (binding.exportCloudAccountId) {
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'mported To:', binding.importCloudAccountId)
                            }
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Date of build:', binding.build.started)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build duration:', binding.build.duration)
                        }
                    }
                }

                tr {
                    td(style: "text-align:left;padding:15px 20px 0", class: "text-color") {
                        h4(style: "margin-bottom:0", "${binding.commandName} Details")
                        p {
                            mkp.yield "${binding.commandName} of Fabric app ${binding.fabricAppName}(${binding.fabricAppVersion}) is: "
                            strong binding.build.result
                            mkp.yield '.'
                        }
                    }
                }

                if (binding.build.result.equalsIgnoreCase('FAILURE')) {
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
     * Converts the given time difference into hours, minutes, seconds
     * @param Time difference between two times
     * @returns value with a specific time format
     * */
    @NonCPS
    protected static def changeTimeFormat(timeDifference){
        def value = ""
        Map mapWithTimeFormat = [:]
        if(timeDifference) {
            timeDifference = timeDifference / 1000
            mapWithTimeFormat.seconds = timeDifference.remainder(60)
            timeDifference = (timeDifference - mapWithTimeFormat.seconds) / 60
            mapWithTimeFormat.minutes = timeDifference.remainder(60)
            timeDifference = (timeDifference - mapWithTimeFormat.minutes) / 60
            mapWithTimeFormat.hours = timeDifference.remainder(24)
            if (mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP) + " hrs "
            if (mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP) + " mins "
            if (mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP) + " secs "
        }
        return value
    }
}
