package com.kony.appfactory.dto.buildstatus

import com.kony.appfactory.enums.Status
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper


/**
 *  Sample JSON :
 *
 *  {
 *     "buildNumber": "349",
 *     "buildGuid": "eyJidWlsZF9ndWlkIjoiMmE1N2FlYTUtMGQwMS00OTBiLThjYzktZDg4NDU3YWY2MDllIiwicHJvamVjdF9uYW1lIjoiU1dBTGlmZSJ9",
 *     "status": "SUCCESS",
 *     "startedAt": "Tue Aug 21 04:20:52 UTC 2018",
 *     "lastUpdatedAt": "Tue Aug 21 04:20:52 UTC 2018",
 *     "platforms": [
 *         {
 *           "IOS": {
 *                 "mobileStatus": "SUCCESS",
 *                 "tabletStatus": "SUCCESS",
 *                 "universalStatus": "SUCCESS",
 *                 "platformName": "IOS",
 *                 "logsLink": null,
 *                 "mobileFlag": true,
 *                 "tabletFlag": true,
 *                 "universalFlag": false,
 *                 "mobileDownloadLink": "accountId/Builds/EnvId/Platform/iOSMobile.ipa",
 *                 "tabletDownloadLink": "accountId/Builds/EnvId/Platform/iOSTablet.ipa",
 *                 "universalDownloadLink": null
 *                 }
 *         },
 *         {
 *             "ANDROID": {
 *                 "mobileStatus": "SUCCESS",
 *  *              "tabletStatus": "SUCCESS",
 *  *              "universalStatus": "SUCCESS",
 *                 "platformName": "ANDROID",
 *                 "logsLink": null,
 *                 "mobileFlag": true,
 *                 "tabletFlag": true,
 *                 "universalFlag": false,
 *                 "mobileDownloadLink": "accountId/Builds/EnvId/Platform/androidMobile.apk",
 *                 "tabletDownloadLink": "accountId/Builds/EnvId/Platform/androidTablet.apk",
 *                 "universalDownloadLink": null
 *             }
 *         }
 *     ]
 * }
 *
 */

class BuildStatusDTO implements Serializable {

    private String buildNumber
    private String buildGuid
    private Status status
    private String startedAt
    private String lastUpdatedAt
    private List<PlatformsDTO> platforms

    static BuildStatusDTO single_instance=null

    private BuildStatusDTO(){

    }

    @NonCPS
    static BuildStatusDTO getInstance() {
        if (single_instance == null)
            single_instance = new BuildStatusDTO()
        return single_instance;
    }

    @NonCPS
    void setBuildNumber(String buildNumber){
        this.buildNumber = buildNumber
    }

    @NonCPS
    String getBuildNumber(){
        return buildNumber
    }

    @NonCPS
    void setBuildGuid(String buildGuid){
        this.buildGuid = buildGuid
    }

    @NonCPS
    String getBuildGuid(){
        return buildGuid
    }

    @NonCPS
    void setStatus(Status status){
        this.status=status
    }

    @NonCPS
    Status getStatus(){
        return status
    }

    @NonCPS
    String getStartedAt(){
        return startedAt
    }

    @NonCPS
    void setStartedAt(String startedAt){
        this.startedAt = startedAt
    }

    @NonCPS
    String getLastUpdatedAt(){
        return lastUpdatedAt
    }

    @NonCPS
    String setLastUpdatedAt(String lastUpdatedAt){
        this.lastUpdatedAt = lastUpdatedAt
    }

    @NonCPS
    setPlatforms(List<PlatformsDTO> platforms){
        this.platforms = platforms
    }

    @NonCPS
    List<PlatformsDTO> getPlatforms(){
        return platforms
    }

    @NonCPS
    List<JsonBuilder> getPlatformData(){
        List<JsonBuilder> platformsJsonBuilder = new ArrayList<JsonBuilder>();
        List<PlatformsDTO> platforms = getPlatforms();

        for(int index = 0; index < platforms.size(); index++){
            def json = new JsonBuilder()
            def platformType=platforms.get(index).getPlatformType()

            String platformsString = platforms.get(index).toString()

            // To mark platform type as a key for PlatformsDTO Object we use the below code
            JsonSlurper jsonSlurper = new JsonSlurper()
            def platformsJson = jsonSlurper.parseText(platformsString)
            def mainJson = new JsonBuilder()
            mainJson "${platformType}":platformsJson

            String formattedString = mainJson.toString()
            platformsJson = jsonSlurper.parseText(formattedString)

            platformsJsonBuilder.add(platformsJson)
        }
        return platformsJsonBuilder
    }

    @NonCPS
    @Override
    String toString() {
        def json = new JsonBuilder()
        json buildNumber: getBuildNumber(),
                buildGuid: getBuildGuid(),
                status: getStatus(),
                startedAt: getStartedAt(),
                lastUpdatedAt: getLastUpdatedAt(),
                platforms: getPlatformData()
        return json.toPrettyString()
    }

}