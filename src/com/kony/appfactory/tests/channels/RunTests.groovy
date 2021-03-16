package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.CustomHookHelper
import com.kony.appfactory.helper.ValidationHelper
import groovy.json.JsonSlurper
import com.kony.appfactory.enums.BuildType
import com.kony.appfactory.helper.FabricHelper

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
    protected baseAppName
    /* List of apps (including parent app & child apps) */
    protected appsList = []
    /* Map with details of jasTestScriptUrl and jasTestScriptDeploymentPath for each app*/
    protected appsTestScriptDeploymentInfo = [:]

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
        String appTestResourcesPath = [projectFullPath, 'testresources'].join(separator)
        String customReporterRelativePath = ['Jasmine', 'Common', 'customReporter.js'].join(separator)
        String customReporter = [appTestResourcesPath, customReporterRelativePath].join(separator)
        String integrationTestJsonFileName = 'IntegrationTests.json'
        String metaInfoJsonFileName= 'metaInfo.json'
        boolean isMultiAppTest = false
        def formFactors = []
        /* List of child apps */
        def childAppsList = []
        /* Map with details of scriptUrl and protocloType for each app*/
        def appsTestScriptUrlInfo = [:]

        script.dir(projectFullPath) {
            
            /* Read the parent app name from projectproperties.json file */
            def projectPropsJsonFile = [projectFullPath, libraryProperties.'project.props.json.file.name'].join(separator)
            if(script.fileExists(projectPropsJsonFile)) {
                def projectPropJsonContent = script.readJSON file: projectPropsJsonFile
                baseAppName = projectPropJsonContent['appidkey']
                appsList << baseAppName
            } else {
                throw new AppFactoryException("Failed to find ${libraryProperties.'project.props.json.file.name'} at path ${projectFullPath} , please check your Visualizer project source!!", 'ERROR')
            }

            /* Check the repo contain "JasmineIntegrationTests" folder at app source "/testresources" path  */
            def jasIntegrationTestRelativePath = ['testresources', 'JasmineIntegrationTests'].join(separator)
            def jasIntegrationTestPath = [projectFullPath, jasIntegrationTestRelativePath].join(separator)
            def jasIntegrationTestJsonFile = [jasIntegrationTestPath, integrationTestJsonFileName].join(separator)
            isMultiAppTest = BuildHelper.isDirExist(script, jasIntegrationTestRelativePath, true)

            if (channelType.equalsIgnoreCase("Web") && isMultiAppTest) {
                /* Get the apps list including parent app if test run is for Web */
                childAppsList = BuildHelper.getSubDirectories(script, true, jasIntegrationTestRelativePath)
                appsList = appsList + childAppsList

                /* Checking the 'IntegrationTests.json' file exist or not*/
                if (!script.fileExists("${jasIntegrationTestJsonFile}")) {
                    throw new AppFactoryException("Failed to find ${integrationTestJsonFileName} at path ${jasIntegrationTestJsonFile} , please check your test resources!!", 'ERROR')
                }
            }

            /* Appending the appfactory reporting js code for all apps to retrieve the jasmine results as json. */
            appsList?.each { appName ->
                def customReporteFilePath
                if(appName == baseAppName) {
                    customReporteFilePath = [appTestResourcesPath, customReporterRelativePath].join(separator)
                } else {
                    customReporteFilePath = [jasIntegrationTestPath, appName, customReporterRelativePath].join(separator)
                }
                String customReporting = script.readFile file: customReporteFilePath
                String appfactoryReporting = script.libraryResource(appfactoryReporter)
                script.writeFile file: customReporteFilePath, text: customReporting + appfactoryReporting, encoding: 'UTF-8'
            }
            
            if (channelType.equalsIgnoreCase("Web")) {
                /* Rename the custom test plan name for parent app with default testPlan.js */
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
                    /* Building Base app test script */
                    def nodePkgScript = 'node generateJasmineArtifacts.js --output-dir ' + [jasminePkgFolder, baseAppName].join(separator)
                    script.shellCustom(nodePkgScript, true)
                    
                    /* Building Child apps test script */
                    /* Generating Jasmine test artifacts for web channel if child app exist
                     * Note: Currently, As a work around to generate the jasmine test artifact moving each child app's jasmine artifacts
                     * at viz project's 'testresources' path to run and generate the jasmine test artifacts, because 'generateJasmineArtifacts.js' file
                     * looks at specific path to generate the jasmine artifacts so making each child app as parent app test resources.
                     * */
                    if(channelType.equalsIgnoreCase("Web") && isMultiAppTest) {
                        script.shellCustom("set +x;mv ${appTestResourcesPath}/Jasmine ${appTestResourcesPath}/Jasmine_${baseAppName}", true)
                        childAppsList?.each { appName ->
                            script.shellCustom("set +x;mv ${jasIntegrationTestPath}/${appName}/Jasmine ${appTestResourcesPath}", true)
                            def jasmineChildAppPkgFolder = [jasminePkgFolder, appName].join(separator)
                            def nodePkgChildAppScript = 'node generateJasmineArtifacts.js --output-dir ' + jasmineChildAppPkgFolder
                            script.shellCustom(nodePkgChildAppScript, true)
                            script.shellCustom("set +x;mv ${appTestResourcesPath}/Jasmine ${appTestResourcesPath}/Jasmine_${appName}", true)
                        }
                    }
                }
            })
            
            // Only in the case Web, we will be hosting the jasmine test scripts through jetty server.
            if(channelType.equalsIgnoreCase("Web")) {
                /* Test script deployment path: <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<EPOCH_TIME>/
                 * or <jettyWebAppsFolder><ACCOUNT_ID>/<APPFACTORY_PROJECT_NAME>_<WedBuildNo>/
                 * "eg: /opt/jetty/webapps/testresources/100000005/RsTestOnly_1612446923377/" 
                 */
                String fullPathToCopyScripts = jettyWebAppsFolder + script.env.JASMINE_TEST_URL.split('testresources')[-1]
                
                def jasScriptDeploymentInfo = [:]
                jasScriptDeploymentInfo.put('jasTestScriptUrl', script.env.JASMINE_TEST_URL)
                jasScriptDeploymentInfo.put('jasTestScriptDeploymentPath', fullPathToCopyScripts)
                appsTestScriptDeploymentInfo.put(baseAppName, jasScriptDeploymentInfo)

                /* If child app exist iterate for each child app & generate the script deployment info */
                childAppsList?.each { appName ->
                    def testScriptSubPath = script.env.CLOUD_ACCOUNT_ID + '/' + appName + '_' + new Date().time + '/'
                    def childAppJasTestScriptUrl = libraryProperties.'test.automation.jasmine.base.host.url' + testScriptSubPath
                    String fullPathToCopyChildAppScripts = [jettyWebAppsFolder, testScriptSubPath].join(separator)

                    def childAppJasTestScriptDeploymentInfo = [:]
                    childAppJasTestScriptDeploymentInfo.put('jasTestScriptUrl', childAppJasTestScriptUrl)
                    childAppJasTestScriptDeploymentInfo.put('jasTestScriptDeploymentPath', fullPathToCopyChildAppScripts)
                    appsTestScriptDeploymentInfo.put(appName, childAppJasTestScriptDeploymentInfo)
                }
                /* Populate a map with script url info for updating integrationTest.josn and metaInfo.json file for each app */
                appsList?.each { appName ->
                    def jasScriptUrl = appsTestScriptDeploymentInfo[appName].get('jasTestScriptUrl')
                    def (protocolType, scriptUrl)  = jasScriptUrl?.split('://')
                    
                    def testScriptUrlInfo = [:]
                    testScriptUrlInfo.put('protocolType', protocolType)
                    testScriptUrlInfo.put('scriptUrl', scriptUrl)
                    appsTestScriptUrlInfo.put(appName, testScriptUrlInfo)
                }

                if(isMultiAppTest) {
                    /* Updating the jasmineIntegrationTest.json file */
                    def integrationTestJsonFileContent
                    script.dir(jasIntegrationTestRelativePath) {
                        integrationTestJsonFileContent = script.readJSON file: integrationTestJsonFileName
                        boolean isBaseAppInfoExist = integrationTestJsonFileContent.containsKey(baseAppName)
                        /* if base app json object exist update it with latest script URL info generated */
                        def webAppUrlParamName = BuildHelper.getCurrentParamName(script, 'WEB_APP_URL', 'FABRIC_APP_URL')
                        if(isBaseAppInfoExist) {
                            integrationTestJsonFileContent[baseAppName]['URL'] = script.params[webAppUrlParamName]
                            integrationTestJsonFileContent[baseAppName]['protocol'] = appsTestScriptUrlInfo[baseAppName].get('protocolType')
                            integrationTestJsonFileContent[baseAppName]['ScriptURL'] = appsTestScriptUrlInfo[baseAppName].get('scriptUrl')
                        } else {
                            /* Add a json object with base app's script URL info in existing json file */
                            def parentAppScriptInfo = [:]
                            parentAppScriptInfo.put("URL", script.params[webAppUrlParamName])
                            parentAppScriptInfo.put("protocol", appsTestScriptUrlInfo[baseAppName].get('protocolType'))
                            parentAppScriptInfo.put("ScriptURL", appsTestScriptUrlInfo[baseAppName].get('scriptUrl'))
                            integrationTestJsonFileContent.putAt(baseAppName, parentAppScriptInfo)
                        }

                        /* Updating child app's script info in integrationTest.json file*/
                        childAppsList?.each { appName ->
                            integrationTestJsonFileContent[appName]['protocol'] = appsTestScriptUrlInfo[appName].get('protocolType')
                            integrationTestJsonFileContent[appName]['ScriptURL'] = appsTestScriptUrlInfo[appName].get('scriptUrl')
                        }
                        script.writeJSON file: integrationTestJsonFileName, json: integrationTestJsonFileContent
                    }

                    /* Update meta info.json file for each child app with integrationTest.json object */
                    def metaInfoJsonContent
                    appsList?.each { appName ->
                        def appMetaInfoJsonFile = [jasminePkgFolder, appName, 'Desktop', metaInfoJsonFileName].join(separator)
                        metaInfoJsonContent = script.readJSON file: appMetaInfoJsonFile
                        metaInfoJsonContent << [IntegrationTests: integrationTestJsonFileContent]
                        script.writeJSON file: appMetaInfoJsonFile, json: metaInfoJsonContent
                    }
                }

                /* Copying app's Desktop channel jasmine test artifacts in the jetty webapps folder for deployment */
                appsList?.each { appName ->
                    def appScriptDeploymentPath = appsTestScriptDeploymentInfo[appName].get('jasTestScriptDeploymentPath')
                    script.shellCustom("set +x;mkdir -p $appScriptDeploymentPath", true)
                    script.shellCustom("set +x;cp -R ${jasminePkgFolder}/${appName}/Desktop ${appScriptDeploymentPath}", true)
                }
            } else {
                script.dir(deviceFarmWorkingFolder) {
                    prepareExtraDataPackage("Mobile", baseAppName)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/${baseAppName}/Mobile/AndroidJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_Android_Mobile_TestExtraDataPkg.zip", true)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/${baseAppName}/Mobile/iOSJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_iOS_Mobile_TestExtraDataPkg.zip", true)
                    prepareExtraDataPackage("Tablet", baseAppName)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/${baseAppName}/Tablet/AndroidJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_Android_Tablet_TestExtraDataPkg.zip", true)
                    script.shellCustom("set +x;cp ${jasminePkgFolder}/${baseAppName}/Tablet/iOSJasmineScripts.zip ${deviceFarmWorkingFolder}/${projectName}_iOS_Tablet_TestExtraDataPkg.zip", true)
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
            jasmineTestPlan = BuildHelper.getParamValueOrDefault(script, "WEB_TEST_PLAN", "testPlan.js")
        } else {
            jasmineTestPlan = BuildHelper.getParamValueOrDefault(script, "NATIVE_TEST_PLAN", "testPlan.js")
        }
        String testPlanBasePath = ['testresources', 'Jasmine', formFactor, 'Test Plans'].join(separator)
        String defaultTestPlan = [testPlanBasePath, 'testPlan.js'].join(separator)
        String testPlanFile = [testPlanBasePath, jasmineTestPlan].join(separator)

        if (script.fileExists("${testPlanFile}")) {
            if(!testPlanFile.equals(defaultTestPlan)) {
                defaultTestPlan = BuildHelper.addQuotesIfRequired(defaultTestPlan)
                testPlanFile = BuildHelper.addQuotesIfRequired(testPlanFile)
                script.shellCustom("set +x;cp -f ${testPlanFile} ${defaultTestPlan}", true)
            }
        } else {
            throw new AppFactoryException("Failed to find ${testPlanFile}, please provide valid TEST_PLAN!!", 'ERROR')
        }
    }


    /*
     * This method prepares the extra data package in which the jasmine scripts will be placed in the way the frameworks expects.
     * @param testInvokerFolder is the path of the folder in which we will be creating the TestNG Project for running the Jasmine tests
     * @param appName is the viz project app name for which test execution is triggered(value read from "appidkey" of projectProperties.json)
     */
    protected final prepareExtraDataPackage(formFactor, appName) {
        def targetFolder = [jasminePkgFolder, appName, formFactor].join(separator)
        script.dir(targetFolder){
            // Android Packaging
            script.shellCustom("set +x;mkdir ${targetFolder}/JasmineTests ", true)
            script.shellCustom("set +x;cp ${jasminePkgFolder}/${appName}/${formFactor}/automationScripts.zip ${targetFolder}/JasmineTests/ ", true)
            script.shellCustom("set +x;zip -r AndroidJasmineScripts.zip JasmineTests", true)

            // iOS Packaging
            script.shellCustom("set +x;mkdir ${targetFolder}/AutomationScripts ", true)
            script.unzip zipFile: "${jasminePkgFolder}/${appName}/${formFactor}/automationScripts.zip", dir: "${targetFolder}/AutomationScripts/"
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
