

// This is the appfactory specific reporting. Above empty lines are kept intentionally. If we make any 
// changes (like event names, types, etc.) in this file, we should also verify the same in the kony-commons code.

var reporter = userReporter;
var jasmineEvents = [];
var specDurations = {};
var suiteDurations = {};

var writeJSONFile = function(fileLoc) {
	if (new kony.io.File(fileLoc).exists()) {
        kony.print("Results JSON file already exists!!!");
    } else {
        var myFile = new kony.io.File(fileLoc).createFile();
    }
    try {
        var writing = new kony.io.File(fileLoc).write(JSON.stringify(jasmineEvents));
        if (writing != null) {
            kony.print("Wrote the Jasmine Events into JSON File.");
        } else {
            kony.print("Failed to write the Jasmine events into JSON File.");
        }
    } catch (err) {
        kony.print("Exception occurred while trying to write the jasmine events into JSON File.");
    }
};

var writeJasmineEventsToFile = function() {
    var platform = (kony.os.deviceInfo().name).toLowerCase();
    var fileLoc;
    if(platform == "android") {
        var isExternalStorageAvailable = kony.io.FileSystem.isExternalStorageAvailable();
        if (isExternalStorageAvailable) {
            var jasmineResultsFolder = "/sdcard" + constants.FILE_PATH_SEPARATOR + "JasmineTestResults";
	        if (new kony.io.File(jasmineResultsFolder).exists()) {
	            kony.print("Results folder already exists.");
	        } else {
	            kony.print("Results folder doesn't exists. Creating one now.....");
	            var myDir = new kony.io.File(jasmineResultsFolder).createDirectory();
	        }
	        fileLoc = jasmineResultsFolder + constants.FILE_PATH_SEPARATOR + "jasmineReport.json";
        }
    } else {
    	var dataDirectoryPath = kony.io.FileSystem.getDataDirectoryPath();
    	if (dataDirectoryPath) {
            fileLoc = dataDirectoryPath + constants.FILE_PATH_SEPARATOR + "JasmineTestResults/jasmineReport.json";
    	}
    }
    kony.print("Writing the JSON Results into - " + fileLoc + ", On Platform - " + platform);
    writeJSONFile(fileLoc);
};

userReporter = {
    jasmineStarted: function(suiteInfo) {
        reporter.jasmineStarted(suiteInfo);
        var jasmineStarted = {};
        jasmineStarted.event = "jasmineStarted";
        suiteInfo.order = "Empty"
        jasmineStarted.result = suiteInfo;
        jasmineEvents.push(jasmineStarted);
        writeJasmineEventsToFile();
    },
    
    suiteStarted: function(result) {
      reporter.suiteStarted(result);
        var suiteStarted = {};
        suiteStarted.event = "suiteStarted";
        suiteDurations[result.id] = (new Date()).toISOString();
        suiteStarted.result = result;
        jasmineEvents.push(suiteStarted);
    },
    
    specStarted:function (result) {
        reporter.specStarted(result);
        var specStarted = {};
        specStarted.event = "specStarted";
        specDurations[result.id] = (new Date()).toISOString();
        specStarted.result = result;
        jasmineEvents.push(specStarted);
    },
    
    specDone: function(result) {
        reporter.specDone(result);
        var specDone = {};
        specDone.event = "specDone";
        result.duration = new Date((new Date()).toISOString()) - new Date(specDurations[result.id]);
        specDone.result = result;
        jasmineEvents.push(specDone);
        writeJasmineEventsToFile();
    },
 
    suiteDone: function(result) {
        reporter.suiteDone(result);
        var suiteDone = {};
        suiteDone.event = "suiteDone";
        result.duration = new Date((new Date()).toISOString()) - new Date(suiteDurations[result.id]);
        suiteDone.result = result;
        jasmineEvents.push(suiteDone);
    },
 
    jasmineDone: function() {
        var jasmineDone = {};
        jasmineDone.event = "jasmineDone";
        jasmineEvents.push(jasmineDone);
        reporter.jasmineDone();
    }
 };