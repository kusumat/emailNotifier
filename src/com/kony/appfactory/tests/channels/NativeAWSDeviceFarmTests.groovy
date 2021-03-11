package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ArtifactHelper
import com.kony.appfactory.helper.AwsDeviceFarmHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.dto.tests.DetailedNativeResults

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonSlurperClassic
import groovyx.net.http.*
import groovy.util.slurpersupport.*
import java.util.*

class NativeAWSDeviceFarmTests extends RunTests implements Serializable {

    /*
    Platform dependent default name-separator character as String.
    For windows, it's '\' and for unix it's '/'.
    Because all logic for test will be run on linux, set separator to '/'.
 */
    private separator = '/'

    /* Build parameters */
    private devicePoolName = script.params.AVAILABLE_TEST_POOLS
    protected boolean runInCustomTestEnvironment
    protected String appiumVersion = script.params.APPIUM_VERSION
    protected testngFiles = script.params.TESTNG_FILES

    /* Device Farm related variables */
    private deviceFarm, deviceFarmProjectArn, devicePoolArns, deviceFarmTestUploadArtifactArn, deviceFarmTestSpecUploadArtifactArn
    protected deviceFarmUploadArns = [], deviceFarmTestRunArns = [:], deviceFarmTestRunResults = [], summary = [:]
    protected isPoolWithDeviceFarmFilters
    protected devicePoolConfigFileContent
    /* Temp folder for Device Farm objects (test run results) */
    private deviceFarmWorkingFolder

    private runTests = false
    /* Device Farm AWS region */
    private awsRegion
    protected projectArtifacts = [
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
                                       url       : (script.env.NATIVE_TESTS_URL ?: script.env.TESTS_BINARY_URL ?: 'jobWorkspace')]
    ]

    private testSpec = [
            "${projectName}_TestSpec": [extension : 'yml',
                                        uploadType: 'APPIUM_JAVA_TESTNG_TEST_SPEC',
                                        url       : '']
    ]

    private testExtraDataPkg = [
            "${projectName}_TestExtraDataPkg": [extension : 'zip',
                                                uploadType: 'EXTERNAL_DATA',
                                                url       : '']
    ]

    protected ymlTemplate = 'com/kony/appfactory/configurations/KonyYamlTestSpec.template'
    protected testSpecUploadFileName = "TestSpec.yml"
    public testSpecUploadFilePath
    private APPIUM_1_8_1_VERSION = "1.8.1"

    /* Jasmine extra data package arns */
    private mobileExtraDataPkgArtifactArn, tabletExtraDataPkgArtifactArn
    private extraDataPkgArtifactArnsMap = [:]
    private deviceStatsList = [:]
    private deviceStats = [:]
    private Date pretestdurstart, devicewaitstart
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    NativeAWSDeviceFarmTests(script) {
        super(script, "Native")
        /* Initializer Device Farm scrips object */
        deviceFarm = new AwsDeviceFarmHelper(script)
        awsRegion = libraryProperties.'test.automation.device.farm.aws.region'
        runInCustomTestEnvironment = (script.params.containsKey("TEST_ENVIRONMENT")) ? ((script.params.TEST_ENVIRONMENT == "Custom") ? true : false) : BuildHelper.getParamValueOrDefault(script, "RUN_IN_CUSTOM_TEST_ENVIRONMENT", false)
    }

    /**
     * Uploads application binaries and Schedules the run.
     *
     * @param deviceFarmProjectArn is the ARN that is associated with a particular project
     * @param devicePoolArns is the ARN that is associated with a device pool
     */
    final void uploadAndRun(deviceFarmProjectArn, devicePoolArns) {
        /* Prepare step to run in parallel */
        def step = { artifactName, artifactURL, artifactExt, uploadType, extraDataPkgArn ->
            /* Upload application binaries to Device Farm */
            def uploadArn = deviceFarm.uploadArtifact(deviceFarmProjectArn,
                    uploadType, artifactName + '.' + artifactExt)
            /* Add upload ARN to list for cleanup at the end of the build */
            deviceFarmUploadArns.add(uploadArn)
            String formFactor = (artifactName.toLowerCase().contains('mobile')) ? "MOBILE" : "TABLET"
            String channel = (artifactExt.toLowerCase().contains('ipa')) ? "IOS" : "ANDROID"

            /* Depending on artifact name we need to chose appropriate pool for the run */
            def devicePoolArnOrSelectionConfig
            if (isPoolWithDeviceFarmFilters) {
                devicePoolArnOrSelectionConfig = getDevicePoolSelectionConfig(channel, formFactor)
            } else {
                devicePoolArnOrSelectionConfig = (devicePoolArns."${channel.toLowerCase()}_${formFactor.toLowerCase()}") ?: script.echoCustom("Artifacts provided " +
                                "for ${formFactor}, but no ${formFactor} were found in the device pool", 'ERROR')
            }

            /* If we have application binaries and test binaries, schedule the custom run */
            if (uploadArn && deviceFarmTestUploadArtifactArn) {
                devicewaitstart = new Date()
                /* Once all parameters gotten, schedule the Device Farm run */
                def runArn = deviceFarm.scheduleRun(deviceFarmProjectArn, devicePoolArnOrSelectionConfig, 'APPIUM_JAVA_TESTNG', uploadArn, deviceFarmTestUploadArtifactArn, artifactName, deviceFarmTestSpecUploadArtifactArn, extraDataPkgArn, isPoolWithDeviceFarmFilters)
                pretestdurstart = new Date()
                deviceFarmTestRunArns["$artifactName"] = runArn
                /* Otherwise, fail the stage, because run couldn't be scheduled without one of the binaries */
            } else {
                script.echoCustom("Failed to get uploadArn", 'WARN')
            }
        }

        deviceFarmTestSpecUploadArtifactArn ? script.echoCustom("Running in Custom Test Environment.", 'INFO') : script.echoCustom("Running in Standard Test Environment.", 'INFO')

        /* Setting the Universal binary url to respective platform input run test job paramaters*/
        if (script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'Android_Mobile'.'url' = devicePoolArns.'android_mobile' ? script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL : null
            projectArtifacts.'Android_Tablet'.'url' = devicePoolArns.'android_tablet' ? script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL : null
        }
        if (script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL) {
            projectArtifacts.'iOS_Mobile'.'url' = devicePoolArns.'ios_mobile' ? script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL : null
            projectArtifacts.'iOS_Tablet'.'url' = devicePoolArns.'ios_tablet' ? script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL : null
        }
        /* Prepare parallel steps */
        def stepsToRun = prepareParallelSteps(projectArtifacts, 'uploadAndRun_', step)

        if (!stepsToRun) {
            throw new AppFactoryException("No artifacts to upload and run!", 'ERROR')
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }
    }

    /**
     * Uploads Test binaries to Device Farm.
     *
     * @param testPackage is the compiled test code package
     * @param deviceFarmProjectArn is the ARN that is associated with a particular project
     */
    final void uploadTestBinaries(testPackage, deviceFarmProjectArn) {
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

    /**
     * Uploads Extra Data Package to Device Farm.
     *
     * @param deviceFarmProjectArn is the ARN that is associated with a particular project
     */
    final String uploadExtraDataPackage(deviceFarmProjectArn, platformName, formFactor) {
        /* Get required parameters for test binaries upload */
        def testUploadType = testExtraDataPkg.get("${projectName}_TestExtraDataPkg").uploadType
        def testExtension = testExtraDataPkg.get("${projectName}_TestExtraDataPkg").extension
        def testUploadFileName = "${projectName}_${platformName}_${formFactor}_TestExtraDataPkg.${testExtension}"

        /* Upload the Jasmine test packages and get upload ARN */
        def deviceFarmExtraDataPackageArn = deviceFarm.uploadArtifact(
                deviceFarmProjectArn,
                testUploadType,
                testUploadFileName
        )
        /* Add Jasmine test packages ARN to upload ARNs list */
        deviceFarmUploadArns.add(deviceFarmExtraDataPackageArn)

        deviceFarmExtraDataPackageArn
    }

    @NonCPS
    String getFinalDeviceFarmStatus(deviceFarmTestRunResults) {
        def jsonSlurper = new JsonSlurper()
        def testResultsToText = JsonOutput.toJson(deviceFarmTestRunResults)
        def testResultsToJson = jsonSlurper.parseText(testResultsToText)
        return testResultsToJson[0].result
    }

    /**
     * Fetches application and test binaries (if provided).
     */
    protected final void fetchBinaries(testFolder, deviceFarmWorkingFolder) {
        /* Prepare step to run in parallel */
        def step = { artifactName, artifactURL, artifactExt ->
            /* If test binaries URL was not provided, copy binaries from the build step */
            if (artifactURL == 'jobWorkspace') {
                String artifactPath = "${testFolder}/target/${artifactName}.${artifactExt}"
                script.shellCustom("cp $artifactPath $deviceFarmWorkingFolder", true)
                /* else, fetch binaries */
            } else {
                ArtifactHelper.retrieveArtifact(script, artifactName + '.' + artifactExt, artifactURL)
            }
        }
        /* For universal build test run job setting the artifacts url path to fetch the binary from universal artifacts  */
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
        if (!stepsToRun) {
            throw new AppFactoryException("No artifacts to fetch!", 'ERROR')
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }
    }

    /**
     * Fetches test run results from Device Farm, generates data for e-mail template and JSON file and moves artifacts
     * to artifactStorage.
     */
    protected final fetchTestResults() {
        def stepsToRun = [:]
        def deviceFarmTestRunArnsKeys = deviceFarmTestRunArns.keySet().toArray()

        /* Workaround to iterate over map keys in c++ style for loop */
        for (int i = 0; i < deviceFarmTestRunArnsKeys.size(); ++i) {
            def arn = deviceFarmTestRunArns[deviceFarmTestRunArnsKeys[i]]
            def testRunArtifacts
            script.echoCustom("Run ARN for ${deviceFarmTestRunArnsKeys[i]} is: " + arn)
            /* Prepare step to run in parallel */
            stepsToRun["testResults_${deviceFarmTestRunArnsKeys[i]}"] = {
                def results = [:]
                def testRunResult = deviceFarm.getTestRunResult(arn, results)
                /* If we got a test result */
                if (testRunResult) {
                    /*
                        Query Device Farm for test artifacts (logs, videos, etc) and
                        store test run artifact object in list.
                        Using addAll method to collect all run results, because
                        getTestRunArtifacts returns list of one item (run result).
                     */
                    testRunArtifacts = deviceFarm.getTestRunArtifacts(arn)

                    if(runInCustomTestEnvironment)
                        testRunArtifacts = testRunArtifacts.each { testRunArtifact -> fetchCustomTestResults(testRunArtifact) }
                    deviceFarmTestRunResults.addAll(testRunArtifacts)

                }
                /* else notify user that result value is empty */
                else {
                    script.echoCustom("Test run result for ${deviceFarmTestRunArnsKeys[i]} is empty!", 'WARN')
                }

                def key, authUrl, name, os, displayName, deviceMinutes

                for(runArtifacts in testRunArtifacts) {
                    DetailedNativeResults result = results.get(runArtifacts.device.name + ' ' + runArtifacts.device.os)
                    def artifacts = fetchTestBinaryDetails(result.getDevice().getFormFactor(), result.getDevice().getPlatform())
                    if(artifacts.url.contains(script.env.S3_BUCKET_NAME)) {
                        artifacts.url = ArtifactHelper.createAuthUrl(artifacts.url, script, true)
                    }
                    result.setBinaryURL(artifacts.url)
                    result.setBinaryExt(artifacts.extension)
                    result.setRunARN(arn)
                    
                    if(runInCustomTestEnvironment) {
                        for(reports in runArtifacts.reports) {
                            authUrl = reports.getValue()
                        }

                        result.setResultsLink(authUrl)

                        // setting DetailedNativeResults result object with custom_mode_test run stats
                        if (runArtifacts.skippedTests && runArtifacts.skippedTests instanceof Integer)
                            result.getResultsCount().setSkipped(runArtifacts.skippedTests)
                        else if (runArtifacts.skippedTests && runArtifacts.skippedTests instanceof String)
                            result.getResultsCount().setSkipped(runArtifacts.skippedTests.toInteger())
                        else
                            result.getResultsCount().setSkipped(0)

                        if (runArtifacts.totalSuites && runArtifacts.totalSuites instanceof Integer)
                            result.getResultsCount().setTotal(runArtifacts.totalSuites)
                        else if (runArtifacts.totalSuites && runArtifacts.totalSuites instanceof String)
                            result.getResultsCount().setTotal(runArtifacts.totalSuites.toInteger())
                        else
                            result.getResultsCount().setTotal(0)

                        if (runArtifacts.passedTests && runArtifacts.passedTests instanceof Integer)
                            result.getResultsCount().setPassed(runArtifacts.passedTests)
                        else if (runArtifacts.passedTests && runArtifacts.passedTests instanceof String)
                            result.getResultsCount().setPassed(runArtifacts.passedTests.toInteger())
                        else
                            result.getResultsCount().setPassed(0)

                        if (runArtifacts.failedTests && runArtifacts.failedTests instanceof Integer)
                            result.getResultsCount().setFailed(runArtifacts.failedTests)
                        else if (runArtifacts.failedTests && runArtifacts.failedTests instanceof String)
                            result.getResultsCount().setFailed(runArtifacts.failedTests.toInteger())
                        else
                            result.getResultsCount().setFailed(0)

                        if (runArtifacts.erroredTests && runArtifacts.erroredTests instanceof Integer)
                            result.getResultsCount().setErrored(runArtifacts.erroredTests)
                        else if (runArtifacts.erroredTests && runArtifacts.erroredTests instanceof String)
                            result.getResultsCount().setErrored(runArtifacts.erroredTests.toInteger())
                        else
                            result.getResultsCount().setErrored(0)

                        if (runArtifacts.stoppedTests && runArtifacts.stoppedTests instanceof Integer)
                            result.getResultsCount().setStopped(runArtifacts.stoppedTests)
                        else if (runArtifacts.stoppedTests && runArtifacts.stoppedTests instanceof String)
                            result.getResultsCount().setStopped(runArtifacts.stoppedTests.toInteger())
                        else
                            result.getResultsCount().setStopped(0)

                        if (runArtifacts.warnedTests && runArtifacts.warnedTests instanceof Integer)
                            result.getResultsCount().setWarned(runArtifacts.warnedTests)
                        else if (runArtifacts.warnedTests && runArtifacts.warnedTests instanceof String)
                            result.getResultsCount().setWarned(runArtifacts.warnedTests.toInteger())
                        else
                            result.getResultsCount().setWarned(0)
                    }

                    deviceStats.put('atype', channelType)
                    deviceStats.put('testdur', result.getTestDuration())
                    deviceStats.put('plat', result.getDevice().getPlatform())
                    deviceStats.put('chnl', result.getDevice().getFormFactor().equalsIgnoreCase('phone') ? 'mobile' : result.getDevice().getFormFactor())
                    deviceStats.put('dm', java.net.URLEncoder.encode(result.getDevice().getName(), "UTF-8"))
                    deviceStats.put('deviceosver', result.getDevice().getOS())
                    deviceStats.put('allottedtime', result.getDeviceMinutes())
                    deviceStats.put('devicedevwaitdur', BuildHelper.getDuration(devicewaitstart, deviceFarm.testExecutionStartTimeMap.get(runArtifacts.device.name + ' ' + runArtifacts.device.os)))
                    deviceStats.put('tsstarttestplat', deviceFarm.testExecutionStartTimeMap.get(runArtifacts.device.name + ' ' + runArtifacts.device.os).time)
                    deviceStats.put('testdur', result.getTestDuration())
                    deviceStats.put('testskip',result.getResultsCount().getSkipped())
                    deviceStats.put('testpass',result.getResultsCount().getPassed())
                    deviceStats.put('testfail',result.getResultsCount().getFailed())
                    deviceStats.put('testwarn', result.getResultsCount().getWarned())
                    deviceStats.put('teststop', result.getResultsCount().getStopped())
                    deviceStats.put('testerror', result.getResultsCount().getErrored())

                    deviceStatsList.put("device-run-stats", deviceStats)
                    summary.put(result.getDevice().getName() + ' ' + result.getDevice().getOS(), result)
                    script.statspublish deviceStatsList.inspect()
                }
            }
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }

        def separator = {
            script.echoCustom("*" * 99)
            script.echoCustom("*" * 99)
        }

        script.echoCustom("Test execution is completed for all the devices in device pool.", 'INFO')
        script.echoCustom("Summary of Test Results: ", 'INFO')
        separator()
        summary.each { deviceName, deviceResults -> 
            
            String displayResults = 'Total Tests: ' + deviceResults.getResultsCount().getTotal() +
                ', Passed: ' + deviceResults.getResultsCount().getPassed() +
                ', Failed: ' + deviceResults.getResultsCount().getFailed() +
                ', Skipped: ' + deviceResults.getResultsCount().getSkipped() +
                ', Warned: ' + deviceResults.getResultsCount().getWarned() +
                ', Stopped: ' + deviceResults.getResultsCount().getStopped() +
                ', Errored: ' + deviceResults.getResultsCount().getErrored() +
                ', Test Duration: ' + deviceResults.getTestDuration() +
                ', Run ARN: ' + deviceResults.getRunARN()
            script.echoCustom("On " + deviceName + ":: " + displayResults )
        }
        separator()

        /* Move artifacts to customer artifactStorage (S3 or Master or other) */
        script.dir('artifacts') {
            deviceFarm.publishDeviceFarmTestRunResults(
                    deviceFarmTestRunResults,
                    ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
            )
        }

        deviceFarmTestRunResults
    }

    /**
     * It iterates and run custom hook available for Universal binary hook stages in runTest job.
     * This method is called when "runCustomHook" flag and "nativeTestsResultStatus" is true.
     * @throws Exception saying "Something wrong with custom hooks execution" when hooks execution failed
     */
    protected final void runCustomHookForUniversalBinaryRunTest() {
        ['ANDROID_UNIVERSAL', 'IOS_UNIVERSAL'].each { project ->
            if (getBinaryName(script.env[project + "_NATIVE_BINARY_URL"])) {
                def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', project + "_STAGE")
                if (!isSuccess)
                    throw new Exception("Something went wrong with the Custom hooks execution.")
            }
        }
    }

    /**
     * This runs the custom hooks available for Native channels in runTest job.
     * This method is called when "runCustomHook" flag and "nativeTestsResultStatus" is true.
     * @throws Exception saying "Something wrong with custom hooks execution" when hooks execution failed
     */
    protected final void runCustomHooks() {
        ['Android_Mobile', 'Android_Tablet', 'iOS_Mobile', 'iOS_Tablet'].each { project ->
            if (projectArtifacts."$project".'binaryName') {
                hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', project.toUpperCase() + "_STAGE")
            }
        }
        runCustomHookForUniversalBinaryRunTest()
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
    protected final getBinaryNameForEmail(artifacts) {
        def result = [:]

        for (artifact in artifacts) {
            result[artifact.key] = artifact.value.get('binaryName')
        }

        result
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
            def extraPkg = artifact.extraPkg ? artifact.extraPkg : null
            if (artifactUrl) {
                def step = {
                    stepClosure(artifactName, artifactUrl, artifactExt, uploadType, extraPkg)
                }

                stepsToRun.put("${stageName}${artifactName}", step)
            } else {
                script.echoCustom("${artifactName.replaceAll('_', ' ')} binary was not provided!", 'WARN')
            }
        }

        stepsToRun
    }

    /**
     * Validates build parameters.
     *
     * @param buildParameters job parameters.
     */
    protected final void validateBuildParameters(buildParameters) {
        def awsCustomEnvMandatoryParameters = []
        /* Filter all application binaries build parameters */
        def nativeAppBinaryUrlParameters = buildParameters.findAll {
            it.key.contains('NATIVE_BINARY_URL') && it.value
        }
        /* Filter all SCM build parameters */
        def scmParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        /* Filter test binaries build parameter. This check is needed to maintain backward compatibility*/
        def nativeTestsUrl = script.params.containsKey('NATIVE_TESTS_URL') ? 'NATIVE_TESTS_URL' : 'TESTS_BINARY_URL'
        def nativeTestBinaryUrlParameter = buildParameters.findAll { it.key == nativeTestsUrl && it.value }
        /* Filter pool name build parameter */
        def poolNameParameter = buildParameters.findAll { it.key.contains('AVAILABLE_TEST_POOLS') && it.value }
        /* Combine binaries build parameters */
        def nativeUrlParameters = nativeTestBinaryUrlParameter + nativeAppBinaryUrlParameters
        if (runInCustomTestEnvironment) {
            awsCustomEnvMandatoryParameters.add('APPIUM_VERSION')
            if (!isJasmineEnabled){
                /*Filter AWS Test Environment related parameters */
                awsCustomEnvMandatoryParameters.add('TESTNG_FILES')
            }
            /* Check all required parameters depending on user input */
            ValidationHelper.checkBuildConfiguration(script, awsCustomEnvMandatoryParameters)
        }

        /* Restrict the user to run tests either with Universal build binary or with normal native test binaries,
           fail the build if both options are provided.
         */
        if (script.env.ANDROID_UNIVERSAL_NATIVE_BINARY_URL && (script.env.ANDROID_MOBILE_NATIVE_BINARY_URL || script.env.ANDROID_TABLET_NATIVE_BINARY_URL)) {
            throw new AppFactoryException("Sorry, You can't run test for Android Universal binary along with Android Mobile/Tablet", 'ERROR')
        }
        if (script.env.IOS_UNIVERSAL_NATIVE_BINARY_URL && (script.env.IOS_MOBILE_NATIVE_BINARY_URL || script.env.IOS_TABLET_NATIVE_BINARY_URL)) {
            throw new AppFactoryException("Sorry, You can't run test for iOS Universal binary along with iOS Mobile/Tablet", 'ERROR')
        }
        if (scmParameters && nativeTestBinaryUrlParameter) {
            throw new AppFactoryException("Please provide only one option for the source of test scripts: GIT or TESTS_URL", 'ERROR')
        }
        /* Same if none of the options provided */
        else if (!nativeTestBinaryUrlParameter && !scmParameters) {
            throw new AppFactoryException("Please provide at least one source of test binaries", 'ERROR')
        }

        /* Fail build if nativeTestBinaryUrlParameter or scmParameters have been provided without providing nativeAppBinaryUrlParameters */
        if (!nativeAppBinaryUrlParameters && (nativeTestBinaryUrlParameter || scmParameters)) {
            throw new AppFactoryException("Please provide at least one of application binaries URL",'ERROR')
        }
        /* Fail build if nativeAppBinaryUrlParameters been provided without test pool */
        else if (!poolNameParameter && nativeAppBinaryUrlParameters) {
            throw new AppFactoryException("Please provide pool to test on", 'ERROR')
        }

        if (isJasmineEnabled){
            String jasmineTestPlan = BuildHelper.getParamValueOrDefault(script, "NATIVE_TEST_PLAN", "testPlan.js")
            // Fail the build if param contains special characters that can cause security breach on the agent.
            if(!jasmineTestPlan.endsWith(".js") || jasmineTestPlan.contains(";") || jasmineTestPlan.contains("..") || jasmineTestPlan.contains("&&")){
                throw new AppFactoryException("Please provide valid NATIVE_TEST_PLAN.",'ERROR')
            }
        }

        /* Check if at least one application binaries parameter been provided */
        (!nativeAppBinaryUrlParameters) ?: validateApplicationBinariesURLs(nativeUrlParameters)

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
        if (deviceFarmTestSpecUploadArtifactArn){
            if (script.fileExists(testSpecUploadFileName))
                script.shellCustom("rm -f ${testSpecUploadFileName}", true)
        }
    }

    /**
     * Prepares the Test Spec YAML and uploads it to AWS.
     */
    private final void prepareAndUploadTestSpec() {
        def testSpecUploadType = testSpec.get("${projectName}_TestSpec").uploadType
        def testSpecExtension = testSpec.get("${projectName}_TestSpec").extension
        String configFolderPath = 'com/kony/appfactory/configurations'
        /* If Test Spec File is not available inside the source, create one from template. */
        if (!testSpecUploadFilePath) {
            script.echoCustom("Value of Appium Version is :" + appiumVersion)
            /* Load YAML Template */
            ymlTemplate = script.loadLibraryResource(configFolderPath + '/KonyYamlTestSpec.template')
            testngFiles = testngFiles.replaceAll("," , " ")
            def customWDA = ''
            // We need to use the WDA V1 if user has selected appium 1.8.1 or higher version.
            if (ValidationHelper.compareVersions(appiumVersion, APPIUM_1_8_1_VERSION) > -1) {
                customWDA = '--default-capabilities "{\\"usePrebuiltWDA\\": true, \\"derivedDataPath\\":\\"$DEVICEFARM_WDA_DERIVED_DATA_PATH\\"}"'
            }
            def template = BuildHelper.populateTemplate(ymlTemplate, [appiumVersion: appiumVersion, testngFiles: testngFiles, customWDA: customWDA])
            /* Create YAML file from template */
            testSpecUploadFileName = "${projectName}_TestSpec.${testSpecExtension}"
            script.writeFile file: testSpecUploadFileName, text: template
            testSpecUploadFilePath = script.pwd() + "/${testSpecUploadFileName}"
        }
        else{
            script.shellCustom("cp ${testSpecUploadFilePath} .", true)
        }
        mustHaveArtifacts.add(testSpecUploadFilePath)
        /* Upload test spec and get upload ARN */
        deviceFarmTestSpecUploadArtifactArn = deviceFarm.uploadArtifact(
                deviceFarmProjectArn,
                testSpecUploadType,
                testSpecUploadFileName
        )
        script.echoCustom("Device Farm Test Spec Arn : " + deviceFarmTestSpecUploadArtifactArn)
        /* Add test spec upload ARN to upload ARNs list */
        deviceFarmUploadArns.add(deviceFarmTestSpecUploadArtifactArn)
    }

    protected createPipeline() {
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.properties([[$class: 'CopyArtifactPermissionProperty', projectNames: '/*']])

                script.stage('Validate parameters') {
                    validateBuildParameters(script.params)
                }

                nodeLabel = TestsHelper.getTestNode(script, libraryProperties, isJasmineEnabled)

                /* Allocate a slave for the run */
                script.node(nodeLabel) {

                    try {
                        pipelineWrapper {
                            /*
                            Clean workspace, to be sure that we have not any items from previous build,
                            and build environment completely new.
                            */
                            script.cleanWs deleteDirs: true
                            isPoolWithDeviceFarmFilters = checkDevicePoolConfigFileContent(devicePoolName)
                            /* Set the device farm working directory */
                            deviceFarmWorkingFolder = [projectFullPath, libraryProperties.'test.automation.device.farm.working.folder.name'].join(separator)
                            /* Build test automation scripts if URL with test binaries was not provided */
                            if (testPackage.get("${projectName}_TestApp").url == 'jobWorkspace') {
                                script.stage('Checkout') {
                                    scmMeta = BuildHelper.checkoutProject script: script,
                                            projectRelativePath: checkoutRelativeTargetFolder,
                                            scmBranch: scmBranch,
                                            checkoutType: "scm",
                                            scmCredentialsId: scmCredentialsId,
                                            scmUrl: scmUrl
                                }
                                testFolder = getTestsFolderPath(projectFullPath)

                                /* Preparing the environment for the Jasmine Tests */
                                if(isJasmineEnabled){
                                    // Running the Jasmine tests on the custom test environment by default.
                                    runInCustomTestEnvironment = true
                                    testngFiles = "Testng.xml"
                                    prepareEnvForJasmineTests(testFolder)
                                }

                                script.stage('Build') {
                                    if (runInCustomTestEnvironment && !appiumVersion) {
                                        if (script.fileExists("${testFolder}/src/test/resources/${testSpecUploadFileName}"))
                                            testSpecUploadFilePath = "${testFolder}/src/test/resources/${testSpecUploadFileName}"
                                    }
                                    /* Build Test Automation scripts */
                                    buildTestScripts(testFolder)

                                    script.dir(testFolder) {
                                        script.shellCustom("mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip", true)
                                    }
                                    /* Set runTests flag to true when build is successful,otherwise set it to false (in catch block) */
                                    runTests = true
                                }
                                script.stage('Publish test automation scripts build results') {
                                    if (script.fileExists("${testFolder}/target/${projectName}_TestApp.zip")) {
                                        ArtifactHelper.publishArtifact sourceFileName: "${projectName}_TestApp.zip",
                                                destinationPath: [
                                                        'Tests',
                                                        script.env.JOB_BASE_NAME,
                                                        script.env.BUILD_NUMBER
                                                ].join('/'),
                                                sourceFilePath: "${testFolder}/target", script, true
                                    } else
                                        throw new AppFactoryException('Failed to find build result artifact!', 'ERROR')
                                }
                            }
                        }

                        /* To make runTests flag true when Tests URL is given instead of passing SCM details */
                        if (testPackage.get("${projectName}_TestApp").url != 'jobWorkspace') {
                            runTests = true
                        }

                        /* Run tests on provided binaries */
                        if (runTests) {
                            script.dir(deviceFarmWorkingFolder) {
                                /* Providing AWS region for Device Farm, currently it is available in us-west-2 */
                                script.withAWS(region: awsRegion) {
                                    script.stage('Fetch Binaries') {
                                        fetchBinaries(testFolder, deviceFarmWorkingFolder)
                                    }

                                    script.stage('Create Device Farm Project') {
                                        /* Check if project already exists */
                                        deviceFarmProjectArn = (deviceFarm.getProject(projectName)) ?:
                                                /*
                                                If not, create new project and return ARN or break the build,
                                                if ARN equals null
                                             */
                                                deviceFarm.createProject(projectName)
                                        if (!deviceFarmProjectArn) {
                                            throw new AppFactoryException("Project ARN is empty!", 'ERROR')
                                        }

                                    }

                                    script.stage('Create Device Pools') {
                                        if(!isPoolWithDeviceFarmFilters) {
                                            devicePoolArns = deviceFarm.createDevicePools(
                                                    deviceFarmProjectArn, devicePoolName)
                                            if (!devicePoolArns) {
                                                throw new AppFactoryException("Device pool ARN list is empty!", 'ERROR')
                                            }
                                        }
                                    }

                                    script.stage('Upload test package') {
                                        uploadTestBinaries(testPackage, deviceFarmProjectArn)

                                        /* Uploading the Jasmine scripts as extra data package */
                                        if(isJasmineEnabled){
                                            projectArtifacts.each { platformData ->
                                                def pltArray = platformData.key.split('_')
                                                platformData.value.extraPkg = uploadExtraDataPackage(deviceFarmProjectArn, pltArray[0], pltArray[1])
                                            }
                                        }
                                    }
                                    script.stage('Upload test spec') {
                                        script.when(runInCustomTestEnvironment, 'Upload test spec')
                                                {
                                                    prepareAndUploadTestSpec()
                                                }
                                    }
                                    script.stage('Upload application binaries and schedule run') {
                                        uploadAndRun(deviceFarmProjectArn, devicePoolArns)
                                    }

                                    script.stage('Get Test Results') {
                                        deviceFarmTestRunResults = fetchTestResults()
                                        if (!deviceFarmTestRunResults)
                                            throw new AppFactoryException('Tests results are not found as the run result is skipped.', 'ERROR')

                                    }
                                }

                                script.stage('Check PostTest Hook Points') {
                                    if (!deviceFarmTestRunResults)
                                        throw new AppFactoryException('Tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                    def overAllDeviceFarmTestRunResult = getFinalDeviceFarmStatus(deviceFarmTestRunResults)
                                    def nativeTestsResultStatus = overAllDeviceFarmTestRunResult == "PASSED" ? true : false
                                    script.currentBuild.result = nativeTestsResultStatus ? 'SUCCESS' : 'UNSTABLE'

                                    if (runCustomHook) {
                                        nativeTestsResultStatus ? runCustomHooks() :
                                                script.echoCustom('Tests got failed for Native Channel. Hence CustomHooks execution is skipped.', 'WARN')
                                    } else
                                        script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                }
                            }
                        }
                    }
                    finally {
                        channelTestsStats.put('srccmtid', scmMeta['commitID'])
                        //push stats to statspublish
                        script.statspublish channelTestsStats.inspect()

                        NotificationsHelper.sendEmail(script, 'runTests', [
                                isNativeAppTestRun : true,
                                deviceruns      : deviceFarmTestRunResults,
                                devicePoolName  : devicePoolName,
                                binaryName      : getBinaryNameForEmail(projectArtifacts),
                                missingDevices  : script.env.MISSING_DEVICES,
                                summaryofResults: summary,
                                appiumVersion   : appiumVersion,
                                runInCustomTestEnvironment : runInCustomTestEnvironment,
                                defaultDeviceFarmTimeLimit : Long.parseLong(libraryProperties.'test.automation.device.farm.default.time.run.limit'),
                                isJasmineEnabled : isJasmineEnabled,
                                testFramework : testFramework,
                                jasmineNativeTestPlan : jasmineTestPlan
                        ], true)

                        if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                            TestsHelper.PrepareMustHaves(script, runCustomHook, "runNativeTests", libraryProperties, mustHaveArtifacts, false)
                            if (TestsHelper.isBuildDescriptionNeeded(script))
                                TestsHelper.setBuildDescription(script)
                        }
                        /* Cleanup created pools and uploads */
                        cleanup(deviceFarmUploadArns, devicePoolArns)
                    }
                }
            }
        }
    }

    /**
     * Publish the TestNG test-output folder to ArtifactStorage (S3 or Master or other).
     *
     * @param runArtifact the run result from devicefarm
     * @param suiteName the test suite name
     * @param folderPath the folder path need to upload
     * @returns testngReportsAuthURL the authenticated S3 URL of TestNG test-output
     */
    protected  publishTestReports(runArtifact, suiteName, folderPath) {
        def destinationArtifactPath = TestsHelper.getDestinationResultsPath(script, runArtifact, suiteName)
        def testngReportsUrl = ArtifactHelper.publishArtifact sourceFilePath: folderPath, destinationPath: destinationArtifactPath, script
        def testngReportsAuthURL = ArtifactHelper.createAuthUrl(testngReportsUrl, script, true, "view")
        return testngReportsAuthURL

    }

    /**
     * Downloads customer artifacts
     * Parsing testng-results and updating the count of total failed/passed/skipped/total tests to display in email notification.
     *
     * @param testRunArtifacts the run result from devicefarm.
     * @return testRunArtifacts the run result with updated count.
     */
    protected  final def fetchCustomTestResults(testRunArtifacts) {
        def customerArtifactUrl, deviceDisplayName, reportsUrl =[:]
        def deviceName = ""
        def artifactName = 'Customer Artifacts'
        for(suite in testRunArtifacts.suites) {
            for (test in suite.tests) {
                for (artifact in test.artifacts) {
                    if(artifact.name == artifactName) {
                        customerArtifactUrl = artifact.url
                    }
                }
            }
        }
        if(customerArtifactUrl != null) {
            for(device in testRunArtifacts.device) {
                if (device.getValue())
                    deviceName += device.getValue().replaceAll("[^a-zA-Z0-9]", "");
                if(device.getKey() == "name") {
                    deviceDisplayName = device.getValue()
                }
            }
            artifactName = artifactName.replaceAll('\\s', '_')
            script.dir(deviceFarmWorkingFolder) {
                script.shellCustom("mkdir -p " + deviceName, true)
                script.dir(deviceName) {
                    script.shellCustom("mkdir -p test-output", true)
                    script.shellCustom("curl --silent --show-error --fail -o \'${artifactName}\' \'${customerArtifactUrl.toString()}\'", true, [returnStatus: true, returnStdout: true])
                    // extract the final downloaded zip
                    if(script.fileExists(artifactName)) {
                        def zipExtractStatus = script.shellCustom("unzip -q ${artifactName}", true, [returnStatus: true])
                        if (zipExtractStatus) {
                            script.currentBuild.result = "FAILED"
                            throw new AppFactoryException("Failed to extract Customer Artifacts zip", 'ERROR')
                        }
                    }
                }
            }
            def authUrl, test_results
            if (isJasmineEnabled) {
                script.shellCustom("cp -R ${deviceFarmWorkingFolder}/${deviceName}/Host_Machine_Files/*DEVICEFARM_LOG_DIR/* ${deviceFarmWorkingFolder}/${deviceName}/", true)
                boolean isJasmineJSONReportExists = script.fileExists file: "${deviceFarmWorkingFolder}/${deviceName}/JasmineTestResult.json"
                boolean isJasmineHTMLReportExists = script.fileExists file: "${deviceFarmWorkingFolder}/${deviceName}/JasmineTestResult.html"
                if(isJasmineJSONReportExists && isJasmineHTMLReportExists) {
                    def jasmineTestResults = script.readJSON file: "${deviceFarmWorkingFolder}/${deviceName}/JasmineTestResult.json"
                    test_results = parseAndgetJasmineResults(jasmineTestResults)
                    def destinationArtifactPath = TestsHelper.getDestinationResultsPath(script, testRunArtifacts, "JasmineSuite")
                    authUrl = ArtifactHelper.publishArtifact sourceFileName: "JasmineTestResult.html",
                            sourceFilePath: "${deviceFarmWorkingFolder}/${deviceName}",
                            destinationPath: destinationArtifactPath, script
                    authUrl = ArtifactHelper.createAuthUrl(authUrl, script, true)
                } else {
                    script.echoCustom("Jasmine Test report is not found for the ${deviceDisplayName} device. Please check the device logs for more information!!!", "ERROR", false)
                    script.currentBuild.result = "UNSTABLE"
                }
            } else {
                script.shellCustom("cp -R ${deviceFarmWorkingFolder}/${deviceName}/Host_Machine_Files/*DEVICEFARM_LOG_DIR/*  ${deviceFarmWorkingFolder}/${deviceName}/", true)
                boolean isTestNGResultsFileExists = script.fileExists file: "${deviceFarmWorkingFolder}/${deviceName}/test-output/testng-results.xml"
                if(isTestNGResultsFileExists) {
                    authUrl = publishTestReports(testRunArtifacts, "TestNGSuite", "${deviceFarmWorkingFolder}/${deviceName}/test-output/")
                    String testNGResultsFileContent = script.readFile("${deviceFarmWorkingFolder}/${deviceName}/test-output/testng-results.xml")
                    test_results = parseTestResults(testNGResultsFileContent)
                    authUrl = authUrl.replace("*/**", 'index.html')
                } else {
                    script.echoCustom("TestNG report is not found for the ${deviceDisplayName} device. Please check the device logs for more information!!!", "ERROR", false)
                    script.currentBuild.result = "UNSTABLE"
                }
            }
            reportsUrl.put("url", authUrl)
            testRunArtifacts.reports = reportsUrl
            if(test_results) {
                testRunArtifacts.totalSuites = test_results.totalSuites
                testRunArtifacts.failedTests = test_results.failedTests
                testRunArtifacts.passedTests = test_results.passedTests
                testRunArtifacts.skippedTests = test_results.skippedTests
                for(suite in testRunArtifacts.suites) {
                    if(suite.name == "Tests Suite") {
                        suite.totalTests = test_results.totalSuites
                    }
                }
            } else {
                testRunArtifacts.totalSuites = 0
                testRunArtifacts.failedTests = 0
                testRunArtifacts.passedTests = 0
                testRunArtifacts.skippedTests = 0
                testRunArtifacts.erroredTests = 0
                testRunArtifacts.stoppedTests = 0
                testRunArtifacts.warnedTests = 0
            }
        }
        return testRunArtifacts
    }

    /*
     * Parses TestNG results file content and gets the test cases counts
     *
     * @param testNGResultsFileContent the TestNG xml content
     * @return testResultsMap the map containing test cases counts
     */
    @NonCPS
    protected Map parseTestResults(testNGResultsFileContent) {
        def testng_results = new XmlSlurper().parseText(testNGResultsFileContent)
        def testResultsMap = [:]
        testResultsMap.put('totalSuites', testng_results.@total.join(""))
        testResultsMap.put('passedTests', testng_results.@passed.join(""))
        testResultsMap.put('failedTests', testng_results.@failed.join(""))
        testResultsMap.put('skippedTests', testng_results.@skipped.join(""))
        return testResultsMap
    }

    /*
     * Parses Jasmine results file content and gets the test cases counts
     *
     * @param jasmineResults Json file content received from Device.
     * @return testResultsMap the map containing test cases counts
     */
    protected Map parseAndgetJasmineResults(jasmineResults) {
        def testResultsMap = [:]
        def passedResults, failedResults, totalResults
        String successMessage = 'Jasmine Test Results have been fetched successfully for Native'
        String errorMessage = 'Failed to fetch the Jasmine Test Results for Native'

        script.catchErrorCustom(errorMessage, successMessage) {
            passedResults = jasmineResults.findAll { event -> event.event.equalsIgnoreCase('specDone') && event.result.status.equalsIgnoreCase("passed") }
            failedResults = jasmineResults.findAll { event -> event.event.equalsIgnoreCase('specDone') && !event.result.status.equalsIgnoreCase("passed") }
        }

        testResultsMap.put('totalSuites', passedResults.size() + failedResults.size())
        testResultsMap.put('passedTests', passedResults.size())
        testResultsMap.put('failedTests', failedResults.size())
        testResultsMap.put('skippedTests', 0)
        return testResultsMap
    }

    /*
     * Fetches test binary details based on form factor and platform
     *
     * @param formFactor the formFactor either phone or tablet
     * @param platform the platform either ios or android
     *
     * @return projectArtifacts the projectArtifacts details
     */
    protected Map fetchTestBinaryDetails(formFactor, platform) {
        def device = platform + '_' + formFactor
        switch(device) {
            case 'IOS_PHONE' :
                return projectArtifacts.iOS_Mobile
            case 'ANDROID_PHONE' :
                return projectArtifacts.Android_Mobile
            case 'IOS_TABLET' :
                return projectArtifacts.iOS_Tablet
            case 'ANDROID_TABLET' :
                return projectArtifacts.Android_Tablet
        }
    }


    /*
    * Fetches the required SelectionConfig form the devicePoolConfig Json for the given channel and platform
    *
    * @param Channel the channel either phone or tablet
    * @param platform the platform either ios or android
    *
    */
    protected final getDevicePoolSelectionConfig(channel, formFactor) {
        String selectionConfigKey = channel + '_' + formFactor + '_' + 'NATIVE'
        def devicePoolConfigFileContentJson = deviceFarm.parseJsonStringToObject(devicePoolConfigFileContent)
        def selectionConfig = devicePoolConfigFileContentJson[selectionConfigKey]
        if (selectionConfig) {
            script.echoCustom("Selecting a set of devices with the following device filter from ${devicePoolName} pool - ${selectionConfig}")
            return new JsonBuilder(selectionConfig).toPrettyString()
        } else {
            String currentFormFactor = (formFactor.equals("MOBILE")) ? "phones" : "tablets"
            script.echoCustom("Artifacts provided for ${currentFormFactor}, but no ${currentFormFactor} were found in the device pool", 'ERROR', false)
            return null
        }
    }

    /**
     * Check the type of devicePool(Old/New) based on the content of devicePoolConfigFile, is pool created with statics devices or devicefarm filters.
     *
     * @param configId pool name string.
     */
    protected final boolean checkDevicePoolConfigFileContent(configId) {
        script.configFileProvider([script.configFile(fileId: "$configId", variable: 'DEVICES')]) {
            devicePoolConfigFileContent = script.shellCustom('cat $DEVICES', true, [returnStdout: true]).trim()
            try {
                def jsonSlurper = new JsonSlurper()
                jsonSlurper.parseText((String) devicePoolConfigFileContent)
                return true
            } catch (Exception e) {
                /*For the older device farm the config file is constructed with 5 properties (Formfactor * Platform * Vendor * Model * OSversion)
                    * So if any of these properties is missing then throwing an AppFactory exception */
                for (item in devicePoolConfigFileContent.tokenize(',')) {
                    def deviceProperties = item.tokenize('*')
                    if (deviceProperties.size() != 5)
                        throw new AppFactoryException("Something went wrong with the device pool config file content", 'ERROR')
                }
                return false
            }
        }
    }
}