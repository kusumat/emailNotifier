package com.kony.appfactory.dto.buildstatus

import com.kony.appfactory.enums.PlatformType
import com.kony.appfactory.enums.Status
import groovy.json.JsonBuilder

/**
 *    Sample JSON:
 *
 *     {
 *         "mobileStatus": "SUCCESS",
 *         "tabletStatus": "SUCCESS",
 *         "universalStatus": "SUCCESS",
 *         "platformName": "ANDROID",
 *         "logsLink": null,
 *         "mobileFlag": true,
 *         "tabletFlag": true,
 *         "universalFlag": false,
 *         "mobileDownloadLink": "accountId/Builds/EnvId/Platform/androidMobile.apk",
 *  *      "tabletDownloadLink": "accountId/Builds/EnvId/Platform/androidTablet.apk",
 *         "universalDownloadLink": null
 *     }
 */
class PlatformsDTO implements Serializable{

    private PlatformType platformType
    private Status mobileStatus
    private Status tabletStatus
    private Status universalStatus
    private String logsLink
    private boolean mobileFlag
    private boolean tabletFlag
    private boolean universalFlag
    private String mobileDownloadLink
    private String tabletDownloadLink
    private String universalDownloadLink

    PlatformsDTO(PlatformType platformType){
        this.platformType = platformType
    }

    @NonCPS
    void setPlatformType(PlatformType platformType){
        this.platformType = platformType
    }

    @NonCPS
    PlatformType getPlatformType(){
        return platformType
    }

    @NonCPS
    void setMobileStatus(Status mobileStatus){
        this.mobileStatus = mobileStatus
    }

    @NonCPS
    Status getMobileStatus(){
        return mobileStatus
    }

    @NonCPS
    void setTabletStatus(Status tabletStatus){
        this.tabletStatus = tabletStatus
    }

    @NonCPS
    Status getTabletStatus(){
        return tabletStatus
    }

    @NonCPS
    void setUniversalStatus(Status universalStatus){
        this.universalStatus = universalStatus
    }

    @NonCPS
    Status getUniversalStatus(){
        return universalStatus
    }

    @NonCPS
    String getLogsLink(){
        return logsLink
    }

    @NonCPS
    void setLogsLink(String logsLink){
        this.logsLink = logsLink
    }

    @NonCPS
    boolean getMobileFlag(){
        return mobileFlag
    }

    @NonCPS
    void setMobileFlag(boolean mobileFlag){
        this.mobileFlag = mobileFlag
    }

    @NonCPS
    boolean getTabletFlag(){
        return tabletFlag
    }

    @NonCPS
    void setTabletFlag(boolean tabletFlag){
        this.tabletFlag = tabletFlag
    }

    @NonCPS
    void setUniversalFlag(boolean universalFlag){
        this.universalFlag = universalFlag
    }

    @NonCPS
    boolean getUniversalFlag(){
        return universalFlag
    }

    void setMobileDownloadLink(String mobileDownloadLink){
        this.mobileDownloadLink = mobileDownloadLink
    }

    @NonCPS
    String getMobileDownloadLink(){
        return mobileDownloadLink
    }

    @NonCPS
    void setTabletDownloadLink(String tabletDownloadLink){
        this.tabletDownloadLink = tabletDownloadLink
    }

    @NonCPS
    String getTabletDownloadLink(){
        return tabletDownloadLink
    }

    @NonCPS
    void setUniversalDownloadLink(String universalDownloadLink){
        this.universalDownloadLink = universalDownloadLink
    }

    @NonCPS
    String getUniversalDownloadLink(){
        return universalDownloadLink
    }

    @NonCPS
    @Override
    String toString(){

        def json = new JsonBuilder()
        json platformType: getPlatformType(),
                mobileStatus: getMobileStatus(),
                tabletStatus: getTabletStatus(),
                universalStatus: getUniversalStatus(),
                logsLink: getLogsLink(),
                mobileFlag: getMobileFlag(),
                tabletFlag: getTabletFlag(),
                universalFlag: getUniversalFlag(),
                mobileDownloadLink: getMobileDownloadLink(),
                tabletDownloadLink: getTabletDownloadLink(),
                universalDownloadLink: getUniversalDownloadLink()

        return json.toPrettyString()
    }
}