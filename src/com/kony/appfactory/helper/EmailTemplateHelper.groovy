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
                            table(style: "width:100%;text-align:left", class: "text-color table-border-channels") {
                                thead {
                                    tr {
                                        th(style: "text-align:center", 'Channel')
                                        th(style: "text-align:center", colspan:"2", 'INSTALLER')
                                    }
                                }
                                tbody {
                                    for (artifact in binding.artifacts) {
                                        if (artifact.name) {
                                            /* iOS */
                                            if (artifact.otaurl) {
                                                tr {
                                                    th(rowspan: "2", artifact.channelPath.replaceAll('/', ' '))
                                                    td(style: "border-right: 1px dotted #e8e8e8; width: 65px;", "OTA")
                                                    td {
                                                        a(href: artifact.otaurl, target: '_blank', artifact.name)
                                                    }
                                                }
                                                tr {
                                                    td(style: "border-right: 1px solid #e8e8e8; width: 65px;", "IPA")
                                                    td {
                                                        a(href: artifact.ipaAuthUrl, target: '_blank', artifact.ipaName)
                                                    }
                                                }
                                            }

                                            /* Web */
                                            else if (artifact.webappurl) {
                                                def artifactNameUpperCase = (artifact.name).toUpperCase()
                                                def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)
                                                tr {
                                                    th(rowspan: "2", artifact.channelPath.replaceAll('/', ' '))
                                                    td(style: "border-right: 1px solid #e8e8e8; width: 65px;", artifactExtension)
                                                    td {
                                                        a(href: artifact.authurl, target: '_blank', artifact.name)
                                                    }
                                                }
                                                tr {
                                                    td(style: "border-right: 1px solid #e8e8e8; width: 65px;", "APP URL")
                                                    td {
                                                        a(href: artifact.webappurl, target: '_blank', artifact.webappurl)
                                                    }
                                                }
                                            }

                                            /* Android or channels - SPA/DesktopWeb/Web without publish enabled */
                                            else {
                                                def artifactNameUpperCase = (artifact.name).toUpperCase()
                                                def artifactExtension = artifactNameUpperCase.substring(artifactNameUpperCase.lastIndexOf(".") + 1)
                                                tr {
                                                    th(artifact.channelPath.replaceAll('/', ' '))
                                                    td(style: "border-right: 1px solid #e8e8e8; width: 65px;", artifactExtension)
                                                    td {
                                                        a(href: artifact.authurl, target: '_blank', artifact.name)
                                                    }
                                                }
                                            }
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
                            p { strong "Selected Device Pools: ${binding.devicePoolName}" }
                            p { strong "Devices not available in pool: ${(binding.missingDevices) ?: 'None'}" }
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
                                    table(style: "border:2px solid;width:100%;text-align:left", class: "text-color table-border") {
                                        tr {
                                            th('Name')
                                            th('URL')
                                            th(style: "width:25%", 'Status')
                                        }
                                        for (suite in prop.value) {
                                            tr {
                                                th(
                                                        colspan: "2",
                                                        class: "table-value",
                                                        style: "padding:10px;text-transform:none",
                                                        'Suite Name: ' + suite.name
                                                )
                                                th(
                                                        class: "table-value",
                                                        style: "padding:10px;text-transform:none",
                                                        'Total test cases: ' + suite.totalTests
                                                )
                                            }
                                            for (test in suite.tests) {
                                                tr {
                                                    th(
                                                            colspan: "2",
                                                            class: "table-value",
                                                            style: "padding:10px;text-transform:none",
                                                            'Test Name: ' + test.name
                                                    )
                                                    th(
                                                            class: "table-value",
                                                            style: "padding:10px;text-transform:none",
                                                            test.result
                                                    )
                                                }
                                                for (artifact in test.artifacts) {
                                                    tr {
                                                        td artifact.name + '.' + artifact.extension
                                                        td {
                                                            a(href: artifact.authurl, target:'_blank', 'Download File')
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
                if (binding.desktopruns) {

                    def suiteList= binding.desktopruns["suiteName"]
                    def testNameMap = binding.desktopruns["testName"]
                    def classNameMap = binding.desktopruns["className"]
                    def testMethodNameMap = binding.desktopruns["testMethod"]
                    def statusOfTestsMap = binding.desktopruns["status_Map"]
                    def suiteWiseSummary = [:]

                    tr {
                        td {
                            table(style: "width:100%", class: "text-color table-border cell-spacing") {
                                tr {
                                    td(style: "width:22%;text-align:right", 'Build URL: ')
                                    td {
                                        a(href: binding.build.url, "${binding.build.url}")
                                    }
                                }
                            }
                        }
                    }
                    tr {
                        td(style: "text-align:left", class: "text-color")  {
                            p(style: "margin:10px 5 10px", "Selected Browser : ${binding.desktopruns["browserName"]} (${binding.desktopruns["browserVersion"]})")
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
                            mapForTestCaseAndStatus.each {
                                (mapForTestCaseAndStatus.value == "PASS")?passedTestCases++:(mapForTestCaseAndStatus.value == "FAIL")?failedTestCases++:skippedTestCases++
                            }
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
                    table(style: "border-collapse: collapse;border:0.1px solid DimGray;width:80%;text-align:center", class: "text-color table-border") {
                        def surefireReportshtml = binding.desktopruns["surefireReportshtml"]
                        def testName = binding.desktopruns["testName"]
                        def testNameKeys = testName.keySet()
                        def totalTestCases = 0, totalPassedTestCases = 0, totalFailedTestCases = 0, totalSkippedTestCases = 0

                        //This is to create first row with headings 'Test Name', 'Passed', 'Failed', 'Skipped', 'Total'
                        tr {
                            th(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#4682B4;font-weight:bold;padding:10px",
                                    'Test Name'
                            )
                            th(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#4682B4;font-weight:bold;padding:10px",
                                    'Passed'
                            )
                            th(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#4682B4;font-weight:bold;padding:10px",
                                    'Failed'
                            )
                            th(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#4682B4;font-weight:bold;padding:10px",
                                    'Skipped'
                            )
                            th(
                                    class: "table-value",
                                    style: "background-color:#4682B4;font-weight:bold;padding:10px",
                                    'Total'
                            )
                        }

                        // This loop is for creating a row for each test of each suite along with the passed, failed, skipped, total tests specific to test of suite
                        for (int testNameVar = 0; testNameVar < testName.size(); testNameVar++) {
                            tr {
                                th(
                                        class: "table-value",
                                        style: "padding:10px",
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
                                                class: "table-value",
                                                style: "padding:10px",
                                                summaryList[1]
                                        )
                                        th(
                                                class: "table-value",
                                                style: "padding:10px",
                                                summaryList[2]
                                        )
                                        th(
                                                class: "table-value",
                                                style: "padding:10px",
                                                summaryList[3]
                                        )
                                        th(
                                                class: "table-value",
                                                style: "padding:10px",
                                                summaryList[0]
                                        )
                                    }
                                }
                            }
                        }

                        // This is the last row to print the total number of passed, failed. skipped and total tests across all suites
                        tr(style: "font-weight:bold;background-color:#B0C4DE") {
                            td(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#B0C4DE;font-weight:bold;padding:10px",
                                    'Total'
                            )
                            td(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#B0C4DE;font-weight:bold;padding:10px",
                                    totalPassedTestCases
                            )
                            td(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#B0C4DE;font-weight:bold;padding:10px",
                                    totalFailedTestCases
                            )
                            td(
                                    class: "table-value",
                                    style: "border-right: 2px solid #FFFFFF;background-color:#B0C4DE;font-weight:bold;padding:10px",
                                    totalSkippedTestCases
                            )
                            td(
                                    class: "table-value",
                                    style: "background-color:#B0C4DE;font-weight:bold;padding:10px",
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
                    table(style: "border-collapse: collapse;border:0.1px solid DimGray;width:100%;text-align:left", class: "text-color table-border") {
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
                                            class: "table-value",
                                            style: "background-color:#B0C4DE;border-bottom:0.1px solid DimGray;border-top:0.1px solid DimGray;font-weight:bold;padding:10px",
                                            'Suite Name: ' + suite_name
                                    )
                                    td(
                                            class: "table-value",
                                            style: "background-color:#B0C4DE;border-bottom:0.1px solid DimGray;border-top:0.1px solid DimGray;font-weight:bold;padding:10px",
                                            'Total test cases: ' + mapForTestCaseAndStatus.size()
                                    )
                                }
                                //For each test case, create one row and for each artifact related to this test case, create one row below the test case
                                mapForTestCaseAndStatus.each { mapForTestCaseAndStatusVar ->
                                    if(mapForTestCaseAndStatusVar.key){
                                        tr {
                                            td(
                                                    class: "table-value",
                                                    style: "font-weight:bold;padding:10px",
                                                    'Test Name: ' + mapForTestCaseAndStatusVar.key
                                            )
                                            td(
                                                    class: "table-value",
                                                    style: "font-weight:bold;padding:10px",
                                                    mapForTestCaseAndStatusVar.value
                                            )
                                        }
                                        tr {
                                            td(
                                                    class: "table-value",
                                                    style: "padding:10px",
                                                    mapForTestCaseAndStatusVar.key + ".log"
                                            )
                                            td(
                                                    class: "table-value",
                                                    style: "padding:10px",
                                                    {a(href: binding.listofLogFiles[mapForTestCaseAndStatusVar.key], target: '_blank', 'Download File')}
                                            )
                                        }
                                        tr {
                                            td(
                                                    class: "table-value",
                                                    style: "padding:10px",
                                                    mapForTestCaseAndStatusVar.key + ".jpg"
                                            )
                                            td(
                                                    class: "table-value",
                                                    style: "padding:10px",
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
                                    td(style: "width:22%;text-align:right", 'Message:')
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
                                    td(style: "width:22%;text-align:right", 'Environment:')
                                    td(class: "table-value", binding.fabricEnvironmentName)
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
