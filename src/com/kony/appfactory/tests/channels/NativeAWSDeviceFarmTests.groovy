package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.AwsDeviceFarmHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.ValidationHelper

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
    protected runInCustomTestEnvironment = script.params.RUN_IN_CUSTOM_TEST_ENVIRONMENT
    protected appiumVersion = script.params.APPIUM_VERSION
    protected testngFiles = script.params.TESTNG_FILES

    /* Device Farm related variables */
    private deviceFarm, deviceFarmProjectArn, devicePoolArns, deviceFarmTestUploadArtifactArn, deviceFarmTestSpecUploadArtifactArn
    protected deviceFarmUploadArns = [], deviceFarmTestRunArns = [:], deviceFarmTestRunResults = [], summary = [:], duration = [:], runArnMap = [:]

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
    protected ymlTemplate = 'com/kony/appfactory/configurations/KonyYamlTestSpec.template'
    protected testSpecUploadFileName = "TestSpec.yml"
    protected mustHaveArtifacts = []
    public testSpecUploadFilePath

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    NativeAWSDeviceFarmTests(script) {
        super(script)
        /* Initializer Device Farm scrips object */
        deviceFarm = new AwsDeviceFarmHelper(script)
        awsRegion = libraryProperties.'test.automation.device.farm.aws.region'
    }

    /**
     * Uploads application binaries and Schedules the run.
     *
     * @param deviceFarmProjectArn is the ARN that is associated with a particular project
     * @param devicePoolArns is the ARN that is associated with a device pool
     */
    final void uploadAndRun(deviceFarmProjectArn, devicePoolArns) {
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
                            "for phones, but no phones were found in the device pool", 'ERROR') :
                    (devicePoolArns.tablets ?: script.echoCustom("Artifacts provided for " +
                            "tablets, but no tablets were found in the device pool", 'ERROR'))

            /* If we have application binaries and test binaries, schedule the custom run */
            if (uploadArn && deviceFarmTestUploadArtifactArn) {
                deviceFarmTestSpecUploadArtifactArn ? script.echoCustom("Running in Custom Test Environment.", 'INFO') : script.echoCustom("Running in Standard Test Environment.", 'INFO')
                /* Once all parameters gotten, schedule the Device Farm run */
                def runArn = deviceFarm.scheduleRun(deviceFarmProjectArn, devicePoolArn, 'APPIUM_JAVA_TESTNG', uploadArn, deviceFarmTestUploadArtifactArn, artifactName, deviceFarmTestSpecUploadArtifactArn)
                deviceFarmTestRunArns["$artifactName"] = runArn
                /* Otherwise, fail the stage, because run couldn't be scheduled without one of the binaries */
            } else {
                script.echoCustom("Failed to get uploadArn", 'WARN')
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
     * to customer S3 bucket.
     */
    protected final fetchTestResults() {
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
                    script.echoCustom("Test run result for ${deviceFarmTestRunArnsKeys[i]} is empty!", 'WARN')
                }
                summary.putAll(deviceFarm.testSummaryMap)
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
        script.echoCustom("Summary of Test Results : ", 'INFO')
        separator()
        duration.putAll(deviceFarm.durationMap)
        runArnMap.putAll(deviceFarm.runArnMap)
        summary.each {
            script.echoCustom("On " + it.key + ":: " + it.value + ", Duration: " + duration[it.key] + ", Run ARN: " + runArnMap[it.key])
        }
        separator()

        /* Move artifacts to customer bucket */
        script.dir('artifacts') {
            deviceFarm.moveArtifactsToCustomerS3Bucket(
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
            if (artifactUrl) {
                def step = {
                    stepClosure(artifactName, artifactUrl, artifactExt, uploadType)
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
        /* Filter all application binaries build parameters */
        def nativeAppBinaryUrlParameters = buildParameters.findAll {
            it.key.contains('NATIVE_BINARY_URL') && it.value
        }

        if (runInCustomTestEnvironment) {
            /*Filter AWS Test Environment related parameters */
            def awsCustomEnvMandatoryParameters = ['TESTNG_FILES']
            /* Check all required parameters depending on user input */
            ValidationHelper.checkBuildConfiguration(script, awsCustomEnvMandatoryParameters)
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

        /* Check if at least one application binaries parameter been provided */
        (!nativeAppBinaryUrlParameters) ?: validateApplicationBinariesURLs(nativeUrlParameters)

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
            def cleanTestSpecFile = "rm -f ${testSpecUploadFileName}"
            script.shellCustom("$cleanTestSpecFile", true)
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
            appiumVersion ? script.echoCustom("Value of Appium Version is :" + appiumVersion) : script.echoCustom("No Appium Version entered, will run with default.")
            /* Load YAML Template */
            ymlTemplate = script.loadLibraryResource(configFolderPath + '/KonyYamlTestSpec.template')
            def template = BuildHelper.populateTemplate(ymlTemplate, [appiumVersion: appiumVersion, testngFiles: testngFiles])
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
                script.stage('Validate parameters') {
                    validateBuildParameters(script.params)
                }

                /* Allocate a slave for the run */
                script.node(libraryProperties.'test.native.aws.automation.node.label') {

                    pipelineWrapper ("Native", {
                        /*
                        Clean workspace, to be sure that we have not any items from previous build,
                        and build environment completely new.
                     */
                        script.cleanWs deleteDirs: true

                        /* Build test automation scripts if URL with test binaries was not provided */
                        if (testPackage.get("${projectName}_TestApp").url == 'jobWorkspace') {
                            script.stage('Checkout') {
                                BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        checkoutType: "scm",
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }

                            script.stage('Build') {
                                if(runInCustomTestEnvironment && !appiumVersion) {
                                    if (script.fileExists("${testFolder}/src/test/resources/${testSpecUploadFileName}"))
                                        testSpecUploadFilePath = "${testFolder}/src/test/resources/${testSpecUploadFileName}"
                                }
                                /* Build Test Automation scripts */
                                buildTestScripts("Native", testFolder)
                                
                                script.dir(testFolder) {
                                    script.shellCustom("mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip", true)
                                }
                                /* Set runTests flag to true when build is successful,otherwise set it to false (in catch block) */
                                runTests = true
                            }
                            script.stage('Publish test automation scripts build result to S3') {
                                if (script.fileExists("${testFolder}/target/${projectName}_TestApp.zip")) {
                                    AwsHelper.publishToS3 sourceFileName: "${projectName}_TestApp.zip",
                                            bucketPath: [
                                                    'Tests',
                                                    script.env.JOB_BASE_NAME,
                                                    script.env.BUILD_NUMBER
                                            ].join('/'),
                                            sourceFilePath: "${testFolder}/target", script, true
                                } else
                                    throw new AppFactoryException('Failed to find build result artifact!', 'ERROR')
                            }
                        }
                    })

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
                                        devicePoolArns = deviceFarm.createDevicePools(
                                                deviceFarmProjectArn, devicePoolName)
                                        if (!devicePoolArns) {
                                            throw new AppFactoryException("Device pool ARN list is empty!", 'ERROR')
                                        }
                                    }

                                    script.stage('Upload test package') {
                                        uploadTestBinaries(testPackage, deviceFarmProjectArn)
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

                                    if (runCustomHook) {
                                        nativeTestsResultStatus ? runCustomHooks() :
                                                script.echoCustom('Tests got failed for Native Channel. Hence CustomHooks execution is skipped.', 'WARN')
                                    } else
                                        script.echoCustom('RUN_CUSTOM_HOOK parameter is not selected by the user or there are no active CustomHooks available. Hence CustomHooks execution skipped', 'INFO')
                                }
                            }
                        } catch (AppFactoryException e) {
                            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage, e.getErrorType(), false)
                            script.currentBuild.result = 'FAILURE'
                        } catch (Exception e) {
                            String exceptionMessage = (e.toString()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage, 'WARN')
                            script.currentBuild.result = 'FAILURE'
                        } finally {
                            /* Add the test results to env variables so that those can be accessible from FacadeTests class and will be used during email template creation */
                            script.env['NATIVE_RUN_RESULTS'] = deviceFarmTestRunResults?.inspect()
                            script.env['DEVICE_POOL_NAME'] = devicePoolName
                            script.env['BINARY_NAME'] = getBinaryNameForEmail(projectArtifacts).inspect()
                            script.env['MISSING_DEVICES'] = script.env.MISSING_DEVICES.inspect()
                            script.env['NATIVE_RESULTS_SUMMARY'] = summary?.inspect()
                            script.env['DURATION'] = duration?.inspect()

                            NotificationsHelper.sendEmail(script, 'runTests', [
                                    deviceruns      : deviceFarmTestRunResults,
                                    devicePoolName  : devicePoolName,
                                    binaryName      : getBinaryNameForEmail(projectArtifacts),
                                    missingDevices  : script.env.MISSING_DEVICES,
                                    summaryofResults: summary,
                                    duration      : duration,
                                    appiumVersion : appiumVersion,
                                    runInCustomTestEnvironment : runInCustomTestEnvironment
                            ], true)

                            if (script.currentBuild.result != 'SUCCESS') {
                                TestsHelper.PrepareMustHaves(script, runCustomHook, "runNativeTests", libraryProperties, mustHaveArtifacts, false)
                                (!TestsHelper.isBuildDescriptionNeeded(script)) ?: TestsHelper.setBuildDescription(script)
                            }
                            /* Cleanup created pools and uploads */
                            cleanup(deviceFarmUploadArns, devicePoolArns)
                        }
                    }
                }
            }
        }
    }
}