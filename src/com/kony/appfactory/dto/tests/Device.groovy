package com.kony.appfactory.dto.tests

import java.io.Serializable
import groovy.json.JsonBuilder

public class Device implements Serializable {
      private String name; 
      private String os;
      private String formFactor;
      private String platform;

      public Device() {
          this.formFactor = new String();
          this.name = new String();
          this.os = new String();
          this.platform = new String();
      }
      
      public Device(String name, String os, String formFactor, String platform) {
          this.formFactor = formFactor;
          this.name = name;
          this.os = os;
          this.platform = platform;
      }
      
      @NonCPS
      public String getName(){
        return name; 
      }
      @NonCPS
      public void setName(String name){
         this.name = name;
      }
      @NonCPS
      public String getOS(){
        return os;
      }
      @NonCPS
      public void setOS(String os){
         this.os = os;
      }
      @NonCPS
      public String getFormFactor(){
        return formFactor;
      }
      @NonCPS
      public void setFormFactor(String formFactor){
         this.formFactor = formFactor;
      }
      @NonCPS
      public String getPlatform(){
        return platform;
      }
      @NonCPS
      public void setPlatform(String platform){
         this.platform = platform;
      }

      @NonCPS
      @Override
      String toString() {
          def json = new JsonBuilder()
          json name: getName(),
                  os: getOS(),
                  formfactor: getFormFactor(),
                  platform: getPlatform()
          
          return json.toPrettyString()
      }
}