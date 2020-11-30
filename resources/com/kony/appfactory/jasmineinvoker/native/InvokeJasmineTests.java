package com.kony.appfactory.jasmine;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.Map;
import java.util.Set;

import org.openqa.selenium.WebElement;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import io.appium.java_client.InteractsWithFiles;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.appmanagement.ApplicationState;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.remote.AndroidMobileCapabilityType;
import io.appium.java_client.remote.IOSMobileCapabilityType;

/**
 * Appium test for invoking jasmine native test run.
 */
public class InvokeJasmineTests {

    public static String platformName;
    public static AndroidDriver <WebElement> androiddriver;
    public static IOSDriver <WebElement> iosdriver;
    public static RemoteWebDriver driver;
    public boolean isAndroidDevice = false;
    public String bundleID = "";
    public String jasmineAndroidNativeReportDefaultLocation = "/sdcard/JasmineTestResults/";
    public String jasmineIosNativeReportDefaultLocation = "@[APPLICATIONID]/Library/JasmineTestResults/";
    public String jasmineNativeTestReportFileName = "TestResult.html";
    public String jasmineJSONReportFileName = "jasmineReport.json";
    int totalTests = 0;
    int totalPassed = 0;
    int totalFailed = 0;
    

    private String getTestRunEnvironment() {
        String testRunEnvType = System.getProperty("TEST_RUN_ENVIRONMENT");
        if(testRunEnvType == null) 
            return "RUN_IN_CUSTOM_TEST_ENVIRONMENT";
        else
            return testRunEnvType;
    }
    
    private boolean isAndroidDevice() {
        String devicePlatform = driver.getCapabilities().getPlatform().toString();
        String devicePlatformName = System.getenv("DEVICEFARM_DEVICE_PLATFORM_NAME");
        return (devicePlatformName.equalsIgnoreCase("Android") || devicePlatform.equalsIgnoreCase("LINUX"));
    }
    
    private String getDefaultReportLocationForDevice() {
        if(isAndroidDevice) {
            return jasmineAndroidNativeReportDefaultLocation;
        }
        return jasmineIosNativeReportDefaultLocation;
    }
    
    private String getJSONReportLocationForDevice() {
        return getDefaultReportLocationForDevice() + jasmineJSONReportFileName;
    }

    /**
     * Evaluate the in-progress test execution stats
     * @param resultsJson json string of current results
     * @throws JSONException
     */
    private void evaluateTestResultStats(String resultsJson) throws JSONException {
        totalTests = 0;
        totalPassed = 0;
        totalFailed = 0;
        
        JSONArray results = new JSONArray(resultsJson);
        for(int i=0; i<results.length(); i++) {
            JSONObject event = (JSONObject) results.get(i);
            String eventType = (String) event.get("event");
            if(!eventType.equalsIgnoreCase("jasmineDone")) {
                JSONObject eventResult = (JSONObject) event.get("result");
                switch(eventType) {
                    case "jasmineStarted":
                        totalTests = eventResult.getInt("totalSpecsDefined");
                        break;
                    case "specDone":
                        String result = eventResult.getString("status");
                        if(result.equalsIgnoreCase("Passed")) {
                            totalPassed++ ;
                        } else { 
                            totalFailed++ ;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }
    
    private void initializeDriver() throws Exception {
        
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setCapability("noReset", false);
        capabilities.setCapability("autoGrantPermissions", true);
        capabilities.setCapability("newCommandTimeout", "300");
        if (getTestRunEnvironment().equalsIgnoreCase("RUN_IN_CUSTOM_TEST_ENVIRONMENT")) {

            System.out.println("Running tests in the Custom Test Mode");
            if ("Android".equalsIgnoreCase(System.getenv("DEVICEFARM_DEVICE_PLATFORM_NAME"))) {
                System.out.println("Initializing the Android Driver!!!!!!!!!!!!");
                capabilities.setCapability("automationName", "UiAutomator2");
                capabilities.setCapability(AndroidMobileCapabilityType.AUTO_GRANT_PERMISSIONS, true);   
                androiddriver = new AndroidDriver<WebElement>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
                driver = androiddriver;
                bundleID = (String) androiddriver.getSessionDetail("appPackage");
            } else {
                capabilities.setCapability("automationName", "XCUITest");
                capabilities.setCapability(IOSMobileCapabilityType.AUTO_ACCEPT_ALERTS, true);
                System.out.println("Initializing the iOS Driver!!!!!!!!!!!!");
                iosdriver = new IOSDriver<WebElement>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
                driver = iosdriver;
                bundleID = (String) iosdriver.getSessionDetail("CFBundleIdentifier");
            }
            System.out.println("Driver is initialized!!!!!!!!!!!!!!!!!!!!!");
            isAndroidDevice = isAndroidDevice();

        } else {

            System.out.println("Running tests in the Standard Test Mode");
            
            if ("MAC".equalsIgnoreCase(platformName)) {
                System.out.println("Inside platform MAC............");
                driver = iosdriver;
            } else {
                if (driver != null) {
                    driver.quit();
                    driver = null;
                }
                if (androiddriver != null) {
                    androiddriver.quit();
                    androiddriver = null;
                }
                if (iosdriver != null) {
                    iosdriver.quit();
                    iosdriver = null;
                }

                System.out.println("Inside platform ANDROID............");
                androiddriver = new AndroidDriver<WebElement>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
                driver = androiddriver;
            }
        }
    }

    private void tearDownAppium() {
        if (driver != null)
            driver.quit();
        if (androiddriver != null)
            androiddriver.quit();
        if (iosdriver != null)
            iosdriver.quit();
    }

    private byte[] getFileContentFromDevice(String deviceFilePath) {
        byte[] fileData = null;
        
        if(!isAndroidDevice) {
            deviceFilePath = deviceFilePath.replace("[APPLICATIONID]", bundleID);
        }
        
        try {
            fileData = ((InteractsWithFiles) driver).pullFile(deviceFilePath);

            if (fileData == null || fileData.length == 0) {
                System.out.println("Oops.. found that jasmine test results file " + deviceFilePath + " is either empty or not exists.");
            }
        } catch (Exception e) {
            System.out.println("Exception occurred while reading the file from the path: " + deviceFilePath);
        }
        
        return fileData;
    }
    
    private void checkTestExecutionStatus(String htmlResultsFilePath) throws Exception {
        String resultsJSON = null;
        boolean isTestInProgress = false;
        int iterations = 0;
        // Sleeping for 30 seconds to let jasmine tests initialize and start execution.
        Thread.sleep(30000);
        
        System.out.println("Looking for the results file...");
        do {
            
            /* Check if jasmine test is triggered or not.
             * If "resultsJSON" object consists of value, marking the status as in-progress.
             * */
            try {
                byte[] jasmineReportContentFromDevice = getFileContentFromDevice(getJSONReportLocationForDevice());
                if (jasmineReportContentFromDevice != null && jasmineReportContentFromDevice.length != 0) {
                    resultsJSON = new String(jasmineReportContentFromDevice);
                }
            } catch (Exception e) {
                System.out.println("Unable to find the jasmine Events !!!!");
            }
            
            if(resultsJSON != null) {
                isTestInProgress = true;
                evaluateTestResultStats(resultsJSON);
                /* Evaluate the test execution stats.
                 * Comparing the current test execution stats (total passed and failed test count)
                 * with total test count, if it matches marking test execution is completed.
                 * */
                int currentTotalTestsCount = totalPassed + totalFailed;
                if(currentTotalTestsCount == totalTests && currentTotalTestsCount > 0) {
                    System.out.println("Jasmine tests execution is completed.");
                    break;
                }
            } else if (!isTestInProgress && iterations > 10) {  // Here we give a 150 seconds time to start jasmine tests execution.
                System.out.println("Jasmine tests doesn't seem to be initiated in the expected time (150 seconds). Please look into the device logs for more information.");
                break;
            }
            
            if (isAndroidDevice) {
                String currentRunningApp = ((AndroidDriver<?>) driver).getCurrentPackage();
                if (!currentRunningApp.equalsIgnoreCase(bundleID)) {
                    System.out.println("Application seems crashed or closed. We will try to fetch the jasmine test results if avaliable.");
                    break;
                }
            } else {
                ApplicationState testAppStatus = ((IOSDriver<?>) driver).queryAppState(bundleID);
                if (testAppStatus != ApplicationState.RUNNING_IN_FOREGROUND) {
                    System.out.println("Application seems crashed or closed. We will try to fetch the jasmine test results if avaliable.");
                    break;
                }
            }
            
            System.out.println("Tests execution seems in progress.. Waiting for the results..");
            iterations = iterations + 1;
            Thread.sleep(15000);
        } while (iterations <= 100 && driver != null);

        saveToFile(htmlResultsFilePath, System.getenv("DEVICEFARM_LOG_DIR") + File.separator + "JasmineTestResult.html");
        saveToFile(getJSONReportLocationForDevice(), System.getenv("DEVICEFARM_LOG_DIR") + File.separator + "JasmineTestResult.json");
        
    }

    private void saveToFile(String sourceFile, String destFile) {
        try{
            byte[] fileContents = getFileContentFromDevice(sourceFile);
            if (fileContents != null && fileContents.length != 0) {
                File fs = new File(destFile);
                FileOutputStream fos = new FileOutputStream(fs);
                fos.write(fileContents, 0, fileContents.length);
                fos.flush();
                fos.close();
            }
        } catch(Exception e) {
            System.out.println("Error While saving the File : " + destFile);
            e.printStackTrace();
        }
    }
    
    @Test
    public void runJasmineNativeTests() throws Exception {
        try {
            initializeDriver();
            checkTestExecutionStatus(getDefaultReportLocationForDevice() + jasmineNativeTestReportFileName);
        } catch(Exception e) {
            System.out.println("Exception occured while running the tests!!!!!");
        } finally {
            tearDownAppium();
        }
    }
}
