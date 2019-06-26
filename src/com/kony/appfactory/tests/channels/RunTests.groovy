package com.kony.appfactory.tests.channels

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.TestsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.NotificationsHelper
import com.kony.appfactory.helper.CustomHookHelper

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
    protected projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.tokenize('/')
    protected scmBranch = script.params.PROJECT_SOURCE_CODE_BRANCH
    protected scmCredentialsId = script.params.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
    protected scmUrl = script.env.PROJECT_SOURCE_CODE_URL
    protected runCustomHook = script.params.RUN_CUSTOM_HOOKS

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

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    RunTests(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'
        this.hookHelper = new CustomHookHelper(script)
        this.script.env['CLOUD_ACCOUNT_ID'] = (script.kony.CLOUD_ACCOUNT_ID) ?: ''
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = (script.kony.CLOUD_ENVIRONMENT_GUID) ?: ''
        this.script.env['CLOUD_DOMAIN'] = (script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (script.kony.URL_PATH_INFO) ?: ''
    }

    /**
     * Builds Test Automation scripts for Native and DesktopWeb.
     *
     * @param testChannel The channel for which you want to run the tests.
     * @param testFolder The folder in which you want to run the tests.
     *
     */
    protected final buildTestScripts(testChannel, testFolder) {
        String successMessage = "Test Automation scripts have been built successfully for ${testChannel}"
        String errorMessage = "Failed to build the Test Automation scripts for ${testChannel}"

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
     * @param channelType contains whether it is a native or desktopweb channel.
     * @return testsFolderPath contains the base path for tests directory.
     */
    protected final String getTestsFolderPath(projectFullPath, channelType) {
        String testsFolderPath
        List<String> testPoms = [] as String[]
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
        testsFolderPath
    }

    /**
     * Wraps block of code with required steps for every build pipeline.
     *
     * @param closure block of code that implements build pipeline.
     */
    protected final void pipelineWrapper(channelType, closure) {
        /* Set environment-dependent variables */
        workspace = script.env.WORKSPACE
        checkoutRelativeTargetFolder = [projectWorkspaceFolderName, projectName].join(separator)
        projectWorkspacePath = (projectRoot) ?
                ([workspace, checkoutRelativeTargetFolder] + projectRoot.dropRight(1))?.join(separator) :
                [workspace, projectWorkspaceFolderName]?.join(separator)
        projectFullPath = [
                workspace, checkoutRelativeTargetFolder, projectRoot?.join(separator)
        ].findAll().join(separator)
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