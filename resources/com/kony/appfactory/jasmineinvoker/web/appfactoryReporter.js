

// This is the appfactory specific reporting for results that are generated out of jasmine test execution. Above empty lines are 
// kept intentionally. If we make any changes (like event names, types, etc.) in this file, we should also verify the same in the kony-commons code.

var reporter = userReporter;
var jasmineEvents = [];
var specDurations = {};
var suiteDurations = {};

userReporter = {
    jasmineStarted: function(suiteInfo) {
        reporter.jasmineStarted(suiteInfo);
        var jasmineStarted = {};
        jasmineStarted.event = "jasmineStarted";
        jasmineStarted.result = suiteInfo;
        jasmineEvents.push(jasmineStarted);
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

        let tempResult = Object.assign({}, result);
        if(result.status === 'failed') {
            for (i = 0; i < tempResult.failedExpectations.length; i++) {
                //Nullifying screenshot information in appfactory related json.
                tempResult.failedExpectations[i].additionalDetails = null;
            }
        }
        kony.print('Temp Result is : ' + tempResult);
        kony.print('Result is : ' + result);
        specDone.result = tempResult;
        jasmineEvents.push(specDone);
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