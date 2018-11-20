package com.kony.appfactory.visualizer

import com.kony.appfactory.enums.FormFactor
import com.kony.appfactory.enums.PlatformType
import com.kony.appfactory.dto.buildstatus.PlatformsDTO
import com.kony.appfactory.dto.buildstatus.BuildStatusDTO
import com.kony.appfactory.enums.Status
import com.kony.appfactory.enums.ChannelType
import com.kony.appfactory.helper.AwsHelper
import com.kony.appfactory.sanitizers.RegexSanitizer
import com.kony.appfactory.sanitizers.RemoveLinesSanitizer
import com.kony.appfactory.sanitizers.TextSanitizer

/**
 * This class acts as an interface for the json, any update on the json is done through this class
 */
class BuildStatus implements Serializable {

    public BuildStatusDTO buildJson
    private script
    private static String buildService = "BS"
    private static String statusFilePath
    private channelsToRun
    protected final String CLOUD_BUILD_LOG_FILENAME = 'consolelog_' + script.env.BUILD_NUMBER + '.txt'

    BuildStatus(script, channelsToRun) {
        buildJson = BuildStatusDTO.getInstance()
        this.script = script
        this.channelsToRun = channelsToRun
        statusFilePath = script.params.BUILD_STATUS_PATH
    }

    /**
     * This function returns the platform object of a individual platform
     *
     * @param type This refers to platform type {ANDROID, IOS}* @return PlatformsDTO object of the required PlatfromType
     */
    @NonCPS
    public PlatformsDTO getPlatformByType(PlatformType type) {

        ArrayList<PlatformsDTO> seletedPlatforms = buildJson.getPlatforms()

        for (platformDto in seletedPlatforms) {
            if (platformDto.getPlatformType().toString().equals(type.toString()))
                return platformDto
        }
    }

    /**
     * Updates the individual platformsDto Object inside the buildstatusdto object
     *
     * @param type This refers to platform type {ANDROID, IOS} of which PlatformsDTO object has to be updated
     * @param updatedPlatform is the updated one that has to be updated
     */
    @NonCPS
    void updatePlatformInStatusJSON(PlatformType type, PlatformsDTO updatedPlatform) {
        ArrayList<PlatformsDTO> platformList = buildJson.getPlatforms()

        for (platformDto in platformList) {
            if (platformDto.getPlatformType().toString().equals(type.toString())) {
                platformList.remove(platformDto)
                platformList.add(updatedPlatform)
                buildJson.setPlatforms(platformList)
                return
            }
        }
    }

    /**
     * This function sets the value for the status at the platform level
     *
     * @param buildStatus The value refers to the build status value { IN_PROGRESS, SUCCESSFUL, FAILED}* @param type This refers to the platform type for which the status has to be set
     */
    @NonCPS
    void updatePlatformStatus(ChannelType channelType, Status buildStatus) {
        PlatformType platformType = channelType.toString().contains("IOS") ? PlatformType.IOS : PlatformType.ANDROID
        PlatformsDTO platformDTO = getPlatformByType(platformType)
        channelType.toString().contains("TABLET") ? platformDTO.setTabletStatus(buildStatus) : channelType.toString().contains("MOBILE") ? platformDTO.setMobileStatus(buildStatus) : platformDTO.setUniversalStatus(buildStatus)
        updatePlatformInStatusJSON(platformType, platformDTO)
    }

    /**
     * This function sets the value for the download link at the platform level
     *
     * @param type This refers to the platform type for which the status has to be set
     * @param downloadURI The value refers to the tablet download link of the specified platform *
     */
    @NonCPS
    void updateDownloadLink(ChannelType channelType, String downloadURI) {
        PlatformType platformType = channelType.toString().contains("IOS") ? PlatformType.valueOf("IOS") : PlatformType.valueOf("ANDROID")
        PlatformsDTO platformDTO = getPlatformByType(platformType)
        channelType.toString().contains("TABLET") ? platformDTO.setTabletDownloadLink(downloadURI) : channelType.toString().contains("MOBILE") ? platformDTO.setMobileDownloadLink(downloadURI) : platformDTO.setUniversalDownloadLink(downloadURI)
        updatePlatformInStatusJSON(platformType, platformDTO)
    }

    /**
     * This function sets the value for the logsLink at the platform level
     *
     * @param downloadURI The value refers to the logs link of the specified platform
     * @param type This refers to the platform type for which the status has to be set
     */
    @NonCPS
    void updateLogsLink(String downloadURI, PlatformType type) {
        PlatformsDTO platformDTO = getPlatformByType(type)
        if (platformDTO == null)
            return
        platformDTO.setLogsLink(downloadURI)
        updatePlatformInStatusJSON(type, platformDTO)
    }

    /**
     * This function sets the value for the status at the platform level
     * @param downloadURI The value refers to the logs link of the specified platform
     */
    @NonCPS
    void updateLogsLink(String downloadURI) {
        for (PlatformType type : PlatformType.values()) {
            updateLogsLink(downloadURI, type)
        }
    }

    /**
     * This method constructs the initial json with the channels selected as input and updates it on to s3
     * @param updateFlag based on which we will choose whether to upload or not
     */
    void prepareStatusJson(updateFlag = false) {

        if (!script.params.IS_SOURCE_VISUALIZER) {
            return
        }
        BuildStatusDTO buildStatus = BuildStatusDTO.getInstance()
        ArrayList<PlatformsDTO> platforms = new ArrayList<PlatformsDTO>()
        boolean iOSFlag = false
        boolean androidFlag = false
        buildStatus.setBuildNumber(script.env.BUILD_NUMBER)
        buildStatus.setStatus(Status.IN_PROGRESS)
        buildStatus.setStartedAt(new Date().toString())
        buildStatus.setLastUpdatedAt(new Date().toString())

        for (channel in channelsToRun) {

            if (channel.contains(PlatformType.ANDROID.toString()))
                androidFlag = true
            if (channel.contains(PlatformType.IOS.toString()))
                iOSFlag = true
        }

        if (iOSFlag) {
            PlatformsDTO iOSDTO = preparePlatformDTO(PlatformType.IOS, channelsToRun)
            platforms.add(iOSDTO)
        }
        if (androidFlag) {
            PlatformsDTO androidDTO = preparePlatformDTO(PlatformType.ANDROID, channelsToRun)
            platforms.add(androidDTO)
        }
        buildStatus.setPlatforms(platforms)
        if (updateFlag)
            updateBuildStatusOnS3()
    }

    /**
     * This method constructs the JSON payload of a selected PlatformType
     * @param platformType holds either ANDROID or IOS
     * @param channelsToRun holds the list of channels to run
     * @return This method returns the individual platform json which will be consumed by the prepareStatusJson Function
     */
    private PlatformsDTO preparePlatformDTO(PlatformType platformType, channelsToRun) {

        boolean mobileFlag
        boolean tabletFlag
        boolean universalFlag

        PlatformsDTO platformDTO = new PlatformsDTO(platformType)

        mobileFlag = channelsToRun.contains(platformType.toString() + "_" + FormFactor.MOBILE_NATIVE.toString()) ? true : false
        tabletFlag = channelsToRun.contains(platformType.toString() + "_" + FormFactor.TABLET_NATIVE.toString()) ? true : false
        universalFlag = channelsToRun.contains(platformType.toString() + "_" + FormFactor.UNIVERSAL_NATIVE.toString()) ? true : false

        platformDTO.setMobileStatus(mobileFlag ? Status.IN_PROGRESS : Status.NA)
        platformDTO.setTabletStatus(tabletFlag ? Status.IN_PROGRESS : Status.NA)
        platformDTO.setUniversalStatus(universalFlag ? Status.IN_PROGRESS : Status.NA)

        platformDTO.setMobileFlag(mobileFlag)
        platformDTO.setTabletFlag(tabletFlag)
        platformDTO.setUniversalFlag(universalFlag)

        return platformDTO
    }

    /**
     * This function creates flags for each channel selected, at the end they are used to figure out channels status
     * @param channelsToRun holds the list of channels to run
     * @param value is the build status value for the list of channels
     */
    private void prepareBuildServiceEnvironment(channels, value = false) {
        if (!channels || !script.params.IS_SOURCE_VISUALIZER)
            return
        for (item in channels) {
            script.env[buildService.concat(item)] = value
        }
    }

    /**
     * This function sets the global build status
     * @param status{SUCCESS, FAILED, UNSTABLE} are the possible values
     */
    public void updateGlobalStatus(Status status) {
        buildJson.setStatus(status)
    }

    /**
     * This function is used to update channel (Platform) env status to SUCCESS, update the json with status and channel artifact link on S3.
     * @param channelType contains the channel for which the status has to be updated
     * @param artefactURL contains the artifacts url that has been uploaded to s3
     */
    void updateSuccessBuildStatusOnS3(ChannelType channelType, String artefactURL) {
        updateDownloadLink(channelType, artefactURL)
        prepareBuildServiceEnvironment([channelType.toString()], true)
        updatePlatformStatus(channelType, Status.SUCCESS)
        updateBuildStatusOnS3()
    }

    /**
     * This function is used to update channel (Platform) env status to FAILED, update the json with status on S3.
     * This sets all the selected platforms to Failed and updates the global status to Failed as well
     * @param channelType contains the channel for which the status has to be updated
     */
    void updateFailureBuildStatusOnS3(ChannelType channelType) {
        updatePlatformStatus(channelType, Status.FAILED)
        updateBuildStatusOnS3()
    }

    /**
     * This function updates the current status json file on to S3
     */
    void updateBuildStatusOnS3() {
        String statusUrl = script.params.BUILD_STATUS_PATH
        statusUrl = statusUrl.substring(0, statusUrl.lastIndexOf('/'))
        buildJson.setLastUpdatedAt(new Date().toString())
        String resultJson = buildJson.toString()
        script.writeFile file: "buildStatus.json", text: resultJson
        script.echoCustom("The status url path is ${statusUrl} and ${resultJson}")
        AwsHelper.publishToS3 sourceFileName: "buildStatus.json",
                sourceFilePath: "./", statusUrl, script
    }

    /**
     * This function is called at the end of the build, it figures out the status of the build based on individual platforms result
     * @param channelsToRun
     */
    private void deriveGlobalBuildStatus(channelsToRun) {

        boolean successFlag = true
        boolean failureFlag = true

        if (!channelsToRun) {
            updateGlobalStatus(Status.FAILED)
            updateBuildStatusOnS3()
            return
        }

        for (channel in channelsToRun) {
            String buildValue = script.env[buildService.concat(channel)]
            if (buildValue.equals("false")) {
                successFlag = false
                updatePlatformStatus(ChannelType.valueOf(channel), Status.FAILED)
            } else {
                failureFlag = false
            }
        }

        successFlag ? updateGlobalStatus(Status.SUCCESS) : failureFlag ? updateGlobalStatus(Status.FAILED) : updateGlobalStatus(Status.UNSTABLE)
        if (failureFlag)
            script.currentBuild.result = 'FAILURE'
        updateBuildStatusOnS3()
    }

    /**
     * This function helps in uploading the logfile to the s3 with an exception message
     * Uploads both status json as well as the exception message
     *
     * @param channelsToRun contains the list of channels selected by the user to build
     * @param buildLog holds the the console log of jobs.
     */
    void createAndUploadLogFile(channelsToRun, String buildLog) {

        String projectWorkspacePath = script.env.WORKSPACE

        /*
         * Filtering AppFactory build console log for CloudBuildService. In cloud build
         *
         * we aren't exposing git logs, for this case, we will remove top lines 5 to 30,
         * which contains GIT code checkout of kony-commons repo.
         * Same time, removing all occurrences of AppFactory workspace path in build log.
         */

        def gitCheckoutStartLineNumber = 6
        def gitCheckoutEndLineNumber = 30

        def sanitizer = new RegexSanitizer(projectWorkspacePath, "CloudBuildService",
                new RemoveLinesSanitizer(
                        gitCheckoutStartLineNumber,
                        gitCheckoutEndLineNumber,
                        new TextSanitizer())
        )

        String sanitizedLogs = sanitizer.sanitize(buildLog)
        script.writeFile file: CLOUD_BUILD_LOG_FILENAME, text: sanitizedLogs, encoding: 'UTF-8'
        String s3ArtifactPath = [script.env.CLOUD_ACCOUNT_ID, script.env.PROJECT_NAME, 'Builds', 'logs'].join('/')
        String s3Url = AwsHelper.publishToS3 sourceFileName: CLOUD_BUILD_LOG_FILENAME,
                sourceFilePath: "./", s3ArtifactPath, script
        updateLogsLink(s3ArtifactPath + "/" + CLOUD_BUILD_LOG_FILENAME)
        updateBuildStatusOnS3()
    }
}