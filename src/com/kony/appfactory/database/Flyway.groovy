package com.kony.appfactory.database

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.CredentialsHelper
import com.kony.appfactory.helper.AppFactoryException
import com.kony.appfactory.helper.ValidationHelper
import com.kony.appfactory.helper.NotificationsHelper

/**
 * Implements logic for flyway builds.
 */
class Flyway implements Serializable {
    /* Pipeline object */
    private script
    /* Library configuration */
    private libraryProperties
    /* Build Parameters */
    private boolean isUnixNode
    private separator
    /* Common environment variables */
    protected final projectName = script.env.PROJECT_NAME
    protected final projectSourceCodeUrl = script.params.SCM_URL
    protected CredentialsHelper credentialsHelper
    private final buildNumber = script.env.BUILD_NUMBER
    private workspace
    private locationTypeString = ''

    private final projectSourceCodeRepositoryCredentialsId = script.params.SCM_CREDENTIALS
    private final projectSourceCodeBranch = script.params.SCM_BRANCH
    private final location = script.params.LOCATION
    private final flywayCommands = script.params.FLYWAY_COMMAND
    private final dbConfigCredentialsId = script.params.DB_CREDENTIALS
    private final commandLineArgs = script.params.OPTIONS
    private final String nodeLabel
    private commands = []
    private flywayRunnerCredentialsId
    private flywayInstallationName
    private databaseUrl
    private String flywayRunnerCredentialIdString = "flywayRunnerCredentials"
    private resultsMap = [:]
    private checkoutDirectory = projectName
    private String projectFullPath
    /*
    List of artifacts to be captured for the must haves to debug
   */
    protected mustHaveArtifacts = []
    protected String s3MustHaveAuthUrl

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    Flyway(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        nodeLabel = libraryProperties.'flyway.node.label'
        flywayInstallationName = libraryProperties.'flyway.installation.name'
        /* JAVA classpath locations can be added based on future implementation, as per current fileSystem is being used. */
        if (!location.contains('filesystem'))
            locationTypeString = "filesystem:"
        credentialsHelper = new CredentialsHelper()
        this.script.env['CLOUD_ACCOUNT_ID'] = this.script.kony.CLOUD_ACCOUNT_ID
        this.script.env['CLOUD_ENVIRONMENT_GUID'] = this.script.kony.CLOUD_ENVIRONMENT_GUID
        this.script.env['CLOUD_DOMAIN'] = (this.script.kony.CLOUD_DOMAIN) ?: 'kony.com'
        this.script.env['URL_PATH_INFO'] = (this.script.kony.URL_PATH_INFO) ?: ''
    }

    private void prepareCommandsList(flywayCommands) {
        commands = flywayCommands.split("\\s*,\\s*")
        commands = commands.collect { it.toLowerCase() }
    }

    private void flywayCredentialWrapper(dbCredentialId, closure) {
        script.withCredentials([
                script.databaseCredentials(
                        credentialsId: "${dbCredentialId}",
                        usernameVariable: 'DATABASE_USERNAME',
                        passwordVariable: 'DATABASE_PASSWORD',
                        databaseUrlVariable: 'DATABASE_URL'
                )
        ]) {
            closure()
        }
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void runFlywayPipeline() {
        /* Wrapper for injecting timestamp to the build console output */
        script.timestamps {
            /* Wrapper for colorize the console output in a pipeline build */
            script.ansiColor('xterm') {
                script.stage('Check provided parameters') {
                    def mandatoryParameters = ['SCM_URL', 'SCM_BRANCH', 'SCM_CREDENTIALS', 'FLYWAY_COMMAND', 'DB_CREDENTIALS']
                    ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
                }
                script.node(nodeLabel) {
                    try {
                        isUnixNode = script.isUnix()
                        separator = isUnixNode ? '/' : '\\'
                        workspace = script.env.WORKSPACE
                        projectFullPath = [workspace, checkoutDirectory].join(separator)
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
                        script.stage('Prepare Inputs') {
                            prepareCommandsList(flywayCommands)
                            flywayCredentialWrapper(dbConfigCredentialsId) {
                                flywayRunnerCredentialsId = credentialsHelper.addUsernamePassword(flywayRunnerCredentialIdString + buildNumber, "Flyway Username Password for FlywayRunner", script.env.DATABASE_USERNAME, script.env.DATABASE_PASSWORD)
                                databaseUrl = script.env.DATABASE_URL
                            }
                        }
                        script.stage('Run Flyway Commands') {
                            script.echoCustom("Flyway commands to be run: " + commands)
                            for (command in commands) {
                                script.echoCustom("Running " + command + " command.")
                                try {
                                    script.flywayrunner installationName: "${flywayInstallationName}", flywayCommand: "${command}", credentialsId: "${flywayRunnerCredentialsId}", url: "${databaseUrl}", locations: "${locationTypeString}${[projectFullPath, location].join(separator)}", commandLineArgs: "${commandLineArgs}"
                                } catch (Exception ex) {
                                    script.echoCustom("Found exception while running "+ command +" command.", 'WARN')
                                    script.echoCustom(ex.getLocalizedMessage() + "\n" + ex.getStackTrace().toString(), 'ERROR', false)
                                    script.currentBuild.result = 'UNSTABLE'
                                    break
                                }
                                finally {
                                    if (script.currentBuild.currentResult != "SUCCESS")
                                        resultsMap.put(command, "FAILURE")
                                    else
                                        resultsMap.put(command, script.currentBuild.currentResult)
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
                        credentialsHelper.deleteUserCredentials([flywayRunnerCredentialsId])
                        for (command in commands) {
                            if (!resultsMap.containsKey(command)) {
                                if (script.currentBuild.result == 'UNSTABLE')
                                    resultsMap.put(command, "SKIPPED")
                                else
                                    resultsMap.put(command, script.currentBuild.result)
                            }
                        }
                        if (!resultsMap.containsValue("SUCCESS"))
                            script.currentBuild.result = 'FAILURE'
                        if (script.currentBuild.currentResult != 'SUCCESS' && script.currentBuild.currentResult != 'ABORTED') {
                            s3MustHaveAuthUrl = BuildHelper.prepareMustHaves(script, "flyway", "FlywayMustHaves", "FlywayBuild.log", libraryProperties, mustHaveArtifacts)
                        }
                        BuildHelper.setBuildDescription(script, s3MustHaveAuthUrl)
                        NotificationsHelper.sendEmail(script, 'flyway',
                                [
                                        commands               : commands,
                                        resultsMap             : resultsMap,
                                        options                : commandLineArgs.replaceFirst("-", ""),
                                        projectSourceCodeBranch: projectSourceCodeBranch
                                ],
                                true)
                    }
                }
            }
        }
    }
}