package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper

import groovyx.net.http.*
import groovy.util.slurpersupport.*
import java.util.*

class DesktopWebTests extends RunTests implements Serializable {

    /* Build parameters */
    protected scriptArguments = script.params.RUN_DESKTOPWEB_TESTS_ARGUMENTS

    private static desktopTestRunResults = [:]
    /* desktopweb tests Map variables */
    def static browserVersionsMap = [:], listofLogFiles = [:], listofScreenshots = [:], summary = [:], testList = [:], testMethodMap = [:], classList = [:], testStatusMap = [:], duration = [:], runArnMap = [:]
    def suiteNameList = [], surefireReportshtmlAuthURL = []
    def failedTests = 0, totalTests = 0, passedTests = 0, skippedTests = 0

    String surefireReportshtml = ""

    /*Added backward compatibility check here so that it works for both NATIVE_TESTS_URL and TESTS_BINARY_URL */
    private testPackage = BuildHelper.getCurrentParamValue(script, 'NATIVE_TESTS_URL', 'TESTS_BINARY_URL')?: 'jobWorkspace'

    private runTests = false

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebTests(script) {
        super(script)
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
    private final def fetchTestResults(testFolder) {
        String successMessage = 'Test Results have been fetched successfully for DesktopWeb'
        String errorMessage = 'Failed to fetch the Test Results for DesktopWeb'

        script.catchErrorCustom(errorMessage, successMessage) {
            String testOutputFolder = testFolder + "/testOutput"
            script.dir(testOutputFolder) {
                script.shellCustom("mkdir -p Smoke", true)
                script.shellCustom("mkdir -p test-output", true)
            }
            script.dir(testFolder) {
                script.shellCustom("cp -R target/surefire-reports/* testOutput/Smoke", true)
                script.shellCustom("cp -R test-output/Appscommon-Logs testOutput/test-output", true)
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
        desktopTestRunResults << ["suiteName":suiteNameList, "className":classList, "testName":testList, "testMethod":testMethodMap, "testStatusMap":testStatusMap, "duration":durationList, "finishTime":finishedAtList]
        desktopTestRunResults << ["passedTests":passedTests, "skippedTests":skippedTests, "failedTests":failedTests, "totalTests":totalTests, "browserName":script.params.AVAILABLE_BROWSERS, "browserVersion":browserVersionsMap[script.params.AVAILABLE_BROWSERS], "startTime":startedAtList]

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

    protected publishTestsResults() {
        def s3ArtifactsPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER]
        s3ArtifactsPath.add("DesktopWeb")
        s3ArtifactsPath.add(desktopTestRunResults["browserName"] + '_' + desktopTestRunResults["browserVersion"])
        def s3PublishPath = s3ArtifactsPath.join('/').replaceAll('\\s', '_')
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
            String screenshotsURL = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/test-output/Screenshots", sourceFileName: it.key + ".jpg",
                    sourceFilePath: "${testFolder}/testOutput/test-output/Screenshots", script, true
            listofScreenshots.put(it.key, BuildHelper.createAuthUrl(screenshotsURL, script, true))
            String logFileURL = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/test-output/Appscommon-Logs", sourceFileName: it.key + ".log",
                    sourceFilePath: "${testFolder}/testOutput/test-output/Appscommon-Logs", script, true
            listofLogFiles.put(it.key, BuildHelper.createAuthUrl(logFileURL, script, true))
        }
    }

    /**
     * Uploads application binaries and Schedules the run for DesktopWeb.
     */
    private final void runTests(browserName, testFolder) {
        script.dir(testFolder) {
            scriptArguments.contains('-Dsurefire.suiteXmlFiles')?: (scriptArguments += " -Dsurefire.suiteXmlFiles=Testng.xml")
            switch (browserName) {
                case 'CHROME':
                    script.shellCustom("mvn test -DDRIVER_PATH=${script.env.CHROME_DRIVER_PATH} -DBROWSER_PATH=${script.env.CHROME_BROWSER_PATH} -Dmaven.test.failure.ignore=true ${scriptArguments}", true)
                    browserVersionsMap << ["CHROME":getBrowserVersion('CHROME', script.env.CHROME_BROWSER_PATH)]
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
                /* Allocate a slave for the run */
                script.node(libraryProperties.'test.dweb.automation.node.label') {

                    pipelineWrapper("DesktopWeb", {
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        /* Build test automation scripts if URL with test binaries was not provided */
                        if (testPackage == 'jobWorkspace') {
                            script.stage('Checkout') {
                                BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        checkoutType: "scm",
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }

                            script.stage('Build') {
                                /* Build Test Automation scripts */
                                buildTestScripts("DesktopWeb", testFolder)
                                /* Set runTests flag to true when build is successful,otherwise set it to false (in catch block) */
                                runTests = true
                            }
                        }
                    })

                    /* To make runTests flag true when Tests URL is given instead of passing SCM details */
                    if (testPackage != 'jobWorkspace') {
                        runTests = true
                    }

                    /* Run tests on provided binaries */
                    if (runTests) {
                        try {
                                script.stage('Run the Tests') {
                                    runTests(script.params.AVAILABLE_BROWSERS, testFolder)
                                }
                                script.stage('Get Test Results') {
                                    desktopTestRunResults = fetchTestResults(testFolder)
                                    if (!desktopTestRunResults)
                                        throw new AppFactoryException('DesktopWeb tests results are not found as the run result is skipped.', 'ERROR')
                                    publishTestsResults()
                                }
                                script.stage('Check PostTest Hook Points'){
                                    def testsResultsStatus = true
                                    if (!desktopTestRunResults)
                                        throw new AppFactoryException('DesktopWeb tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                    testsResultsStatus = !testStatusMap.any {it.value != "PASS"}
                                    testsResultsStatus ?: (script.currentBuild.result = 'UNSTABLE')

                                    if(runCustomHook) {
                                        if(testsResultsStatus) {
                                            def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', "DESKTOP_WEB_STAGE")
                                            if (!isSuccess)
                                                throw new Exception("Something went wrong with the Custom hooks execution.")
                                        } else {
                                            script.echoCustom('Tests got failed for DesktopWeb. Hence CustomHooks execution is skipped.', 'WARN')
                                        }
                                    }
                                    else{
                                        script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                    }
                                }

                        } catch (AppFactoryException e) {
                            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage, e.getErrorType(), false)
                            script.currentBuild.result = 'FAILURE'
                        } catch (Exception e) {
                            String exceptionMessage = (e.toString()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage,'WARN')
                            script.currentBuild.result = 'FAILURE'
                        } finally {
                            /* Add the test results to env variables so that those can be accessible from FacadeTests class and will be used during email template creation */
                            script.env['DESKTOP_TEST_RUN_RESULTS'] = desktopTestRunResults?.inspect()
                            script.env['LOG_FILES_LIST'] = listofLogFiles?.inspect()
                            script.env['SCREENSHOTS_LIST'] = listofScreenshots?.inspect()

                            NotificationsHelper.sendEmail(script, 'runTests', [
                                    desktopruns: desktopTestRunResults,
                                    listofLogFiles: listofLogFiles,
                                    listofScreenshots: listofScreenshots
                            ], true)
                            if (script.currentBuild.result != 'SUCCESS') {
                                TestsHelper.PrepareMustHaves(script, runCustomHook, "runDesktopWebTests", libraryProperties)
                                (!TestsHelper.isBuildDescriptionNeeded(script)) ?: TestsHelper.setBuildDescription(script)

                            }
                        }
                    }
                }
            }
        }
    }
}