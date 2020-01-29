package com.kony.appfactory.dto.tests

import java.io.Serializable
import groovy.json.JsonBuilder

import com.kony.appfactory.dto.tests.DetailedNativeResults

public class NativeTestsDTO implements Serializable {
      private String appiumVersion; 
      private String testPlan; 
      private String devicePool; 
      private String devicesNotAvailable; 
      private String testEnvironment; 
      private List<DetailedNativeResults> results; 

      public NativeTestsDTO() {
          this.appiumVersion = new String();
          this.testPlan = new String();
          this.devicePool = new String();
          this.devicesNotAvailable = new String();
          this.testEnvironment = new String();
          this.results = new ArrayList();
      }
      
      @NonCPS
      public String getAppiumVersion(){
        return appiumVersion; 
      }
      @NonCPS
      public void setAppiumVersion(String appiumVersion){
         this.appiumVersion = appiumVersion;
      }
      @NonCPS
      public String getTestPlan(){
        return testPlan; 
      }
      @NonCPS
      public void setTestPlan(String testPlan){
         this.testPlan = testPlan;
      }
      @NonCPS
      public String getDevicePool(){
        return devicePool; 
      }
      @NonCPS
      public void setDevicePool(String devicePool){
         this.devicePool = devicePool;
      }
      @NonCPS
      public String getDevicesNotAvailable(){
        return devicesNotAvailable; 
      }
      @NonCPS
      public void setDevicesNotAvailable(String devicesNotAvailable){
         this.devicesNotAvailable = devicesNotAvailable;
      }
      @NonCPS
      public String getTestEnvironment(){
        return testEnvironment; 
      }
      @NonCPS
      public void setTestEnvironment(String testEnvironment){
         this.testEnvironment = testEnvironment;
      }
      @NonCPS
      public List<DetailedNativeResults> getResults(){
        return results; 
      }
      @NonCPS
      public void setResults(List<DetailedNativeResults> results){
         this.results = results;
      }
      
      @NonCPS
      @Override
      String toString() {
          def json = new JsonBuilder()
          json appiumVersion: getAppiumVersion(),
                  testPlan: getTestPlan(),
                  devicePool: getDevicePool(),
                  devicesNotAvailable: getDevicesNotAvailable(),
                  testEnvironment: getTestEnvironment(),
                  detailedNativeResults : getResults().toString()
                  
          return json.toPrettyString()

      }
}
  