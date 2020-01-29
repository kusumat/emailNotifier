package com.kony.appfactory.dto.tests

import java.io.Serializable
import groovy.json.JsonBuilder

public class ResultsCount implements Serializable {
      private int total; 
      private int passed; 
      private int failed; 
      private int skipped; 
      private int warned; 
      private int stopped; 
      private int errored; 

      public ResultsCount(int total, int passed, int failed, int skipped, int warned, int stopped, int errored) {
          this.total = total;
          this.passed = passed;
          this.failed = failed;
          this.skipped = skipped;
          this.warned = warned;
          this.stopped = stopped;
          this.errored = errored;
      }
      
      public ResultsCount() {
          this.total = 0;
          this.passed = 0;
          this.failed = 0;
          this.skipped = 0;
          this.warned = 0;
          this.stopped = 0;
          this.errored = 0;
      }
      
      @NonCPS
      public int getTotal(){
        return total; 
      }
      @NonCPS
      public void setTotal(int total){
         this.total = total;
      }
      @NonCPS
      public int getPassed(){
        return passed; 
      }
      @NonCPS
      public void setPassed(int passed){
         this.passed = passed;
      }
      @NonCPS
      public int getFailed(){
        return failed; 
      }
      @NonCPS
      public void setFailed(int failed){
         this.failed = failed;
      }
      @NonCPS
      public int getSkipped(){
        return skipped; 
      }
      @NonCPS
      public void setSkipped(int skipped){
         this.skipped = skipped;
      }
      @NonCPS
      public int getWarned(){
        return warned; 
      }
      @NonCPS
      public void setWarned(int warned){
         this.warned = warned;
      }
      @NonCPS
      public int getStopped(){
        return stopped; 
      }
      @NonCPS
      public void setStopped(int stopped){
         this.stopped = stopped;
      }
      @NonCPS
      public int getErrored(){
        return errored; 
      }
      @NonCPS
      public void setErrored(int errored){
         this.errored = errored;
      }
      
      @NonCPS
      @Override
      String toString() {
          def json = new JsonBuilder()
          json total: getTotal(),
                  passed: getPassed(),
                  failed: getFailed(),
                  skipped: getSkipped(),
                  warned: getWarned(),
                  stopped : getStopped(),
                  errored : getErrored()
                  
          return json.toPrettyString()
      }
      
}