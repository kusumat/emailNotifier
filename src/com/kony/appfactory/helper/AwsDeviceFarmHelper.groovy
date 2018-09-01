package com.kony.appfactory.helper

import groovy.json.JsonOutput
import java.net.URLDecoder
import java.text.SimpleDateFormat
import groovy.time.TimeCategory
import java.math.*;

/**
 * Implements Device Farm logic.
 */
class AwsDeviceFarmHelper implements Serializable {
    def script
    def testSummaryMap = [:]
    def durationMap = [:], testStartTimeMap = [:], testEndTimeMap = [:], mapWithTimeFormat = [:], runArnMap = [:]
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    AwsDeviceFarmHelper(script) {
        this.script = script
    }

    /**
     * Fetches artifact via provided URL. Checks if URL contains S3 bucket path, then simply fetch from S3.
     *
     * @param artifactName artifact name.
     * @param artifactUrl artifact URL.
     */
    protected final void fetchArtifact(String artifactName, String artifactUrl) {
        String successMessage = 'Artifact ' + artifactName + ' fetched successfully'
        String errorMessage = 'Failed to fetch artifact ' + artifactName
        artifactUrl = artifactUrl.replace(' ', '%20')
        script.catchErrorCustom(errorMessage, successMessage) {

            /* We need to check artifactUrl link is containing S3 bucket name that appfactory instance is pointing,
             * if so instead of downloading through https URL we can simply copy directly from S3.
             * This eliminates unnecessary burden of processing signed URLs for downloading artifact passed by Facade job.
             **/
            artifactUrl = (artifactUrl) ? (artifactUrl.contains(script.env.S3_BUCKET_NAME) ?
                                                artifactUrl.replaceAll('https://'+script.env.S3_BUCKET_NAME+'(.*)amazonaws.com',
                                                        's3://'+script.env.S3_BUCKET_NAME) : artifactUrl) : ''
            if (artifactUrl.startsWith('http://') || artifactUrl.startsWith('https://')) {
                script.shellCustom("curl -k -s -S -f -L -o \'${artifactName}\' \'${artifactUrl}\'", true)
            }
            else {
                /* copy from S3 bucket without printing expansion of command on console */
                String artifactUrlDecoded = URLDecoder.decode(artifactUrl, "UTF-8")
                String artifactNameDecoded = URLDecoder.decode(artifactName, "UTF-8")

                String cpS3Cmd="set +x;aws s3 cp \"${artifactUrlDecoded}\" \"${artifactNameDecoded}\" --only-show-errors"
                script.shellCustom(cpS3Cmd, true)
            }

        }
    }

    /**
     * Checks whether Device Farm project exists.
     *
     * @param name project name.
     * @return project ARN.
     */
    protected final String getProject(String name) {
        def projectArn
        String successMessage = 'Project ' + name + ' already exists!'
        String errorMessage = 'Failed to create project ' + name

        script.catchErrorCustom(errorMessage, successMessage) {
            String getProjectScript = "set +x;aws devicefarm list-projects --no-paginate --query \'projects[?name==" +
                    '`' + name + '`' + "]\'"
            String getProjectScriptOutput = script.shellCustom(getProjectScript, true, [returnStdout: true]).trim()
            def getProjectScriptOutputJSON = script.readJSON(text: getProjectScriptOutput)

            projectArn = (getProjectScriptOutputJSON.isEmpty()) ? null : getProjectScriptOutputJSON[0].arn
        }

        projectArn
    }

    /**
     * Creates project with provided name.
     *
     * @param name project name.
     * @return project ARN.
     */
    protected final createProject(String name) {
        def projectArn
        String successMessage = 'Project ' + name + ' created successfully'
        String errorMessage = 'Failed to create project ' + name

        script.catchErrorCustom(errorMessage, successMessage) {
            String createProjectScript = "set +x;aws devicefarm create-project --name \'" + name + "\'" +
                    ' --query project.arn'
            def createProjectOutput = script.shellCustom(createProjectScript, true, [returnStdout: true]).trim()

            projectArn = (createProjectOutput) ?: null
        }

        projectArn
    }

    /**
     * Parses list of provided devices.
     *
     * @param devicesList string with comma-separated devices.
     * @return list of device objects.
     */
    private parseDevicesList(devicesList) {
        def devices = []

        script.catchErrorCustom('Failed to parse devices list') {
            for (item in devicesList.tokenize(',')) {
                def deviceProperties = item.tokenize('*')
                devices.add([
                        formFactor  : "${deviceProperties[0].trim()}",
                        manufacturer: "${deviceProperties[2].trim()}",
                        model       : "${deviceProperties[3].trim()}",
                        os          : "${deviceProperties[4].trim()}",
                        platform    : "${deviceProperties[1].trim()}"
                ])
            }
        }

        devices
    }

    /**
     * Gets devices from predefined pool.
     *
     * @param configId pool name string.
     * @return string with comma-separated devices.
     */
    private getDevicesInPool(String configId) {
        def devices

        String successMessage = "Pool $configId found successfully"
        String errorMessage = "Failed to find $configId pool"

        script.catchErrorCustom(errorMessage, successMessage) {
            script.configFileProvider([script.configFile(fileId: "$configId", variable: 'DEVICES')]) {
                def getDevicesInPoolOutput = script.shellCustom('cat $DEVICES', true, [returnStdout: true]).trim()
                devices = parseDevicesList(getDevicesInPoolOutput)
            }
        }

        devices
    }

    /**
     * Gets device ARNs.
     *
     * @param selectedDevices list of device objects.
     * @return Map with two(phones and tables) items (pools).
     *         Each of them contains device ARNs.
     */
    protected final getDeviceArns(selectedDevices) {
        def deviceArns = [:]
        String errorMessage = 'Failed to find device ARNs'

        script.catchErrorCustom(errorMessage) {
            String getDeviceArnsScript = "set +x;aws devicefarm list-devices"
            String getDeviceArnsScriptOutput = script.shellCustom(
                    getDeviceArnsScript, true, [returnStdout: true]
            ).trim()
            def existingDevices = script.readJSON(text: getDeviceArnsScriptOutput).devices
            def phonesList = []
            def tabletsList = []
            def missingDevicesList = []

            for (selectedDevice in selectedDevices) {
                /* Flag for existing devices */
                def deviceExists = false

                /* Iterate over devices that been fetched from Device Farm */
                for (existingDevice in existingDevices) {
                    /* Filter the list of required for compare device properties */
                    def truncatedExistingDevice = existingDevice.subMap(selectedDevice.keySet() as List)

                    /* Check if all required properties are equal, which means that we have a matching device */
                    if (truncatedExistingDevice == selectedDevice) {
                        /* Set flag to mark device as existing */
                        deviceExists = true
                        /* Filter devices by form factor */
                        if (existingDevice.formFactor == 'PHONE') {
                            phonesList.add(existingDevice.arn)
                        } else {
                            tabletsList.add(existingDevice.arn)
                        }
                    }
                }

                /* If device doesn't exists, add it to missingDevicesList to display it in e-mail notification */
                if (!deviceExists) {
                    missingDevicesList.add("$selectedDevice.manufacturer $selectedDevice.model")
                }
            }

            /* Assigning collected devices to map properties */
            deviceArns.phones = phonesList
            deviceArns.tablets = tabletsList

            /*
                Exposing missing devices as env variable, to display them in e-mail notification,
                maybe it's a good idea to add missing devices as property to result map,
                because exposing them via env variables been done for jelly templates, which are now gone.
             */
            script.env['MISSING_DEVICES'] = missingDevicesList.join(', ')
        }

        deviceArns
    }

    /**
     * Creates device pools for provided project.
     *
     * @param projectArn project ARN.
     * @param devicePoolName predefined device pool name.
     * @return Map with two (phones and tables) items.
     *         Each of them contains device pool ARNs.
     */
    protected final createDevicePools(projectArn, devicePoolName) {
        def devicePoolArns = [:]
        def devicePoolJsons = [:]
        String successMessage = 'Device pools created successfully'
        String errorMessage = 'Failed to create device pools'
        def deviceNames = getDevicesInPool(devicePoolName)
        if(!deviceNames){
            throw new AppFactoryException('Device list is empty!','ERROR')
        }
        def deviceArns = getDeviceArns(deviceNames)
        if(!deviceArns){
            throw new AppFactoryException('Device ARNs list is empty!','ERROR')
        }

        script.catchErrorCustom(errorMessage, successMessage) {
            String generateSkeletonScript = "set +x;aws devicefarm create-device-pool --generate-cli-skeleton"
            String generateSkeletonScriptResult = script.shellCustom(
                    generateSkeletonScript, true, [returnStdout: true]
            ).trim()
            /* Generate a template(Skeleton) for device pool creation request */
            def devicePool = script.readJSON text: generateSkeletonScriptResult

            for (item in deviceArns) {
                /* If we have a list for devices for any of the form factors */
                if (item.value) {
                    /* Create a device pool object for creation request of the device pool */
                    devicePool.projectArn = projectArn
                    /*
                        Device pool name on Device Farm has following format:
                        <user_provided_pool_name>-[<job_build_number>-]<form_factor>
                     */
                    devicePool.name = [
                            "${devicePoolName?.replaceAll('\\s', '-')}",
                            (script.env.BUILD_NUMBER ?: ''),
                            ((item.key == 'phones') ? 'Phones-Device-Pool' : 'Tablets-Device-Pool')
                    ].findAll().join('-')
                    devicePool.rules[0].attribute = 'ARN'
                    /* Currently only this operator is working */
                    devicePool.rules[0].operator = 'IN'
                    /* Format list of the devices */
                    devicePool.rules[0].value = '[' + item.value.collect { '"' + it + '"' }.join(',') + ']'
                    /* Add device pool object to map (key=<name of the form factor, value=json string with devices) */
                    devicePoolJsons.put(item.key, JsonOutput.toJson(devicePool))
                }
            }

            /* Get device pool names and create it */
            def poolNames = devicePoolJsons.keySet().toArray()
            /* Workaround to iterate over map keys in c++ style for loop */
            for (int i = 0; i < poolNames.size(); ++i) {
                String createDevicePoolScript = 'set +x;aws devicefarm create-device-pool --cli-input-json' +
                        " '${devicePoolJsons.get(poolNames[i])}' --query devicePool.arn"
                String createDevicePoolScriptOutput = script.shellCustom(
                        createDevicePoolScript, true, [returnStdout: true]
                ).trim()

                /* Fetch arn of created device pool */
                devicePoolArns.put(poolNames[i], createDevicePoolScriptOutput)
            }
        }

        devicePoolArns
    }

    /**
     * Uploads provided artifact to Device Farm.
     *
     * @param projectArn project ARN.
     * @param uploadType Device Farm upload type.
     * @param uploadFileName upload file name.
     * @return upload ARN.
     */
    protected final uploadArtifact(String projectArn, String uploadType, String uploadFileName) {
        def uploadArn = null
        String successMessage = "Artifact ${uploadFileName} uploaded successfully"
        String errorMessage = "Failed to upload ${uploadFileName} artifact"

        script.catchErrorCustom(errorMessage, successMessage) {
            String createUploadScript = "set +x;aws devicefarm create-upload --project-arn ${projectArn}" +
                    " --name ${uploadFileName}" + " --type ${uploadType}"
            /*
                To bee able to upload artifacts to Device Farm we need create upload "request"
                for specific project and type of the upload first
             */
            String createUploadOutput = script.shellCustom(createUploadScript, true, [returnStdout: true]).trim()
            def createUploadJSON = script.readJSON text: createUploadOutput
            /* Get upload ARN to be able to track upload status */
            uploadArn = createUploadJSON.upload.arn
            String uploadUrl = createUploadJSON.upload.url
            String uploadScript = "curl -k -s -S -f -T \'${uploadFileName}\' \'${uploadUrl}\'"

            /* Upload artifact */
            script.shellCustom(uploadScript, true)

            /* Check status of upload */
            script.waitUntil {
                String getUploadScript = "set +x;aws devicefarm get-upload --arn ${uploadArn}"
                String getUploadOutput = script.shellCustom(getUploadScript, true, [returnStdout: true]).trim()
                def getUploadJSON = script.readJSON text: getUploadOutput
                String uploadStatus = getUploadJSON.upload.status
                String uploadMetadata = getUploadJSON.upload.metadata

                if (uploadStatus == 'FAILED') {
                    throw new AppFactoryException(uploadMetadata, 'ERROR')
                }

                uploadStatus == 'SUCCEEDED'
            }
        }

        uploadArn
    }

    /**
     * Schedule run with provided application and test binaries.
     *
     * @param projectArn project ARN.
     * @param devicePoolArn device pool ARN.
     * @param runType Device Farm run type.
     * @param uploadArtifactArn application binaries upload ARN.
     * @param testPackageArn test binaries upload ARN.
     * @return scheduled run ARN.
     */
    protected final scheduleRun(
            String projectArn, String devicePoolArn, String runType, String uploadArtifactArn, String testPackageArn, String artifactName
    ) {
        def runArn
        String getDevicePoolScript = "set +x;aws devicefarm get-device-pool --arn ${devicePoolArn}"
        String getDevicePoolOutput = script.shellCustom(getDevicePoolScript, true, [returnStdout: true]).trim()
        def getDevicePoolJSON = script.readJSON text: getDevicePoolOutput
        def list = getDevicePoolJSON.devicePool.rules[0].value.tokenize(",")
	 def isAndroidDevicePresentInPool = false, isiOSDevicePresentInPool = false
	    
        for(def i=0; i <list.size(); i++){
            def deviceArn = list[i].minus('[').minus(']')
            String getDeviceScript = "set +x;aws devicefarm get-device --arn ${deviceArn}"
            String getDeviceOutput = script.shellCustom(getDeviceScript, true, [returnStdout: true]).trim()
            def getDeviceJSON = script.readJSON text: getDeviceOutput
	
	     // The below line is used to make isAndroidDevicePresentInPool to true if pool has android device and vice versa for iOS devices	
	     (getDeviceJSON.device.platform.equalsIgnoreCase("Android")) ?(isAndroidDevicePresentInPool=true): (isiOSDevicePresentInPool = true)
        
            String successMessage = "Test run is scheduled successfully on \'" + getDeviceJSON.device.name + " " + getDeviceJSON.device.os + "\' device."
            String errorMessage = "Failed to schedule run on \'" + getDeviceJSON.device.name + " " + getDeviceJSON.device.os + "\' device."

            script.catchErrorCustom(errorMessage, successMessage) {
                String runScript = [
                        'set +x;aws devicefarm schedule-run',
                        "--project-arn ${projectArn}",
                        "--app-arn ${uploadArtifactArn}",
                        "--device-pool-arn ${devicePoolArn}",
                        "--test type=${runType},testPackageArn=${testPackageArn}",
                        "--query run.arn"
                ].join(' ')
                /* Schedule the run */
                runArn = script.shellCustom(runScript, true, [returnStdout: true]).trim() ?: null
            }
        }
        //Validate whether pool has android device if artifact is given for Android, else throw error and vice versa for iOS as well    
        if(artifactName.toLowerCase().contains('android') && !isAndroidDevicePresentInPool){
            throw new AppFactoryException("Artifacts provided for Android platform, but no android devices were found in the device pool", 'ERROR')
        }else if(artifactName.toLowerCase().contains('ios') && !isiOSDevicePresentInPool){
            throw new AppFactoryException("Artifacts provided for iOS platform, but no iOS devices were found in the device pool", 'ERROR')
        }
        runArn
    }

    /**
     * Queries Device Farm to get test run result.
     *
     * @param testRunArn scheduled run ARN.
     * @return string with run result, possible values: PENDING, PASSED, WARNED, FAILED, SKIPPED, ERRORED, STOPPED.
     */
    protected final getTestRunResult(testRunArn) {
        String testRunStatus, testRunResult
        def listJobsArrayList
        String successMessage = 'Test run results fetched successfully'
        String errorMessage = 'Failed to fetch test run results'
        def completedRunDevicesList = []
	 def index = 0
	    
        script.catchErrorCustom(errorMessage, successMessage) {
            script.waitUntil {
                String runResultScript = "set +x;aws devicefarm get-run --arn ${testRunArn}"
                String runResultOutput = script.shellCustom(runResultScript, true, [returnStdout: true]).trim()
                def runResultJSON = script.readJSON text: runResultOutput
                testRunStatus = runResultJSON.run.status
                testRunResult = runResultJSON.run.result

                String listJobsScript = "set +x;aws devicefarm list-jobs --arn ${testRunArn} --no-paginate"
                String listJobsOutput = script.shellCustom(listJobsScript, true, [returnStdout: true]).trim()
                def listJobsJSON = script.readJSON text: listJobsOutput

                if(listJobsJSON.jobs.size()){
                    for(def i=0; i< listJobsJSON.jobs.size(); i++){
                        listJobsArrayList = listJobsJSON.jobs[i]
                        runArnMap.put(listJobsArrayList.name + " " + listJobsArrayList.device.os, testRunArn)
			if(completedRunDevicesList.contains(listJobsArrayList.arn))
			    continue
                        switch (listJobsArrayList.status){
                            case 'PENDING':
                                Date startTime = new Date()
                                testStartTimeMap.put(listJobsArrayList.name + " " + listJobsArrayList.device.os, startTime.time)
                                script.echoCustom("Tests are initiated, execution is yet to start on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'PENDING_DEVICE':
			         script.echoCustom("Tests Execution is pending, trying to get the device \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'PROCESSING':
		    	         script.echoCustom("Processing the tests on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'SCHEDULING':
		    	         script.echoCustom("Scheduling the tests on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'PREPARING':
			         script.echoCustom("Preparing to run the tests on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'STOPPING':
			         script.echoCustom("Tests execution is being stopped on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
		                 break
		             case 'RUNNING':
		    	         script.echoCustom("Tests are running on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os 
		    	             + "\'... will fetch final results once the execution is completed.", 'INFO')
		                 break
		             case 'PENDING_CONCURRENCY':
		    	         script.echoCustom("Tests execution is in PENDING_CONCURRENCY state on \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
			         break
		             default:
			         break
		         }
		         switch (listJobsArrayList.result) {
			     case 'PASSED':
			         script.echoCustom("Test Execution is completed on \'"+ listJobsArrayList.name + " " + listJobsArrayList.device.os 
	                             + "\' and over all test result is PASSED", 'INFO')
                               createSummaryOfTestCases(listJobsArrayList, testSummaryMap, testStartTimeMap, testEndTimeMap, completedRunDevicesList, index)
			         break
			     case 'WARNED':
			         script.echoCustom("Build is warned for unknown reason on the device \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'WARN')
			         break
			     case 'SKIPPED':
			         script.echoCustom("Test Execution is skipped on the device \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os + "\'", 'INFO')
			         break
			     case 'STOPPED':
			     case 'ERRORED':
			     case 'FAILED':
			         script.echoCustom("Test Execution is completed. One/more tests are failed on the device \'" + listJobsArrayList.name + " " + listJobsArrayList.device.os 
			             + "\', please find more details of failed test cases in the summary email that you will receive at the end of this build completion.", 'ERROR', false)
			         createSummaryOfTestCases(listJobsArrayList, testSummaryMap, testStartTimeMap, testEndTimeMap, completedRunDevicesList, index)
			         break
			     default:
			         break
	                 }    
	                  
                     }
    		}   
		else
		    (testRunStatus == 'COMPLETED')?
		        script.echoCustom("No test jobs were found on one/more of the devices of device farm. Test run status is " + testRunStatus + " and test run result is " + testRunResult, 'ERROR', false):
		        script.echoCustom("No test jobs were found on one/more of the devices of device farm till now. Test run status is " + testRunStatus + " and test run result is " + testRunResult, 'INFO')
		
    		testRunStatus == 'COMPLETED'
            }
        }
	testRunResult
    }

    /**
     * Collect the details that are to be printed in console output at the end of tests execution such as duration, summary specific to each device.
     * @param listJobsArrayList List that is returned when we run list-jobs aws command
     * @param testSummaryMap Map used to collect the summary of test cases for each device
     * @param testEndTimeMap Map used to collect the time at which the tests execution is completed for each device
     * @param testStartTimeMap Map used to collect the time at which the tests execution is started for each device
     * @param completedRunDevicesList This holds the list of devices for which the tests execution is completed
     * @param index This is the index for the list completedRunDevicesList
     * */
    protected void createSummaryOfTestCases(def listJobsArrayList, def testSummaryMap, def testStartTimeMap, def testEndTimeMap, def completedRunDevicesList, def index){
        def keys = listJobsArrayList.counters.keySet()
        def counterValues = ""
        for(int j = 0; j < keys.size()-1; j++){
            counterValues += keys[j] + ": " + listJobsArrayList.counters.get(keys[j]) + " "
        }
        counterValues += "total tests: " + listJobsArrayList.counters.get('total')
        testSummaryMap.put(listJobsArrayList.name + " " + listJobsArrayList.device.os, counterValues)
        Date endTime = new Date()
        testEndTimeMap.put(listJobsArrayList.name + " " + listJobsArrayList.device.os, endTime.time)
        use(groovy.time.TimeCategory) {
            Long timeDifference = testEndTimeMap[listJobsArrayList.name + " " + listJobsArrayList.device.os] - testStartTimeMap[listJobsArrayList.name + " " + listJobsArrayList.device.os]
            mapWithTimeFormat.putAll(changeTimeFormat(timeDifference))
            def value = ""
            if(mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP))
                value+=mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP) + " hrs "
            if(mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP))
                value+=mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP)  + " mins "
            if(mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP))
                value+=mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP) + " secs "
            durationMap.put(listJobsArrayList.name + " " + listJobsArrayList.device.os, value)
        }
        completedRunDevicesList[index] = listJobsArrayList.arn
        index++
    }

    /**
     * Converts the given time difference into hours, minutes, seconds
     * @param Time difference between two times
     * @return a map with a specific time format
     * */
    protected Map changeTimeFormat(Long timeDifference){
        Map mapWithTimeFormat =[:]
        timeDifference = timeDifference / 1000
        mapWithTimeFormat.seconds = timeDifference.remainder(60)
        timeDifference = (timeDifference - mapWithTimeFormat.seconds) / 60
        mapWithTimeFormat.minutes = timeDifference.remainder(60)
        timeDifference = (timeDifference - mapWithTimeFormat.minutes) / 60
        mapWithTimeFormat.hours = timeDifference.remainder(24)
        return mapWithTimeFormat
    }


    /**
     * Queries Device Farm to get test run artifacts.
     *
     * @param arn ARN of the object that needs to be queried.
     * @return Array of Maps with specific structure.
     */
    protected final getTestRunArtifacts(String arn) {
        def queryParameters = [:]
        def resultStructure = []

        /* Checking ARN to get the type of the run object, depending on the object type get required properties */
        switch (arn) {
            case ~/^.*run.*$/:
                queryParameters = [
                        queryScript                : "set +x;aws devicefarm list-jobs --arn ${arn} --no-paginate",
                        queryProperty              : 'jobs',
                        resultStructure            : ['result', 'device', 'totalSuites'],
                        resultStructureNextProperty: 'suites'
                ]
                break
            case ~/^.*job.*$/:
                queryParameters = [
                        queryScript                : "set +x;aws devicefarm list-suites --arn ${arn} --no-paginate",
                        queryProperty              : 'suites',
                        resultStructure            : ['name', 'totalTests'],
                        resultStructureNextProperty: 'tests'
                ]
                break
            case ~/^.*suite.*$/:
                queryParameters = [
                        queryScript                : "set +x;aws devicefarm list-tests --arn ${arn} --no-paginate",
                        queryProperty              : 'tests',
                        resultStructure            : ['name', 'result'],
                        resultStructureNextProperty: 'artifacts'
                ]
                break
            case ~/^.*test.*$/:
                queryParameters = [
                        queryScript    : "set +x;aws devicefarm list-artifacts --arn ${arn} --no-paginate --type FILE",
                        queryProperty  : 'artifacts',
                        resultStructure: ['name', 'url', 'extension']
                ]
                break
            default:
                break
        }
        /* If we have an ARN that match our requirements */
        if (queryParameters) {
            /* Convert command JSON string result to map */
            def queryOutput = script.readJSON text: script.shellCustom(
                    queryParameters.queryScript, true, [returnStdout: true]).trim()

            /* If we do have result */
            if (queryOutput) {
                def queryProperty = queryParameters.queryProperty
                def nextProperty = queryParameters.resultStructureNextProperty

                /* Check if result map has required properties */
                if (queryOutput.containsKey(queryProperty)) {
                    /* Iterate over required properties, filter and get values */
                    for (item in queryOutput[queryProperty]) {
                        def resultStructureProperties = queryParameters.resultStructure
                        def queryResultStructure = [:]

                        /* Main loop for filtering and fetching values from result map */
                        for (property in resultStructureProperties) {
                            def keyValue

                            switch (property) {
                                case 'totalSuites':
                                    keyValue = (item.counters.total) ?: ''
                                    break
                                case 'device':
                                    keyValue = ([formFactor: item.device.formFactor,
                                                 name      : item.device.name,
                                                 os        : item.device.os,
                                                 platform  : item.device.platform]) ?: ''
                                    break
                                case 'totalTests':
                                    keyValue = (item.counters.total) ?: ''
                                    break
                                default:
                                    keyValue = item[property]
                                    break
                            }

                            queryResultStructure[property] = keyValue
                        }

                        /*
                            If we do have nextProperty in result map,
                            call this method recursively with updated arguments
                         */
                        if (nextProperty) {
                            queryResultStructure.get(nextProperty, getTestRunArtifacts(item.arn))
                        }

                        /* Accumulate generated from result map structures */
                        resultStructure.add(queryResultStructure)
                    }
                } else {
                    script.echoCustom("Failed to find query property!",'WARN')
                }
            } else {
                script.echoCustom("Failed to query Device Farm!",'WARN')
            }
        }

        resultStructure
    }

    /**
     * Move test run artifacts to customer S3 bucket.
     *
     * @param runResultArtifacts Map with run result artifacts.
     * @param bucketName S3 bucket name.
     * @param bucketRegion S3 bucket region.
     * @param s3BasePath base path in S3 bucket.
     * @param artifactFolder path to artifact in workspace.
     */
    protected final moveArtifactsToCustomerS3Bucket(runResultArtifacts, s3BasePath) {
        String successMessage = 'Artifacts moved successfully'
        String errorMessage = 'Failed to move artifacts'

        script.catchErrorCustom(errorMessage, successMessage) {
            for (runArtifact in runResultArtifacts) {
                for (suite in runArtifact.suites) {
                    for (test in suite.tests) {
                        def resultPath = [s3BasePath]
                        resultPath.add(runArtifact.device.formFactor)
                        resultPath.add(runArtifact.device.name + '_' + runArtifact.device.platform + '_' +
                                runArtifact.device.os)
                        resultPath.add(suite.name)
                        resultPath.add(test.name)
                        for (artifact in test.artifacts) {
                            /* Replace all spaces with underscores in S3 path */
                            def s3path = resultPath.join('/').replaceAll('\\s', '_')
                            /* Replace all spaces with underscores in artifact name */
                            def artifactFullName = (artifact.name + '.' + artifact.extension).replaceAll('\\s', '_')

                            /* Fetch run artifact */
                            fetchArtifact(artifactFullName, artifact.url)
                            /* Publish to S3 and update run artifact URL */
                            artifact.url = AwsHelper.publishToS3 bucketPath: s3path, sourceFileName: artifactFullName,
                                    sourceFilePath: script.pwd(), script
                            artifact.authurl = BuildHelper.createAuthUrl(artifact.url, script, true)
                        }
                    }
                }
            }
        }

        runResultArtifacts
    }

    /**
     * Delete uploaded artifact by ARN.
     *
     * @param artifactArn artifact ARN.
     */
    protected final void deleteUploadedArtifact(String artifactArn) {
        script.catchErrorCustom('Failed to delete artifact') {
            script.shellCustom("set +x;aws devicefarm delete-upload --arn ${artifactArn}", true)
        }
    }

    /**
     * Delete device pool by ARN.
     *
     * @param devicePoolArn device pool ARN.
     */
    protected final void deleteDevicePool(devicePoolArn) {
        script.catchErrorCustom('Failed to delete device pool') {
            script.shellCustom("set +x;aws devicefarm delete-device-pool --arn ${devicePoolArn}", true)
        }
    }
}
