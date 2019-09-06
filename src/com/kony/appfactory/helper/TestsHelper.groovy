package com.kony.appfactory.helper

import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.helper.AppFactoryException

import java.util.*

class TestsHelper implements Serializable {
    /* must gathering related variables */
    protected static String s3MustHaveAuthUrl

    /**
     * Validates provided URL.
     * @param urlString URL to validate.
     * @return validation result (true or false).
     */
    protected final static boolean isValidUrl(urlString) {
        try {
            urlString.replace(" ", "%20").toURL().toURI()
            return true
        } catch (Exception exception) {
            return false
        }
    }

    /**
     * Sets build description at the end of the build.
     * @param script Current build instance
     */
    protected final static void setBuildDescription(script) {
        script.currentBuild.description = """\
            <div id="build-description">
                <p><a href='${s3MustHaveAuthUrl}'>Logs</a></p>
            </div>\
        """.stripIndent()
    }

    /**
     * Deserializes channel artifact object.
     *
     * @param artifacts serialized list of channel artifacts.
     * @return list of channel artifacts.
     */
    protected final static getArtifactObjects(artifacts) {
        (artifacts) ? Eval.me(artifacts) : [name: '', url: '', path: '']
    }

    /**
     * This method checks the Test Framework type and also the test platform and assign the 
     * respective label so that the proper jenkins slave is selected for the building.
     * @param script is the object that contains the current build information
     * @param libraryProperties is the contents of the common.properties file
     * @param isJasmineEnabled is the flag which is set to true
     * @param testPlatform is the platform for which the jenkins node has to be identified.
     * @return the appropriate node label run the appropriate tests.
     * */
    protected static String getTestNode(script, libraryProperties, isJasmineEnabled, testPlatform = 'Native') {
        
        String nodeLabel
        
        if (isJasmineEnabled) {
            nodeLabel = libraryProperties.'test.jasmine.automation.node.label'
        } else if (testPlatform == 'DesktopWeb') {
            nodeLabel = libraryProperties.'test.dweb.automation.node.label'
        } else {
            nodeLabel = libraryProperties.'test.native.aws.automation.node.label'
        }
        
        nodeLabel
    }
    
    /**
     * Converts the given time difference into hours, minutes, seconds.
     * @param timeInMilliseconds The time of execution in milliseconds
     * @return The time in hours, minutes and seconds format
     * */
    protected static String convertTimeFromMilliseconds(Long timeInMilliseconds) {
        Map diffMap = [:]
        timeInMilliseconds = timeInMilliseconds / 1000
        diffMap.seconds = timeInMilliseconds.remainder(60)
        timeInMilliseconds = (timeInMilliseconds - diffMap.seconds) / 60
        diffMap.minutes = timeInMilliseconds.remainder(60)
        timeInMilliseconds = (timeInMilliseconds - diffMap.minutes) / 60
        diffMap.hours = timeInMilliseconds.remainder(24)
        def value = ""
        if (diffMap.hours.setScale(0, BigDecimal.ROUND_HALF_UP))
            value += diffMap.hours.setScale(0, BigDecimal.ROUND_HALF_UP) + " hrs "
        if (diffMap.minutes.setScale(0, BigDecimal.ROUND_HALF_UP))
            value += diffMap.minutes.setScale(0, BigDecimal.ROUND_HALF_UP) + " mins "
        if (diffMap.seconds.setScale(0, BigDecimal.ROUND_HALF_UP))
            value += diffMap.seconds.setScale(0, BigDecimal.ROUND_HALF_UP) + " secs "
        return value
    }

    /**
     * Prepare must haves for the debugging
     * @param script current build instance
     * @param runCustomHook contains true if custom hooks is selected , else false
     * @param channelVariableName The channel from which this method is being called
     * @param libraryProperties This contains the properties that are present in common.properties file
     * @param mustHaveArtifacts The artifacts which you want to add in musthaves
     * @param isSourceS3 Boolean flag which tells whether the source of artifacts is S3 or local.
     */
    private final static void PrepareMustHaves(script, runCustomHook, channelVariableName, libraryProperties, mustHaveArtifacts = [], isSourceS3 = true) {

        String workspace = script.env.WORKSPACE
        String projectName = script.env.PROJECT_NAME
        String separator = '/'
        String projectWorkspaceFolderName = libraryProperties.'project.workspace.folder.name'

        String projectFullPath = [workspace, projectWorkspaceFolderName, projectName].join(separator)
        String mustHaveFolderPath = [projectFullPath, "${channelVariableName}"].join(separator)
        String mustHaveFile = ["MustHaves", script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join("_") + ".zip"
        String s3ArtifactPath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join(separator)
        def chBuildLogs = [workspace, projectWorkspaceFolderName, projectName, libraryProperties.'customhooks.buildlog.folder.name'].join(separator)
        def mustHaves = []

        script.dir(mustHaveFolderPath) {
            script.writeFile file: "environmentInfo.txt", text: BuildHelper.getEnvironmentInfo(script)
            script.writeFile file: "ParamInputs.txt", text: BuildHelper.getInputParamsAsString(script)
            script.writeFile file: "runTestBuildLog.log", text: BuildHelper.getBuildLogText(script.env.JOB_NAME, script.env.BUILD_ID, script)

            if(isSourceS3){
                AwsHelper.downloadChildJobMustHavesFromS3(script, mustHaveArtifacts)
            }
            else {
                for (artifact in mustHaveArtifacts) {
                    if(script.fileExists(artifact)) {
                        script.shellCustom("cp ${artifact} .", true)
                    }
                }
            }
        }

        if (runCustomHook) {
            script.dir(chBuildLogs) {
                script.shellCustom("find \"${chBuildLogs}\" -name \"*.log\" -exec cp -f {} \"${mustHaveFolderPath}\" \\;", true)
            }
        }

        try {
            s3MustHaveAuthUrl = BuildHelper.uploadBuildMustHavesToS3(script, projectFullPath, mustHaveFolderPath, mustHaveFile, separator, s3ArtifactPath, "Tests")
        } catch (Exception e) {
            String exceptionMessage = (e.toString()) ?: 'Failed while collecting the logs (must-gather) for debugging.'
            script.echoCustom(exceptionMessage, 'ERROR')
        }
    }

    /**
     * Method which says whether to setBuildDescription or not for a build.
     * @param script Current build instance
     */
    protected final static void isBuildDescriptionNeeded(script) {
        String upstreamJob = BuildHelper.getUpstreamJobName(script)
        boolean isRebuild = BuildHelper.isRebuildTriggered(script)

        ((upstreamJob == null || isRebuild) && s3MustHaveAuthUrl != null)
    }
    
    
    /**
     * Method which constructs the S3 Path for the Test Results storage
     * @param script Current build instance
     * @param runArtifact current run artifact results
     * @param suiteName SuiteName for which the results are to be copied
     * @return the complete the path on the S3 for the storage of the results.
     */
    protected final static getS3ResultsPath(script, runArtifact, suiteName) {
        def s3BasePath = ['Tests', script.env.JOB_BASE_NAME, script.env.BUILD_NUMBER].join('/')
        def resultPath = [s3BasePath]
        resultPath.add(runArtifact.device.formFactor.toString())
        resultPath.add(runArtifact.device.name.toString() + '_' + runArtifact.device.platform.toString() + '_' + runArtifact.device.os.toString())
        resultPath.add(suiteName)
        resultPath.join('/').replaceAll('\\s', '_')
    }
}