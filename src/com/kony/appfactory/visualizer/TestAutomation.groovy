package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AwsDeviceFarmHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.AppFactoryException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.*
import groovy.util.slurpersupport.*
import org.apache.commons.lang.StringUtils
import java.io.File
import java.util.*

/**
 * Implements logic for runTests job.
 *
 * runTests job responsible for Test Automation scripts build and scheduling Device Farm test runs with provided
 *  application binaries and device pools.
 *
 * Test run and Test Automation scripts artifacts are stored on S3 according with approved folder structure.
 *
 * E-mail notification template and JSON file for App Factory console,
 *  also stored on S3 according with approved folder structure.
 */
class TestAutomation implements Serializable {
    /* Pipeline object */
    private script
    /* Library configuration */
    private libraryProperties
    /*
        Platform dependent default name-separator character as String.
        For windows, it's '\' and for unix it's '/'.
        Because all logic for test will be run on linux, set separator to '/'.
     */
    private separator = '/'
    /* Job workspace path */
    private workspace
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    /*
        If projectRoot value has been provide, than value of this property
        will be set to <job_workspace>/vis_ws/<project_name> otherwise it will be set to <job_workspace>/vis_ws
     */
    protected projectWorkspacePath
    /* Absolute path to the project folder (<job_workspace>/vis_ws/<project_name>[/<project_root>]) */
    private projectFullPath
    /* must gathering related variables */
    protected String upstreamJob = null
    protected boolean isRebuild = false
    protected String s3MustHaveAuthUrl
    /*
        Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and
        can not be used.
     */
    final projectWorkspaceFolderName
    /* Folder name with test automation scripts */
    private testFolder
    private testFolderForDesktopWeb
    /* Build parameters */
    private scmBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private scmCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private devicePoolName = script.params.AVAILABLE_TEST_POOLS
    protected scriptArgumentsForDesktopWeb = script.params.RUN_DESKTOPWEB_TESTS_ARGUMENTS
    /* Environment variables */
    private projectName = script.env.PROJECT_NAME
    private projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')
    private scmUrl = script.env.PROJECT_SOURCE_CODE_URL
    private runCustomHook = script.params.RUN_CUSTOM_HOOKS
    /* Device Farm properties */
    private runTests = false
    /* Device Farm scripts object */
    private deviceFarm
    private deviceFarmProjectArn
    private devicePoolArns
    private deviceFarmTestUploadArtifactArn
    private deviceFarmUploadArns = []
    private deviceFarmTestRunArns = [:]
    private deviceFarmTestRunResults = []
    private desktopTestRunResults = [:]
    /* desktopweb tests Map variables */
    def listofLogFiles = [:], listofScreenshots = [:],summary = [:], testList = [:], testMethodMap = [:], classList = [:], testStatusMap = [:], duration = [:], runArnMap = [:]
    def suiteNameList = [], surefireReportshtmlAuthURL = []
    def failedTests = 0, totalTests = 0, passedTests = 0, skippedTests = 0
    def browserVersionsMap = [:]
    String surefireReportshtml = ""	
    /* Temp folder for Device Farm objects (test run results) */
    private deviceFarmWorkingFolder
    /* Device Farm AWS region */
    private awsRegion
    private projectArtifacts = [
            Android_Mobile: [binaryName: getBinaryName(script.env.ANDROID_MOBILE_NATIVE_BINARY_URL),
                             extension : 'apk',
                             uploadType: 'ANDROID_APP',
                             url       : script.env.ANDROID_MOBILE_NATIVE_BINARY_URL],
            Android_Tablet: [binaryName: getBinaryName(script.env.ANDROID_TABLET_NATIVE_BINARY_URL),
                             extension : 'apk',
                             uploadType: 'ANDROID_APP',
                             url       : script.env.ANDROID_TABLET_NATIVE_BINARY_URL],
            iOS_Mobile    : [binaryName: getBinaryName(script.env.IOS_MOBILE_NATIVE_BINARY_URL),
                             extension : 'ipa',
                             uploadType: 'IOS_APP',
                             url       : script.env.IOS_MOBILE_NATIVE_BINARY_URL],
            iOS_Tablet    : [binaryName: getBinaryName(script.env.IOS_TABLET_NATIVE_BINARY_URL),
                             extension : 'ipa',
                             uploadType: 'IOS_APP',
                             url       : script.env.IOS_TABLET_NATIVE_BINARY_URL]
    ]

    /*Added backward compatibility check here so that it works for both NATIVE_TESTS_URL and TESTS_BINARY_URL */
    private testPackage = [
            "${projectName}_TestApp": [extension : 'zip',
                                       uploadType: 'APPIUM_JAVA_TESTNG_TEST_PACKAGE',
                                       url       : (script.env.NATIVE_TESTS_URL ?: script.env.TESTS_BINARY_URL?: 'jobWorkspace')]
    ]
    public isDesktopwebApp = script.params.findAll { it.key == 'FABRIC_APP_URL' && it.value }
    public isNativeApp =  script.params.findAll { it.key.contains('NATIVE_BINARY_URL') && it.value }

    /* CustomHookHelper object */
    protected hookHelper

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    TestAutomation(script) {
        this.script = script
        this.hookHelper = new CustomHookHelper(script)
        /* Initializer Device Farm scrips object */
        deviceFarm = new AwsDeviceFarmHelper(this.script)
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        awsRegion = libraryProperties.'test.automation.device.farm.aws.region'
        this.script.env['CLOUD_ACCOUNT_ID'] = (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
    }

    /**
     * Validates build parameters.
     *
     * @param buildParameters job parameters.
     */
    protected final void validateBuildParameters(buildParameters) {
        /* Filter all application binaries build parameters */
        def nativeAppBinaryUrlParameters = buildParameters.findAll {
            it.key.contains('NATIVE_BINARY_URL') && it.value
        }
        /* Filter desktopWeb application binaries build parameters */
        def desktopWebPublishedAppUrlParameters = buildParameters.findAll { it.key.contains('FABRIC_APP_URL') && it.value }

        /* Filter all SCM build parameters */
        def scmParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        /* Filter test binaries build parameter. This check is needed to maintain backward compatibility*/
        def nativeTestsUrl = script.params.containsKey('NATIVE_TESTS_URL') ? 'NATIVE_TESTS_URL' : 'TESTS_BINARY_URL'
        def nativeTestBinaryUrlParameter = buildParameters.findAll { it.key == nativeTestsUrl && it.value }
        /* Filter desktopWeb test binaries build parameter */
        def desktopWebTestBinaryUrlParameter = buildParameters.findAll { it.key == 'DESKTOPWEB_TESTS_URL' && it.value }
        /* Filter pool name build parameter */
        def poolNameParameter = buildParameters.findAll { it.key.contains('AVAILABLE_TEST_POOLS') && it.value }
        /* Combine binaries build parameters */
        def nativeUrlParameters = nativeTestBinaryUrlParameter + nativeAppBinaryUrlParameters
        /* Combine desktopWeb binaries build parameters */
        def desktopWebUrlParameters = desktopWebTestBinaryUrlParameter + desktopWebPublishedAppUrlParameters

        /* Check if at least one application binaries parameter been provided */
        (!nativeAppBinaryUrlParameters) ?: validateApplicationBinariesURLs(nativeUrlParameters)
        (!desktopWebPublishedAppUrlParameters) ?: validateApplicationBinariesURLs(desktopWebUrlParameters)

        /* Restrict the user to run tests either with Universal build binary or with normal native test binaries,
           fail the build if both options are provided.
         */
        if (script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL && (script.env.ANDROID_MOBILE_NATIVE_BINARY_URL || script.env.ANDROID_TABLET_NATIVE_BINARY_URL)) {
            throw new AppFactoryException("Sorry, You can't run test for Android Universal binary along with Android Mobile/Tablet",'ERROR')
        }
        if (script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL && (script.env.IOS_MOBILE_NATIVE_BINARY_URL || script.env.IOS_TABLET_NATIVE_BINARY_URL)) {
            throw new AppFactoryException("Sorry, You can't run test for iOS Universal binary along with iOS Mobile/Tablet",'ERROR')
        }
        if (scmParameters && (nativeTestBinaryUrlParameter || desktopWebTestBinaryUrlParameter)) {
            throw new AppFactoryException("Please provide only one option for the source of test scripts: GIT or TESTS_URL",'ERROR')
        }
        /* Same if none of the options provided */
        else if (!nativeTestBinaryUrlParameter && !scmParameters && !desktopWebTestBinaryUrlParameter) {
            throw new AppFactoryException("Please provide at least one source of test binaries",'ERROR')
        }

        /* Fail build if nativeTestBinaryUrlParameter been provided without nativeAppBinaryUrlParameters, similarly for DesktopWeb */
        if ((!nativeAppBinaryUrlParameters && nativeTestBinaryUrlParameter) || (!desktopWebPublishedAppUrlParameters && desktopWebTestBinaryUrlParameter)) {
            throw new AppFactoryException("Please provide at least one of application binaries URL",'ERROR')
        }
        /* Fail build if nativeAppBinaryUrlParameters been provided without test pool */
        else if (!poolNameParameter && nativeAppBinaryUrlParameters) {
            throw new AppFactoryException("Please provide pool to test on",'ERROR')
        }
    }

    /**
     * Validates provided URL.
     *
     * @param urlString URL to validate.
     * @return validation result (true or false).
     */
    protected final boolean isValidUrl(urlString) {
        try {
            urlString.replace(" ", "%20").toURL().toURI()
            return true
        } catch (Exception exception) {
            return false
        }
    }

    /**
     * Validate application binaries URLs
     */

    protected final void validateApplicationBinariesURLs(AppBinaryUrlParameters) {
        for (parameter in AppBinaryUrlParameters) {
            if (parameter.value.contains('//') && isValidUrl(parameter.value))
                parameter.value=parameter.value.replace(" ", "%20")
            else
                throw new AppFactoryException("Build parameter ${parameter.key} value is not valid URL!",'ERROR')
        }
    }

    /**
     * Builds Test Automation scripts for Native and DesktopWeb.
     */
    protected final void buildTestScripts(nativeOrWeb) {
        String successMessage = "Test Automation scripts have been built successfully for ${nativeOrWeb}"
        String errorMessage = "Failed to build the Test Automation scripts for ${nativeOrWeb}"

        script.catchErrorCustom(errorMessage, successMessage) {
            if(nativeOrWeb.equals("Native")){
                script.dir(testFolder) {
                    script.shellCustom('mvn clean package -DskipTests=true', true)
                    script.shellCustom("mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip", true)
                }
            }
            if(nativeOrWeb.equals("DesktopWeb")){
                script.dir(testFolderForDesktopWeb) {
                    script.shellCustom('mvn clean package -DskipTests=true', true)
                }
            }
        }
    }

    /**
     * Prepares steps to run in parallel. Used for fetching and uploading artifacts in parallel.
     *
     * @param artifacts artifact name.
     * @param stageName stage name (for console output).
     * @param stepClosure step body.
     */
    protected final void prepareParallelSteps(artifacts, stageName, stepClosure) {
        def stepsToRun = [:]
        def artifactsNames = artifacts.keySet().toArray()
        for (int i = 0; i < artifactsNames.size(); i++) {
            def artifact = artifacts.get(artifactsNames[i])
            def artifactName = artifactsNames[i]
            def artifactUrl = artifact.url
            def artifactExt = artifact.extension
            def uploadType = artifact.uploadType
            if (artifactUrl) {
                def step = {
                    stepClosure(artifactName, artifactUrl, artifactExt, uploadType)
                }

                stepsToRun.put("${stageName}${artifactName}", step)
            } else {
                script.echoCustom("${artifactName.replaceAll('_', ' ')} binary was not provided!",'WARN')
            }
        }

        stepsToRun
    }
    
    /**
     * Cleans Device Farm objects after every run.
     * Currently removes only uploads and device pools that are generated on every run.
     *
     * @param deviceFarmUploadArns list of upload ARNs.
     * @param devicePoolArns list of pool ARNs.
     */
    protected final void cleanup(deviceFarmUploadArns, devicePoolArns) {
        script.withAWS(region: awsRegion) {
            if (deviceFarmUploadArns) {
                for (int i = 0; i < deviceFarmUploadArns.size(); ++i) {
                    def deviceFarmUploadArn = deviceFarmUploadArns[i]
                    deviceFarm.deleteUploadedArtifact(deviceFarmUploadArn)
                }
            }

            if (devicePoolArns) {
                def poolNames = devicePoolArns.keySet().toArray()
                /* Workaround to iterate over map keys in c++ style for loop */
                for (int i = 0; i < poolNames.size(); ++i) {
                    def devicePoolArn = devicePoolArns[poolNames[i]].value

                    deviceFarm.deleteDevicePool(devicePoolArn)
                }
            }
        }
    }

    /**
     * Parses binary URL to get binary name.
     *
     * @param urlString provided URL.
     * @return binary name.
     */
    @NonCPS
    protected final getBinaryName(urlString) {
        def binaryName = (urlString) ? urlString.replaceAll(/.*\//, '') : ''
        
        binaryName
    }

    /**
     * Collects binaries names for e-mail notification.
     *
     * @param artifacts list of artifacts that been provided for the test run.
     * @return key value pairs with artifact name as a key and binary name as a value.
     */
    private final getBinaryNameForEmail(artifacts) {
        def result = [:]

        for (artifact in artifacts) {
            result[artifact.key] = artifact.value.get('binaryName')
        }

        result
    }

    /**
     * Fetches test run results from Device Farm, generates data for e-mail template and JSON file and moves artifacts
     * to customer S3 bucket.
     */
    private final void fetchTestResults() {
        def stepsToRun = [:]
        def deviceFarmTestRunArnsKeys = deviceFarmTestRunArns.keySet().toArray()

        /* Workaround to iterate over map keys in c++ style for loop */
        for (int i = 0; i < deviceFarmTestRunArnsKeys.size(); ++i) {
            def arn = deviceFarmTestRunArns[deviceFarmTestRunArnsKeys[i]]
            script.echoCustom("Run ARN for ${deviceFarmTestRunArnsKeys[i]} is: " + arn)
            /* Prepare step to run in parallel */
            stepsToRun["testResults_${deviceFarmTestRunArnsKeys[i]}"] = {
                def testRunResult = deviceFarm.getTestRunResult(arn)
                /* If we got a test result */
                if (testRunResult) {
                    /*
                        Query Device Farm for test artifacts (logs, videos, etc) and
                        store test run artifact object in list.
                        Using addAll method to collect all run results, because
                        getTestRunArtifacts returns list of one item (run result).
                     */
                    deviceFarmTestRunResults.addAll(deviceFarm.getTestRunArtifacts(arn))

                }
                /* else notify user that result value is empty */
                else {
                    script.echoCustom("Test run result for ${deviceFarmTestRunArnsKeys[i]} is empty!",'WARN')
                }
		 summary.putAll(deviceFarm.testSummaryMap)
            }
        }
	
        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }
	
	def separator = {
            script.echoCustom("*"*99);
        }

	 script.echoCustom("Test execution is completed for all the devices in device pool.", 'INFO' )
        script.echoCustom("Summary of Test Results : ",'INFO')
	 separator()
	 separator()
        duration.putAll(deviceFarm.durationMap)
        runArnMap.putAll(deviceFarm.runArnMap)
        summary.each{
            script.echoCustom("On " + it.key + ":: " + it.value + ", Duration: " + duration[it.key] + ", Run ARN: " + runArnMap[it.key])
        }
	 separator()
	 separator()
	
        /* Move artifacts to customer bucket */
        script.dir('artifacts') {
            deviceFarm.moveArtifactsToCustomerS3Bucket(
                    deviceFarmTestRunResults,
                    ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
            )
        }
    }

    /**
     * Collect all the required log files, store them in a folder under testFolderForDesktopWeb
     * publish the artifacts and parse the results to form email template
     * We create certain folder structure which is used to display screenshots and logs in html which will be shown in email
     * First we create testOutput folder, under that 2 sub-folders Smoke and test-output
     * Now copy the content of target/surefire-reports/ to testOutput/Smoke
     * Also copy files from test-output/Appscommon-Logs to testOutput/test-output, now copy from test-output/Screenshots to testOutput/test-output
     * Now we have all the date in testOutput folder , zip this and place it under target/${projectName}_TestApp
     */
    private final void fetchTestResultsforDesktopWeb() {
        String successMessage = 'Test Results have been fetched successfully for DesktopWeb'
        String errorMessage = 'Failed to fetch the Test Results for DesktopWeb'

        script.catchErrorCustom(errorMessage, successMessage) {
            String testOutputFolderForDesktopWeb = testFolderForDesktopWeb + "/testOutput"
            script.dir(testOutputFolderForDesktopWeb) {
                script.shellCustom("mkdir -p Smoke", true)
                script.shellCustom("mkdir -p test-output", true)
            }
            script.dir(testFolderForDesktopWeb) {
                script.shellCustom("cp -R target/surefire-reports/* testOutput/Smoke", true)
                script.shellCustom("cp -R test-output/Appscommon-Logs testOutput/test-output", true)
                script.shellCustom("cp -R test-output/Screenshots testOutput/test-output", true)
                script.shellCustom("zip -r target/${projectName}_TestApp testOutput", true)
            }
        }

        /* Parse testng-results, collecting the count of total failed/passed/skipped/total tests, to display in email notification */
        String testNGResultsFileContent=script.readFile("${testFolderForDesktopWeb}/target/surefire-reports/testng-results.xml")
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
            durationList.add(convertTimeFromMilliseconds(Long.valueOf(testng_results.suite[suiteNo]."@duration-ms".join(""))))
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
                            testMethodMap.put(testng_results.suite[suiteNo].test[testsCount].class[classCount]."test-method"[testMethodCount].@name.join(""),testng_results.suite[suiteNo].test[testsCount].class[classCount].@name.join(""))
                        }
                        testMethodCount++
                    }
                    classCount++
                }
                testsCount++
            }
            suiteNo++
        }
        desktopTestRunResults << ["suiteName":suiteNameList, "className":classList, "testName":testList, "testMethod":testMethodMap, "testStatusMap":testStatusMap, "duration":durationList, "finishTime":finishedAtList]
        desktopTestRunResults << ["passedTests":passedTests, "skippedTests":skippedTests, "failedTests":failedTests, "totalTests":totalTests, "browserName":script.params.AVAILABLE_BROWSERS, "browserVersion":browserVersionsMap[script.params.AVAILABLE_BROWSERS], "startTime":startedAtList]
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

    void publishDesktopWebTestsResults() {
        def s3ArtifactsPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER]
        s3ArtifactsPath.add("DesktopWeb")
        s3ArtifactsPath.add(desktopTestRunResults["browserName"] + '_' + desktopTestRunResults["browserVersion"])
        def s3PublishPath = s3ArtifactsPath.join('/').replaceAll('\\s', '_')
        String testng_reportsCSSAndCSS = AwsHelper.publishToS3 bucketPath: s3PublishPath + "/testOutput/Smoke", sourceFileName: "testng.css,testng-reports.css",
                sourceFilePath: "${testFolderForDesktopWeb}/testOutput/Smoke", script, true

        suiteNameList.each {
            def suiteName = it.value
            def testListKeys = testList.keySet()
            def testListValues = testList.values()
            for(int testListVar=0;testListVar<testList.size();testListVar++){
                if(testListValues[testListVar].equalsIgnoreCase(suiteName.join(""))){
                    def testFolderForDWeb = testFolderForDesktopWeb
                    surefireReportshtml = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/Smoke/" + suiteName , sourceFileName: testListKeys[testListVar] + ".html",
                            sourceFilePath: testFolderForDWeb + "/testOutput/Smoke/" + suiteName, script, true
                    surefireReportshtmlAuthURL.add(BuildHelper.createAuthUrl(surefireReportshtml, script, true, "view"))
                }
            }
        }
        desktopTestRunResults.put("surefireReportshtml", surefireReportshtmlAuthURL)
        testMethodMap.each {
            String screenshotsURL = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/test-output/Screenshots", sourceFileName: it.key + ".jpg",
                    sourceFilePath: "${testFolderForDesktopWeb}/testOutput/test-output/Screenshots", script, true
            listofScreenshots.put(it.key, BuildHelper.createAuthUrl(screenshotsURL, script, true))
            String logFileURL = AwsHelper.publishToS3  bucketPath: s3PublishPath + "/testOutput/test-output/Appscommon-Logs", sourceFileName: it.key + ".log",
                    sourceFilePath: "${testFolderForDesktopWeb}/testOutput/test-output/Appscommon-Logs", script, true
            listofLogFiles.put(it.key, BuildHelper.createAuthUrl(logFileURL, script, true))
        }
    }

    /**
     * Uploads application binaries and Schedules the run.
     */
    private final void uploadAndRun() {
        /* Prepare step to run in parallel */
        def step = { artifactName, artifactURL, artifactExt, uploadType ->
            /* Upload application binaries to Device Farm */
            def uploadArn = deviceFarm.uploadArtifact(deviceFarmProjectArn,
                    uploadType, artifactName + '.' + artifactExt)
            /* Add upload ARN to list for cleanup at the end of the build */
            deviceFarmUploadArns.add(uploadArn)

            /* Depending on artifact name we need to chose appropriate pool for the run */
            def devicePoolArn = artifactName.toLowerCase().contains('mobile') ?
                    (devicePoolArns.phones) ?: script.echoCustom("Artifacts provided " +
                            "for phones, but no phones were found in the device pool",'ERROR') :
                    (devicePoolArns.tablets ?: script.echoCustom("Artifacts provided for " +
                            "tablets, but no tablets were found in the device pool",'ERROR'))

            /* If we have application binaries and test binaries, schedule the run */
            if (uploadArn && deviceFarmTestUploadArtifactArn) {
                /* Once all parameters gotten, schedule the Device Farm run */
                def runArn = deviceFarm.scheduleRun(deviceFarmProjectArn, devicePoolArn,
                        'APPIUM_JAVA_TESTNG', uploadArn, deviceFarmTestUploadArtifactArn, artifactName)
                deviceFarmTestRunArns["$artifactName"] = runArn
                /* Otherwise, fail the stage, because run couldn't be scheduled without one of the binaries */
            } else {
                script.echoCustom("Failed to get uploadArn",'WARN')
            }
        }
        /* Setting the Universal binary url to respective platform input run test job paramaters*/
        if (script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'Android_Mobile'.'url' = devicePoolArns.phones ? script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL : null
            projectArtifacts.'Android_Tablet'.'url' = devicePoolArns.tablets ? script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL : null
        }
        if (script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'iOS_Mobile'.'url' = devicePoolArns.phones ? script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL : null
            projectArtifacts.'iOS_Tablet'.'url' = devicePoolArns.tablets ? script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL : null
        }
        /* Prepare parallel steps */
        def stepsToRun = prepareParallelSteps(projectArtifacts, 'uploadAndRun_', step)
        
        if(!stepsToRun){
            throw new AppFactoryException("No artifacts to upload and run!",'ERROR')
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }
    }

    /**
     * Uploads application binaries and Schedules the run for DesktopWeb.
     */
    private final void runTestsforDesktopWeb(browserName) {
          script.dir(testFolderForDesktopWeb) {
              scriptArgumentsForDesktopWeb.contains('-Dsurefire.suiteXmlFiles')?: (scriptArgumentsForDesktopWeb += " -Dsurefire.suiteXmlFiles=Testng.xml")
              switch (browserName) {
                  case 'CHROME':
                      script.shellCustom("mvn test -DDRIVER_PATH=${script.env.CHROME_DRIVER_PATH} -DBROWSER_PATH=${script.env.CHROME_BROWSER_PATH} -Dmaven.test.failure.ignore=true ${scriptArgumentsForDesktopWeb}", true)
                      browserVersionsMap << ["CHROME":getBrowserVersion('CHROME', script.env.CHROME_BROWSER_PATH)]
                      break
                  default:
                      throw new AppFactoryException("Unable to find the browser.. might be unknown/unsupported browser selected!!", 'ERROR')
                      break
              }
          }
    }

    /**
     * Fetches application and test binaries (if provided).
     */
    private final void fetchBinaries() {
        /* Prepare step to run in parallel */
        def step = { artifactName, artifactURL, artifactExt ->
            /* If test binaries URL was not provided, copy binaries from the build step */
            if (artifactURL == 'jobWorkspace') {
                String artifactPath = "${testFolder}/target/${artifactName}.${artifactExt}"
                script.shellCustom("cp $artifactPath $deviceFarmWorkingFolder", true)
                /* else, fetch binaries */
            } else {
                deviceFarm.fetchArtifact(artifactName + '.' + artifactExt, artifactURL)
            }
        }
        /*For universal build test run job setting the artifacts url path to fetch the binary from universal artifacts  */
        if (script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'Android_Mobile'.'url' = script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL
            projectArtifacts.'Android_Tablet'.'url' = script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL
        }
        if (script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'iOS_Mobile'.'url' = script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL
            projectArtifacts.'iOS_Tablet'.'url' = script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL
        }
        def stepsToRun = prepareParallelSteps(
                testPackage << projectArtifacts, 'fetch_', step
        ) 
        if(!stepsToRun){
            throw new AppFactoryException("No artifacts to fetch!",'ERROR')
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }
    }

    /**
     * Sets build description at the end of the build.
     */
    protected final void setBuildDescription() {
        if((upstreamJob == null || isRebuild) && s3MustHaveAuthUrl != null){
            script.currentBuild.description = """\
            <div id="build-description">
                <p><a href='${s3MustHaveAuthUrl}'>Logs</a></p>
            </div>\
        """.stripIndent()
        }
    }
    
    /**
     * Prepare must haves for the debugging
     */
    private final void PrepareMustHaves(isBuildTestsPassed) {
        String mustHaveFolderPath = [projectFullPath, "RunTestsMustHaves"].join(separator)
        String mustHaveFile = ["MustHaves", script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join("_") + ".zip"
        String mustHaveFilePath = [projectFullPath, mustHaveFile].join(separator)
        String s3ArtifactPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
        def mustHaves = []
        def chBuildLogs = [workspace, projectWorkspaceFolderName, projectName, libraryProperties.'customhooks.buildlog.folder.name'].join("/")
        script.dir(mustHaveFolderPath){
            script.writeFile file: "environmentInfo.txt", text: BuildHelper.getEnvironmentInfo(script)
            script.writeFile file: "ParamInputs.txt", text: BuildHelper.getInputParamsAsString(script)
            script.writeFile file: "runTestBuildLog.log", text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)
        }
        if(runCustomHook){
            script.dir(chBuildLogs){
                script.shellCustom("find \"${chBuildLogs}\" -name \"*.log\" -exec cp -f {} \"${mustHaveFolderPath}\" \\;", true)
            }
        }

        script.dir(projectFullPath){
            script.zip dir:mustHaveFolderPath, zipFile: mustHaveFile
            try {
                script.catchErrorCustom("Failed to create the zip file") {
                    if(script.fileExists(mustHaveFilePath)){
                        String s3FullMustHavePath = [script.env.CLOUD_ACCOUNT_ID, projectName, s3ArtifactPath].join('/')
                        String s3MustHaveUrl = AwsHelper.publishToS3  sourceFileName: mustHaveFile,
                                        sourceFilePath: projectFullPath, s3FullMustHavePath, script
                        upstreamJob = BuildHelper.getUpstreamJobName(script)
                        isRebuild = BuildHelper.isRebuildTriggered(script)
                        s3MustHaveAuthUrl = BuildHelper.createAuthUrl(s3MustHaveUrl, script, false)
                        /* We will be keeping the s3 url of the must haves into the collection only if the
                         * channel job is triggered by the parent job that is buildVisualiser job.
                         * Handling the case where we rebuild a child job, from an existing job which was
                         * triggered by the buildVisualiser job.
                         */
                        if(upstreamJob != null && !isRebuild) {
                            mustHaves.add([
                                channelVariableName: "Tests", name: mustHaveFile, path: s3FullMustHavePath
                            ])
                            script.env['MUSTHAVE_ARTIFACTS'] = mustHaves?.inspect()
                        }
                    }
                }
            } catch (Exception e){
                String exceptionMessage = (e.toString()) ?: 'Failed while collecting the logs (must-gather) for debugging.'
                script.echoCustom(exceptionMessage,'ERROR')
            }
        }
    }
    /**
     * Uploads Test binaries to Device Farm.
     */
    private final void uploadTestBinaries() {
        /* Get required parameters for test binaries upload */
        def testUploadType = testPackage.get("${projectName}_TestApp").uploadType
        def testExtension = testPackage.get("${projectName}_TestApp").extension
        def testUploadFileName = "${projectName}_TestApp.${testExtension}"

        /* Upload test binaries and get upload ARN */
        deviceFarmTestUploadArtifactArn = deviceFarm.uploadArtifact(
                deviceFarmProjectArn,
                testUploadType,
                testUploadFileName
        )
        /* Add test binaries upload ARN to upload ARNs list */
        deviceFarmUploadArns.add(deviceFarmTestUploadArtifactArn)
    }

    @NonCPS
    protected String getFinalDeviceFarmStatus(deviceFarmTestRunResults){
        def jsonSlurper = new JsonSlurper()
        def testResultsToText = JsonOutput.toJson(deviceFarmTestRunResults)
        def testResultsToJson = jsonSlurper.parseText(testResultsToText)
        return testResultsToJson[0].result
    }

    /**
     * Converts the given time difference into hours, minutes, seconds
     * */
    protected String convertTimeFromMilliseconds(Long difference){
        Map diffMap =[:]
        difference = difference / 1000
        diffMap.seconds = difference.remainder(60)
        difference = (difference - diffMap.seconds) / 60
        diffMap.minutes = difference.remainder(60)
        difference = (difference - diffMap.minutes) / 60
        diffMap.hours = difference.remainder(24)
        def value = ""
        if(diffMap.hours.setScale(0, BigDecimal.ROUND_HALF_UP))
            value+=diffMap.hours.setScale(0, BigDecimal.ROUND_HALF_UP) + " hrs "
        if(diffMap.minutes.setScale(0, BigDecimal.ROUND_HALF_UP))
            value+=diffMap.minutes.setScale(0, BigDecimal.ROUND_HALF_UP)  + " mins "
        if(diffMap.seconds.setScale(0, BigDecimal.ROUND_HALF_UP))
            value+=diffMap.seconds.setScale(0, BigDecimal.ROUND_HALF_UP) + " secs "
        return value
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    validateBuildParameters(script.params)
                }

                /* Allocate a slave for the run */
                script.node(libraryProperties.'test.automation.node.label') {
                    /* Set environment-dependent variables */
                    workspace = script.env.WORKSPACE
                    checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
                    projectWorkspacePath = (projectRoot) ?
                            ([workspace, checkoutRelativeTargetFolder] + projectRoot.dropRight(1))?.join(separator) :
                            [workspace, projectWorkspaceFolderName]?.join(separator)
                    projectFullPath = [
                            workspace, checkoutRelativeTargetFolder, projectRoot?.join(separator)
                    ].findAll().join(separator)
                    testFolder = [projectFullPath, libraryProperties.'test.automation.scripts.path'].join(separator)
                    testFolderForDesktopWeb = [projectFullPath, libraryProperties.'test.automation.scripts.path.for.desktopweb'].join(separator)
                    deviceFarmWorkingFolder = [
                            projectFullPath, libraryProperties.'test.automation.device.farm.working.folder.name'
                    ].join(separator)

                    try {
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        /* Build test automation scripts if URL with test binaries was not provided */
                        if (testPackage.get("${projectName}_TestApp").url == 'jobWorkspace') {
                            script.stage('Checkout') {
                                // source code checkout from scm
                                BuildHelper.checkoutProject script: script,
                                        checkoutType: "scm",
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }

                            script.stage('Build') {
                                /* Build Test Automation scripts */
                                if(isNativeApp)
                                    buildTestScripts("Native")
                                if(isDesktopwebApp)
                                    buildTestScripts("DesktopWeb")
				  /* Set runTests flag to run tests on Device Farm when build is successful,otherwise set in to false (in catch block) */  
				  runTests = true  
                            }
                            if(isNativeApp){
                                script.stage('Publish test automation scripts build result to S3') {
                                    if (script.fileExists("${testFolder}/target/${projectName}_TestApp.zip")) {
                                        AwsHelper.publishToS3 sourceFileName: "${projectName}_TestApp.zip",
                                                bucketPath: [
                                                        'Tests',
                                                        script.env.JOB_BASE_NAME,
                                                        script.env.BUILD_NUMBER
                                                ].join('/'),
                                                sourceFilePath: "${testFolder}/target", script, true
                                    }
				     else
					 throw new AppFactoryException('Failed to find build result artifact!','ERROR')
                                }
                            }
                        }
                    } catch (AppFactoryException e) {
                        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage, e.getErrorType(), false)
                        script.currentBuild.result = 'FAILURE'
                    } catch (Exception e) {
                        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage,'WARN')
                        script.currentBuild.result = 'FAILURE'
			  /* Set runTests flag to false so that tests will not get triggered on Device Farm when build is failed */    
			  runTests = false
                    } finally {
                        NotificationsHelper.sendEmail(script, 'buildTests')
                        /* Exit in case of test binaries failed, throw error to build console. */
                        if (script.currentBuild.result == 'FAILURE') {
                            PrepareMustHaves(false)
                            setBuildDescription()
                        }
                    }

                    /* To make runTests flag true when Tests URL is given instead of passing SCM details */
                    if (testPackage.get("${projectName}_TestApp").url != 'jobWorkspace') {
                        runTests = true
                    }

                    /* Run tests on provided binaries */
                    if (runTests) {
                        try {
                            script.dir(deviceFarmWorkingFolder) {
                                /* Providing AWS region for Device Farm, currently it is available in us-west-2 */
                                script.withAWS(region: awsRegion) {
                                    if (isNativeApp) {
                                        script.stage('Fetch binaries') {
                                            fetchBinaries()
                                        }

                                        script.stage('Create Device Farm Project') {
                                            /* Check if project already exists */
                                            deviceFarmProjectArn = (deviceFarm.getProject(projectName)) ?:
                                                    /*
                                                    If not, create new project and return ARN or break the build,
                                                    if ARN equals null
                                                 */
                                                    deviceFarm.createProject(projectName)
                                            if(!deviceFarmProjectArn){
                                                throw new AppFactoryException("Project ARN is empty!", 'ERROR')
                                            }

                                        }

                                        script.stage('Create Device Pools') {
                                            devicePoolArns = deviceFarm.createDevicePools(
                                                    deviceFarmProjectArn, devicePoolName)
                                            if(!devicePoolArns){ 
                                                throw new AppFactoryException("Device pool ARN list is empty!", 'ERROR')
                                            }

                                        }

                                        script.stage('Upload test package') {
                                            uploadTestBinaries()
                                        }
                                    }
                                    if(isNativeApp) {
                                        script.stage('Upload application binaries and schedule run') {
                                            uploadAndRun()
                                        }
                                    }
                                    if(isDesktopwebApp){
                                        script.stage('Run the Tests') {
                                            runTestsforDesktopWeb(script.params.AVAILABLE_BROWSERS)
                                        }

                                    }

                                    script.stage('Get Test Results') {
                                        if(isNativeApp) {
                                            fetchTestResults()
                                            if(!deviceFarmTestRunResults)
                                                throw new AppFactoryException('Tests results are not found as the run result is skipped.', 'ERROR')
                                        }
                                        if(isDesktopwebApp){
                                            fetchTestResultsforDesktopWeb()
                                            if(!desktopTestRunResults) 
                                                throw new AppFactoryException('DesktopWeb tests results are not found as the run result is skipped.', 'ERROR')
                                            publishDesktopWebTestsResults()
                                        }
                                    }
                                }
                                
                                script.stage('Check PostTest Hook Points'){
                                    def desktopWebTestsResultsStatus = true
                                    def nativeTestsResultStatus = true
                                    if (isNativeApp) {
                                        if (!deviceFarmTestRunResults)
                                            throw new AppFactoryException('Tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                        def overAllDeviceFarmTestRunResult = getFinalDeviceFarmStatus(deviceFarmTestRunResults)
                                        nativeTestsResultStatus = overAllDeviceFarmTestRunResult == "PASSED" ? true : false
                                    }
                                    if (isDesktopwebApp) {
                                        if (!desktopTestRunResults) 
                                            throw new AppFactoryException('DesktopWeb tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                        desktopWebTestsResultsStatus = !testStatusMap.any {it.value != "PASS"}
                                        desktopWebTestsResultsStatus ?: (script.currentBuild.result = 'UNSTABLE')
                                    }
                                    if(runCustomHook) {
                                        if(isNativeApp && isDesktopwebApp){
                                            if (nativeTestsResultStatus && desktopWebTestsResultsStatus) {
                                                runCutomHookForNativeChannels()
                                                runCustomHookForDesktopWebApp()
                                            } else {
                                                script.echoCustom('Tests got failed for one/more devices. Hence CustomHooks execution is skipped.', 'WARN')
                                            }
                                        } else if (isNativeApp) {
                                            nativeTestsResultStatus ? runCutomHookForNativeChannels() :
                                                    script.echoCustom('Tests got failed for one/more devices. Hence CustomHooks execution is skipped.', 'WARN')

                                        } else {
                                            desktopWebTestsResultsStatus ? runCustomHookForDesktopWebApp() :
                                                    script.echoCustom('Tests got failed for DesktopWeb. Hence CustomHooks execution is skipped.', 'WARN')
                                        }
                                    }
                                    else{
                                        script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                    }
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
                            if (isDesktopwebApp) {
                                NotificationsHelper.sendEmail(script, 'runTests', [
                                        desktopruns: desktopTestRunResults,
                                        listofLogFiles: listofLogFiles,
                                        listofScreenshots:listofScreenshots
                                ], true)
                            }
                            if (isNativeApp) {
                                NotificationsHelper.sendEmail(script, 'runTests', [
                                        deviceruns    : deviceFarmTestRunResults,
                                        devicePoolName: devicePoolName,
                                        binaryName    : getBinaryNameForEmail(projectArtifacts),
                                        missingDevices: script.env.MISSING_DEVICES,
                                        summaryofResults: summary,
                                        duration      : duration
                                ], true)
                            }
                            if(script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED'){
                                PrepareMustHaves(true)
                                setBuildDescription()
                            }
                            /* Cleanup created pools and uploads */
                            cleanup(deviceFarmUploadArns, devicePoolArns)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * It iterates and run custom hook available for Universal binary hook stages in runTest job.
     * This method is called when "runCustomHook" flag and "nativeTestsResultStatus" is true.
     */
    private void runCustomHookForUniversalBinaryRunTest() {
        ['ANDROID_UNIVERSAL','IOS_UNIVERSAL'].each { project ->
            if (getBinaryName(script.env[project + "_NATIVE_BINARY_URL"])) {
                def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', project + "_STAGE")
                if (!isSuccess)
                    throw new Exception("Something went wrong with the Custom hooks execution.")
            }
        }
    }

    /**
     * This runs the custom hooks available for DesktopWeb stage in runTest job.
     * This method is called when "runCustomHook" flag and "desktopWebTestsResultsStatus" is true.
     * @exception Exception saying "Something wrong with custom hooks execution" when hooks execution failed
     */
    private void runCustomHookForDesktopWebApp() {
        def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', "DESKTOP_WEB_STAGE")
        if (!isSuccess)
            throw new Exception("Something went wrong with the Custom hooks execution.")
    }
    /**
     * This runs the custom hooks available for Native channels in runTest job.
     * This method is called when "runCustomHook" flag and "nativeTestsResultStatus" is true.
     * @exception Exception saying "Something wrong with custom hooks execution" when hooks execution failed
     */
    private void runCutomHookForNativeChannels() {
        ['Android_Mobile', 'Android_Tablet', 'iOS_Mobile', 'iOS_Tablet'].each { project ->
            if (projectArtifacts."$project".'binaryName') {
                def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', project.toUpperCase() + "_STAGE")
                if (!isSuccess)
                    throw new Exception("Something went wrong with the Custom hooks execution.")
            }
        }
        runCustomHookForUniversalBinaryRunTest()
    }
}
