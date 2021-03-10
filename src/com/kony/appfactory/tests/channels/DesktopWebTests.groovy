package com.kony.appfactory.tests.channels

import groovy.json.JsonSlurperClassic
import groovy.json.internal.LazyMap
import groovy.lang.Writable
import org.codehaus.groovy.runtime.InvokerHelper

import com.kony.appfactory.helper.ArtifactHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper


class DesktopWebTests extends RunTests implements Serializable {

    /* Build parameters */
    protected webTestsArguments = BuildHelper.getCurrentParamName(script, "RUN_WEB_TESTS_ARGUMENTS", "RUN_DESKTOPWEB_TESTS_ARGUMENTS")
    protected scriptArguments = script.params[webTestsArguments]
    protected runWebTestsJobName = (webTestsArguments == "RUN_WEB_TESTS_ARGUMENTS") ? "runWebTests" : "runDesktopWebTests"
    protected runWebTestsChannelName = (runWebTestsJobName == "runWebTests") ? "Web" : "DesktopWeb"
    protected webAppUrlParamName = script.params['FABRIC_APP_URL']

    private static desktopTestRunResults = [:]
    private static jasmineTestResults = [:]
    /* desktopweb tests Map variables */
    def static browserVersionsMap = [:], listofLogFiles = [:], listofScreenshots = [:], summary = [:], testList = [:], testMethodMap = [:], classList = [:], testStatusMap = [:], duration = [:], runArnMap = [:]
    def suiteNameList = [], surefireReportshtmlAuthURL = []
    def failedTests = 0, totalTests = 0, passedTests = 0, skippedTests = 0

    String surefireReportshtml = ""

    private boolean runTests = true
    private boolean overAllTestsResultsStatus = true

    protected desktopwebArtifactUrl = script.params.DESKTOPWEB_ARTIFACT_URL

    private selectedBrowser = script.params.AVAILABLE_BROWSERS

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebTests(script) {
        super(script, "Web")
    }

    /*
     * This method captures the test results from the given testFolder based on the framework.
     * @param testFolder is the folder from which the results will be captured.
     */
    private final def fetchTestResults(testFolder) {
        mustHaveArtifacts.add("${testFolder}/browserConsoleLog.txt")
        if(isJasmineEnabled){
            fetchJasmineResults(testFolder)
        } else {
            fetchTestNGResults(testFolder)
        }
    }
    
    /*
     * This method captures the Jasmine test results from the given testFolder.
     * @param testFolder is the folder from which the Jasmine results will be captured.
     */
    private final def fetchJasmineResults(testFolder) {
        String successMessage = 'Jasmine Test Results have been fetched successfully for ' + runWebTestsChannelName
        String errorMessage = 'Failed to fetch the Jasmine Test Results for ' + runWebTestsChannelName
        
        def suiteMap = [:]
        def suiteSummaryMap = [:]
        def logFilesMap = [:]
        def suiteTestPassed = 0
        def suiteTestFailed = 0
        script.catchErrorCustom(errorMessage, successMessage) {
            String reportJSON = script.readJSON file: "${testFolder}/report.json"
            def response = new JsonSlurperClassic().parseText(reportJSON)
            def mapDuration = [:]
            def testMap = [:]
            def testArray = []
            
            response.each { event ->
                String eventType = event.event
                switch(eventType) {
                    case 'suiteStarted':
                                    suiteTestPassed = 0
                                    suiteTestFailed = 0
                                    break;
                    case 'specStarted':
                                    testMap = [:]
                                    break;
                    case 'specDone':
                                    testMap["testID"] = event.result.id
                                    testMap["testDesc"] = event.result.description
                                    testMap["testFullName"] = event.result.fullName
                                    testMap["testStatus"] = event.result.status
                                    if (event.result.status.equalsIgnoreCase("passed")){
                                        suiteTestPassed++
                                    } else {
                                        suiteTestFailed++
                                        overAllTestsResultsStatus = false
                                    }
                                    testMap["failedExpectations"] = event.result.failedExpectations
                                    testMap["duration"] = event.result.duration
                                    testArray.add(testMap)
                                    break;
                    case 'suiteDone':
                                    def suiteSummary = [:]
                                    suiteSummary["totalPassed"] = suiteTestPassed
                                    suiteSummary["totalFailed"] = suiteTestFailed
                                    suiteSummary["totalTests"] = suiteTestPassed + suiteTestFailed
                                    suiteSummary["duration"] = event.result.duration
                                    suiteSummaryMap[event.result.description] = suiteSummary
                                    suiteMap[event.result.description] = testArray
                                    testArray.removeAll()
                                    break;
                }
            }
        } 
        
        def totalTests = suiteTestPassed + suiteTestFailed
        script.echoCustom('Summary of Test Results :: \n Passed: ' + suiteTestPassed +  ' Failed: ' + suiteTestFailed + ' Total Tests: ' + totalTests)
        
        jasmineTestResults["results"] = suiteMap
        jasmineTestResults["summary"] = suiteSummaryMap
        jasmineTestResults["browserName"] = selectedBrowser
        jasmineTestResults["browserVersion"] = browserVersionsMap[selectedBrowser]
    }

    /**
     * Collect all the required log files, store them in a folder under testFolder
     * publish the artifacts and parse the results to form email template
     * We create certain folder structure which is used to display screenshots and logs in html which will be shown in email
     * First we create testOutput folder, under that 2 sub-folders Smoke and test-output
     * Now copy the content of target/surefire-reports/ to testOutput/Smoke
     * Also copy files from test-output/Appscommon-Logs to testOutput/test-output, now copy from test-output/Screenshots to testOutput/test-output
     * Now we have all the date in testOutput folder , zip this and place it under target/${projectName}_TestApp
     */
    private final def fetchTestNGResults(testFolder) {
        String successMessage = 'Test Results have been fetched successfully for ' + runWebTestsChannelName
        String errorMessage = 'Failed to fetch the Test Results for ' + runWebTestsChannelName

        script.catchErrorCustom(errorMessage, successMessage) {
            // 'testOutput' folder is being used for storing the log files, css files and all collectively and will be used while publishing to artifact storage (S3 or Master or other).
            // We will try to remove this reference and directly use the 'target' and 'test-output' folders going forward.
            String testOutputFolder = testFolder + "/testOutput"
            script.dir(testOutputFolder) {
                // 'Smoke' folder stores the default files that get generated by TestNG.
                script.shellCustom("mkdir -p Smoke", true)
                // 'test-output' folder stores the log files of tests under 'Logs' folder and screenshots under 'Screenshots' folder.
                script.shellCustom("mkdir -p test-output", true)
            }
            script.dir(testFolder) {
                script.shellCustom("cp -R target/surefire-reports/* testOutput/Smoke", true)
                // Going forward, we will be using the folder 'Logs' as a generic folder where the customers should store the test case log files if any.
                if(script.fileExists("test-output/Logs"))
                    script.shellCustom("cp -R test-output/Logs testOutput/test-output", true)
                // To support existing projects, we need to refer to 'Appscommon-Logs' folder for the logs
                if(script.fileExists("test-output/Appscommon-Logs"))
                    script.shellCustom("cp -R test-output/Appscommon-Logs testOutput/test-output", true)
                // We refer to 'Screenshots' folder to access the screenshots of the tests if any.
                if(script.fileExists("test-output/Screenshots"))
                    script.shellCustom("cp -R test-output/Screenshots testOutput/test-output", true)
                script.shellCustom("zip -r target/${projectName}_TestApp testOutput", true)
            }
        }

        /* Parse testng-results, collecting the count of total failed/passed/skipped/total tests, to display in email notification */
        String testNGResultsFileContent = script.readFile("${testFolder}/target/surefire-reports/testng-results.xml")
        def testng_results = new XmlSlurper().parseText(testNGResultsFileContent)
        failedTests = testng_results.@failed.join("")
        totalTests = testng_results.@total.join("")
        skippedTests = testng_results.@skipped.join("")
        passedTests = testng_results.@passed.join("")
        int suiteNo=0
        def durationList  = [], startedAtList = [], finishedAtList = []

        /* In this step we are parsing each suite and collecting the data into corresponding lists */
        testng_results.suite.each{
            int testsCount = 0
            int classCount = 0
            int testMethodCount = 0
            suiteNameList.add(testng_results.suite[suiteNo].@name.join(""))
            durationList.add(TestsHelper.convertTimeFromMilliseconds(Long.valueOf(testng_results.suite[suiteNo]."@duration-ms".join(""))))
            startedAtList.add(testng_results.suite[suiteNo]."@started-at".join(""))
            finishedAtList.add(testng_results.suite[suiteNo]."@finished-at".join(""))

            /* In this step we are parsing each test of suite */
            testng_results.suite[suiteNo].test.each {
                testList.put(testng_results.suite[suiteNo].test[testsCount].@name.join(""), testng_results.suite[suiteNo].@name.join(""))
                testng_results.suite[suiteNo].test[testsCount].class.each {
                    classList.put(testng_results.suite[suiteNo].test[testsCount].class[classCount].@name.join(""), testng_results.suite[suiteNo].test[testsCount].@name.join(""))

                    /* In this step we are parsing each test case of test*/
                    testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method".each {
                        if(testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method"[testMethodCount]."@is-config".join("") != "true"){
                            testStatusMap.put(testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method"[testMethodCount].@name.join(""), testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method"[testMethodCount].@status.join(""))
                            testMethodMap.put(testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method"[testMethodCount].@name.join(""), testng_results.suite[suiteNo].test[testsCount].class[classCount].@name.join(""))
                        }
                        testMethodCount++
                    }
                    classCount++
                    /* Once we finsh fetching the results for one class and start moving to another class, we need to reset the test methods count variable (testMethodCount) to 0 */
                    testMethodCount = 0
                }
                testsCount++
                /* Once we finsh fetching the results for one test and start moving to another test, we need to reset the classes count variable (classCount) to 0 */
                classCount = 0
            }
            suiteNo++
        }
        
        overAllTestsResultsStatus = !testStatusMap.any { it.value != "PASS" }
        
        desktopTestRunResults << ["suiteName":suiteNameList, "className":classList, "testName":testList, "testMethod":testMethodMap, "testStatusMap":testStatusMap, "duration":durationList, "finishTime":finishedAtList]
        desktopTestRunResults << ["passedTests":passedTests, "skippedTests":skippedTests, "failedTests":failedTests, "totalTests":totalTests, "browserName":selectedBrowser, "browserVersion":browserVersionsMap[selectedBrowser], "startTime":startedAtList]

        desktopTestRunResults
    }

    /*
     * This method returns the corresponding browser version
     * @param browserName is the name of the browser for which we are trying to get the version
     * @return the version of corresponding browser
     */
    private final def getBrowserVersion(browserName, browserPath) {
        String errorMessage = 'Failed to find the version of ' + browserName + ' browser!'
        def version
        script.catchErrorCustom(errorMessage) {
            version = script.shellCustom("${browserPath} --version", true, [returnStdout: true])
        }
        version
    }
    
    /* This method deletes the Jasmine tests which are hosted in the jetty webapps folder */
    private cleanupJasmineTests(){
        /* Test script path: <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<EPOC_TIME>/
         * or <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<WedBuildNo>/
         * "eg: /opt/jetty/webapps/testresources/100000005/RsTestOnly_1612446923377/" 
         */
        
        // Cleanup the jasmine test scripts in the jetty webapps folder for each app
        appsList?.each { appName ->
            def appTesScriptDepyloyedPath = appsTestScriptDeploymentInfo[appName]?.get('jasTestScriptDeploymentPath')
            script.shellCustom("set +x;rm -Rf $appTesScriptDepyloyedPath", true)
        }
    }
    
    /*
     * This method publishes the test results to artifactStorage (S3 or Master or other), based on the framework.
     */
    protected final publishTestsResults() {
        def artifactsPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER]
        artifactsPath.add(runWebTestsChannelName)
        artifactsPath.add(selectedBrowser + '_' + browserVersionsMap[selectedBrowser])
        def publishPath = artifactsPath.join('/').replaceAll('\\s', '_')
        if(isJasmineEnabled){
            publishJasmineResults(publishPath)
        } else {
            publishTestNGResults(publishPath)
        }
    }

    /*
     * This method publishes the jasmine test results.
     * 
     * @param destinationArtifactPath is the artifact path into which the results will be published.
     */
    private publishJasmineResults(destinationArtifactPath) {
        String jasmineHTMLReport, browserConsoleLog
        def htmlFilesAuthUrl = [:]
        script.dir(testFolder) {
            def htmlFiles = script.findFiles(glob: '**/TestResult_*.html')
            htmlFiles?.each { htmlResultFile ->
                jasmineHTMLReport = ArtifactHelper.publishArtifact sourceFileName: htmlResultFile.name,
                    sourceFilePath: "${testFolder}", destinationPath: destinationArtifactPath, script
                
                def htmlFileAuthUrl = ArtifactHelper.createAuthUrl(jasmineHTMLReport, script, true)
                htmlFilesAuthUrl.put(htmlResultFile.name, htmlFileAuthUrl)
            }
            listofLogFiles.put("Detailed Test Report", htmlFilesAuthUrl);
        }
        if (script.fileExists("${testFolder}/browserConsoleLog.txt")) {
            browserConsoleLog = ArtifactHelper.publishArtifact sourceFileName: "browserConsoleLog.txt",
                sourceFilePath: "${testFolder}", destinationPath: destinationArtifactPath, script
            listofLogFiles.put("Browser Console Log", ArtifactHelper.createAuthUrl(browserConsoleLog, script, true));
        }
    }
    
    /*
     * This method publishes the TestNG test results to artifactStorage (S3 or Master or other) in the given artifact path.
     *
     * @param destinationArtifactPath is the artifactStorage (S3 or Master or other) path into which the results will be published.
     */
    private publishTestNGResults(destinationArtifactPath) {
        
        String testng_reportsCSSAndCSS = ArtifactHelper.publishArtifact sourceFileName: "testng.css,testng-reports.css",
                sourceFilePath: "${testFolder}/testOutput/Smoke", destinationPath: destinationArtifactPath + "/testOutput/Smoke", script, true

        suiteNameList.each {
            def suiteName = it.value
            def testListKeys = testList.keySet()
            def testListValues = testList.values()
            for(int testListVar=0;testListVar<testList.size();testListVar++){
                if(testListValues[testListVar].equalsIgnoreCase(suiteName.join(""))){
                    def testFolderForDWeb = testFolder
                    surefireReportshtml = ArtifactHelper.publishArtifact sourceFileName: testListKeys[testListVar] + ".html",
                            sourceFilePath: testFolderForDWeb + "/testOutput/Smoke/" + suiteName,
                            destinationPath: destinationArtifactPath + "/testOutput/Smoke/" + suiteName, script, true
                    surefireReportshtmlAuthURL.add(ArtifactHelper.createAuthUrl(surefireReportshtml, script, true, "view"))
                }
            }
        }
        desktopTestRunResults.put("surefireReportshtml", surefireReportshtmlAuthURL)

        testMethodMap.each {
            //If there are some jpg files in the desired location, only then add them to the listofScreenshots which will be used in email tempalte.
            if (script.fileExists("${testFolder}/testOutput/test-output/Screenshots/${it.key}.jpg")) {
                String screenshotsURL = ArtifactHelper.publishArtifact sourceFileName: it.key + ".jpg",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Screenshots",
                        destinationPath: destinationArtifactPath + "/testOutput/test-output/Screenshots", script, true
                listofScreenshots.put(it.key, ArtifactHelper.createAuthUrl(screenshotsURL, script, true))
            }

            //If there are some log files in the desired location (either under 'AppsCommon-Logs' or 'Logs' folder), only then add them to the listofLogFiles which will be used in email tempalte.
            if (script.fileExists("${testFolder}/testOutput/test-output/Appscommon-Logs/${it.key}.log")) {

                String logFileURL = ArtifactHelper.publishArtifact sourceFileName: it.key + ".log",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Appscommon-Logs",
                        destinationPath: destinationArtifactPath + "/testOutput/test-output/Appscommon-Logs", script, true
                listofLogFiles.put(it.key, ArtifactHelper.createAuthUrl(logFileURL, script, true))

            } else if (script.fileExists("${testFolder}/testOutput/test-output/Logs/${it.key}.log")) {

                String logFileURL = ArtifactHelper.publishArtifact sourceFileName: it.key + ".log",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Logs",
                        destinationPath: destinationArtifactPath + "/testOutput/test-output/Appscommon-Logs", script, true
                listofLogFiles.put(it.key, ArtifactHelper.createAuthUrl(logFileURL, script, true))

            }
        }
    }

    /**
     * Uploads application binaries and Schedules the run for Web.
     */
    private final void runTests(browserName, testFolder) {
        script.dir(testFolder) {
            // Create these directories so that the generated log files and screenshots will be saved here.
            script.shellCustom("mkdir -p test-output/Screenshots", true)
            script.shellCustom("mkdir -p test-output/Logs", true)
            script.shellCustom("mkdir -p test-output/Appscommon-Logs", true)
            def webAppUrlForTest = webAppUrlParamName
            
            if(isJasmineEnabled) {
                if (ValidationHelper.compareVersions(script.env["visualizerVersion"], libraryProperties.'jasmine.testonly.support.base.version') == -1) {
                    scriptArguments = " -Dsurefire.suiteXmlFiles=Testng.xml -DJASMINE_TEST_APP_URL=${script.env['JASMINE_TEST_URL']} -DSKIP_URL_CHECK=false -DBASE_APP_NAME=${baseAppName}"
                } else {
                    scriptArguments = " -Dsurefire.suiteXmlFiles=Testng.xml -DSKIP_URL_CHECK=true -DBASE_APP_NAME=${baseAppName}"
                    def protocolType = script.env.JASMINE_TEST_URL?.split('://')[0]
                    def testResourceUrl= script.env.JASMINE_TEST_URL?.split('://')[1]
                    /* Sample Web App Url for V9.3 "https://appfactoryserver.dev-temenos-cloud.net/apps/SanityWRAutoTest/?protocol=http&testurl=localhost:8888/testresources/100000005/RsTestOnly_1612359688388/"*/
                    webAppUrlForTest = webAppUrlParamName + "/" + "?protocol=${protocolType}&testurl=${testResourceUrl}"
                }
            }
            else {
                scriptArguments.contains('-Dsurefire.suiteXmlFiles')?: (scriptArguments += " -Dsurefire.suiteXmlFiles=Testng.xml")
            }
            
            def testHyphenDParams = "-DDRIVER_PATH=${script.env.CHROME_DRIVER_PATH} -DBROWSER_PATH=${script.env.CHROME_BROWSER_PATH} -DWEB_APP_URL='${webAppUrlForTest}' -Dmaven.test.failure.ignore=true -DFILE_DOWNLOAD_PATH=" + testFolder
            
            if (script.params.containsKey('SCREEN_RESOLUTION')) {
                testHyphenDParams = testHyphenDParams + " -DSCREEN_RESOLUTION=${script.params.SCREEN_RESOLUTION}"
            }
            
            switch (browserName) {
                case 'CHROME':
                    script.shellCustom("mvn test " + testHyphenDParams + " ${scriptArguments}", true)
                    browserVersionsMap << ["CHROME":script.env.CHROME_VERSION]
                    break
                default:
                    throw new AppFactoryException("Unable to find the browser.. might be unknown/unsupported browser selected!!", 'ERROR')
                    break
            }
        }
    }

    /**
     * Validates build parameters.
     *
     * @param buildParameters job parameters.
     */
    protected final void validateBuildParameters(buildParameters) {
        /* Filter Web application binaries build parameters */
        def publishedAppUrlParameters = (!buildParameters['FABRIC_APP_URL']) ? [:] : ['FABRIC_APP_URL': buildParameters['FABRIC_APP_URL']]
        /* Filter all SCM build parameters */
        def scmParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        /* Combine Web binaries build parameters */
        def urlParameters = publishedAppUrlParameters

        if (!scmParameters) {
            throw new AppFactoryException("Please provide SCM branch details to checkout test scripts source from GIT repository to run the test.",'ERROR')
        }
        
        /* Fail build if testBinaryUrlParameter been provided without publishedAppUrlParameters */
        if (scmParameters && !publishedAppUrlParameters) {
            throw new AppFactoryException("Please provide Web application URL!",'ERROR')
        }
        
        /* Check if at least one application binaries parameter been provided */
        (!publishedAppUrlParameters) ?: validateApplicationBinariesURLs(urlParameters)
    }

    protected createPipeline() {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.properties([[$class: 'CopyArtifactPermissionProperty', projectNames: '/*']])
                script.stage('Validate parameters') {
                    validateBuildParameters(script.params)
                }
                
                nodeLabel = TestsHelper.getTestNode(script, libraryProperties, isJasmineEnabled, runWebTestsChannelName)
                
                /* Allocate a slave for the run */
                script.node(nodeLabel) {

                    try {

                        pipelineWrapper {
                            /*
                            Clean workspace, to be sure that we have not any items from previous build,
                            and build environment completely new.
                            */
                            script.cleanWs deleteDirs: true

                            /* Checkout source to build test automation scripts */
                            script.stage('Checkout') {
                                scmMeta = BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        checkoutType: "scm",
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }
                            /* Get the automation test folder */
                            testFolder = getTestsFolderPath(projectFullPath)
                            
                            /* Here, Setting JASMINE_TEST_URL env variable based on JASMINE_TEST_URL param
                             * Note: JASMINE_TEST_URL can be as "http://localhost:8888/testresources/<ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<EPOC_TIME>/
                             * eg: "http://localhost:8888/testresources/100000005/RsTestOnly_1612446923377/" or
                             * "http://localhost:8888/testresources/<ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<WebBuildNo>/"
                             * eg: "http://localhost:8888/testresources/100000005/RsTestOnly_5/"
                             */
                            
                            script.env["JASMINE_TEST_URL"] = (script.params.JASMINE_TEST_URL)?: libraryProperties.'test.automation.jasmine.base.host.url' + script.env.CLOUD_ACCOUNT_ID + '/' + script.env.PROJECT_NAME + '_' + new Date().time + '/'

                            /* Preparing the environment for the Jasmine Tests */
                            if(isJasmineEnabled){
                                prepareEnvForJasmineTests(testFolder)
                            }

                            script.stage('Build') {
                                /* Build Test Automation scripts */
                                buildTestScripts(testFolder)
                            }

                            /* Run tests on provided binaries */
                            script.stage('Run the Tests') {
                                runTests(script.params.AVAILABLE_BROWSERS, testFolder)
                            }
                            script.stage('Get Test Results') {
                                fetchTestResults(testFolder)
                                if (!desktopTestRunResults && !jasmineTestResults)
                                    throw new AppFactoryException('Web tests results are not found as the run result is skipped.', 'ERROR')
                                publishTestsResults()
                            }
                            script.stage('Check PostTest Hook Points') {
                                if (!desktopTestRunResults && !jasmineTestResults)
                                    throw new AppFactoryException('Web tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                script.currentBuild.result = overAllTestsResultsStatus ? 'SUCCESS' : 'UNSTABLE'

                                if (runCustomHook) {
                                    if (overAllTestsResultsStatus) {
                                        def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', "DESKTOP_WEB_STAGE")
                                        if (!isSuccess)
                                            throw new Exception("Something went wrong with the Custom hooks execution.")
                                    } else {
                                        script.echoCustom('Tests got failed for Web. Hence CustomHooks execution is skipped.', 'WARN')
                                    }
                                } else {
                                    script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                }
                            }
                        }
                    } finally {
                        channelTestsStats.put('atype', 'spa')
                        channelTestsStats.put('plat', 'web')
                        channelTestsStats.put('chnl', 'desktop')
                        channelTestsStats.put('browserver', browserVersionsMap[selectedBrowser])
                        channelTestsStats.put('srccmtid', scmMeta['commitID'])

                        if (desktopTestRunResults.passedTests && desktopTestRunResults.passedTests instanceof Integer)
                            channelTestsStats.put('testpass', desktopTestRunResults.passedTests)
                        else if (desktopTestRunResults.passedTests && desktopTestRunResults.passedTests instanceof String)
                            channelTestsStats.put('testpass', desktopTestRunResults.passedTests.toInteger())
                        else
                            channelTestsStats.put('testpass', 0)


                        if (desktopTestRunResults.failedTests && desktopTestRunResults.failedTests instanceof Integer)
                            channelTestsStats.put('testfail', desktopTestRunResults.failedTests)
                        else if (desktopTestRunResults.failedTests && desktopTestRunResults.failedTests instanceof String)
                            channelTestsStats.put('testfail', desktopTestRunResults.failedTests.toInteger())
                        else
                            channelTestsStats.put('testfail', 0)


                        if (desktopTestRunResults.skippedTests && desktopTestRunResults.skippedTests instanceof Integer)
                            channelTestsStats.put('testskip', desktopTestRunResults.skippedTests)
                        else if (desktopTestRunResults.skippedTests && desktopTestRunResults.skippedTests instanceof String)
                            channelTestsStats.put('testskip', desktopTestRunResults.skippedTests.toInteger())
                        else
                            channelTestsStats.put('testskip', 0)

                        script.statspublish channelTestsStats.inspect()

                        if(isJasmineEnabled){
                            cleanupJasmineTests()
                        }
                        def testArtifact = [:]
                        if (desktopwebArtifactUrl) {
                            testArtifact.put("url", desktopwebArtifactUrl)
                            testArtifact.put("extension", desktopwebArtifactUrl.substring(desktopwebArtifactUrl.lastIndexOf('.') + 1))
                        }
                        NotificationsHelper.sendEmail(script, 'runTests', [
                                isDesktopWebAppTestRun  : true,
                                isJasmineEnabled : isJasmineEnabled,
                                webAppURL        : webAppUrlParamName,
                                jasmineruns      : jasmineTestResults,
                                desktopruns      : desktopTestRunResults,
                                listofLogFiles   : listofLogFiles,
                                listofScreenshots: listofScreenshots,
                                testArtifact : testArtifact,
                                testFramework : testFramework,
                                jasmineWebTestPlan : jasmineTestPlan,
                                runWebTestsChannelName : runWebTestsChannelName
                        ], true)
                        if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                            TestsHelper.PrepareMustHaves(script, runCustomHook, runWebTestsJobName, libraryProperties, mustHaveArtifacts, false)
                            if (TestsHelper.isBuildDescriptionNeeded(script))
                                TestsHelper.setBuildDescription(script)
                        }

                    }
                }
            }
        }
    }
}
