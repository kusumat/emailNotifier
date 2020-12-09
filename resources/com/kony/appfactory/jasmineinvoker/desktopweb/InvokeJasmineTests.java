package com.kony.appfactory.jasmine;

import org.testng.annotations.Test;
import org.testng.internal.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.WebDriverException;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

public class InvokeJasmineTests implements ITestListener {
    ChromeDriver driver;
    String webDriverPath;
    String browserPath;
    String appUrl;
    String windowSize;
    String fileDownloadPath;
    String jasmineTestAppUrl;
    int totalTests = 0;
    int totalPassed = 0;
    int totalFailed = 0;
    Map<String, String> prevResultsMap = new HashMap<String, String>();
    String inProgressTest = null;
            
    private String getWebDriverPath() {
        webDriverPath = System.getProperty("DRIVER_PATH");
        if (webDriverPath != null) 
            return webDriverPath;
        else throw new RuntimeException("Browser driver path is not set properly.");
    }
    
    private String getBrowserPath() {
        browserPath = System.getProperty("BROWSER_PATH");
        if (browserPath != null) 
            return browserPath;
        else throw new RuntimeException("Browser Path is not set properly.");
    }
    
    private String getWindowSize() {
        windowSize = System.getProperty("SCREEN_RESOLUTION");
        if (windowSize != null) 
            return windowSize;
        else return "1024x768";
    }
    
    private String getApplicationUrl() {
        appUrl = System.getProperty("WEB_APP_URL");
        if (appUrl != null) 
            return appUrl;
        else throw new RuntimeException("Web Application URL is not set properly.");
    }
    
    private String getDownloadFilePath() {
        fileDownloadPath = System.getProperty("FILE_DOWNLOAD_PATH");
        if (fileDownloadPath != null) 
            return fileDownloadPath;
        else throw new RuntimeException("Download File path is not set properly.");
    }
    
    private String getJasmineTestAppUrl() {
        jasmineTestAppUrl = System.getProperty("JASMINE_TEST_APP_URL");
        if (jasmineTestAppUrl != null)
            return jasmineTestAppUrl;
        else throw new RuntimeException("Jasmine test app URL is not set properly.");
    }
    
    private void setupDriver() throws ClientProtocolException, IOException {
        System.setProperty("webdriver.chrome.driver", getWebDriverPath());
        String screenResolution[] = getWindowSize().split("x");
        
        DesiredCapabilities cap = DesiredCapabilities.chrome();
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.BROWSER, Level.ALL);
        cap.setCapability("goog:loggingPrefs", logPrefs);
        cap.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true);
        
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setBinary(getBrowserPath());
        chromeOptions.addArguments("window-size=" + screenResolution[0] + "," + screenResolution[1]);
        chromeOptions.setHeadless(true);
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-web-security");
        chromeOptions.addArguments("--unsafely-treat-insecure-origin-as-secure=http://localhost:8888/ --user-data-dir=/tmp");
        chromeOptions.merge(cap);
          
        ChromeDriverService driverService = ChromeDriverService.createDefaultService();
        
        Map<String, Object> commandParams = new HashMap<>();
        commandParams.put("cmd", "Page.setDownloadBehavior");
        Map<String, String> params = new HashMap<>();
        params.put("behavior", "allow");
        params.put("downloadPath", getDownloadFilePath());
        commandParams.put("params", params);
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClientBuilder.create().build();
        String command = objectMapper.writeValueAsString(commandParams);
        driver = new ChromeDriver(driverService, chromeOptions);
        
        driver.manage().deleteAllCookies();
        String postURL = driverService.getUrl().toString() + "/session/" + driver.getSessionId() + "/chromium/send_command";
        HttpPost request = new HttpPost(postURL);
        request.addHeader("content-type", "application/xml");
        request.setEntity(new StringEntity(command));
        httpClient.execute(request);
        
    }
  
    private void destroyDriver() {
        if(driver != null){
            driver.quit();
        }
    }

    /**
     * Capturing the browser console logs into a file
     * @throws SecurityException
     * @throws IOException
     */
    private void captureBrowserConsoleLog() throws SecurityException, IOException {
        File file = new File("browserConsoleLog.txt");
        if (file.exists() == true) {
            file.delete();
        }

        if(driver != null) {
            LogEntries logEntries = driver.manage().logs().get(LogType.BROWSER);
            for (LogEntry entry : logEntries) {
                String logEntry = new Date(entry.getTimestamp()) + " " + entry.getLevel() + " " + entry.getMessage() + "\n";
                writeToFile(file, logEntry);
            }
        }
    }

    /**
     * This method writes the given entries in to a file.
     * @param file File object of the file.
     * @param message Entry that has to be written in the opened file.
     * @throws SecurityException
     * @throws IOException
     */
    private void writeToFile(File file, String message) throws SecurityException, IOException {
        FileWriter fr = new FileWriter(file, true);
        BufferedWriter br = new BufferedWriter(fr);
        br.write(message);
        br.close();
        fr.close();
    }
    
    /**
     * Check the app configuration is valid for jasmine test run
     * @throws Exception 
     */
    private void checkFabricAppConfigForJasmineTest() throws Exception {
        String jasmineTestAppMode = null;
        String jasmineTestRunUrl = null;
        try {
            JavascriptExecutor jse = (JavascriptExecutor) driver;
            jasmineTestAppMode = jse.executeScript("return appConfig.isDebug").toString();
            jasmineTestRunUrl = jse.executeScript("return appConfig.testAutomation.scriptsURL").toString();
        } catch (WebDriverException e) {
            System.out.println("Unable to fetch the app details, because of the following exception:");
            e.printStackTrace();
        }
        
        if((jasmineTestAppMode != null && !jasmineTestAppMode.equalsIgnoreCase("true")) || !getJasmineTestAppUrl().equalsIgnoreCase(jasmineTestRunUrl)) {
            System.out.println("Web Application doesn't have the appropriate jasmine configuration!");
            throw new Exception("WEB APP CONFIG ERROR!!!");
        }
    }
    
    /**
     * Check the test execution status based on report file, that is passed to it
     * @param filePath html test execution report file path
     * @throws Exception
     */
    private void checkTestExecutionStatus(String downloadPath) throws Exception {
        String resultsJSON = null;
        boolean isTestInProgress = false;
        boolean isHTMLReportExists = false;
        int iterations = 0;
        do {
            /* Check jasmine test is triggered or not.
             * If "resultsJSON" object consists of value, marking the status as in-progress.
             * */
            try {
                JavascriptExecutor jse = (JavascriptExecutor) driver;
                resultsJSON = jse.executeScript("return JSON.stringify(jasmineEvents)").toString();
            } catch (WebDriverException exception) {
                System.out.println("Failed to get the jasmine tests execution status because of the following exception - ");
                exception.printStackTrace();
                System.out.println("We will continue to fetch the test execution status after some time "
                        + "and also look for the jasmine test execution report.");
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
            
            /* Looking for the report.html
             * if it is generated, Test run will be considered as complete.*/
            isHTMLReportExists = searchForHTMLReport(downloadPath);
            if(isHTMLReportExists) {
                System.out.println("Jasmine tests execution is completed. Jasmine Test execution report is found.");
                break;
            }
            
            iterations = iterations + 1;
            Thread.sleep(30000);
        } while (!isHTMLReportExists && iterations <= 1000 && isTestInProgress);
    }

    /**
     * @return Results json contains the jasmine test execution events as results.
     * @throws Exception
     */
    private String getResultsInfoJSON() throws Exception {
        String resultsJSON = null;
        try {
            JavascriptExecutor jse = (JavascriptExecutor) driver;
            resultsJSON = jse.executeScript("return JSON.stringify(jasmineEvents)").toString();
            File jsonFile = new File(getDownloadFilePath() + File.separator + "report.json");
            writeToFile(jsonFile, resultsJSON);
            evaluateTestResultStats(resultsJSON);
        } catch (WebDriverException e) {
            System.out.println("Exception occurred while fetching the Jasmine results!");
            e.printStackTrace();
            throw new Exception("TEST RESULTS FETCH ERROR!!!");
        }
        return resultsJSON;
    }
    
    /**
     * @return true if the html report file exists, otherwise false.
     * @throws Exception
     */
    private boolean searchForHTMLReport(String downloadPath) throws Exception {
        
        try (Stream<Path> walk = Files.walk(Paths.get(downloadPath))) {

            List<String> result = walk.map(path -> path.toString())
                    .filter((fileName) -> fileName.endsWith(".html") && fileName.indexOf("TestResult_") > 0).collect(Collectors.toList());

            return (result.size() > 0);
            
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return false;
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
        int jasmineStartedEventCount = 0;
        
        Map<String, String> currResultsMap = new HashMap<String, String>();
        
        JSONArray results = new JSONArray(resultsJson);
        for(int i=0; i<results.length(); i++) {
            JSONObject event = (JSONObject) results.get(i);
            String eventType = (String) event.get("event");
            if(!eventType.equalsIgnoreCase("jasmineDone")) {
                JSONObject eventResult = (JSONObject) event.get("result");
                switch(eventType) {
                    case "jasmineStarted":
                        totalTests = eventResult.getInt("totalSpecsDefined");
                        jasmineStartedEventCount++;
                        break;
                    case "specDone":
                        String result = eventResult.getString("status");
                        currResultsMap.put(eventResult.getString("fullName"), result);
                        if(result.equalsIgnoreCase("Passed")) {
                            totalPassed++ ;
                        } else { 
                            totalFailed++ ;
                        }
                        break;
                    case "specStarted":
                        currResultsMap.put(eventResult.getString("fullName"), "In-Progress");
                        break;
                    default:
                        break;
                }
            }
        }
        
        if(jasmineStartedEventCount > 1){
            System.out.println("Tests are re-instantiated");
            prevResultsMap.clear();
            inProgressTest = null;
        }
        
        if(prevResultsMap.size() == 0) {
            System.out.println("");
            System.out.println("Test Results Status is as follows :");
            System.out.println("------------------------------------------------------------------------------------------------------");
            System.out.println("|  TEST SPEC NAME                                                                     |  Status      |");
            System.out.println("------------------------------------------------------------------------------------------------------");
        }
        
        if(inProgressTest != null && !currResultsMap.get(inProgressTest).equalsIgnoreCase("in-progress")) {
            printMe(inProgressTest, currResultsMap.get(inProgressTest));
            prevResultsMap.put(inProgressTest, currResultsMap.get(inProgressTest));
            inProgressTest = null;
        }
        
        for(String testCase : currResultsMap.keySet()) {
            if(!prevResultsMap.containsKey(testCase)) {
                if(currResultsMap.get(testCase).equalsIgnoreCase("in-progress")) {
                    inProgressTest = testCase;
                } else {
                    printMe(testCase, currResultsMap.get(testCase));
                    prevResultsMap.put(testCase, currResultsMap.get(testCase));
                }
            }
        }
        
        if(inProgressTest != null) {
            printMe(inProgressTest, currResultsMap.get(inProgressTest));
        }
    }
    
    private void printMe(String testCase, String status) {
        System.out.printf("|  %-80s   |", testCase);
        System.out.printf(" %-11s  |", status);
        System.out.println("");
    }
    
    /**
     * Read the json file and return the json file data as string
     * @param jsonFileName json file path that need to be read
     * @return jsonStringData json file data as string
     */
    private String readJsonFile(String jsonFileName) {
        String jsonStringData = null;
        try {
            jsonStringData = new String(Files.readAllBytes(Paths.get(jsonFileName)));
        } catch (IOException e) {
            System.out.println("Unable to read the file: " + jsonFileName);
            e.printStackTrace();
        }
        return jsonStringData;
    }
    
    /**
     * Process the jasmine results events json and compute the passed and failed test counts which would be consumed in the Listener class.
     * @param jsonFileName json file path that is created from report.html
     * @throws JSONException
     */
    private void processResultsJson(String jsonFileName) throws JSONException {
        File jsonFile = new File(jsonFileName);
        String resultsJson = null;
        if (jsonFile.exists()) {
            resultsJson = readJsonFile(jsonFileName);
        }
        if(resultsJson != null) {
            //evaluateTestResultStats(resultsJson);
        }
    }
    
    @Test
    public void runJasmineTests() throws InterruptedException, SecurityException, IOException, WebDriverException, JSONException {
        String resultsInfoJSON = null;
        try {
            setupDriver();
            driver.get(getApplicationUrl());
            /* Sleeping for 60 sec to let the application loaded in to the browser.
             * Currently few of application are taking more time to load so increased the time to 60sec.
             * But this will be re-visted to look for better logic.
             * */
            Thread.sleep(300000);
            checkFabricAppConfigForJasmineTest();
            checkTestExecutionStatus(getDownloadFilePath());
            resultsInfoJSON = getResultsInfoJSON();
        } catch (Exception e) {
            System.out.println("Exception occured while running the tests. Please check the browser console log (if created) for more details. " + e.getMessage());
            e.printStackTrace();
        } finally {
            captureBrowserConsoleLog();
            destroyDriver();
        }
        Assert.assertEquals(resultsInfoJSON != null, true);
    }
    
    @Override
    public void onTestStart(ITestResult result) {
    }

    @Override
    public void onTestSuccess(ITestResult result) {
    }

    @Override
    public void onTestFailure(ITestResult result) {
    }

    @Override
    public void onTestSkipped(ITestResult result) {
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
    }

    @Override
    public void onStart(ITestContext context) {
    }

    @Override
    public void onFinish(ITestContext context) {

        ITestNGMethod passedMethod = null;
        ITestNGMethod testNgMethod = null;
        Set<ITestResult> clonedResultSet = null;
        ITestResult testResultSet = null;
        
        /* This is required to initialize the value of global variable :totalTests, totalPassed, totalFailed 
         * to iterate and add their respective test results count for each passed and failed list.
         * */
        try {
            processResultsJson(getDownloadFilePath()+ File.separator + "report.json");
        } catch (JSONException e) {
            System.out.println("Unable to process the test results!");
        }
        
        Collection<ITestNGMethod> listOfPassedMethods = new ArrayList<ITestNGMethod>();
        listOfPassedMethods = context.getPassedTests().getAllMethods();
        
        if (listOfPassedMethods.size() > 0) {
            passedMethod = listOfPassedMethods.iterator().next();
            
            /*Create a copy of test result*/
            Set<ITestResult> testResults = context.getPassedTests().getResults(passedMethod);
            clonedResultSet = new HashSet<ITestResult>(testResults);
            testResultSet = clonedResultSet.iterator().next();
            
            /*Create a copy of test method*/
            testNgMethod = passedMethod.clone();
        }
        
        /*Remove the existing passed results of testNG*/
        for(ITestNGMethod pm : listOfPassedMethods) {
            context.getPassedTests().removeResult(pm);
        }
        
        /*Remove the existing failed test results of testNG*/
        Collection<ITestNGMethod> listOfFailedMethods = new ArrayList<ITestNGMethod>();
        listOfFailedMethods = context.getFailedTests().getAllMethods();
        for(ITestNGMethod fm : listOfFailedMethods) {
            context.getFailedTests().removeResult(fm);
        }
        
        /*Remove the existing skipped test results of testNG*/
        Collection<ITestNGMethod> listOfSkippedMethods = new ArrayList<ITestNGMethod>();
        listOfSkippedMethods = context.getSkippedTests().getAllMethods();
        for(ITestNGMethod sm : listOfSkippedMethods) {
            context.getSkippedTests().removeResult(sm);
        }
        
        if(testResultSet != null) {
            for(int i = 0; i < totalPassed ; i++) {
                ITestResult copiedPassedResult = new TestResult(
                        testResultSet.getTestClass(),
                        testResultSet.getInstance(),
                        testResultSet.getMethod(),
                        testResultSet.getThrowable(),
                        testResultSet.getStartMillis(),
                        testResultSet.getEndMillis(),
                        testResultSet.getTestContext());
                
                context.getPassedTests().addResult(copiedPassedResult, testNgMethod);
            }
            
            for (int i = 0; i < totalFailed ; i++) {
                ITestResult copiedFailedResult = new TestResult(
                        testResultSet.getTestClass(),
                        testResultSet.getInstance(),
                        testResultSet.getMethod(),
                        testResultSet.getThrowable(),
                        testResultSet.getStartMillis(),
                        testResultSet.getEndMillis(),
                        testResultSet.getTestContext());
                
                copiedFailedResult.setStatus(2);
                context.getFailedTests().addResult(copiedFailedResult, testNgMethod);
            }
        }
    }
}
