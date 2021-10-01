package com.kony.appfactory.microservice

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper
import org.json.JSONObject;
import groovy.json.JsonOutput;

import java.util.Base64
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils
import org.apache.http.impl.client.HttpClientBuilder

/**
 * Implements logic for Microservice builds.
 */
class ConfigureMS implements Serializable {
    /* Pipeline object */
    private script
    /* Library configuration */
    private libraryProperties
    /* Build Parameters */
    private boolean isUnixNode
    private separator
    /* Common environment variables */
    private final String nodeLabel
    private workspace
    private final buildNumber = script.env.BUILD_NUMBER
    /* Project settings parameters */
    protected final projectSourceCodeUrl
    private final projectSourceCodeRepositoryCredentialsId
    private final recipientsList
    private final projectRoot
    /* Job build parameters */
    private final projectSourceCodeBranch = script.params.SCM_BRANCH
    private final microserviceBaseUrl = script.params.MICROSERVICE_BASE_URL
    private final msGroupID = script.params.GROUP_ID
    private boolean deployJoltFiles = script.params.DEPLOY_JOLT_FILES
    private final joltFilesDir = script.params.JOLT_FILES_DIR?.trim()
    private final joltFilesList = script.params.JOLT_FILES_LIST?.trim()
    private boolean deployPolicyFiles = script.params.DEPLOY_POLICY_FILES
    private final policyFilesDir = script.params.POLICY_FILES_DIR?.trim()
    private final policyFilesList = script.params.POLICY_FILES_LIST?.trim()

    private checkoutDirectory = "Microservice"
    private String projectFullPath
    private String projectDir
    private policyFiles
    private joltFiles
    private apiVersion
    private long configFileMaxLength

    /*
    List of artifacts to be captured for the must haves to debug
   */
    protected mustHaveArtifacts = []
    protected String mustHaveAuthUrl

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    ConfigureMS(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        /* Set the Microservice project settings values to the environmental variables */
        BuildHelper.setProjSettingsFieldsToEnvVars(this.script, 'Microservice')
        projectSourceCodeUrl = script.env.PROJECT_SOURCE_CODE_URL
        projectSourceCodeRepositoryCredentialsId = script.env.PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID
        recipientsList = script.env.RECIPIENTS_LIST?.trim()
        projectRoot = script.env.PROJECT_ROOT_FOLDER_NAME?.trim()
        nodeLabel = libraryProperties.'microservice.node.label'
        configFileMaxLength = Long.parseLong(libraryProperties.'microservice.configuration.file.max.size')

        this.script.env['CLOUD_ACCOUNT_ID'] = this.script.kony.CLOUD_ACCOUNT_ID
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = this.script.kony.CLOUD_ENVIRONMENT_GUID
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'hclvoltmx.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
    }

    /**
     * Will fetch the configuration files
     * @param configurationFileType
     * @return configFilesList
     */
    private fetchConfigurationFiles(configurationFileType) {
        def configurationFiles = []

        def configFileExtension
        def configFilesDir
        List providedFilesList = []

        if (configurationFileType.equals("Jolt")) {
            configFileExtension = "json"
            configFilesDir = joltFilesDir
            providedFilesList = (joltFilesList) ? joltFilesList.replaceAll(" ", "").split(',') : []
        } else if (configurationFileType.equals("Policy")) {
            configFileExtension = "xml"
            configFilesDir = policyFilesDir
            providedFilesList = (policyFilesList) ? policyFilesList.replaceAll(" ", "").split(',') : []
        }

        def configurationFilesPath = getConfigFileLocation(configFilesDir)

        script.catchErrorCustom("Failed to search ${configurationFileType} configuration files!") {
            script.dir(configurationFilesPath) {
                /* Search complete directory if the provided files list is empty */
                if (providedFilesList.isEmpty()) {
                    def configFiles = script.findFiles(glob: "*.${configFileExtension}")
                    configFiles.each { configFile ->
                        if (checkIfValidConfigFile(configFile))
                            configurationFiles.add([name: configFile.name, path: configurationFilesPath, extension: configFileExtension])
                    }
                } else {
                    providedFilesList.each { configFileName ->
                        /* Check the config file type */
                        if(!configFileName.endsWith("." + configFileExtension)) {
                            throw new AppFactoryException("Invalid file type given for ${configurationFileType.toUpperCase()}_FILES_LIST! Only '.${configFileExtension}' files are supported for ${configurationFileType} config deployments.", "ERROR")
                        }

                        def configFile = script.findFiles(glob: configFileName)[0]
                        if (!configFile)
                            throw new AppFactoryException("The ${configurationFileType} file ${searchFile} does not exist.", 'ERROR')
                        if (checkIfValidConfigFile(configFile))
                            configurationFiles.add([name: configFile.name, path: configurationFilesPath, extension: configFileExtension])
                    }
                }
            }
        }

        configurationFiles
    }


    /**
     * Will throw an exception if the provided file size more than allowed size (10MB) or empty (0 Bytes).
     * @param file
     * @return boolean value
     */
    private boolean checkIfValidConfigFile(file) {
        if (file.length == 0)
            throw new AppFactoryException("The provided file ${file.name} is empty!!", "ERROR")
        if (file.length > configFileMaxLength)
            throw new AppFactoryException("File size can't be more than the allowed size (10 MB). Current file size is bytes ${file.length}", "ERROR")
        return true
    }


    /**
     * Will return configuration files base path
     * @param configFilesDir
     * @return configFilesBasePath
     */
    private getConfigFileLocation(configFilesDir) {
        def configFilesBasePath = [projectDir, configFilesDir].join(separator)
        /* If the given config files path does not exist then failing the build */
        def isConfigFilesDirExist = BuildHelper.isDirExist(script, configFilesBasePath, isUnixNode)
        if (!isConfigFilesDirExist)
            throw new AppFactoryException("The path [${configFilesBasePath}] in revision [${projectSourceCodeBranch}] of repository [${projectSourceCodeUrl}] does not exist.", 'ERROR')
        configFilesBasePath
    }

    /**
     * We need to send the body of the request message in the below format
     *  {
     *     "id": <random ID>,
     *    "name": <root policy file name>,
     *    "version": "<x.x.x>",
     *    "configData": {
     *            "data": <Actual Json content or Base64 encoded xml content>,
     *           "configType": <json / xml>
     *     }
     *  }
     * So creating the above Json by passing the config file content to the specified 'data' key
     * @param configFile - Configuration file (Policy/Jolt)
     * @return requestData - The request json in the above mentioned json format
     */
    private prepareRequestData(configFile) {
        def configFileExtension = configFile.extension
        def configFileName = configFile.name
        def configFileLocation = [configFile.path, configFileName].join(separator)
        def configFileContent

        if (configFileExtension.equals("json")) {
            configFileContent = script.readJSON file: configFileLocation
        } else if (configFileExtension.equals("xml")) {
            def xmlContent = script.readFile(configFileLocation)
            configFileContent = Base64.getEncoder().encodeToString(xmlContent.getBytes())
        }

        def configData = new JSONObject()
        configData.put("data", configFileContent)
        configData.put("configType", configFileExtension)

        def requestData = new JSONObject()
        /* Setting the message id as <BUILD_NUMBER>-<EPOCH_TIME> eg: 58-1612446923377 */
        requestData.put("id", buildNumber.toString() + "-" + new Date().time)
        requestData.put("name", configFileName)
        requestData.put("version", apiVersion)
        requestData.put("configData", configData)

        return requestData
    }

    /**
     * Will push config file content to the provided microservice
     * @param microserviceConfigAPI - The generic config microservice Api to which we are sending the config file content as a JSON POST request.
     * @param configFile - Configuration file (Policy/Jolt)
     */
    private void invokeMSConfigDeployAPI(microserviceConfigAPI, configFile) {

        /* Get post request data */
        JSONObject requestBody = prepareRequestData(configFile)
        script.echoCustom("Invoking the generic microservice config API: ${microserviceConfigAPI}, with message ID : ${requestBody.get('id')} ", "INFO")

        /* Sending HTTP POST Request */
        HttpClient httpClient = HttpClientBuilder.create().build()
        HttpPost request = new HttpPost(microserviceConfigAPI)
        request.setEntity(new StringEntity(requestBody.toString()))
        request.addHeader("content-type", "application/json")
        HttpResponse response = httpClient.execute(request)

        /* Checking response */
        if (response != null) {
            String responseString = EntityUtils.toString(response.getEntity(), "UTF-8")
            int statusCode = response.getStatusLine().getStatusCode()
            if (statusCode == 200 || statusCode == 201) {
                script.echoCustom("Successfully deployed ${configFile.name} configuration file.", "INFO")
                script.echoCustom("Below is the response received from the server : \n" + JsonOutput.prettyPrint(responseString), "INFO")
            } else {
                script.echoCustom("Failed to deploy ${configFile.name} configuration file to the given Microservice.", 'ERROR', false)
                script.echoCustom("Below is the response received from the server with status code '${statusCode}' : \n" + JsonOutput.prettyPrint(responseString), "ERROR")
            }
        }
    }

    /**
     * Creates job pipeline
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void runConfigureMSPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    def mandatoryParameters = ['SCM_BRANCH', 'MICROSERVICE_BASE_URL']
                    if (!(deployJoltFiles || deployPolicyFiles))
                        throw new AppFactoryException("Either of DEPLOY_JOLT_FILES or DEPLOY_POLICY_FILES are need to be selected", 'ERROR')
                    if (deployJoltFiles) {
                        mandatoryParameters.add('GROUP_ID')
                        if (joltFilesDir.contains("*"))
                            throw new AppFactoryException("Wildcard entries are not allowed for the parameter JOLT_FILES_DIR.", 'ERROR')
                        if (joltFilesList.contains("*") || joltFilesList.contains("/"))
                            throw new AppFactoryException("Sub-directories, file paths and wildcard entries are not allowed for the parameter JOLT_FILES_LIST.", 'ERROR')
                    }
                    if (deployPolicyFiles) {
                        if (policyFilesDir.contains("*"))
                            throw new AppFactoryException("Wildcard entries are not allowed for the parameter POLICY_FILES_DIR.", 'ERROR')
                        if (policyFilesList.contains("*") || policyFilesList.contains("/"))
                            throw new AppFactoryException("Sub-directories, file paths and wildcard entries are not allowed for the parameter POLICY_FILES_LIST.", 'ERROR')
                    }
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                script.node(nodeLabel) {
                    try {
                        isUnixNode = script.isUnix()
                        separator = isUnixNode ? '/' : '\\'
                        workspace = script.env.WORKSPACE
                        projectFullPath = [workspace, checkoutDirectory].join(separator)
                        projectDir = (projectRoot) ? [projectFullPath, projectRoot].join(separator) : projectFullPath
                        apiVersion = microserviceBaseUrl.split('/')[-1].replaceAll("[a-zA-Z]", "")
                        script.cleanWs deleteDirs: true
                        script.stage('Code Checkout') {
                            // source code checkout from scm
                            BuildHelper.checkoutProject script: script,
                                    checkoutType: "scm",
                                    projectRelativePath: checkoutDirectory,
                                    scmBranch: projectSourceCodeBranch,
                                    scmCredentialsId: projectSourceCodeRepositoryCredentialsId,
                                    scmUrl: projectSourceCodeUrl
                        }

                        script.stage('Fetch configuration files') {
                            /* Fetch jolt configuration files */
                            if (deployJoltFiles) {
                                joltFiles = fetchConfigurationFiles("Jolt")
                                if (!joltFiles && deployPolicyFiles)
                                    script.echoCustom("There are no Extensions/Jolt files available in the given directory to deploy!!", "WARN")
                            }

                            /* Fetch policy configuration files */
                            if (deployPolicyFiles) {
                                policyFiles = fetchConfigurationFiles("Policy")
                                if (!policyFiles && deployJoltFiles && joltFiles)
                                    script.echoCustom("There are no policy files available in the given directory to deploy!!", "WARN")
                            }

                            if (!policyFiles && !joltFiles) {
                                throw new AppFactoryException("Since there are no Policy files or Extensions/Jolt files found in the given project. Hence, failing the build.", "ERROR")
                            }
                        }

                        script.stage('Invoking deploy config API') {

                            /* Generally the Microservice configuration Api Url will be as follows : URI/system/configurationGroups/{groupId}/configuration/{configName}
                               where
                                   temn.config.service.base.path (URI) - http://<hostname>:<port>/ms-genericconfig-api/api/v2.0.0/
                                   temn.config.service.resource.path - system/configurationGroups/{groupId}/configuration/{configName}
                                   groupId - <temn.msf.name>.jolt / SECURITY.POLICY
                                   configName - <SchemaName>_<EntityName>Extn
                            */

                            // Upload jolt files
                            if (joltFiles) {
                                script.stage("Deploy Jolt configuration files") {
                                    joltFiles.each { joltFile ->
                                        def configFileName = joltFile.name.split("\\.")[0]
                                        def microserviceConfigAPI = [microserviceBaseUrl, "system", "configurationGroups", msGroupID, "configuration", configFileName].join(separator)
                                        invokeMSConfigDeployAPI(microserviceConfigAPI, joltFile)
                                    }
                                }
                            }

                            // Upload policy files
                            if (policyFiles) {
                                script.stage("Deploy Policy configuration files") {
                                    policyFiles.each { policyFile ->
                                        def configFileName = policyFile.name.split("\\.")[0]
                                        def microserviceConfigAPI = [microserviceBaseUrl, "system", "configurationGroups", "SECURITY.POLICY", "configuration", configFileName].join(separator)
                                        invokeMSConfigDeployAPI(microserviceConfigAPI, policyFile)
                                    }
                                }
                            }
                        }
                    }
                    catch (AppFactoryException e) {
                        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage, e.getErrorType(), false)
                        script.currentBuild.result = 'FAILURE'
                    } catch (Exception e) {
                        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                        script.echoCustom(exceptionMessage + '\n' + e.getStackTrace().toString(), 'WARN')
                        script.currentBuild.result = 'FAILURE'
                    }
                    finally {
                        if (script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED') {
                            mustHaveAuthUrl = BuildHelper.prepareMustHaves(script, "configureMS", "ConfigureMicroServiceMustHaves", "configureMicroServiceBuild.log", libraryProperties, mustHaveArtifacts)
                        }
                        BuildHelper.setBuildDescription(script, mustHaveAuthUrl)
                        NotificationsHelper.sendEmail(script, 'configureMS',
                                [
                                        msGroupID              : msGroupID,
                                        deployJoltFiles        : deployJoltFiles,
                                        deployPolicyFiles      : deployPolicyFiles,
                                        microserviceBaseUrl    : microserviceBaseUrl,
                                        projectSourceCodeBranch: projectSourceCodeBranch
                                ],
                                true)
                    }
                }
            }
        }
    }

}