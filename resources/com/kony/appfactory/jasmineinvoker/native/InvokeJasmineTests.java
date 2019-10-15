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
import io.appium.java_client.ios.IOSDriver;

/**
 * Appium test for invoking jasmine native test run.
 */
public class InvokeJasmineTests {

    public static String platformName;
    public static AndroidDriver <WebElement> androiddriver;
    public static IOSDriver <WebElement> iosdriver;
    public static RemoteWebDriver driver;
    public boolean isAndroidDevice = false;
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
                androiddriver = new AndroidDriver<WebElement>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
                driver = androiddriver;
            } else {
                capabilities.setCapability("automationName", "XCUITest");
                System.out.println("Initializing the iOS Driver!!!!!!!!!!!!");
                iosdriver = new IOSDriver<WebElement>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
                driver = iosdriver;
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
            String bundleID = (String) ((IOSDriver<?>) driver).getSessionDetail("CFBundleIdentifier");
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
            }
            
            System.out.println("Tests execution seems in progress.. Waiting for the results..");
            iterations = iterations + 1;
            Thread.sleep(30000);
        } while (iterations <= 100 && isTestInProgress && driver != null);

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
