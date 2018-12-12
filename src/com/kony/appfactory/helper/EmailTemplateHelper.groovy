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
                        table(style: "width:100%", class: "text-color table-border cell-spacing") {
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project:', binding.projectName)
                            if (binding.triggeredBy)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Triggered by:', binding.triggeredBy)

                            if(templateType.equals('buildVisualizerApp')) {
                                EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Build URL:', binding.build.url)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build number:', binding.build.number)
                            }

                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Date of build:', binding.build.started)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build duration:', binding.build.duration)
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
                                table(style: "width:100%;text-align:left", class: "text-color table-border-channels") {
                                    thead {
                                        tr {
                                            th(style: "text-align:center", 'Channel')
                                            th(style: "text-align:center", colspan: "2", 'INSTALLER')
                                        }
                                    }
                                    tbody {
                                        prepareMailBody(htmlBuilder, binding.artifacts, templateType)
                                    }
                                }
                            }
                        }
                    }
                    if (templateType.equals('cloudBuild'))
                        EmailBuilder.addBuildConsoleLogFields(htmlBuilder, [consolelogs: binding.consolelogs])
                }
            }
        }
    }

    @NonCPS
    static void prepareMailBody(htmlBuilder, artifacts, templateType) {
        for (artifact in artifacts) {
            if (artifact.name) {
                /* iOS */
                if (artifact.otaurl) {
                    def map = [
                            channelPath: artifact.channelPath,
                            artifacts  : [
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
                    ]

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                }

                /* Web */
                else if (artifact.webappurl) {
                    def artifactNameUpperCase = (artifact.name).toUpperCase()
                    def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)

                    def map = [
                            channelPath: artifact.channelPath,
                            artifacts  : [
                                    [
                                            name     : artifact.name,
                                            url      : artifact.authurl,
                                            extension: artifactExtension,
                                    ],
                                    [
                                            name     : artifact.webappurl,
                                            url      : artifact.webappurl,
                                            extension: 'APP URL',
                                    ]
                            ]
                    ]

                    EmailBuilder.addMultiSpanArtifactTableRow(htmlBuilder, map)
                }

                /* Android or channels - SPA/DesktopWeb/Web without publish enabled */
                else {
                    def artifactNameUpperCase = (artifact.name).toUpperCase()
                    def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)
                    def map = [
                            name       : artifact.name,
                            extension  : artifactExtension,
                            url        : artifact.authurl,
                            channelPath: artifact.channelPath
                    ]
                    EmailBuilder.addSimpleArtifactTableRowSuccess(htmlBuilder, map)
                }
            }
            else {
                if(templateType.equals('cloudBuild')) {
                    def map = [
                            extension  : artifact.extensionType,
                            channelPath: artifact.channelPath
                    ]
                    EmailBuilder.addSimpleArtifactTableRowFailed(htmlBuilder, map)
                }
                else {
                    tr {
                        th(artifact.channelPath.replaceAll('/', ' '))
                        td(colspan:"2", "Build failed")
                    }
                }
            }
        }
    }


    /**
     * Creates HTML content for runTests job, build of the test binaries part.
     *
     * @param binding provides data for HTML.
     * @return HTML content as a string.
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
     * @param binding provides data for HTML.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static String createRunTestContent(Map binding) {
        def suppressedArtifacts = ['Customer Artifacts Log', 'Test spec shell script', 'Test spec file']
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
                        if (binding.deviceruns) {
                            table(style: "width:100%", class: "text-color table-border cell-spacing") {
                                tr {
                                    td(style: "width:30%;text-align:right", 'Build URL: ')
                                    td {
                                        a(href: binding.build.url, "${binding.build.url}")
                                    }
                                }
                                tr {
                                    td(style: "width:30%;text-align:right", 'Selected Device Pools: ')
                                    td("${binding.devicePoolName}")
                                }
                                tr {
                                    td(style: "width:30%;text-align:right", 'Devices not available in pool: ')
                                    td("${(binding.missingDevices) ?: 'None'}")
                                }
                                if (binding.appiumVersion) {
                                    tr {
                                        td(style: "width:30%;text-align:right", 'Appium Version: ')
                                        td("${binding.appiumVersion}")
                                    }
                                }
                                if (binding.runInCustomTestEnvironment) {
                                    tr {
                                        td(style: "width:30%;text-align:right", 'Run in Custom AWS Environment: ')
                                        td("${binding.runInCustomTestEnvironment}")
                                    }
                                }
                            }
                        }
                    }
                }
                if (binding.deviceruns) {
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            br()
                            p(style: "font-weight:bold", "Brief Summary of Test Results:")
                        }
                    }
                    table(style: "width:100%;text-align:center", class: "text-color table-border") {
                        tr {
                            td(
                                    class: "testresults",
                                    'DEVICE'
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
                                    colspan:"5",
                                    'FAILURES'
                            )
                            td(
                                    class: "testresults",
                                    'DURATION'
                            )
                        }
                        tr {
                            td("")
                            td("")
                            td("")
                            td(class: "fail","Failed")
                            td(class: "skip","Skipped")
                            td(class: "warn","Warned")
                            td(class: "stop","Stopped")
                            td(class: "error","Errored")
                            td("")
                        }
                        def testsSummary = [:]
                        testsSummary.putAll(binding.summaryofResults)
                        def keys= testsSummary.keySet()
                        def vals= testsSummary.values()
                        for(int i=0;i<vals.size();i++){
                            tr {
                                th(
                                        class: "testresults",
                                        keys[i]
                                )
                                th(
                                        class: "testresults",
                                        vals[i].substring(vals[i].lastIndexOf(":") + 1)
                                )
                                (vals[i].contains("errored")) ? th(class: "testresults", StringUtils.substringBetween(vals[i], "passed:", "errored:")) :
                                        th(class: "testresults", StringUtils.substringBetween(vals[i], "passed:", "total tests:"))
                                th(
                                        class: "testresults",
                                        StringUtils.substringBetween(vals[i], "failed:", "stopped:")
                                )
                                th(
                                        class: "testresults",
                                        StringUtils.substringBetween(vals[i], "skipped:", "warned:")
                                )
                                th(
                                        class: "testresults",
                                        StringUtils.substringBetween(vals[i], "warned:", "failed:")
                                )
                                th(
                                        class: "testresults",
                                        StringUtils.substringBetween(vals[i], "stopped:", "passed:")
                                )
                                th(
                                        class: "testresults",
                                        StringUtils.substringBetween(vals[i], "errored:", "total tests:")
                                )
                                th(
                                        class: "testresults",
                                        binding.duration[keys[i]]
                                )
                            }
                        }
                    }
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            br()
                            p(style: "font-weight:bold", "Detailed Summary of Test Results:")
                        }
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
                if (binding.desktopruns) {

                    def suiteList= binding.desktopruns["suiteName"]
                    def testNameMap = binding.desktopruns["testName"]
                    def classNameMap = binding.desktopruns["className"]
                    def testMethodNameMap = binding.desktopruns["testMethod"]
                    def statusOfTestsMap = binding.desktopruns["testStatusMap"]
                    def suiteWiseSummary = [:]

                    tr {
                        td {
                            table(style: "width:100%", class: "text-color table-border cell-spacing") {
                                tr {
                                    td(style: "width:30%;text-align:right", 'Build URL: ')
                                    td {
                                        a(href: binding.build.url, "${binding.build.url}")
                                    }
                                }
                                tr {
                                    td(style: "width:30%;text-align:right", 'Selected Browser: ')
                                    td("${binding.desktopruns["browserVersion"]}")
                                }
                            }
                        }
                    }
                    tr {
                        td(style: "text-align:left", class: "text-color")  {
                            br()
                            p(style: "font-weight:bold", "Brief Summary of Test Results:")
                        }
                    }

                    prepareContentForSummaryTable(suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap, suiteWiseSummary)
                    displayBriefSummaryOfTestResults(htmlBuilder, binding, suiteWiseSummary)
                    displayDetailedSummaryOfTestResults(htmlBuilder, binding, suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap)
                }
                if (!binding.deviceruns && !binding.desktopruns) {
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

    /*
     *This method is used to parse each suite and collect suite-wise tests results
     */
    @NonCPS
    protected static void prepareContentForSummaryTable(suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap, suiteWiseSummary){
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
     *Using the content that got generated in "prepareContentForSummaryTable" method, we will be displaying the Brief summary of tests results in tabular format
     */
    @NonCPS
    protected static void displayBriefSummaryOfTestResults(htmlBuilder, binding, suiteWiseSummary){
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
    protected static displayDetailedSummaryOfTestResults(htmlBuilder, binding, suiteList, testNameMap, classNameMap, testMethodNameMap, statusOfTestsMap){
        htmlBuilder.table(style: "width:100%") {
            tr {
                td {
                    br()
                    p(style: "margin:30px 0 30px;font-weight:bold", "Detailed Summary of Test Results:")
                    table(style: "width:100%;text-align:left", class: "text-color table-border") {
                        //Parse each suite and get status of each test case
                        suiteList.each{
                            def mapForTestCaseAndStatus = [:]
                            def suite_name =  it.value
                            // Parse each test of a suite
                            testNameMap.each { testNameMapVar ->
                                if(testNameMapVar.value.equalsIgnoreCase(suite_name.join(""))){
                                    // Parse each class of a test of a suite
                                    classNameMap.each{ classNameMapVar ->
                                        if(classNameMapVar.value == testNameMapVar.key){
                                            // Put the status of each test corresponding to it in a map called mapForTestCasesAndStatus
                                            testMethodNameMap.each{ testMethodNameMapVar ->
                                                if(testMethodNameMapVar.value == classNameMapVar.key){
                                                    mapForTestCaseAndStatus.put(testMethodNameMapVar.key, statusOfTestsMap.get(testMethodNameMapVar.key))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if(mapForTestCaseAndStatus.size()!=0){
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
                                    if(mapForTestCaseAndStatusVar.key){
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
                                            th(
                                                    class: "testresults-left-aligned",
                                                    mapForTestCaseAndStatusVar.key + ".log"
                                            )
                                            th(
                                                    class: "testresults-left-aligned",
                                                    {a(href: binding.listofLogFiles[mapForTestCaseAndStatusVar.key], target: '_blank', 'Download File')}
                                            )
                                        }
                                        tr {
                                            th(
                                                    class: "testresults-left-aligned",
                                                    mapForTestCaseAndStatusVar.key + ".jpg"
                                            )
                                            th(
                                                    class: "testresults-left-aligned",
                                                    {a(href: binding.listofScreenshots[mapForTestCaseAndStatusVar.key], target: '_blank', 'Download File')}
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

                            if (binding.gitURL) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Repository URL:')
                                    td {
                                        a(href: binding.gitURL, "${binding.exportRepositoryUrl}")
                                    }
                                }
                            }
                            if (binding.gitBranch) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Repository Branch:')
                                    td(class: "table-value", binding.exportRepositoryBranch)
                                }
                            }
                            if (binding.commitAuthor) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Author:')
                                    td(class: "table-value", binding.commitAuthor)
                                }
                            }
                            if (binding.authorEmail) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Author Email:')
                                    td {
                                        a(href: "mailto:${binding.authorEmail}", "${binding.authorEmail}")
                                    }
                                }
                            }
                            if (binding.commitMessage) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Commit Message:')
                                    td(class: "table-value", binding.commitMessage)
                                }
                            }
                            if (binding.overwriteExisting) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Overwrite Existing:')
                                    td(class: "table-value", binding.overwriteExisting)
                                }
                            }
                            if (binding.publishApp) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Enable Publish:')
                                    td(class: "table-value", binding.publishApp)
                                }
                            }
                            if (binding.fabricEnvironmentName) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Published On Environment:')
                                    td(class: "table-value", binding.fabricEnvironmentName)
                                }
                            }
                            if (binding.exportCloudAccountId) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Exported From:')
                                    td(class: "table-value", binding.exportCloudAccountId)
                                }
                            }
                            if (binding.exportCloudAccountId) {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Imported To:')
                                    td(class: "table-value", binding.importCloudAccountId)
                                }
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

                if(binding.build.result.equalsIgnoreCase('FAILURE')){
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
}
