package com.kony.appfactory.dto.tests

import java.io.Serializable
import groovy.json.JsonBuilder

import com.kony.appfactory.dto.tests.ResultsCount
import com.kony.appfactory.dto.tests.Device

public class DetailedNativeResults implements Serializable {
      private Device device;
      private String runARN;
      private String testStatus;
      private ResultsCount resultsCount;
      private String binaryURL;
      private String binaryExt;
      private long testDuration;
      private String resultsLink;
      private long deviceMinutes;

      public DetailedNativeResults() {
          this.device = new Device();
          this.runARN = new String();
          this.testStatus = new String();
          this.resultsCount = new ResultsCount();
          this.binaryURL = new String();
          this.binaryExt = new String();
          this.testDuration = 0;
          this.resultsLink = new String();
          this.deviceMinutes = 0;
      }
      
      @NonCPS
      public Device getDevice(){
        return device; 
      }
      @NonCPS
      public void setDevice(Device input){
         this.device = input;
      }
      @NonCPS
      public String getRunARN(){
        return runARN;
      }
      @NonCPS
      public void setRunARN(String arn){
         this.runARN = arn;
      }
      @NonCPS
      public String getTestStatus(){
        return testStatus; 
      }
      @NonCPS
      public void setTestStatus(String testStatus){
         this.testStatus = testStatus;
      }
      @NonCPS
      public ResultsCount getResultsCount(){
        return resultsCount; 
      }
      @NonCPS
      public void setResultsCount(ResultsCount resultsCount){
         this.resultsCount = resultsCount;
      }
      @NonCPS
      public String getBinaryURL(){
        return binaryURL; 
      }
      @NonCPS
      public void setBinaryURL(String binaryURL){
         this.binaryURL = binaryURL;
      }
      @NonCPS
      public String getBinaryExt(){
        return binaryExt;
      }
      @NonCPS
      public void setBinaryExt(String binaryExt){
         this.binaryExt = binaryExt;
      }
      @NonCPS
      public int getTestDuration(){
        return testDuration; 
      }
      @NonCPS
      public void setTestDuration(long testDuration){
         this.testDuration = testDuration;
      }
      @NonCPS
      public String getResultsLink(){
        return resultsLink; 
      }
      @NonCPS
      public void setResultsLink(String resultsLink){
         this.resultsLink = resultsLink;
      }

    @NonCPS
    public long getDeviceMinutes() {
        return deviceMinutes
    }

    @NonCPS
    public void setDeviceMinutes(long deviceMinutes) {
        this.deviceMinutes = deviceMinutes
    }

      @NonCPS
      @Override
      String toString() {
          def json = new JsonBuilder()
          json deviceName: getDevice().toString(),
                  testStatus: getTestStatus(),
                  counts: getResultsCount().toString(),
                  binaryURL: getBinaryURL(),
                  binaryExt: getBinaryExt(),
                  runARN: getRunARN(),
                  testDuration: getTestDuration(),
                  resultsLink : getResultsLink()
                  
          return json.toPrettyString()
      }
      
}