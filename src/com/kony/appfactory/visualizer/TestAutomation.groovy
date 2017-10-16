package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AwsDeviceFarmHelper

class TestAutomation implements Serializable {
    private script
    private libraryProperties
    private workspace
    private projectFullPath
    private testFolder
    /* Build parameters */
    private gitBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    private gitCredentialsID = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    private devicePoolName = script.params.AVAILABLE_TEST_POOLS
    /* Environment variables */
    private projectName = script.env.PROJECT_NAME
    private gitURL = script.env.PROJECT_GIT_URL
    /* Device Farm properties */
    private runTests = false
    private deviceFarm
    private deviceFarmProjectArn
    private devicePoolArns
    private deviceFarmTestUploadArtifactArn
    private deviceFarmUploadArns = []
    private deviceFarmTestRunArns = [:]
    private deviceFarmTestRunResults = [:]
    private deviceFarmWorkingFolder
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
            iOS_Mobile: [binaryName: getBinaryName(script.env.IOS_MOBILE_NATIVE_BINARY_URL),
                         extension : 'ipa',
                         uploadType: 'IOS_APP',
                         url       : script.env.IOS_MOBILE_NATIVE_BINARY_URL],
            iOS_Tablet: [binaryName: getBinaryName(script.env.IOS_TABLET_NATIVE_BINARY_URL),
                         extension : 'ipa',
                         uploadType: 'IOS_APP',
                         url       : script.env.IOS_TABLET_NATIVE_BINARY_URL]
    ]
    private testPackage = [
            "${projectName}_TestApp": [extension : 'zip',
                                       uploadType: 'APPIUM_JAVA_TESTNG_TEST_PACKAGE',
                                       url       : (script.env.TESTS_BINARY_URL ?: 'jobWorkspace')]
    ]

    TestAutomation(script) {
        this.script = script
        deviceFarm = new AwsDeviceFarmHelper(this.script)
        libraryProperties = BuildHelper.loadLibraryProperties(this.script, 'com/kony/appfactory/configurations/common.properties')
        awsRegion = libraryProperties.'test.automation.device.farm.aws.region'
    }

    protected final void validateBuildParameters(buildParameters) {
        def appBinaryURLParameters = buildParameters.findAll {
            it.key.contains('URL') && it.key != 'TESTS_BINARY_URL' && it.value
        }
        def gitParameters = buildParameters.findAll { it.key.contains('PROJECT_SOURCE_CODE') && it.value }
        def testBinaryURLParameter = buildParameters.findAll { it.key == 'TESTS_BINARY_URL' && it.value }
        def deviceListParameter = buildParameters.findAll { it.key.contains('AVAILABLE_TEST_POOLS') && it.value }
        def URLParameters = testBinaryURLParameter + appBinaryURLParameters

        if (appBinaryURLParameters) {
            for (parameter in URLParameters) {
                if (!isValidURL(parameter.value)) {
                    script.error "Build parameter ${parameter.key} value is not valid URL!"
                }
            }

            runTests = true
        }

        if (testBinaryURLParameter && gitParameters) {
            script.error("Please provide only one option for the source of test scripts: GIT or URL")
        } else if (!testBinaryURLParameter && !gitParameters) {
            script.error "Please provide at least one source of test binaries"
        }

        if (!appBinaryURLParameters && testBinaryURLParameter) {
            script.error "Please provide at least one of application binaries URL"
        } else if (!deviceListParameter && appBinaryURLParameters) {
            script.error "Please provide pool to test on"
        }
    }

    protected final boolean isValidURL(urlString) {
        try {
            urlString.toURL().toURI()
            return true
        } catch (Exception exception) {
            return false
        }
    }

    protected final void build() {
        String successMessage = 'Test Automation scripts have been built successfully'
        String errorMessage = 'FAILED to build the Test Automation scripts'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(testFolder) {
                script.sh 'mvn clean package -DskipTests=true'
                script.sh "mv target/zip-with-dependencies.zip target/${projectName}_TestApp.zip"
            }
        }
    }

    protected final void prepareParallelSteps(artifacts, stageName, stepClosure) {
        def stepsToRun = [:]

        def artifactsNames = artifacts.keySet().toArray()

        for (int i=0; i<artifactsNames.size(); i++) {
            def artifact = artifacts.get(artifactsNames[i])
            def artifactName = artifactsNames[i]
            def artifactURL = artifact.url
            def artifactExt = artifact.extension
            def uploadType = artifact.uploadType

            if (artifactURL) {
                def step = {
                    stepClosure(artifactName, artifactURL, artifactExt, uploadType)
                }
                stepsToRun.put("${stageName}${artifactName}", step)
            } else {
                script.echo "${artifactName.replaceAll('_', ' ')} binary was not provided!"
            }
        }

        stepsToRun
    }

    protected final void cleanup(deviceFarmUploadArns, devicePoolArns) {
        script.withAWS(region: awsRegion) {
            if (deviceFarmUploadArns) {
                for (int i = 0; i < deviceFarmUploadArns.size(); ++i) {
                    deviceFarm.deleteUploadedArtifact(deviceFarmUploadArns[i])
                }
            }

            if (devicePoolArns) {
                def poolNames = devicePoolArns.keySet().toArray()
                // Workaround to iterate over map keys in c++ style for loop
                for (int i = 0; i < poolNames.size(); ++i) {
                    deviceFarm.deleteDevicePool(devicePoolArns[poolNames[i]].value)
                }
            }
        }
    }

    @NonCPS
    protected final getBinaryName(urlString) {
        def binaryName = (urlString) ? urlString.replaceAll(/.*\//, '') : ''

        binaryName
    }

    private final getBinaryNameForEmail(artifacts) {
        def result = [:]

        for (artifact in artifacts) {
            result[artifact.key] = artifact.value.get('binaryName')
        }

        result
    }

    protected final void createPipeline() {
        script.stage('Check provided parameters') {
            validateBuildParameters(script.params)
        }

        script.node(libraryProperties.'test.automation.node.label') {
            /* Set environment-dependent variables */
            workspace = script.env.WORKSPACE
            projectFullPath = workspace + '/' + projectName
            testFolder = projectFullPath + '/' + libraryProperties.'test.automation.scripts.path'
            deviceFarmWorkingFolder = projectFullPath + '/' + libraryProperties.'test.automation.device.farm.working.folder.name'

            try {
                script.cleanWs deleteDirs: true

                /* Build test automation scripts if URL with test binaries was not provided */
                if (testPackage.get("${projectName}_TestApp").url == 'jobWorkspace') {
                    script.stage('Checkout') {
                        BuildHelper.checkoutProject script: script,
                                projectRelativePath: projectName,
                                gitBranch: gitBranch,
                                gitCredentialsID: gitCredentialsID,
                                gitURL: gitURL
                    }

                    script.stage('Build') {
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
                            script.error 'FAILED to find build result artifact!'
                        }
                    }
                }
            } catch (Exception e) {
                String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                script.echo "ERROR: $exceptionMessage"
                script.currentBuild.result = 'FAILURE'
            } finally {
                NotificationsHelper.sendEmail(script, 'buildTests')
                /* Exit in case of test binaries failed, throw error to build console. */
                if (script.currentBuild.result == 'FAILURE') {
                    script.error("Something went wrong... Unable to build Test binary!")
                }
            }

            /* Run tests on provided binaries */
            if (runTests) {
                try {
                    script.dir(deviceFarmWorkingFolder) {
                    /* Providing AWS region for DeviceFarm, currently DeviceFarm available in us-west-2 */
                    script.withAWS(region: awsRegion) {
                        script.stage('Fetch binaries') {
                            /* Prepare step to run in parallel */
                            def step = { artifactName, artifactURL, artifactExt ->
                                /* If test binaries URL was not provided, copy binaries from the build step */
                                if (artifactURL == 'jobWorkspace') {
                                    String artifactPath = "${testFolder}/target/${artifactName}.${artifactExt}"
                                    script.sh "cp $artifactPath $deviceFarmWorkingFolder"
                                    /* Else, fetch binaries */
                                } else {
                                    deviceFarm.fetchArtifact(artifactName + '.' + artifactExt, artifactURL)
                                }
                            }
                            def stepsToRun = (prepareParallelSteps(testPackage << projectArtifacts, 'fetch_', step)) ?:
                                    script.error("No artifacts to fetch!")

                            /* Run prepared step in parallel */
                            if (stepsToRun) {
                                script.parallel(stepsToRun)
                            }
                        }

                        script.stage('Create Device Farm Project') {
                            /* Check if project already exists */
                            deviceFarmProjectArn = (deviceFarm.getProject(projectName)) ?:
                                    /* If not, create new project and return ARN or break the build, if ARN equals null */
                                    (deviceFarm.createProject(projectName)) ?: script.error("Project ARN is empty!")
                        }

                        script.stage('Create Device Pools') {
                            devicePoolArns = (deviceFarm.createDevicePools(deviceFarmProjectArn, devicePoolName)) ?:
                                    script.error("Device pool ARN list is empty!")
                        }

                        script.stage('Upload test package') {
                            /* Get required parameters for test binaries upload */
                            def testUploadType = testPackage.get("${projectName}_TestApp").uploadType
                            def testExtension = testPackage.get("${projectName}_TestApp").extension
                            def testUploadFileName = "${projectName}_TestApp.${testExtension}"

                            /* Upload test binaries and get upload ARN */
                            deviceFarmTestUploadArtifactArn = deviceFarm.uploadArtifact(deviceFarmProjectArn,
                                    testUploadType,
                                    testUploadFileName)
                            /* Add test binaries upload ARN to upload ARNs list */
                            deviceFarmUploadArns.add(deviceFarmTestUploadArtifactArn)
                        }

                        script.stage('Upload application binaries and schedule run') {
                            /* Prepare step to run in parallel */
                            def step = { artifactName, artifactURL, artifactExt, uploadType ->
                                /* Upload application binaries to DeviceFarm and add upload ARN to list */
                                def uploadArn = deviceFarm.uploadArtifact(deviceFarmProjectArn,
                                        uploadType, artifactName + '.' + artifactExt)
                                deviceFarmUploadArns.add(uploadArn)

                                /* If we have applicataion binaries and test binaries, schedule the run */
                                if (uploadArn && deviceFarmTestUploadArtifactArn) {
                                    /* Depending on artifact name we need to chose appropriate pool for the run */
                                    def devicePoolArn = (artifactName.toLowerCase().contains('mobile')) ?
                                            (devicePoolArns.phones) ?:
                                                    script.error("Artifacts provided for tablets, but TABLET devices were provided") :
                                            (devicePoolArns.tablets ?:
                                                    script.error("Artifacts provided for tablets, but PHONE devices were provided"))
                                    /* Once all parameters gotten, shcedule the DeviceFarm run */
                                    def runArn = deviceFarm.scheduleRun(deviceFarmProjectArn, devicePoolArn,
                                            'APPIUM_JAVA_TESTNG', uploadArn, deviceFarmTestUploadArtifactArn)
                                    deviceFarmTestRunArns["${artifactName}"] = runArn
                                    /* Else fail the stage, because run couldn't be scheduled without one of the binaries */
                                } else {
                                    script.echo "FAILED to get uploadArn"
                                }
                            }
                            def stepsToRun = (prepareParallelSteps(projectArtifacts, 'uploadAndRun_', step)) ?:
                                    script.error("No artifacts to upload and run!")

                            /* Run prepared step in parallel */
                            if (stepsToRun) {
                                script.parallel(stepsToRun)
                            }
                        }

                        script.stage('Get Test Results') {
                            def stepsToRun = [:]

                            def deviceFarmTestRunArnsKeys = deviceFarmTestRunArns.keySet().toArray()
                            // Workaround to iterate over map keys in c++ style for loop
                            for (int i=0; i<deviceFarmTestRunArnsKeys.size(); ++i) {
                                def arn = deviceFarmTestRunArns[deviceFarmTestRunArnsKeys[i]]
                                /* Prepare step to run in parallel */
                                stepsToRun["testResults_${deviceFarmTestRunArnsKeys[i]}"] = {
                                    def testRunResult = deviceFarm.getTestRunResult(arn)
                                    /* If we got a test result */
                                    if (testRunResult) {
                                        /*
                                            Query DeviceFarm for test artifacts (logs, videos, etc) and
                                            store test run artifact object in list
                                         */
                                        deviceFarmTestRunResults.runs = deviceFarm.getTestRunArtifacts(arn)
                                        /* Else notify user that result value is empty */
                                    } else {
                                        script.echo "Test run result for ${deviceFarmTestRunArnsKeys[i]} is empty!"
                                    }
                                }
                            }

                            /* Run prepared step in parallel */
                            if (stepsToRun) {
                                script.parallel(stepsToRun)
                            }

                            script.dir('artifacts') {
                                deviceFarm.moveArtifactsToCustomerS3Bucket(
                                        deviceFarmTestRunResults.runs,
                                        ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
                                )
                            }
                        }
                    }
                }
                } catch (Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                    script.echo "ERROR: $exceptionMessage"
                    script.currentBuild.result = 'FAILURE'
                } finally {
                    cleanup(deviceFarmUploadArns, devicePoolArns)
                    NotificationsHelper.sendEmail(script,
                            'runTests',
                            deviceFarmTestRunResults + [
                                    devicePoolName: devicePoolName,
                                    binaryName: getBinaryNameForEmail(projectArtifacts),
                                    missingDevices: script.env.MISSING_DEVICES
                            ],
                            true
                    )
                }
            }
        }
    }
}
