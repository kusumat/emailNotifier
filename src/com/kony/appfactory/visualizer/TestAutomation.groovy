package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AwsDeviceFarmHelper
import com.kony.appfactory.helper.CustomHookHelper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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
    /* Build parameters */
    private scmBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private scmCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private devicePoolName = script.params.AVAILABLE_TEST_POOLS
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
    private testPackage = [
            "${projectName}_TestApp": [extension : 'zip',
                                       uploadType: 'APPIUM_JAVA_TESTNG_TEST_PACKAGE',
                                       url       : (script.env.TESTS_BINARY_URL ?: 'jobWorkspace')]
    ]

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
    }

    /**
     * Validates build parameters.
     *
     * @param buildParameters job parameters.
     */
    protected final void validateBuildParameters(buildParameters) {
        /* Filter all application binaries build parameters */
        def appBinaryUrlParameters = buildParameters.findAll {
            it.key.contains('URL') && it.key != 'TESTS_BINARY_URL' && it.value
        }
        /* Filter all SCM build parameters */
        def scmParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        /* Filter test binaries build parameter */
        def testBinaryUrlParameter = buildParameters.findAll { it.key == 'TESTS_BINARY_URL' && it.value }
        /* Filter pool name build parameter */
        def poolNameParameter = buildParameters.findAll { it.key.contains('AVAILABLE_TEST_POOLS') && it.value }
        /* Combine binaries build parameters */
        def UrlParameters = testBinaryUrlParameter + appBinaryUrlParameters

        /* Check if at least one application binaries parameter been provided */
        if (appBinaryUrlParameters) {
            /* Validate application binaries URLs */
            for (parameter in UrlParameters) {
                if (parameter.value.contains('//') && isValidUrl(parameter.value)) {
                    parameter.value=parameter.value.replace(" ", "%20")
                }
                else
                {
                    script.echoCustom("Build parameter ${parameter.key} value is not valid URL!",'ERROR')
                }
            }

            /* Set flag to run tests on Device Farm */
            runTests = true
        }

        /*
            To restrict user run tests with build Test Automation scripts or with provided test binaries,
            fail build if both options provided.
         */
        if (testBinaryUrlParameter && scmParameters) {
            script.echoCustom("Please provide only one option for the source of test scripts: GIT or URL",'ERROR')
        }
        /* Same if none of the options provided */
        else if (!testBinaryUrlParameter && !scmParameters) {
            script.echoCustom("Please provide at least one source of test binaries",'ERROR')
        }

        /* Fail build if testBinaryUrlParameter been provided without appBinaryUrlParameters */
        if (!appBinaryUrlParameters && testBinaryUrlParameter) {
            script.echoCustom("Please provide at least one of application binaries URL",'ERROR')
        }
        /* Fail build if appBinaryUrlParameters been provided without test pool */
        else if (!poolNameParameter && appBinaryUrlParameters) {
            script.echoCustom("Please provide pool to test on",'ERROR')
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
     * Builds Test Automation scripts.
     */
    protected final void build() {
        String successMessage = 'Test Automation scripts have been built successfully'
        String errorMessage = 'Failed to build the Test Automation scripts'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(testFolder) {
                script.shellCustom('mvn clean package -DskipTests=true', true)
                script.shellCustom("mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip", true)
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
            }
        }

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
        }

        /* Move artifacts to customer bucket */
        script.dir('artifacts') {
            deviceFarm.moveArtifactsToCustomerS3Bucket(
                    deviceFarmTestRunResults,
                    ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
            )
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
                        'APPIUM_JAVA_TESTNG', uploadArn, deviceFarmTestUploadArtifactArn)
                deviceFarmTestRunArns["$artifactName"] = runArn
                /* Otherwise, fail the stage, because run couldn't be scheduled without one of the binaries */
            } else {
                script.echoCustom("Failed to get uploadArn",'WARN')
            }
        }

        /* Prepare parallel steps */
        def stepsToRun = (prepareParallelSteps(projectArtifacts, 'uploadAndRun_', step)) ?:
                script.echoCustom("No artifacts to upload and run!",'ERROR')

        /* Run prepared step in parallel */
        if (stepsToRun) {
            script.parallel(stepsToRun)
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
        def stepsToRun = prepareParallelSteps(
                testPackage << projectArtifacts, 'fetch_', step
        ) ?: script.echoCustom("No artifacts to fetch!",'ERROR')

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
            script.catchErrorCustom("Failed to create the Zip file") {
                if(script.fileExists(mustHaveFilePath)){
                    String s3MustHaveUrl = AwsHelper.publishToS3  bucketPath: s3ArtifactPath, sourceFileName: mustHaveFile,
                                    sourceFilePath: projectFullPath, script
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
                            channelVariableName: "Tests", name: mustHaveFile, url: s3MustHaveUrl
                        ])
                        script.env['MUSTHAVE_ARTIFACTS'] = mustHaves?.inspect()
                    }
                }
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
                                BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: scmBranch,
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }

                            script.stage('Build') {
                                /* Build Test Automation scripts */
                                build()
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
                                } else {
                                    script.echoCustom('Failed to find build result artifact!','ERROR')
                                }
                            }
                        }
                    } catch (Exception e) {
                        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage,'WARN')
                        script.currentBuild.result = 'FAILURE'
                    } finally {
                        NotificationsHelper.sendEmail(script, 'buildTests')
                        /* Exit in case of test binaries failed, throw error to build console. */
                        if (script.currentBuild.result == 'FAILURE') {
                            script.echoCustom("Something went wrong... Unable to build Test binary!",'WARN')
                            PrepareMustHaves(false)
                            setBuildDescription()
                        }
                    }

                    /* Run tests on provided binaries */
                    if (runTests) {
                        try {
                            script.dir(deviceFarmWorkingFolder) {
                                /* Providing AWS region for Device Farm, currently it is available in us-west-2 */
                                script.withAWS(region: awsRegion) {
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
                                                deviceFarm.createProject(projectName) ?:
                                                        script.echoCustom("Project ARN is empty!",'ERROR')
                                    }

                                    script.stage('Create Device Pools') {
                                        devicePoolArns = deviceFarm.createDevicePools(
                                                deviceFarmProjectArn, devicePoolName
                                        ) ?: script.echoCustom("Device pool ARN list is empty!",'ERROR')
                                    }

                                    script.stage('Upload test package') {
                                        uploadTestBinaries()
                                    }

                                    script.stage('Upload application binaries and schedule run') {
                                        uploadAndRun()
                                    }

                                    script.stage('Get Test Results') {
                                        fetchTestResults()
                                        deviceFarmTestRunResults ?: script.echoCustom('Tests results are not found as the run result is skipped.', 'ERROR')
                                    }
                                }

                                script.stage('Check PostTest Hook Points'){
                                    if(runCustomHook) {
                                        deviceFarmTestRunResults ?: script.echoCustom('Tests results not found. Hence CustomHooks execution is skipped.', 'ERROR')
                                        def overAllDeviceFarmTestRunResult = getFinalDeviceFarmStatus(deviceFarmTestRunResults)
                                        def status = overAllDeviceFarmTestRunResult == "PASSED" ? true : false

                                        if (status) {
                                            ['Android_Mobile', 'Android_Tablet', 'iOS_Mobile', 'iOS_Tablet'].each { project ->
                                                if (projectArtifacts."$project".'binaryName') {
                                                    def isSuccess = hookHelper.runCustomHooks(projectName, libraryProperties.'customhooks.posttest.name', project.toUpperCase() + "_STAGE")
                                                    if (!isSuccess)
                                                        throw new Exception("Something went wrong with the Custom hooks execution.")
                                                }
                                            }
                                        } else {
                                            script.echoCustom('Tests got failed for one/more devices. Hence CustomHooks execution is skipped.', 'WARN')
                                        }
                                    }
                                    else{
                                        script.echoCustom('runCustomHook parameter is not selected by the User. Hence CustomHooks execution skipped.', 'INFO')
                                    }
                                }
                            }
                        } catch (Exception e) {
                            String exceptionMessage = (e.toString()) ?: 'Something went wrong...'
                            script.echoCustom(exceptionMessage,'WARN')
                            script.currentBuild.result = 'FAILURE'
                        } finally {
                            NotificationsHelper.sendEmail(script, 'runTests', [
                                    runs          : deviceFarmTestRunResults,
                                    devicePoolName: devicePoolName,
                                    binaryName    : getBinaryNameForEmail(projectArtifacts),
                                    missingDevices: script.env.MISSING_DEVICES
                            ], true)
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
}
