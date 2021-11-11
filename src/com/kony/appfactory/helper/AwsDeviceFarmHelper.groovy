package com.kony.appfactory.helper

import groovy.json.JsonOutput
import groovy.json.JsonBuilder
import java.text.SimpleDateFormat
import groovy.time.TimeCategory
import java.math.*;

import com.kony.appfactory.dto.tests.DetailedNativeResults
import com.kony.appfactory.dto.tests.ResultsCount
import com.kony.appfactory.dto.tests.Device
import com.kony.appfactory.helper.BuildHelper
import com.kony.appfactory.helper.ArtifactHelper

/**
 * Implements Device Farm logic.
 */
class AwsDeviceFarmHelper implements Serializable {
    def script
    def testStartTimeMap = [:], mapWithTimeFormat = [:]
    public def testExecutionStartTimeMap = [:]

    protected libraryProperties
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    AwsDeviceFarmHelper(script) {
        this.script = script
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
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
    protected getDevicesInPool(String configId) {
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
        def androidDeviceArns = [:]
        def iOSDeviceArns = [:]
        String errorMessage = 'Failed to find device ARNs'

        script.catchErrorCustom(errorMessage) {
            String getDeviceArnsScript = "set +x;aws devicefarm list-devices"
            String getDeviceArnsScriptOutput = script.shellCustom(
                    getDeviceArnsScript, true, [returnStdout: true]
            ).trim()
            def existingDevices = script.readJSON(text: getDeviceArnsScriptOutput).devices
            def androidPhonesList = []
            def androidTabletsList = []
            def iOSPhonesList = []
            def iOSTabletsList = []
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
                        if (existingDevice.platform == 'ANDROID') {
                            /* Filter devices by form factor */
                            if (existingDevice.formFactor == 'PHONE') {
                                androidPhonesList.add(existingDevice.arn)
                            } else {
                                androidTabletsList.add(existingDevice.arn)
                            }
                        } else {
                            /* Filter devices by form factor */
                            if (existingDevice.formFactor == 'PHONE') {
                                iOSPhonesList.add(existingDevice.arn)
                            } else {
                                iOSTabletsList.add(existingDevice.arn)
                            }
                        }
                    }
                }

                /* If device doesn't exists, add it to missingDevicesList to display it in e-mail notification */
                if (!deviceExists) {
                    missingDevicesList.add("$selectedDevice.manufacturer $selectedDevice.model")
                }
            }

            /* Assigning collected devices to map properties */
            androidDeviceArns.mobile = androidPhonesList
            androidDeviceArns.tablet = androidTabletsList
            iOSDeviceArns.mobile = iOSPhonesList
            iOSDeviceArns.tablet = iOSTabletsList

            /*
                Exposing missing devices as env variable, to display them in e-mail notification,
                maybe it's a good idea to add missing devices as property to result map,
                because exposing them via env variables been done for jelly templates, which are now gone.
             */
            script.env['MISSING_DEVICES'] = missingDevicesList.join(', ')
        }

        [android : androidDeviceArns, ios : iOSDeviceArns]
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

            /* Removing the maxDevices key which is got appended by the cli */
            if(devicePool.containsKey("maxDevices"))
                devicePool.remove("maxDevices")

            for (platform in deviceArns) {
                platform.value.each { formFactor, listOfDevices -> 
                    /* If we have a list for devices for any of the form factors */
                    if (listOfDevices) {
                        /* Create a device pool object for creation request of the device pool */
                        devicePool.projectArn = projectArn
                            /*
                        Device pool name on Device Farm has following format:
                        <user_provided_pool_name>-[<job_build_number>-]<form_factor>
                             */
                        devicePool.name = [
                                           "${devicePoolName?.replaceAll('\\s', '-')}",
                                           platform.key,
                                           (script.env.BUILD_NUMBER ?: ''),
                                           ((formFactor == 'mobile') ? 'Phones-Device-Pool' : 'Tablets-Device-Pool')
                                           ].findAll().join('-')
                        devicePool.rules[0].attribute = 'ARN'
                        /* Currently only this operator is working */
                        devicePool.rules[0].operator = 'IN'
                        /* Format list of the devices */
                        devicePool.rules[0].value = '[' + listOfDevices.collect { '"' + it + '"' }.join(',') + ']'
                        /* Add device pool object to map (key=<name of the form factor, value=json string with devices) */
                        devicePoolJsons.put(platform.key + '_' + formFactor, JsonOutput.toJson(devicePool))
                    }
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
        def uploadData = null
        def uploadArn = null
        def uploadAppData = [:]
        String successMessage = "Artifact ${uploadFileName} uploaded successfully."
        String errorMessage = "Failed to upload ${uploadFileName} artifact."
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

            def getUploadJSON
            String uploadStatus
            String uploadMetadata
            /* Check status of upload */
            script.waitUntil {
                String getUploadScript = "set +x;aws devicefarm get-upload --arn ${uploadArn}"
                String getUploadOutput = script.shellCustom(getUploadScript, true, [returnStdout: true]).trim()
                getUploadJSON = script.readJSON text: getUploadOutput
                uploadStatus = getUploadJSON.upload.status
                uploadMetadata = getUploadJSON.upload.metadata
                if (uploadStatus == 'FAILED') {
                    throw new AppFactoryException(uploadMetadata, 'ERROR')
                }

                uploadStatus == 'SUCCEEDED'
            }
            if ((uploadType.equalsIgnoreCase("android_app") || uploadType.equalsIgnoreCase("ios_app")) && uploadStatus.equalsIgnoreCase("succeeded")) {
                def uploadMetaDataJSON = script.readJSON text: uploadMetadata
                try {
                    uploadAppData.putAll(["name": getUploadJSON.upload.name, "arn": getUploadJSON.upload.arn, "type": getUploadJSON.upload.type, "packageName": uploadMetaDataJSON."package_name"])
                    script.echoCustom("Fetched App metadata successfully.")
                } catch (Exception e) {
                    throw AppFactoryException("Unable to fetch app metadata" + e.printStackTrace(), 'ERROR')
                }

            }
        }
        uploadData = uploadAppData?:uploadArn
        uploadData
    }

    /**
     * Schedule run with provided application and test binaries.
     *
     * @param projectArn project ARN.
     * @param devicePoolArnOrSelectionConfig device pool ARN/SelectionConfig.
     * @param runType Device Farm run type.
     * @param uploadArtifactArn application binaries upload ARN.
     * @param testPackageArn test binaries upload ARN.
     * @param testSpecArn test spec upload ARN (if custom test environment).
     * @param isPoolWithDeviceFarmFilters flag
     * @return scheduled run ARN.
     */
    protected final scheduleRun(
            String projectArn, String devicePoolArnOrSelectionConfig, String runType, String uploadArtifactArn, String testPackageArn, String artifactName, String testSpecArn = null, String extraDataPkgArn = null, boolean isPoolWithDeviceFarmFilters
    ) {
        def runArn
        String successMessage = "Test run is scheduled successfully on available device "
        String errorMessage = "Failed to schedule run on any device"

        if (!isPoolWithDeviceFarmFilters) {
            String getDevicePoolScript = "set +x;aws devicefarm get-device-pool --arn ${devicePoolArnOrSelectionConfig}"
            String getDevicePoolOutput = script.shellCustom(getDevicePoolScript, true, [returnStdout: true]).trim()
            def getDevicePoolJSON = script.readJSON text: getDevicePoolOutput
            def list = getDevicePoolJSON.devicePool.rules[0].value.tokenize(",")
            def isAndroidDevicePresentInPool = false, isiOSDevicePresentInPool = false
            for (def i = 0; i < list.size(); i++) {
                def deviceArn = list[i].minus('[').minus(']')
                String getDeviceScript = "set +x;aws devicefarm get-device --arn ${deviceArn}"
                String getDeviceOutput = script.shellCustom(getDeviceScript, true, [returnStdout: true]).trim()
                def getDeviceJSON = script.readJSON text: getDeviceOutput

                // The below line is used to make isAndroidDevicePresentInPool to true if pool has android device and vice versa for iOS devices
                (getDeviceJSON.device.platform.equalsIgnoreCase("Android")) ? (isAndroidDevicePresentInPool = true) : (isiOSDevicePresentInPool = true)

                def deviceKey = getDeviceJSON.device.name + ' ' + getDeviceJSON.device.os
                def deviceDisplayName = getDeviceJSON.device.name + ' OS ' + getDeviceJSON.device.os
                successMessage = "Test run is scheduled successfully on \'" + deviceDisplayName + "\' device."
                errorMessage = "Failed to schedule run on \'" + deviceDisplayName + "\' device."

                script.catchErrorCustom(errorMessage, successMessage) {
                    String runScript = [
                            'set +x;aws devicefarm schedule-run',
                            "--project-arn ${projectArn}",
                            "--app-arn ${uploadArtifactArn}",
                            "--device-pool-arn ${devicePoolArnOrSelectionConfig}",
                            "--test type=${runType},testPackageArn=${testPackageArn}" + (testSpecArn ? ",testSpecArn=${testSpecArn}" : ""),
                            (extraDataPkgArn ? "--configuration extraDataPackageArn=${extraDataPkgArn}" : ""),
                            "--query run.arn"
                    ].join(' ')

                    /* Schedule the run */
                    runArn = script.shellCustom(runScript, true, [returnStdout: true]).trim() ?: null
                }
            }
            //Validate whether pool has android device if artifact is given for Android, else throw error and vice versa for iOS as well
            if (artifactName.toLowerCase().contains('android') && !isAndroidDevicePresentInPool) {
                throw new AppFactoryException("Artifacts provided for Android platform, but no android devices were found in the device pool", 'ERROR')
            } else if (artifactName.toLowerCase().contains('ios') && !isiOSDevicePresentInPool) {
                throw new AppFactoryException("Artifacts provided for iOS platform, but no iOS devices were found in the device pool", 'ERROR')
            }
        } else {
            def devicePoolArnOrSelectionConfigJson = new groovy.json.JsonSlurperClassic().parseText(devicePoolArnOrSelectionConfig)
            devicePoolArnOrSelectionConfig = new JsonBuilder(devicePoolArnOrSelectionConfigJson).toPrettyString()
            script.catchErrorCustom(errorMessage, successMessage) {
                String runScript = [
                        'set +x;aws devicefarm schedule-run',
                        "--project-arn ${projectArn}",
                        "--app-arn ${uploadArtifactArn}",
                        "--device-selection-configuration \'${devicePoolArnOrSelectionConfig}\'",
                        "--test type=${runType},testPackageArn=${testPackageArn}" + (testSpecArn ? ",testSpecArn=${testSpecArn}" : ""),
                        (extraDataPkgArn ? "--configuration extraDataPackageArn=${extraDataPkgArn}" : ""),
                        "--query run.arn"
                ].join(' ')

                /* Schedule the run */
                runArn = script.shellCustom(runScript, true, [returnStdout: true]).trim() ?: null
            }
        }

        runArn
    }

    /**
     * Queries Device Farm to get test run result.
     *
     * @param testRunArn scheduled run ARN.
     * @return string with run result, possible values: PENDING, PASSED, WARNED, FAILED, SKIPPED, ERRORED, STOPPED.
     */
    protected final getTestRunResult(testRunArn, results) {
        String testRunStatus, testRunResult
        def listJobsArrayList
        String successMessage = 'Test run results fetched successfully'
        String errorMessage = 'Failed to fetch test run results'
        def completedRunDevicesList = []
        def index = 0

        script.catchErrorCustom(errorMessage, successMessage) {
            script.waitUntil {
                try {
                    String runResultScript = "set +x;aws devicefarm get-run --arn ${testRunArn}"
                    String runResultOutput = script.shellCustom(runResultScript, true, [returnStdout: true]).trim()
                    def runResultJSON = script.readJSON text: runResultOutput
                    testRunStatus = runResultJSON.run.status
                    testRunResult = runResultJSON.run.result
                }
                catch(Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                    script.echoCustom(exceptionMessage + "\nFailed to fetch information about the run, retrying..", 'WARN', false)
                    return false
                }

                def listJobsJSON

                try {
                    String listJobsScript = "set +x;aws devicefarm list-jobs --arn ${testRunArn} --no-paginate"
                    String listJobsOutput = script.shellCustom(listJobsScript, true, [returnStdout: true]).trim()
                    listJobsJSON = script.readJSON text: listJobsOutput
                }
                catch(Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                    script.echoCustom(exceptionMessage + "\nFailed to fetch information about jobs for the run, retrying..", 'WARN', false)
                    return false
                }

                if(listJobsJSON.jobs.size()){
                    for(def i=0; i< listJobsJSON.jobs.size(); i++){
                        DetailedNativeResults result = new DetailedNativeResults()
                        listJobsArrayList = listJobsJSON.jobs[i]
                        String deviceKey = listJobsArrayList.name + " " + listJobsArrayList.device.os

                        //If the run is already completed on particular device with specific ARN, then continue.
                        if(completedRunDevicesList.contains(listJobsArrayList.arn))
                            continue
                        Date startTime = new Date()
                        if (!testStartTimeMap.containsKey(deviceKey))
                            testStartTimeMap.put(deviceKey, startTime.time)

                        switch (listJobsArrayList.status){
                            case 'PENDING':
                                script.echoCustom("Tests are initiated, execution is yet to start on \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'PENDING_DEVICE':
                                script.echoCustom("Tests Execution is pending, trying to get the device \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'PROCESSING':
                                script.echoCustom("Processing the tests on \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'SCHEDULING':
                                script.echoCustom("Scheduling the tests on \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'PREPARING':
                                script.echoCustom("Preparing to run the tests on \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'STOPPING':
                                script.echoCustom("Tests execution is being stopped on \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'RUNNING':
                                if(! testExecutionStartTimeMap.containsKey(deviceKey))
                                    testExecutionStartTimeMap.put(deviceKey, new Date())
                                script.echoCustom("Tests are running on \'" + deviceKey
                                        + "\'... will fetch final results once the execution is completed.", 'INFO')
                                break
                            case 'PENDING_CONCURRENCY':
                                script.echoCustom("Tests execution is in PENDING_CONCURRENCY state on \'" + deviceKey + "\'", 'INFO')
                                break
                            default:
                                break
                        }
                        validateRunWithDeviceFarmTimeLimitAndDisplay(deviceKey)
                        switch (listJobsArrayList.result) {
                            case 'PASSED':
                                script.echoCustom("Test Execution is completed on \'"+ deviceKey
                                        + "\' and over all test result is PASSED", 'INFO')
                                result = createSummaryOfTestCases(listJobsArrayList, completedRunDevicesList, index)
                                showDeviceTestRunResults(result, testRunArn)
                                break
                            case 'WARNED':
                                script.echoCustom("Build is warned for unknown reason on the device \'" + deviceKey + "\'", 'WARN')
                                break
                            case 'SKIPPED':
                                script.echoCustom("Test Execution is skipped on the device \'" + deviceKey + "\'", 'INFO')
                                break
                            case 'ERRORED':
                                script.echoCustom("Looks like your tests failed with an ERRORED message, it usually happens due to some network issue on AWS device or issue with instance itself. Re-triggering the build might solve the issue.", 'WARN')
                            case 'STOPPED':
                            case 'FAILED':
                                script.echoCustom("Test Execution is completed on \'" + deviceKey
                                        + "\' with one or more tests failures. Please find more details of failed test cases in the summary email that you will receive at the end of this build completion.", 'ERROR', false)
                                script.currentBuild.result = 'UNSTABLE'
                                result = createSummaryOfTestCases(listJobsArrayList, completedRunDevicesList, index)
                                showDeviceTestRunResults(result, testRunArn)
                                break
                            default:
                                break
                        }
                        result.setTestStatus(listJobsArrayList.result)
                        results.put(deviceKey, result)
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
     * @param completedRunDevicesList This holds the list of devices for which the tests execution is completed
     * @param index This is the index for the list completedRunDevicesList
     * */
    @NonCPS
    protected DetailedNativeResults createSummaryOfTestCases(def listJobsArrayList, def completedRunDevicesList, def index){
        DetailedNativeResults results = new DetailedNativeResults()
        Long timeDifference = 0
        Date endTime = new Date()
        def deviceKey = listJobsArrayList.name + " " + listJobsArrayList.device.os

        if(testStartTimeMap.containsKey(deviceKey)) {
            use(groovy.time.TimeCategory) {
                timeDifference = endTime.time - testStartTimeMap[deviceKey]
            }
        }
        results.setTestDuration(timeDifference)

        Device device = new Device(listJobsArrayList.name, listJobsArrayList.device.os, listJobsArrayList.device.formFactor, listJobsArrayList.device.platform)
        results.setDevice(device)
        results.setDeviceMinutes(listJobsArrayList.deviceMinutes.get('total'))
        ResultsCount counts = new ResultsCount(listJobsArrayList.counters.get('total'),
            listJobsArrayList.counters.get('passed'),
            listJobsArrayList.counters.get('failed'),
            listJobsArrayList.counters.get('skipped'),
            listJobsArrayList.counters.get('warned'),
            listJobsArrayList.counters.get('stopped'),
            listJobsArrayList.counters.get('errored'))
        results.setResultsCount(counts)
        completedRunDevicesList[index] = listJobsArrayList.arn
        index++
        return results
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
                        queryScript                : ["set +x;aws devicefarm list-jobs --arn ${arn} --no-paginate"],
                        queryProperty              : 'jobs',
                        resultStructure            : ['result', 'device', 'totalSuites'],
                        resultStructureNextProperty: 'suites'
                ]
                break
            case ~/^.*job.*$/:
                queryParameters = [
                        queryScript                : ["set +x;aws devicefarm list-suites --arn ${arn} --no-paginate"],
                        queryProperty              : 'suites',
                        resultStructure            : ['name', 'totalTests'],
                        resultStructureNextProperty: 'tests'
                ]
                break
            case ~/^.*suite.*$/:
                queryParameters = [
                        queryScript                : ["set +x;aws devicefarm list-tests --arn ${arn} --no-paginate"],
                        queryProperty              : 'tests',
                        resultStructure            : ['name', 'result'],
                        resultStructureNextProperty: 'artifacts'
                ]
                break
            case ~/^.*test.*$/:
                queryParameters = [
                        queryScript    : ["set +x;aws devicefarm list-artifacts --arn ${arn} --no-paginate --type FILE", "set +x;aws devicefarm list-artifacts --arn ${arn} --no-paginate --type SCREENSHOT"],
                        queryProperty  : 'artifacts',
                        resultStructure: ['name', 'url', 'extension']
                ]
                break
            default:
                break
        }
        /* If we have an ARN that match our requirements */
        if (queryParameters) {
            def queryOutput = []

            for(def i=0; i<queryParameters.queryScript.size(); i++) {
                /* Convert command JSON string result to map */
                try {
                    queryOutput[i] = script.readJSON text: script.shellCustom(
                            queryParameters.queryScript[i], true, [returnStdout: true]).trim()
                }
                catch (Exception e) {
                    String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                    script.echoCustom(exceptionMessage + "\nFailed to fetch test run artifact." + queryParameters.queryScript[i], 'WARN', false)
                    continue
                }


                /* If we do have result */
                (queryOutput[i]) ? (resultStructure.addAll(parseQueryOutput(queryOutput[i], queryParameters))) : script.echoCustom("Failed to query Device Farm!", 'WARN')

            }
        }
        resultStructure
    }

    /**
     * Parse the output of AWS command and form the result structure so that this can be used to display artifacts in emails.
     *
     * @param queryOutput Contains the output of AWS command as a Map.
     * @param queryParameters Map that contains the AWS command and the required fields.
     * @return finalQueryResult The result that is obtained once the query execution is completed.
     */
    protected def parseQueryOutput(queryOutput, queryParameters) {

        def queryProperty = queryParameters.queryProperty
        def nextProperty = queryParameters.resultStructureNextProperty

        /* Check if result map has required properties */
        if (queryOutput.containsKey(queryProperty)) {
            def finalQueryResult = []
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
                finalQueryResult.addAll(queryResultStructure)
            }
            finalQueryResult
        } else {
            script.echoCustom("Failed to find query property!",'WARN')
        }
    }

    /**
     * Move test run artifacts to artifactStorage (S3 or Master or other).
     *
     * @param runResultArtifacts Map with run result artifacts.
     * @param destinationBasePath base path in artifactStorage.
     */
    protected final publishDeviceFarmTestRunResults(runResultArtifacts, destinationBasePath) {
        String successMessage = 'Artifacts moved successfully'
        String errorMessage = 'Failed to move artifacts'

        script.catchErrorCustom(errorMessage, successMessage) {
            for (runArtifact in runResultArtifacts) {
                for (suite in runArtifact.suites) {
                    for (test in suite.tests) {
                        def resultPath = [destinationBasePath]
                        resultPath.add(runArtifact.device.formFactor)
                        resultPath.add(runArtifact.device.name + '_' + runArtifact.device.platform + '_' +
                                runArtifact.device.os)
                        resultPath.add(suite.name)
                        resultPath.add(test.name)
                        for (artifact in test.artifacts) {
                            /* Replace all spaces with underscores in destination path */
                            def destinationPath = resultPath.join('/').replaceAll('\\s', '_')
                            /* Replace all spaces with underscores in artifact name */
                            def artifactFullName = (artifact.name + '.' + artifact.extension).replaceAll('\\s', '_')
                            /* persisting the aws urls for the future use - we currently use for extracting the customer artifacts */
                            artifact.awsurl = artifact.url

                            /* Fetch run artifact */
                            ArtifactHelper.retrieveArtifact(script, artifactFullName, artifact.url)
                            /* Publish to Artifact Storage and update run artifact URL */
                            artifact.url = ArtifactHelper.publishArtifact sourceFileName: artifactFullName,
                                    sourceFilePath: script.pwd(), destinationPath: destinationPath, script
                            artifact.authurl = ArtifactHelper.createAuthUrl(artifact.url, script, true)
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

    /**
     * Displays a warning message on console if device farm time limit is exceeded of 150 minutes
     *
     * @param deviceKey device name
     */
    protected final void validateRunWithDeviceFarmTimeLimitAndDisplay(deviceKey){
        Long timeDifference = 0
        Date endTime = new Date()
        if(testExecutionStartTimeMap.containsKey(deviceKey))
            timeDifference = endTime.time - testExecutionStartTimeMap[deviceKey].time
        Long defaultTestRunTimeLimit = Long.parseLong(libraryProperties.'test.automation.device.farm.default.time.run.limit')
        if(timeDifference > defaultTestRunTimeLimit) {
            script.echoCustom("Sorry! Device Farm public fleet default time limit of " +(defaultTestRunTimeLimit/60000)+ " minutes exceeded. All remaining tests on device " + deviceKey + " will be skipped.", 'WARN')
        }
    }

    @NonCPS
    protected final parseJsonStringToObject(jsonString) {
        new groovy.json.JsonSlurperClassic().parseText(jsonString)
    }

    /**
     * Will return the list of formFactors which are fetched from ConfigFileContentJson Keys
     *
     * @param devicePoolConfigFileContent
     */
    protected final getDeviceFormFactors(devicePoolConfigFileContent){
        def formFactorsList = []
        def devicePoolConfigFileContentJson = parseJsonStringToObject(devicePoolConfigFileContent)
        devicePoolConfigFileContentJson.each { key, value ->
            def formFactor = key.tokenize('_')[1]
            formFactorsList.add(["formFactor":formFactor])
        }

        formFactorsList
    }

    /**
     * Show the tests results of a particular device immediately after its test execution is completed
     *
     * @param results - detailed test run results of the device
     * @param deviceTestRunArn - Run Arn of the device
     */
    protected final showDeviceTestRunResults(results, deviceTestRunArn) {
        def singleLineSeparator = {
            script.echoCustom("*" * 99)
        }
        script.echoCustom("Summary of Test Results on \"${results.getDevice().getName()}\":", 'INFO')
        singleLineSeparator()
        String displayResults = 'Total Tests: ' + results.getResultsCount().getTotal() +
                ', Passed: ' + results.getResultsCount().getPassed() +
                ', Failed: ' + results.getResultsCount().getFailed() +
                ', Skipped: ' + results.getResultsCount().getSkipped() +
                ', Warned: ' + results.getResultsCount().getWarned() +
                ', Stopped: ' + results.getResultsCount().getStopped() +
                ', Errored: ' + results.getResultsCount().getErrored() +
                ', Test Duration: ' + results.getTestDuration() +
                ', Run ARN: ' + deviceTestRunArn
        script.echoCustom(displayResults)
        singleLineSeparator()
    }
}
