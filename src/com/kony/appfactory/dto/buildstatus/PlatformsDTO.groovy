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
    private String mobileStartedAt = "NA"
    private String mobileFinishedAt = "NA"
    private String tabletStartedAt = "NA"
    private String tabletFinishedAt = "NA"
    private String universalStartedAt = "NA"
    private String universalFinishedAt = "NA"
    private String buildNumber

    PlatformsDTO(PlatformType platformType){
        this.platformType = platformType
    }

    @NonCPS
    String getBuildNumber() {
        return buildNumber
    }

    @NonCPS
    void setBuildNumber(String buildNumber) {
        this.buildNumber = buildNumber
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
        this.logsLink = (!logsLink.equals("null")) ? logsLink : null

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

    @NonCPS
    void setMobileDownloadLink(String mobileDownloadLink){
        this.mobileDownloadLink = (!mobileDownloadLink.equals("null")) ? mobileDownloadLink : null
    }

    @NonCPS
    String getMobileDownloadLink(){
        return mobileDownloadLink
    }

    @NonCPS
    void setTabletDownloadLink(String tabletDownloadLink){
        this.tabletDownloadLink = (!tabletDownloadLink.equals("null")) ? tabletDownloadLink : null
    }

    @NonCPS
    String getTabletDownloadLink(){
        return tabletDownloadLink
    }

    @NonCPS
    void setUniversalDownloadLink(String universalDownloadLink){
        this.universalDownloadLink = (!universalDownloadLink.equals("null")) ? universalDownloadLink : null
    }

    @NonCPS
    String getUniversalDownloadLink(){
        return universalDownloadLink
    }

    @NonCPS
    String getMobileStartedAt() {
        return mobileStartedAt
    }

    @NonCPS
    void setMobileStartedAt(String mobileStartedAt) {
        this.mobileStartedAt = mobileStartedAt
    }

    @NonCPS
    String getMobileFinishedAt() {
        return mobileFinishedAt
    }

    @NonCPS
    void setMobileFinishedAt(String mobileFinishedAt) {
        this.mobileFinishedAt = mobileFinishedAt
    }

    @NonCPS
    String getTabletStartedAt() {
        return tabletStartedAt
    }

    @NonCPS
    void setTabletStartedAt(String tabletStartedAt) {
        this.tabletStartedAt = tabletStartedAt
    }

    @NonCPS
    String getTabletFinishedAt() {
        return tabletFinishedAt
    }

    @NonCPS
    void setTabletFinishedAt(String tabletFinishedAt) {
        this.tabletFinishedAt = tabletFinishedAt
    }

    @NonCPS
    String getUniversalStartedAt() {
        return universalStartedAt
    }

    @NonCPS
    void setUniversalStartedAt(String universalStartedAt) {
        this.universalStartedAt = universalStartedAt
    }

    @NonCPS
    String getUniversalFinishedAt() {
        return universalFinishedAt
    }

    @NonCPS
    void setUniversalFinishedAt(String universalFinishedAt) {
        this.universalFinishedAt = universalFinishedAt
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
                universalDownloadLink: getUniversalDownloadLink(),
                mobileStartedAt: getMobileStartedAt(),
                mobileFinishedAt: getMobileFinishedAt(),
                tabletStartedAt: getTabletStartedAt(),
                tabletFinishedAt: getTabletFinishedAt(),
                universalStartedAt: getUniversalStartedAt(),
                universalFinishedAt: getUniversalFinishedAt()
                buildNumber: getBuildNumber()

        return json.toPrettyString()
    }
}