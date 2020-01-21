package com.kony.appfactory.tests.channels

import groovy.json.JsonSlurperClassic
import groovy.json.internal.LazyMap
import groovy.lang.Writable
import org.codehaus.groovy.runtime.InvokerHelper

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper


class DesktopWebTests extends RunTests implements Serializable {

    /* Build parameters */
    protected scriptArguments = script.params.RUN_DESKTOPWEB_TESTS_ARGUMENTS

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

    private boolean isTestScriptGiven = script.params['DESKTOPWEB_TESTS_URL'] ? true : false
    private selectedBrowser = script.params.AVAILABLE_BROWSERS

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebTests(script) {
        super(script, "DesktopWeb")
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
        String successMessage = 'Jasmine Test Results have been fetched successfully for DesktopWeb'
        String errorMessage = 'Failed to fetch the Jasmine Test Results for DesktopWeb'
        
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
        String successMessage = 'Test Results have been fetched successfully for DesktopWeb'
        String errorMessage = 'Failed to fetch the Test Results for DesktopWeb'

        script.catchErrorCustom(errorMessage, successMessage) {
            // 'testOutput' folder is being used for storing the log files, css files and all collectively and will be used while publishing them to S3 path. We will try to remove this reference and directly use the 'target' and 'test-output' folders going forward.
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
        
        String fullPathToDelete = jettyWebAppsFolder + script.params.JASMINE_TEST_URL.split('testresources')[-1]
        
        // Cleanup the jasmine test scripts in the jetty webapps folder
        script.shellCustom("set +x;rm -Rf $fullPathToDelete", true)
    }
    
    /*
     * This method publishes the test results to S3, based on the framework.
     */
    protected final publishTestsResults() {
        def s3ArtifactsPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER]
        s3ArtifactsPath.add("DesktopWeb")
        s3ArtifactsPath.add(selectedBrowser + '_' + browserVersionsMap[selectedBrowser])
        def s3PublishPath = s3ArtifactsPath.join('/').replaceAll('\\s', '_')
        if(isJasmineEnabled){
            publishJasmineResults(s3PublishPath)
        } else {
            publishTestNGResults(s3PublishPath)
        }
    }

    /*
     * This method publishes the jasmine test results to S3 in the given S3 path.
     * 
     * @param s3PublishPath is the S3 path into which the results will be published.
     */
    private publishJasmineResults(s3PublishPath) {
        String jasmineHTMLReport, bowserConsoleLog
        script.dir(testFolder) {
            def files = script.findFiles(glob: '**/TestResult_*.html')
            if (files.size() > 0) {
                jasmineHTMLReport = AwsHelper.publishToS3 bucketPath: s3PublishPath, sourceFileName: files[0].name,
                    sourceFilePath: "${testFolder}", script
                    listofLogFiles.put("Detailed Test Report", BuildHelper.createAuthUrl(jasmineHTMLReport, script, true));
            }
        }
        if (script.fileExists("${testFolder}/browserConsoleLog.txt")) {
            bowserConsoleLog = AwsHelper.publishToS3 bucketPath: s3PublishPath, sourceFileName: "browserConsoleLog.txt",
                sourceFilePath: "${testFolder}", script
            listofLogFiles.put("Bowser Console Log", BuildHelper.createAuthUrl(bowserConsoleLog, script, true));
        }
    }
    
    /*
     * This method publishes the TestNG test results to S3 in the given S3 path.
     *
     * @param s3PublishPath is the S3 path into which the results will be published.
     */
    private publishTestNGResults(s3PublishPath) {
        
        String testng_reportsCSSAndCSS = AwsHelper.publishToS3 bucketPath: s3PublishPath + "/testOutput/Smoke", sourceFileName: "testng.css,testng-reports.css",
                sourceFilePath: "${testFolder}/testOutput/Smoke", script, true

        suiteNameList.each {
            def suiteName = it.value
            def testListKeys = testList.keySet()
            def testListValues = testList.values()
            for(int testListVar=0;testListVar<testList.size();testListVar++){
                if(testListValues[testListVar].equalsIgnoreCase(suiteName.join(""))){
                    def testFolderForDWeb = testFolder
                    surefireReportshtml = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/Smoke/" + suiteName , sourceFileName: testListKeys[testListVar] + ".html",
                            sourceFilePath: testFolderForDWeb + "/testOutput/Smoke/" + suiteName, script, true
                    surefireReportshtmlAuthURL.add(BuildHelper.createAuthUrl(surefireReportshtml, script, true, "view"))
                }
            }
        }
        desktopTestRunResults.put("surefireReportshtml", surefireReportshtmlAuthURL)

        testMethodMap.each {
            //If there are some jpg files in the desired location, only then add them to the listofScreenshots which will be used in email tempalte.
            if (script.fileExists("${testFolder}/testOutput/test-output/Screenshots/${it.key}.jpg")) {
                String screenshotsURL = AwsHelper.publishToS3 bucketPath: s3PublishPath + "/testOutput/test-output/Screenshots", sourceFileName: it.key + ".jpg",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Screenshots", script, true
                listofScreenshots.put(it.key, BuildHelper.createAuthUrl(screenshotsURL, script, true))
            }

            //If there are some log files in the desired location (either under 'AppsCommon-Logs' or 'Logs' folder), only then add them to the listofLogFiles which will be used in email tempalte.
            if (script.fileExists("${testFolder}/testOutput/test-output/Appscommon-Logs/${it.key}.log")) {

                String logFileURL = AwsHelper.publishToS3 bucketPath: s3PublishPath + "/testOutput/test-output/Appscommon-Logs", sourceFileName: it.key + ".log",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Appscommon-Logs", script, true
                listofLogFiles.put(it.key, BuildHelper.createAuthUrl(logFileURL, script, true))

            } else if (script.fileExists("${testFolder}/testOutput/test-output/Logs/${it.key}.log")) {

                String logFileURL = AwsHelper.publishToS3 bucketPath: s3PublishPath + "/testOutput/test-output/Appscommon-Logs", sourceFileName: it.key + ".log",
                        sourceFilePath: "${testFolder}/testOutput/test-output/Logs", script, true
                listofLogFiles.put(it.key, BuildHelper.createAuthUrl(logFileURL, script, true))

            }
        }
    }

    /**
     * Uploads application binaries and Schedules the run for DesktopWeb.
     */
    private final void runTests(browserName, testFolder) {
        script.dir(testFolder) {
            // Create these directories so that the generated log files and screenshots will be saved here.
            script.shellCustom("mkdir -p test-output/Screenshots", true)
            script.shellCustom("mkdir -p test-output/Logs", true)
            script.shellCustom("mkdir -p test-output/Appscommon-Logs", true)
            
            if(isJasmineEnabled) {
                scriptArguments = " -Dsurefire.suiteXmlFiles=Testng.xml -DJASMINE_TEST_APP_URL=${script.params['JASMINE_TEST_URL']}"
            }
            else {
                scriptArguments.contains('-Dsurefire.suiteXmlFiles')?: (scriptArguments += " -Dsurefire.suiteXmlFiles=Testng.xml")
            }
            
            def testHyphenDParams = "-DDRIVER_PATH=${script.env.CHROME_DRIVER_PATH} -DBROWSER_PATH=${script.env.CHROME_BROWSER_PATH} -DWEB_APP_URL=${script.params['FABRIC_APP_URL']} -Dmaven.test.failure.ignore=true -DFILE_DOWNLOAD_PATH=" + testFolder 
            
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
        /* Filter desktopWeb application binaries build parameters */
        def publishedAppUrlParameters = (!buildParameters['FABRIC_APP_URL']) ? [:] : ['FABRIC_APP_URL': buildParameters['FABRIC_APP_URL']]
        /* Filter all SCM build parameters */
        def scmParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        /* Filter desktopWeb test binaries build parameter */
        def testBinaryUrlParameter = (!buildParameters['DESKTOPWEB_TESTS_URL']) ? [:] : ['DESKTOPWEB_TESTS_URL': buildParameters['DESKTOPWEB_TESTS_URL']]
        /* Combine desktopWeb binaries build parameters */
        def urlParameters = testBinaryUrlParameter + publishedAppUrlParameters

        if (scmParameters && testBinaryUrlParameter) {
            throw new AppFactoryException("Please provide only one option for the source of test scripts: GIT or TESTS_URL",'ERROR')
        }
        /* Same if none of the options provided */
        else if (!scmParameters && !testBinaryUrlParameter) {
            throw new AppFactoryException("Please provide at least one source of test binaries",'ERROR')
        }
        /* Fail build if testBinaryUrlParameter been provided without publishedAppUrlParameters */
        if (!publishedAppUrlParameters && (testBinaryUrlParameter || scmParameters)) {
            throw new AppFactoryException("Please provide at least one of application binaries URL",'ERROR')
        }
        
        /* Check if at least one application binaries parameter been provided */
        (!publishedAppUrlParameters) ?: validateApplicationBinariesURLs(urlParameters)
    }

    protected createPipeline() {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Validate parameters') {
                    validateBuildParameters(script.params)
                }
                
                nodeLabel = TestsHelper.getTestNode(script, libraryProperties, isJasmineEnabled, 'DesktopWeb')
                
                /* Allocate a slave for the run */
                script.node(nodeLabel) {

                    try {

                        pipelineWrapper {
                            /*
                            Clean workspace, to be sure that we have not any items from previous build,
                            and build environment completely new.
                            */
                            script.cleanWs deleteDirs: true

                            /* Build test automation scripts if URL with test binaries was not provided */
                            if (!isTestScriptGiven) {
                                script.stage('Checkout') {
                                    BuildHelper.checkoutProject script: script,
                                            projectRelativePath: checkoutRelativeTargetFolder,
                                            scmBranch: scmBranch,
                                            checkoutType: "scm",
                                            scmCredentialsId: scmCredentialsId,
                                            scmUrl: scmUrl
                                }
                                /* Get the automation test folder */
                                testFolder = getTestsFolderPath(projectFullPath)

                                /* Preparing the environment for the Jasmine Tests */
                                if(isJasmineEnabled){
                                    prepareEnvForJasmineTests(testFolder)
                                }

                                script.stage('Build') {
                                    /* Build Test Automation scripts */
                                    buildTestScripts(testFolder)
                                }
                            }

                            /* Run tests on provided binaries */
                            script.stage('Run the Tests') {
                                runTests(script.params.AVAILABLE_BROWSERS, testFolder)
                            }
                            script.stage('Get Test Results') {
                                fetchTestResults(testFolder)
                                if (!desktopTestRunResults && !jasmineTestResults)
                                    throw new AppFactoryException('DesktopWeb tests results are not found as the run result is skipped.', 'ERROR')
                                publishTestsResults()
                            }
                            script.stage('Check PostTest Hook Points') {
                                if (!desktopTestRunResults && !jasmineTestResults)
                                    throw new AppFactoryException('DesktopWeb tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                script.currentBuild.result = overAllTestsResultsStatus ? 'SUCCESS' : 'UNSTABLE'

                                if (runCustomHook) {
                                    if (overAllTestsResultsStatus) {
                                        def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', "DESKTOP_WEB_STAGE")
                                        if (!isSuccess)
                                            throw new Exception("Something went wrong with the Custom hooks execution.")
                                    } else {
                                        script.echoCustom('Tests got failed for DesktopWeb. Hence CustomHooks execution is skipped.', 'WARN')
                                    }
                                } else {
                                    script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                }
                            }
                        }
                    } finally {
                        /* Add the test results to env variables so that those can be accessible from FacadeTests class and will be used during email template creation */
                        script.env['DESKTOP_TEST_RUN_RESULTS'] = desktopTestRunResults?.inspect()
                        script.env['DESKTOP_JASMINE_TEST_RESULTS'] = jasmineTestResults?.inspect()
                        script.env['LOG_FILES_LIST'] = listofLogFiles?.inspect()
                        script.env['SCREENSHOTS_LIST'] = listofScreenshots?.inspect()

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
                                jasmineruns      : jasmineTestResults,
                                desktopruns      : desktopTestRunResults,
                                listofLogFiles   : listofLogFiles,
                                listofScreenshots: listofScreenshots,
                                testArtifact : testArtifact,
                                testFramework : testFramework,
                                jasmineWebTestPlan : jasmineTestPlan
                        ], true)
                        if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                            TestsHelper.PrepareMustHaves(script, runCustomHook, "runDesktopWebTests", libraryProperties, mustHaveArtifacts, false)
                            if (TestsHelper.isBuildDescriptionNeeded(script))
                                TestsHelper.setBuildDescription(script)
                        }

                    }
                }
            }
        }
    }
}