package com.kony.appfactory.tests

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.tests.channels.NativeAWSDeviceFarmTests
import com.kony.appfactory.tests.channels.DesktopWebTests
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.AppFactoryException

import groovyx.net.http.*
import groovy.util.slurpersupport.*
import java.util.*

/**
 * Implements logic for runTests job.
 *
 * runTests job responsible for Test Automation scripts build and scheduling Device Farm test runs and Web tests with provided
 *  application binaries, device pools and fabric app URLs.
 *
 * Test run and Test Automation scripts artifacts are stored on artifactStorage according with approved folder structure.
 *
 * E-mail notification template and JSON file for App Factory console,
 *  also stored on artifactStorage according with approved folder structure.
 */
class FacadeTests implements Serializable {
    /* Pipeline object */
    private script

    /* Library configuration */
    private libraryProperties
    private mustHaveArtifacts = []

    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder

    /*
        Platform dependent default name-separator character as String.
        For windows, it's '\' and for unix it's '/'.
        Because all logic for test will be run on linux, set separator to '/'.
     */
    private separator = '/'

    /* List of steps for parallel run */
    private runList = [:]

    /* Job workspace path */
    private workspace

    /* Absolute path to the project folder (<job_workspace>/vis_ws/<project_name>[/<project_root>]) */
    private projectFullPath

    /*
    If projectRoot value has been provide, than value of this property
    will be set to <job_workspace>/vis_ws/<project_name> otherwise it will be set to <job_workspace>/vis_ws
 */
    protected projectWorkspacePath

    /* Environment variable */
    private projectName = script.env.PROJECT_NAME
    private projectRoot
    private runCustomHook = script.params.RUN_CUSTOM_HOOKS

    /* Parameters used to differentiate Native and Web apps */
    def webAppUrlParamName = BuildHelper.getCurrentParamName(script, 'WEB_APP_URL', 'FABRIC_APP_URL')
    public isWebApp = script.params.findAll { it.key == webAppUrlParamName && it.value }
    public isNativeApp = script.params.findAll { it.key.contains('NATIVE_BINARY_URL') && it.value }

    /* List of run Results, used for setting up final result of the runTests job */
    private runResults = []

    /* Create instance of DesktopWebTests and NativeAWSDeviceFarmTests*/
    DesktopWebTests desktopWebTests
    NativeAWSDeviceFarmTests nativeAWSDeviceFarmTests

    /* Outputs that get generated when we trigger child jobs(Native and Web)*/
    def nativeTestsJob
    def desktopWebTestsJob
    def testsJob
    def testsJobOutput = [:]

    /* Web TestAutomation build parameters */
    private final webTestsArgumentsParamName = BuildHelper.getCurrentParamName(script, 'RUN_WEB_TESTS_ARGUMENTS', 'RUN_DESKTOPWEB_TESTS_ARGUMENTS')
    private final runWebTestsJobName =  (webTestsArgumentsParamName == "RUN_WEB_TESTS_ARGUMENTS")? "runWebTests" : "runDesktopWebTests"

    /* To maintain backward compatibility, we are checking whether 'Channels' folder is present under 'Tests' folder or not and then modifying the subject of mail accordingly */
    String testAutomationJobBasePath = "${script.env.JOB_NAME}" -
            "${script.env.JOB_BASE_NAME}" + "Channels/"

    /* Contains the email data that has to be sent to sendEmail to display in email body */
    def emailData = [:]

    /* Build Stats */
    def buildStats = [:]
    def runListStats = [:]
    private runInCustomTestEnvironment = (script.params.containsKey("TEST_ENVIRONMENT")) ? ((script.params.TEST_ENVIRONMENT == 'Custom') ? true : false ) : script.params.RUN_IN_CUSTOM_TEST_ENVIRONMENT
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    FacadeTests(script) {
        this.script = script
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        /* Set the visualizer project settings values to the corresponding visualizer environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Visualizer')

        workspace = script.env.WORKSPACE
        projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')
        this.script.env['CLOUD_ACCOUNT_ID'] = (this.script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (this.script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
    }

    /**
     * This method returns Test Automation job build parameters that are common for both Native and Web.
     *
     * @return Test Automation job common build parameters.
     */
    private final getTestAutomationCommonJobParameters() {
        [
                script.string(name: 'PROJECT_SOURCE_CODE_BRANCH', value: script.params.PROJECT_SOURCE_CODE_BRANCH),
                script.credentials(name: 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID',
                        value: script.env.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID),
                script.string(name: 'RECIPIENTS_LIST', value: script.env.RECIPIENTS_LIST),
                script.booleanParam(name: 'RUN_CUSTOM_HOOKS', value: script.params.RUN_CUSTOM_HOOKS),
                script.string(name: 'TEST_FRAMEWORK', value: BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG'))
        ]
    }
    /**
     * This method returns Test Automation job common build parameters along with the parameters that are specific to Native channel.
     *
     * @return Test Automation job common build parameters along with Native channel specific parameters.
     */
    private final getNativeTestAutomationJobParameters() {
        def nativeTestAutomationParams = getTestAutomationCommonJobParameters() +
                [
                        script.string(name: 'ANDROID_UNIVERSAL_NATIVE_BINARY_URL', value: script.params.ANDROID_UNIVERSAL_NATIVE_BINARY_URL),
                        script.string(name: 'ANDROID_MOBILE_NATIVE_BINARY_URL', value: script.params.ANDROID_MOBILE_NATIVE_BINARY_URL),
                        script.string(name: 'ANDROID_TABLET_NATIVE_BINARY_URL', value: script.params.ANDROID_TABLET_NATIVE_BINARY_URL),
                        script.string(name: 'IOS_UNIVERSAL_NATIVE_BINARY_URL', value: script.params.IOS_UNIVERSAL_NATIVE_BINARY_URL),
                        script.string(name: 'IOS_MOBILE_NATIVE_BINARY_URL', value: script.params.IOS_MOBILE_NATIVE_BINARY_URL),
                        script.string(name: 'IOS_TABLET_NATIVE_BINARY_URL', value: script.params.IOS_TABLET_NATIVE_BINARY_URL),
                        script.string(name: 'NATIVE_TESTS_URL', value: script.params.NATIVE_TESTS_URL),
                        script.string(name: 'AVAILABLE_TEST_POOLS', value: script.params.AVAILABLE_TEST_POOLS),
                        script.booleanParam(name: 'RUN_IN_CUSTOM_TEST_ENVIRONMENT', value: (script.params.containsKey("TEST_ENVIRONMENT")) ? ((script.params.TEST_ENVIRONMENT == 'Custom') ? true : false) : script.params.RUN_IN_CUSTOM_TEST_ENVIRONMENT),
                        script.string(name: 'APPIUM_VERSION', value: script.params.APPIUM_VERSION),
                        script.string(name: 'TESTNG_FILES', value: script.params.TESTNG_FILES),
                        script.string(name: 'NATIVE_TEST_PLAN', value: BuildHelper.getParamValueOrDefault(script, "NATIVE_TEST_PLAN", null))

                ]
        if (script.params.containsKey("TEST_ENVIRONMENT")) {
            nativeTestAutomationParams.add(script.string(name: 'TEST_ENVIRONMENT', value: script.params.TEST_ENVIRONMENT))
        }
        nativeTestAutomationParams
    }

    /**
     * This method returns Test Automation job common build parameters along with the parameters that are specific to Web channel.
     *
     * @return Test Automation job common build parameters along with Web channel specific parameters.
     */
    private final getDesktopWebTestAutomationJobParameters() {
        getTestAutomationCommonJobParameters() + getDesktopWebTestAutomationArtifactURLParameter() +
                [
                        script.string(name: webAppUrlParamName, value: script.params[webAppUrlParamName]),
                        script.string(name: 'AVAILABLE_BROWSERS', value: script.params.AVAILABLE_BROWSERS),
                        script.string(name: "${webTestsArgumentsParamName}", value: script.params[webTestsArgumentsParamName]),
                        script.string(name: 'JASMINE_TEST_URL', value: BuildHelper.getParamValueOrDefault(script, "JASMINE_TEST_URL", "")?.trim()),
                        script.string(name: 'WEB_TEST_PLAN',  value: BuildHelper.getParamValueOrDefault(script, "WEB_TEST_PLAN", null))

                ]

    }

    /**
     * This method returns Desktopweb Test Artifact artifactStorage url parameter.
     *
     * @return Desktopweb Test Artifact artifactStorage url parameter.
     */
    private final getDesktopWebTestAutomationArtifactURLParameter() {
        !script.params.containsKey('DESKTOPWEB_ARTIFACT_URL') ? [] :
                [script.string(name: 'DESKTOPWEB_ARTIFACT_URL', value: script.params.DESKTOPWEB_ARTIFACT_URL)]

    }

    /**
     * Returns the parameters needed for running the child jobs.
     * @param testChannel Contains the name of test channel selected for running the tests
     * @return parameters for child test jobs .
     */
    protected final getParamsForTests(testChannel) {
        def parametersForRunningTests = [:]
        switch (testChannel) {
            case ~/^.*Native.*$/:
                String nativeTestAutomationJobName = "${testAutomationJobBasePath}runNativeTests"
                parametersForRunningTests += ["Native Tests": ["jobName"   : nativeTestAutomationJobName,
                                                               "parameters": getNativeTestAutomationJobParameters()]]
                break
            case ~/^.*Web.*$/:
                String dWebTestAutomationJobName = "${testAutomationJobBasePath}" + "${runWebTestsJobName}"
                parametersForRunningTests += ["Web Tests": ["jobName"   : dWebTestAutomationJobName,
                                                                   "parameters": getDesktopWebTestAutomationJobParameters()]]
                break
            default:
                break
        }
        parametersForRunningTests
    }

    /**
     * Prepares run steps for triggering tests jobs in parallel.
     */
    private final void prepareRun() {
        /* Used to filter the channels to run tests*/
        def testsChannelsToRun = []

        if (isNativeApp)
            testsChannelsToRun += ["Native Tests"]
        if (isWebApp)
            testsChannelsToRun += ["Web Tests"]

        testsChannelsToRun.each {
            def parametersForRunningTests = getParamsForTests("${it.value}")
            runList["${it.value}"] = {
                script.stage("${it.value}") {
                    testsJob = script.build job: parametersForRunningTests["${it.value}"].jobName,
                            parameters: parametersForRunningTests["${it.value}"].parameters,
                            propagate: false

                    /* collect job run id to build stats */
                    runListStats.put(testsJob.fullProjectName + "/" + testsJob.number, testsJob.fullProjectName)

                    testsJobOutput += [("${it.value}".trim()): testsJob]
                    runResults.add(testsJob.currentResult)

                    if (testsJob.currentResult != 'SUCCESS') {
                        script.echoCustom("Status of run" + "${it.value}".minus(" ") + " is: ${testsJob.currentResult}", 'WARN')
                    }
                    /* Collect must have artifacts */
                    mustHaveArtifacts.addAll(TestsHelper.getArtifactObjects(testsJob.buildVariables.MUSTHAVE_ARTIFACTS))
                }
            }
        }
    }

    /* Used to detect whether this is parallel run or not*/

    protected final isParallelRun() {
        (Jenkins.instance.getItemByFullName(testAutomationJobBasePath) != null) ? true : false
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        desktopWebTests = new DesktopWebTests(script)
        nativeAWSDeviceFarmTests = new NativeAWSDeviceFarmTests(script)
        if(!isNativeApp && !isWebApp)
            throw new AppFactoryException("Please provide atleast one of the Native Binary URLs or ${webAppUrlParamName} to proceed with the build.", 'ERROR')
        /* Allocate a slave for the run */
        script.node(libraryProperties.'facade.node.label') {
            try {
                if (isParallelRun()) {
                    script.timestamps {
                        /* Wrapper for colorize the console output in a pipeline build */
                        script.ansiColor('xterm') {
                            script.stage('Validate parameters') {
                                if (isNativeApp)
                                    nativeAWSDeviceFarmTests.validateBuildParameters(script.params)
                                if (isWebApp)
                                    desktopWebTests.validateBuildParameters(script.params)
                            }
                                prepareRun()
                                script.parallel(runList)
                                if (runResults.contains('FAILURE') ||
                                        runResults.contains('UNSTABLE') ||
                                        runResults.contains('ABORTED')
                                ) {
                                    /* Set job result to 'UNSTABLE' if above check is true */
                                    script.currentBuild.result = 'UNSTABLE'
                                    TestsHelper.PrepareMustHaves(script, runCustomHook, "Tests_${script.env.BUILD_NUMBER}", libraryProperties, mustHaveArtifacts)
                                    TestsHelper.setBuildDescription(script)
                                } else {
                                    /* Set job result to 'SUCCESS' if above check is false */
                                    script.currentBuild.result = 'SUCCESS'
                                }
                        }
                    }
                } else {
                    runTestsSequentially()
                }
            }
            catch (AppFactoryException e) {
                String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                script.echoCustom(exceptionMessage, 'ERROR', false)
                script.currentBuild.result = 'FAILURE'
                buildStats.put('errmsg', exceptionMessage)
                buildStats.put('errstack', e.getStackTrace().toString())
            } catch (Exception e) {
                String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                script.echoCustom(exceptionMessage, 'ERROR', false)
                script.currentBuild.result = 'FAILURE'
                buildStats.put('errmsg', exceptionMessage)
                buildStats.put('errstack', e.getStackTrace().toString())
            }
            finally {
                if (!runListStats.isEmpty())
                    buildStats.put("pipeline-run-jobs", runListStats)
                buildStats.put('testfwk', BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG'))
                buildStats.put('testfwkver', script.params.APPIUM_VERSION)
                buildStats.put('runmode', runInCustomTestEnvironment ? 'custom' : 'standard' )
                buildStats.put('srcurl', script.env.PROJECT_SOURCE_CODE_URL)
                //push stats to statusAction
                script.statspublish buildStats.inspect()
            }
        }
    }

    /* To maintain backward compatibility, we are using this method */

    protected final void runTestsSequentially() {
        script.node(libraryProperties.'facade.node.label') {
            /* Wrapper for injecting timestamp to the build console output */
            script.timestamps {
                /* Wrapper for colorize the console output in a pipeline build */
                script.ansiColor('xterm') {
                    if (isWebApp)
                        desktopWebTests.createPipeline()
                    if (isNativeApp)
                        nativeAWSDeviceFarmTests.createPipeline()
                    if (script.currentBuild.result != 'SUCCESS' && script.currentBuild.result != 'ABORTED') {
                        TestsHelper.PrepareMustHaves(script, runCustomHook, "Tests_${script.env.BUILD_NUMBER}", libraryProperties)
                        TestsHelper.setBuildDescription(script)
                    }
                }
            }
        }
    }
}
