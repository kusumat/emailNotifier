package com.kony.appfactory.helper

import groovy.json.JsonOutput

/**
 * Implements DeviceFarm logic.
 */
class AwsDeviceFarmHelper implements Serializable {
    def script
    /**
     * Class constructor.
     *
     * @param script the pipeline object.
     */
    AwsDeviceFarmHelper(script) {
        this.script = script
    }

    /**
     * Fetches artifact via provided URL.
     *
     * @param artifactName the artifact name.
     *
     * @param artifactURL the artifact URL.
     */
    protected final void fetchArtifact(artifactName, artifactURL) {
        def successMessage = 'Artifact ' + artifactName + ' fetched successfully'
        def errorMessage = 'FAILED to fetch artifact ' + artifactName

        script.catchErrorCustom(errorMessage, successMessage) {
            script.shellCustom("curl -k -s -S -f -L -o \'${artifactName}\' \'${artifactURL}\'")
        }
    }

    /**
     * Checks whether there DeviceFarm project.
     *
     * @param name the project name.
     *
     * @return the project ARN.
     */
    protected final getProject(name) {
        def projectArn
        def successMessage = 'Project ' + name + ' already exists!'
        def errorMessage = 'FAILED to create project ' + name

        script.catchErrorCustom(errorMessage, successMessage) {
            def getProjectScript = "aws devicefarm list-projects --no-paginate --query \'projects[?name==" +
                    "`" + name + "`" + "]\'"
            def getProjectScriptOutput = script.shellCustom(getProjectScript, true, [returnStdout: true]).trim()
            def getProjectScriptOutputJSON = script.readJSON(text: getProjectScriptOutput)

            projectArn = (getProjectScriptOutputJSON.isEmpty()) ? null : getProjectScriptOutputJSON[0].arn
        }

        projectArn
    }

    /**
     * Creates project with provided name.
     *
     * @param name the project name.
     *
     * @return the project ARN.
     */
    protected final createProject(name) {
        def projectArn
        def successMessage = 'Project ' + name + ' created successfully'
        def errorMessage = 'FAILED to create project ' + name

        script.catchErrorCustom(errorMessage, successMessage) {
            def createProjectScript = "aws devicefarm create-project --name \'" + name + "\'" + " --query project.arn"
            def createProjectOutput = script.shellCustom(createProjectScript, true, [returnStdout: true]).trim()

            projectArn = (createProjectOutput) ?: null
        }

        projectArn
    }

    /**
     * Parses list of provided devices.
     *
     * @param devicesList the string with comma-separated devices.
     *
     * @return the list of device objects.
     */
    private parseDevicesList(devicesList) {
        def devices = []

        script.catchErrorCustom('FAILED to parse device list') {
            for (item in devicesList.tokenize(',')) {
                def deviceProperties = item.tokenize('*')
                devices.add([
                        formFactor  : deviceProperties[0].trim(),
                        manufacturer: deviceProperties[2].trim(),
                        model       : deviceProperties[3].trim(),
                        os          : deviceProperties[4].trim(),
                        platform    : deviceProperties[1].trim()
                ])
            }
        }

        devices
    }

    /**
     * Gets devices from predefined pool.
     *
     * @param configID the pool name string.
     *
     * @return the string string with comma-separated devices.
     */
    private getDevicesInPool(configID) {
        def devices

        def successMessage = "Pool $configID found successfully"
        def errorMessage = "FAILED to find $configID pool"

        script.catchErrorCustom(errorMessage, successMessage) {
            script.configFileProvider([script.configFile(fileId: "$configID", variable: 'DEVICES')]) {
                def getDevicesInPoolOutput = script.shellCustom('cat $DEVICES', true, [returnStdout: true]).trim()
                devices = parseDevicesList(getDevicesInPoolOutput)
            }
        }

        devices
    }

    /**
     * Gets device ARNs.
     *
     * @param selectedDevices the list of device objects.
     *
     * @return the Map with two(phones and tables) items (pools).
     *         Each of them contains device ARNs.
     */
    protected final getDeviceArns(selectedDevices) {
        def deviceArns = [:]
        def errorMessage = 'FAILED to find device ARNs'

        script.catchErrorCustom(errorMessage) {
            def getDeviceArnsScript = "aws devicefarm list-devices"
            def getDeviceArnsScriptOutput = script.shellCustom(getDeviceArnsScript, true, [returnStdout: true]).trim()
            def existingDevices = script.readJSON(text: getDeviceArnsScriptOutput).devices
            def phonesList = []
            def tabletsList = []
            def missingDevicesList = []

            for(selectedDevice in selectedDevices) {
                def deviceExists = false

                for(existingDevice in existingDevices) {
                    def truncatedExistingDevice = existingDevice.subMap(selectedDevice.keySet() as List)

                    if (truncatedExistingDevice == selectedDevice) {
                        deviceExists = true
                        if (existingDevice.formFactor == 'PHONE') {
                            phonesList.add(existingDevice.arn)
                        } else {
                            tabletsList.add(existingDevice.arn)
                        }
                    }
                }

                if (!deviceExists) {
                    missingDevicesList.add("$selectedDevice.manufacturer $selectedDevice.model")
                }
            }

            deviceArns.phones = phonesList
            deviceArns.tablets = tabletsList

            script.env['MISSING_DEVICES'] = missingDevicesList.join(', ') // Exposing missing devices as env variable
        }

        deviceArns
    }

    /**
     * Creates device pools for provied project.
     *
     * @param projectArn the project ARN.
     * @param devicePoolName the predefined device pool name.
     *
     * @return the Map with two (phones and tables) items.
     *         Each of them contains device pool ARNs.
     */
    protected final createDevicePools(projectArn, devicePoolName) {
        def devicePoolArns = [:]
        def devicePoolJsons = [:]
        def successMessage = 'Device pools created successfully'
        def errorMessage = 'FAILED to create device pools'
        def deviceNames = (getDevicesInPool(devicePoolName)) ?: script.error("Device list is empty!")
        def deviceArns = (getDeviceArns(deviceNames)) ?: script.error("Device ARNs list is empty!")

        script.catchErrorCustom(errorMessage, successMessage) {
            def generateSkeletonScript = "aws devicefarm create-device-pool --generate-cli-skeleton"
            def generateSkeletonScriptResult = script.shellCustom(generateSkeletonScript, true, [returnStdout: true]).trim()
            def devicePool = script.readJSON text: generateSkeletonScriptResult

            for (item in deviceArns) {
                if (item.value) {
                    devicePool.projectArn = projectArn
                    devicePool.name = (item.key == 'phones') ? 'Phone-Device-Pool' : 'Tablet-Device-Pool'
                    devicePool.rules[0].attribute = 'ARN'
                    devicePool.rules[0].operator = 'IN' // currently only this operator is working
                    devicePool.rules[0].value = '[' + item.value.collect { '"' + it + '"' }.join(',') + ']'
                    devicePoolJsons.put(item.key, JsonOutput.toJson(devicePool))
                }
            }

            def poolNames = devicePoolJsons.keySet().toArray()
            // Workaround to iterate over map keys in c++ style for loop
            for (int i = 0; i < poolNames.size(); ++i) {
                def createDevicePoolScript = "aws devicefarm create-device-pool --cli-input-json" +
                        " '${devicePoolJsons.get(poolNames[i])}' --query devicePool.arn"
                def createDevicePoolScriptOutput = script.shellCustom(createDevicePoolScript, true, [returnStdout: true]).trim()

                devicePoolArns.put(poolNames[i], createDevicePoolScriptOutput)
            }
        }

        devicePoolArns
    }

    /**
     * Uploads provided artifact to DeviceFarm.
     *
     * @param projectArn the project ARN.
     * @param uploadType the DeviceFarm upload type.
     * @param uploadFileName the upload file name.
     *
     * @return the upload ARN.
     */
    protected final uploadArtifact(projectArn, uploadType, uploadFileName) {
        def uploadArn = null
        def successMessage = "Artifact ${uploadFileName} uploaded successfully"
        def errorMessage = "FAILED to upload ${uploadFileName} artifact"

        script.catchErrorCustom(errorMessage, successMessage) {
            def createUploadScript = "aws devicefarm create-upload --project-arn ${projectArn}" +
                    " --name ${uploadFileName}" + " --type ${uploadType}"
            def createUploadOutput = script.shellCustom(createUploadScript, true, [returnStdout: true]).trim()
            def createUploadJSON = script.readJSON text: createUploadOutput
            uploadArn = createUploadJSON.upload.arn
            def uploadUrl = createUploadJSON.upload.url
            def uploadScript = "curl -k -s -S -f -T ${uploadFileName} '${uploadUrl}'"

            script.shellCustom(uploadScript)

            script.waitUntil {
                def getUploadScript = "aws devicefarm get-upload --arn ${uploadArn}"
                def getUploadOutput = script.shellCustom(getUploadScript, true, [returnStdout: true]).trim()
                def getUploadJSON = script.readJSON text: getUploadOutput
                def uploadStatus = getUploadJSON.upload.status
                def uploadMetadata = getUploadJSON.upload.metadata

                if(uploadStatus == 'FAILED') {
                    script.error uploadMetadata
                }

                uploadStatus == 'SUCCEEDED'
            }
        }

        uploadArn
    }

    /**
     * Schedule run with provided application and test binaries.
     *
     * @param projectArn the project ARN.
     * @param devicePoolArn the device pool ARN.
     * @param runType the DeviceFarm run type.
     * @param uploadArtifactArn the application binaries upload ARN.
     * @param testPackageArn the test binaries upload ARN.
     *
     * @return the scheduled run ARN.
     */
    protected final scheduleRun(projectArn, devicePoolArn, runType, uploadArtifactArn, testPackageArn) {
        def runArn = null
        def successMessage = "Run scheduled successfully"
        def errorMessage = "FAILED to schedule run"

        script.catchErrorCustom(errorMessage, successMessage) {
            def runScript = "aws devicefarm schedule-run" +
                    " --project-arn ${projectArn}" +
                    " --app-arn ${uploadArtifactArn}" +
                    " --device-pool-arn ${devicePoolArn}" +
                    " --test type=${runType},testPackageArn=${testPackageArn}" +
                    " --query run.arn"

            runArn = script.shellCustom(runScript, true, [returnStdout: true]).trim()
        }

        runArn
    }

    /**
     * Queries DeviceFarm to get test run result.
     *
     * @param testRunArn the scheduled run ARN.
     *
     * @return the string with run result, possible values: PENDING, PASSED, WARNED, FAILED, SKIPPED, ERRORED, STOPPED.
     */
    protected final getTestRunResult(testRunArn) {
        def testRunStatus, testRunResult
        def successMessage = "Test run results fetched successfully"
        def errorMessage = "FAILED to fetch test run results"

        script.catchErrorCustom(errorMessage, successMessage) {
            script.waitUntil {
                def runResultScript = "aws devicefarm get-run --arn ${testRunArn}"
                def runResultOutput = script.shellCustom(runResultScript, true, [returnStdout: true]).trim()
                def runResultJSON = script.readJSON text: runResultOutput
                testRunStatus = runResultJSON.run.status
                testRunResult = runResultJSON.run.result

                testRunStatus == 'COMPLETED'
            }
        }

        testRunResult
    }

    /**
     * Queries DeviceFarm to get test run artifacts.
     *
     * @param arn the ARN of the object that needs to be queried.
     *
     * @return the Array of Maps with specific structure.
     */
    protected final getTestRunArtifacts(arn) {
        def queryParameters = [:]
        def resultStructure = []

        switch (arn) {
            case ~/^.*run.*$/:
                queryParameters = [
                        queryScript:  "aws devicefarm list-jobs --arn ${arn} --no-paginate",
                        queryProperty: 'jobs',
                        resultStructure: ['result', 'device', 'totalSuites'],
                        resultStructureNextProperty: 'suites'
                ]
                break
            case ~/^.*job.*$/:
                queryParameters = [
                        queryScript: "aws devicefarm list-suites --arn ${arn} --no-paginate",
                        queryProperty: 'suites',
                        resultStructure: ['name', 'totalTests'],
                        resultStructureNextProperty: 'tests'
                ]
                break
            case ~/^.*suite.*$/:
                queryParameters = [
                        queryScript: "aws devicefarm list-tests --arn ${arn} --no-paginate",
                        queryProperty: 'tests',
                        resultStructure: ['name', 'result'],
                        resultStructureNextProperty: 'artifacts'
                ]
                break
            case ~/^.*test.*$/:
                queryParameters = [
                        queryScript: "aws devicefarm list-artifacts --arn ${arn} --no-paginate --type FILE",
                        queryProperty: 'artifacts',
                        resultStructure: ['name', 'url', 'extension']
                ]
                break
            default:
                break
        }

        if (queryParameters) {
            def queryOutput = script.readJSON text: script.shellCustom(queryParameters.queryScript, true, [returnStdout: true]).trim()

            if (queryOutput) {
                def queryProperty = queryParameters.queryProperty
                def nextProperty = queryParameters.resultStructureNextProperty

                if (queryOutput.containsKey(queryProperty)) {
                    for (item in queryOutput[queryProperty]) {
                        def resultStructureProperties = queryParameters.resultStructure
                        def queryResultStructure = [:]

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

                        if (nextProperty) {
                            queryResultStructure.get(nextProperty, getTestRunArtifacts(item.arn))
                        }

                        resultStructure.add(queryResultStructure)
                    }
                } else {
                    script.echo "FAILED to find query property!"
                }
            } else {
                script.echo "FAILED to query devicefarm!"
            }
        }

        resultStructure
    }

    /**
     * Move test run artifacts to customer S3 bucket
     *
     * @param runResultArtifacts the Map with run result artifacts
     * @param bucketName the S3 bucket name
     * @param bucketRegion the S3 bucket region
     * @param s3BasePath the base path in S3 bucket
     * @param artifactFolder the path to artifact in workspace
     */
    protected final moveArtifactsToCustomerS3Bucket(runResultArtifacts, s3BasePath) {
        def successMessage = "Artifacts moved successfully"
        def errorMessage = "FAILED to move artifacts"

        script.catchErrorCustom(errorMessage, successMessage) {
            for (runArtifact in runResultArtifacts) {
                for (suite in runArtifact.suites) {
                    for(test in suite.tests) {
                        def resultPath = [s3BasePath]
                        resultPath.add(runArtifact.device.formFactor)
                        resultPath.add(runArtifact.device.name +'_' + runArtifact.device.platform + '_' +
                                runArtifact.device.os)
                        resultPath.add(suite.name)
                        resultPath.add(test.name)
                        for (artifact in test.artifacts) {
                            def s3path = resultPath.join('/').replaceAll("\\s", '_')
                            def artifactFullName = (artifact.name + '.' + artifact.extension).replaceAll("\\s", '_')

                            fetchArtifact(artifactFullName, artifact.url)
                            AwsHelper.publishToS3 bucketPath: s3path, sourceFileName: artifactFullName,
                                    sourceFilePath: script.pwd(), script
                            artifact.url = AwsHelper.getS3ArtifactUrl(script, [s3path, artifactFullName].join('/'))
                        }
                    }
                }
            }
        }

        runResultArtifacts
    }

    /**
     * Delete uploaded artifact by ARN
     *
     * @param artifactArn the artifact ARN
     */
    protected final void deleteUploadedArtifact(artifactArn) {
        script.catchErrorCustom('FAILED to delete artifact') {
            script.shellCustom("aws devicefarm delete-upload --arn ${artifactArn}")
        }
    }

    /**
     * Delete device pool by ARN
     *
     * @param devicePoolArn the device pool ARN
     */
    protected final void deleteDevicePool(devicePoolArn) {
        script.catchErrorCustom('FAILED to delete device pool') {
            script.shellCustom("aws devicefarm delete-device-pool --arn ${devicePoolArn}")
        }
    }
}
