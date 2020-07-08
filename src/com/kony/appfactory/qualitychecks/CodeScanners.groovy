package com.kony.appfactory.qualitychecks

import org.apache.ivy.core.module.descriptor.ExcludeRule

import com.cloudbees.hudson.plugins.folder.AbstractFolder
import hudson.plugins.sonar.client.HttpClient
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.project.settings.ProjectSettingsProperty
import com.kony.appfactory.project.settings.ProjectSettingsDTO
import com.kony.appfactory.project.settings.Sonar
import com.kony.appfactory.helper.AppFactoryException

import jenkins.model.Jenkins

/**
 * Implements logic for running the code scanners for the projects.
 */
class CodeScanners implements Serializable {
    /* Pipeline object */
    private script

    /*
        Currently all scripts in this class been written with the thought that they(scripts) will be executed
        on unix machine, for future improvements OS type check been added to every scan.
     */
    private boolean isUnixNode

    private final scmCredentialsId = script.params.SCM_CREDENTIALS
    private final scmBranch = script.params.SCM_BRANCH
    
    private final workspaceURL = script.params.WORKSPACE_URL
    private final String nodeLabel
    private String checkoutType = workspaceURL ? "downloadzip" : "scm"
    private libraryProperties
    private String projectFullPath
    private sonarToken
    
    /* Common environment variables */
    protected final projectName = script.env.PROJECT_NAME
    protected final scmUrl = script.env.PROJECT_SOURCE_CODE_URL
    protected final projectRootFolder = script.env.PROJECT_ROOT_FOLDER_NAME
    
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    CodeScanners(script) {
        this.script = script
                /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        nodeLabel = libraryProperties.'sonarqube.node.label'
    }

    /*
     * Gets the complete list of Sonar properties as command line options so that it can be used directly.
     */
    @NonCPS
    private final getSonarOptions() {
        ProjectSettingsDTO projectSettings = BuildHelper.getAppFactoryProjectSettings(projectName)
        if(projectSettings && projectSettings.getScans() && projectSettings.getScans().getSonar()) {
            Sonar sonarSettings = projectSettings.getScans().getSonar()
            String serverURL = (sonarSettings.getSonarServerURL()) ? "-Dsonar.host.url=" + sonarSettings.getSonarServerURL() : ""
            String prjID = (sonarSettings.getSonarVizPrjID()) ? "-Dsonar.projectKey=" + sonarSettings.getSonarVizPrjID() : ""
            String prjBaseDir = (sonarSettings.getSonarPrjBaseDir()) ? "-Dsonar.projectBaseDir=" + sonarSettings.getSonarPrjBaseDir() : ""
            String prjSources = (sonarSettings.getSonarPrjSrc()) ? "-Dsonar.sources=" + sonarSettings.getSonarPrjSrc() : ""
            String exclusions = (sonarSettings.getSonarExclusions()) ? "-Dsonar.exclusions=" + sonarSettings.getSonarExclusions() : ""
            String otherProps = (sonarSettings.getScanProps()) ? sonarSettings.getScanProps() : ""
            String debugFlag = (sonarSettings.getIsDebugEnabled()) ? "-X" : ""

            exclusions = exclusions.replaceAll(', ', ',')
            exclusions = exclusions.replaceAll(' ,', ',')

            def commandLineOpts = [prjID, serverURL, exclusions, prjBaseDir, otherProps, debugFlag].join(" ")

            return [version: sonarSettings.getSonarVersion(), cmdOptions: commandLineOpts, sonarAuth: sonarSettings.getSonarToken()]
        }
        return null
    }

    /**
     * Wraps code with try/catch/finally block to reduce code duplication.
     *
     * @param closure block of code.
     */
    private final void scannerWrapper(closure) {
        try {
            projectFullPath = script.env.WORKSPACE
            isUnixNode = script.isUnix()
            closure()
        } catch (AppFactoryException e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(exceptionMessage, e.getErrorType(), false)
            script.currentBuild.result = 'FAILURE'
        } catch (Exception e) {
            String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
            script.echoCustom(e.getStackTrace().toString(),'WARN')
            script.currentBuild.result = 'FAILURE'
        }
    }

    /**
     * Runs the sonar scan on the given project source code.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void runSonarScan() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {

                script.stage('Check provided parameters') {
                    def mandatoryParameters = ['PROJECT_SOURCE_CODE_BRANCH', 'PROJECT_SOURCE_CODE_REPOSITORY_CREDENTIALS_ID']
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                
                /* Allocate a slave for the run */
                script.node(nodeLabel) {
                    scannerWrapper {
                        script.cleanWs deleteDirs: true
                        script.dir(projectFullPath) {
                            script.stage('Code Checkout') {
                               BuildHelper.checkoutProject script: script,
                                        checkoutType: checkoutType,
                                        projectRelativePath: '',
                                        scmBranch: scmBranch,
                                        scmCredentialsId: scmCredentialsId,
                                        scmUrl: scmUrl
                            }
                        }
                        script.stage("Sonar QualityGate run") {
                            def scannerHome
                            def nodeVersion = libraryProperties.'sonarqube.nodejs.version'
                            def sonarOpts = getSonarOptions()
                            def authToken = ""
                            if(sonarOpts) {
                                if (sonarOpts.sonarAuth != null) {
                                    script.withCredentials([
                                            script.sonarTokenCredential(
                                                    credentialsId: "${sonarOpts.sonarAuth}",
                                                    sonarTokenVariable: 'SONARTOKEN'
                                            )
                                    ]) {
                                        sonarToken = script.env.SONARTOKEN
                                        authToken = "-Dsonar.login=" + sonarToken
                                    }
                                } else {
                                    // We need this to fetch the status of the scan from the Sonar Qube server.
                                    sonarToken = fetchTokenFromProperties(sonarOpts.cmdOptions)
                                }

                                try {
                                    scannerHome = script.tool sonarOpts.version
                                } catch (Exception e) {
                                    throw new AppFactoryException("Selected Sonar Scanner is not configured in AppFactory.", 'ERROR')
                                }
                                String sonarLog = "sonarScanner.log"
                                script.dir(projectFullPath) {
                                    script.nodejs(nodeVersion) {
                                        script.echoCustom("Initiating the Sonar scan...")
                                        script.shellCustom("set +xe; ${scannerHome}/bin/sonar-scanner ${sonarOpts.cmdOptions} ${authToken} >> ${sonarLog}", true)
                                        if (script.fileExists(sonarLog)) {
                                            script.shellCustom("set +xe; cat ${sonarLog}", true)
                                        }
                                    }
                                    String status = getScanResultsStatus(sonarLog)
                                    if (!status.equalsIgnoreCase("OK")) {
                                        script.currentBuild.result = 'FAILURE'
                                    }
                                }
                            }
                            else
                                throw new AppFactoryException("Sonar Scan is not configured in AppFactory project settings.", 'ERROR')

                        }
                    }
                }
            }
        }
    }
    
    /**
     * Reads the log file and fetches the URL to get the status of the scan and Quality Gate scan results.
     * 
     * @param logFileName Name of the log file which contains the logs of sonar scanning
     * @return status of the execustion. OK or NOT_OK.
     */
    private String getScanResultsStatus(String logFileName) {
        def logFileContentLines = script.readFile(logFileName)?.trim()?.readLines()
        def sample = logFileContentLines?.find{ it =~ /(http|https)*task\?id=\w/}
        def url = sample.split(' ').find {it =~ /(http|https)*task\?id=\w/}
        url ? fetchStatus(url) : "NOT_OK"
    }
    
    /**
     * Invoke the URL with the task id and fetches the status of the scan. Also invoke the 
     * quality Gate status by forming the analysis URL and fetching the status from it.
     * 
     * @param taskDtlURL URL with which we can fetch the status of the scanning.
     * @return status of the execustion. OK or NOT_OK.
     */
    private String fetchStatus(String taskDtlURL) {
        HttpClient client = new HttpClient()
        String status = "NOT_OK"
        try {
          String output = client.getHttp(taskDtlURL, sonarToken)
          JSONObject json = (JSONObject) JSONSerializer.toJSON(output)
          JSONObject task = json.getJSONObject("task")
          status = task.getString("status")
          // analysisId will not be available if the task is pending, in rare scenarios.
          String analysisId = task.optString("analysisId", null)
          switch(status) {
              case "SUCCESS":
                          status = fetchSonarQGStatus(client, taskDtlURL, analysisId);
                          break
              case "FAILED":
              case "CANCELED":
                          script.echoCustom("Sonar Scanning is either Failed or Cancelled, please check on the Sonar Qube Server.", 'ERROR', false)
                          break
              default:
                          script.echoCustom("Unknown status is returned from the Sonar Qube server", 'ERROR', false)
                          break
          }
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to parse response from " + taskDtlURL + ":\n" + output, e)
        } catch (Exception e) {
            throw new AppFactoryException("Error occured while fetching the status of the Sonar scan!!", 'ERROR')
        }
        
        status
    }
    
    /**
     * Invoke the URL with the task id and fetches the status of the scan. Also invoke the 
     * quality Gate status by forming the analysis URL and fetching the status from it.
     * 
     * @param client HttpClient object to invoke the APIs.
     * @param taskDtlURL URL with which we can fetch the status of the scanning.
     * @param analysisId used to fetch the Sonar Quality Gate status.
     * @return status of the execustion. OK or NOT_OK.
     */
    private String fetchSonarQGStatus(client, taskDtlURL, analysisId) {
        String status = "NOT_OK"
        String url = taskDtlURL.split('api')[0]
        def PROJECT_STATUS_URI = "api/qualitygates/project_status?analysisId="
        if(analysisId) {
            try {
                String output = client.getHttp(url + PROJECT_STATUS_URI + URLEncoder.encode(analysisId, "UTF-8"), sonarToken)
                String finalURL = url + PROJECT_STATUS_URI + analysisId
                try {
                    JSONObject json = (JSONObject) JSONSerializer.toJSON(output)
                    JSONObject projectStatus = json.getJSONObject("projectStatus")
                    status = projectStatus.getString("status")
                } catch (JSONException e) {
                    throw new IllegalStateException("Unable to parse response from " + url + ":\n" + output, e)
                }
            } catch (Exception e) {
                throw new AppFactoryException("Error occured while fetching the status of the Quality Gate analysis!!", 'ERROR')
            }
        }
        status
    }
    
    /**
     * Fetch the sonar token from the sonar properties file if the customer chose to store all the keys in the file.
     * 
     * @param cmdOptions String that contains the list of command options. This is needed, since user can use a 
     * properties with a non-default name.
     * @return sonar token which is read from the properties file. 
     */
    private String fetchTokenFromProperties(cmdOptions) {
        def customFileName 
        if(cmdOptions.contains('-Dproject.settings=')) {
            customFileName = cmdOptions.split(' ').find {
                it.contains('-Dproject.settings=')
            }
        }
        String propsFileName = (customFileName) ? customFileName.replace('-Dproject.settings=','') : 'sonar-project.properties'
        script.dir(projectFullPath) {
            def props = script.readProperties file:propsFileName
            return props['sonar.login']
        }
    }
}
