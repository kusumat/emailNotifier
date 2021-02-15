package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import groovy.json.JsonSlurper
import com.kony.appfactory.enums.BuildType

class RunTests implements Serializable {
    /* Pipeline object */
    protected script
    /* Library configuration */
    protected libraryProperties
    /* Job workspace path */
    protected workspace
    /* Target folder for checkout, default value vis_ws/<project_name> */
    protected checkoutRelativeTargetFolder
    /* Folder name with test automation scripts */
    protected testFolder
    /*
        If projectRoot value has been provide, than value of this property
        will be set to <job_workspace>/vis_ws/<project_name> otherwise it will be set to <job_workspace>/vis_ws
     */
    protected projectWorkspacePath
    /* Common environment variables */
    protected final projectName = script.env.PROJECT_NAME
    protected projectRoot
    protected scmBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    protected scmCredentialsId
    protected scmUrl
    protected runCustomHook = script.params.RUN_CUSTOM_HOOKS
    protected isJasmineEnabled = BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG')?.trim()?.equalsIgnoreCase("jasmine")
    protected testFramework = BuildHelper.getParamValueOrDefault(script, 'TEST_FRAMEWORK', 'TestNG')?.trim()
    protected jasminePkgFolder

    protected String jettyWebAppsFolder = script.env.JETTY_WEBAPP_PATH ? script.env.JETTY_WEBAPP_PATH + '/testresources' :'/opt/jetty/webapps/testresources'
    protected nodeLabel
    protected mustHaveArtifacts = []
    protected String channelType
    /*
        Visualizer workspace folder, please note that values 'workspace' and 'ws' are reserved words and
        can not be used.
     */
    final projectWorkspaceFolderName

    /*
        Platform dependent default name-separator character as String.
        For windows, it's '\' and for unix it's '/'.
        Because all logic for test will be run on linux, set separator to '/'.
     */
    protected separator = '/'
    /* Absolute path to the project folder (<job_workspace>/vis_ws/<project_name>[/<project_root>]) */
    protected projectFullPath
    /* CustomHookHelper object */
    protected hookHelper
    protected  String jasmineTestPlan
    protected runTestsStats = [:]
    protected channelTestsStats = [:]

    /*scm meta info like commitID ,commitLogs */
    protected scmMeta = [:]

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    RunTests(script, channelType) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        this.channelType = channelType
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        /* Set the visualizer project settings values to the corresponding visualizer environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Visualizer')
        scmCredentialsId = script.env.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
        scmUrl = script.env.PROJECT_SOURCE_CODE_URL
        projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')

        this.hookHelper = new CustomHookHelper(script, BuildType.Visualizer)
        this.script.env['CLOUD_ACCOUNT_ID'] = (script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (script.kony.URL_PATH_INFO) ?: ''
    }

    /**
     * Builds Test Automation scripts for Native and Web.
     *
     * @param testChannel The channel for which you want to run the tests.
     * @param testFolder The folder in which you want to run the tests.
     *
     */
    protected final buildTestScripts(testFolder) {
        String successMessage = "Test Automation scripts have been built successfully for ${channelType}"
        String errorMessage = "Failed to build the Test Automation scripts for ${channelType}"

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(testFolder) {
                script.shellCustom('mvn clean package -DskipTests=true', true)
            }
        }
    }

    /**
     * Validate application binaries URLs
     * @param AppBinaryUrlParameters contains the application binary parameters that need to be validated
     */

    protected final validateApplicationBinariesURLs(AppBinaryUrlParameters) {
        for (parameter in AppBinaryUrlParameters) {
            if (parameter.value.contains('//') && TestsHelper.isValidUrl(parameter.value))
                parameter.value = parameter.value.replace(" ", "%20")
            else
                throw new AppFactoryException("Build parameter ${parameter.key} value is not valid URL!", 'ERROR')
        }
    }
    /**
     * Provide the Test Directory Path in source.
     * Since V8SP4, the testing framework locations have been updated to <project_root>/testresources/..
     * To be able to add such new modifications and support the  backward compatibility, we will go through each possible location
     * and check for a valid POM file. For future additions, make sure to add the latest paths first.
     *
     * @param projectFullPath contains the path for project root.
     * @return testsFolderPath contains the base path for tests directory.
     */
    protected final String getTestsFolderPath(projectFullPath) {
        String testsFolderPath
        List<String> testPoms = [] as String[]

        if(isJasmineEnabled) {
            testsFolderPath = [projectFullPath, libraryProperties.'test.automation.jasmine.testinvoker.path'].join(separator)
            return testsFolderPath
        }

        /* Add possible pom file locations, Please make sure to add the latest versions first. */
        channelType.equalsIgnoreCase("Native")?
                testPoms.addAll([[projectFullPath, libraryProperties.'testresources.automation.scripts.path', 'pom.xml'].join(separator),[projectFullPath, libraryProperties.'test.automation.scripts.path', 'pom.xml'].join(separator)])
                :testPoms.addAll([[projectFullPath, libraryProperties.'testresources.automation.scripts.path.for.desktopweb', 'pom.xml'].join(separator),[projectFullPath, libraryProperties.'test.automation.scripts.path.for.desktopweb', 'pom.xml'].join(separator)])
        for (String pomFilePath : testPoms) {
            if (script.fileExists(pomFilePath)) {
                testsFolderPath = pomFilePath.minus(separator + 'pom.xml')
                break
            }
        }

        if (!testsFolderPath)
            throw new AppFactoryException("No test automation scripts found for " + channelType + " channels!!", 'ERROR')

        testsFolderPath
    }

    /*
     * This method prepares the env for running the Jasmine Tests
     * @param testInvokerFolder is the path of the folder in which we will be creating the TestNG Project for running the Jasmine tests
     */
    protected final prepareEnvForJasmineTests(testInvokerFolder) {
        String resourceLocation = ['com', 'kony', 'appfactory', 'jasmineinvoker', channelType.toLowerCase()].join(separator)
        String appfactoryReporter = resourceLocation + '/appfactoryReporter.js'
        String testNGClass = resourceLocation + '/InvokeJasmineTests.java'
        String mavenPOM = resourceLocation + '/pom.xml'
        String testNGXML = resourceLocation + '/TestNG.xml'
        String customReporter = [projectFullPath, 'testresources', 'Jasmine', 'Common', 'customReporter.js'].join(separator)
        def formFactors = []

        script.dir(projectFullPath) {

            // Appending the appfactory reporting js code to retrieve the jasmine results as json.
            String customReporting = script.readFile file: customReporter
            String appfactoryReporting = script.libraryResource(appfactoryReporter)
            script.writeFile file: customReporter, text: customReporting + appfactoryReporting, encoding: 'UTF-8'

            if (channelType.equalsIgnoreCase("Web")) {
                copyTestPlanFile('Desktop')
            } else {
                def nativeDevices
                if (isPoolWithDeviceFarmFilters)
                    nativeDevices = deviceFarm.getDeviceFormFactors(devicePoolConfigFileContent)
                else
                    nativeDevices = deviceFarm.getDevicesInPool(devicePoolName)

                if (nativeDevices.findResult { it.formFactor }.contains('PHONE') || nativeDevices.findResult { it.formFactor }.contains('MOBILE')) {
                    formFactors.add('Mobile')
                }
                if (nativeDevices.findResult { it.formFactor }.contains('TABLET')) {
                    formFactors.add('Tablet')
                }
                formFactors.each { formFactor -> copyTestPlanFile(formFactor) }
            }

            BuildHelper.jasmineTestEnvWrapper(script, {

                script.catchErrorCustom('Something went wrong, FAILED to run "npm install" on this project') {
                    def npmInstallScript = "npm install"
                    script.shellCustom(npmInstallScript, true)
                }

                /* Run node generateJasmineArtifacts.js */
                script.catchErrorCustom('Error while packaging the Jasmine test scripts') {
                    def nodePkgScript = 'node generateJasmineArtifacts.js --output-dir ' + jasminePkgFolder
                    script.shellCustom(nodePkgScript, true)
                }
            })
            
            // Only in the case Web, we will be hosting the jasmine test scripts through jetty server.
            if(channelType.equalsIgnoreCase("Web")) {
                /* Test script path: <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<EPOC_TIME>/
                 * or <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<WedBuildNo>/
                 * "eg: /opt/jetty/webapps/testresources/100000005/RsTestOnly_1612446923377/" 
                 */
                String fullPathToCopyScripts = jettyWebAppsFolder + script.env.JASMINE_TEST_URL.split('testresources')[-1]
                
                // Copying the Desktop jasmine test scripts in the jetty webapps folder
                script.shellCustom("set +x;mkdir -p $fullPathToCopyScripts", true)
                script.shellCustom("set +x;cp -R ${jasminePkgFolder}/Desktop ${fullPathToCopyScripts}", true)
            } else {
                script.dir(deviceFarmWorkingFolder) {
                    prepareExtraDataPackage("Mobile")
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/Mobile/AndroidJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_Android_Mobile_TestExtraDataPkg.zip", true)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/Mobile/iOSJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_iOS_Mobile_TestExtraDataPkg.zip", true)
                    prepareExtraDataPackage("Tablet")
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/Tablet/AndroidJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_Android_Tablet_TestExtraDataPkg.zip", true)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/Tablet/iOSJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_iOS_Tablet_TestExtraDataPkg.zip", true)
                }
            }
        }

        // Copying the TestNG code and pom.xml to build and execute the maven tests to run the Jasmine tests
        script.dir(testInvokerFolder) {

            String mvnSourceFolderPath = 'src/test/java/com/kony/appfactory/jasmine'
            String invokeJasminePOM = script.libraryResource(mavenPOM)
            String invokeJasmineTestNG = script.libraryResource(testNGXML)

            script.writeFile file: "pom.xml", text: invokeJasminePOM
            script.writeFile file: "Testng.xml", text: invokeJasmineTestNG

            script.dir(mvnSourceFolderPath) {
                String invokeJasmineTestsSrc = script.libraryResource(testNGClass)
                script.writeFile file: "InvokeJasmineTests.java", text: invokeJasmineTestsSrc
            }

            // In case Native(DeviceFarm) we need to create the assembly xml to assemble the required jars, classes, resources, etc.
            if(channelType.equalsIgnoreCase("Native")) {
                String mvnAssemblyPath = 'src/main/assembly'
                script.dir(mvnAssemblyPath) {
                    String assemblyXMLSrc = script.libraryResource(resourceLocation + '/zip.xml')
                    script.writeFile file: "zip.xml", text: assemblyXMLSrc
                }
            }
        }
    }
    /** This method checks if test plan file is provided as a field. If not we will consider testPlan.js as test plan.
     * If test plan is provided, test plan file is renamed to testPlan.js.
     * @param formFactor depicts if Desktop, Mobile or Tablet
     */
    protected void copyTestPlanFile(String formFactor) {
        if (formFactor.equalsIgnoreCase("Desktop")) {
            jasmineTestPlan = BuildHelper.getParamValueOrDefault(script, "WEB_TEST_PLAN", null)
        } else {
            jasmineTestPlan = BuildHelper.getParamValueOrDefault(script, "NATIVE_TEST_PLAN", null)
        }
        String testPlanBasePath = ['testresources', 'Jasmine', formFactor, 'Test Plans'].join(separator)
        String defaultTestPlan = [testPlanBasePath, 'testPlan.js'].join(separator)
            if(jasmineTestPlan.equals("") || jasmineTestPlan.equals("testPlan.js")){
                if (!script.fileExists("${defaultTestPlan}")) {
                    throw new AppFactoryException("Failed to find ${defaultTestPlan}, please check your application!!", 'ERROR')
                }
                jasmineTestPlan = "testPlan.js"
            } else {
                String testPlanFile = [testPlanBasePath, jasmineTestPlan].join(separator)
                if (script.fileExists("${testPlanFile}")) {
                    defaultTestPlan = BuildHelper.addQuotesIfRequired(defaultTestPlan)
                    testPlanFile = BuildHelper.addQuotesIfRequired(testPlanFile)
                    script.shellCustom("set +x;cp -f ${testPlanFile} ${defaultTestPlan}", true)
                } else {
                    throw new AppFactoryException("Failed to find ${testPlanFile}, please provide valid TEST_PLAN!!", 'ERROR')
                }
            }

    }


    /*
     * This method prepares the extra data package in which the jasmine scripts will be placed in the way the frameworks expects.
     * @param testInvokerFolder is the path of the folder in which we will be creating the TestNG Project for running the Jasmine tests
     */
    protected final prepareExtraDataPackage(formFactor) {
        def targetFolder = [jasminePkgFolder, formFactor].join(separator)
        script.dir(targetFolder){
            // Android Packaging
            script.shellCustom("set +x;mkdir ${targetFolder}/JasmineTests ", true)
            script.shellCustom("set +x;cp ${jasminePkgFolder}/${formFactor}/automationScripts.zip ${targetFolder}/JasmineTests/ ", true)
            script.shellCustom("set +x;zip -r AndroidJasmineScripts.zip JasmineTests", true)

            // iOS Packaging
            script.shellCustom("set +x;mkdir ${targetFolder}/AutomationScripts ", true)
            script.unzip zipFile: "${jasminePkgFolder}/${formFactor}/automationScripts.zip", dir: "${targetFolder}/AutomationScripts/"
            script.shellCustom("set +x;zip -r iOSJasmineScripts.zip AutomationScripts", true)
        }

    }

    /**
     * Wraps block of code with required steps for every build pipeline.
     *
     * @param closure block of code that implements build pipeline.
     */
    protected final void pipelineWrapper(closure) {
        /* Set environment-dependent variables */
        workspace = script.env.WORKSPACE
        checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
        projectWorkspacePath = (projectRoot) ?
                ([workspace, checkoutRelativeTargetFolder] + projectRoot.dropRight(1))?.join(separator) :
                [workspace, projectWorkspaceFolderName]?.join(separator)
        projectFullPath = [
                workspace, checkoutRelativeTargetFolder, projectRoot?.join(separator)
        ].findAll().join(separator)
        jasminePkgFolder = [projectFullPath, 'JasmineScriptsOutput'].join(separator)
        try {
            closure()
        } catch (AppFactoryException e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, e.getErrorType(), false)
            script.currentBuild.result = 'FAILURE'
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, 'WARN')
            script.currentBuild.result = 'FAILURE'
            /* Set runTests flag to false so that tests will not get triggered on Device Farm when build is failed */
            runTests = false
        }
    }
}
