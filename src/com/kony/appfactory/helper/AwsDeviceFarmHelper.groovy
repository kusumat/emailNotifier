package com.kony.appfactory.helper

import groovy.json.JsonOutput

/**
 * Implements Device Farm logic.
 */
class AwsDeviceFarmHelper implements Serializable {
    def script
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    AwsDeviceFarmHelper(script) {
        this.script = script
    }

    /**
     * Fetches artifact via provided URL.
     *
     * @param artifactName artifact name.
     * @param artifactUrl artifact URL.
     */
    protected final void fetchArtifact(String artifactName, String artifactUrl) {
        String successMessage = 'Artifact ' + artifactName + ' fetched successfully'
        String errorMessage = 'Failed to fetch artifact ' + artifactName

        script.catchErrorCustom(errorMessage, successMessage) {
            script.shellCustom("curl -k -s -S -f -L -o \'${artifactName}\' \'${artifactUrl}\'", true)
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
            String getProjectScript = "aws devicefarm list-projects --no-paginate --query \'projects[?name==" +
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
            String createProjectScript = "aws devicefarm create-project --name \'" + name + "\'" +
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
            String getDeviceArnsScript = "aws devicefarm list-devices"
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
        def deviceNames = (getDevicesInPool(devicePoolName)) ?: script.error('Device list is empty!')
        def deviceArns = (getDeviceArns(deviceNames)) ?: script.error('Device ARNs list is empty!')

        script.catchErrorCustom(errorMessage, successMessage) {
            String generateSkeletonScript = "aws devicefarm create-device-pool --generate-cli-skeleton"
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
                String createDevicePoolScript = 'aws devicefarm create-device-pool --cli-input-json' +
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
            String createUploadScript = "aws devicefarm create-upload --project-arn ${projectArn}" +
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
                String getUploadScript = "aws devicefarm get-upload --arn ${uploadArn}"
                String getUploadOutput = script.shellCustom(getUploadScript, true, [returnStdout: true]).trim()
                def getUploadJSON = script.readJSON text: getUploadOutput
                String uploadStatus = getUploadJSON.upload.status
                String uploadMetadata = getUploadJSON.upload.metadata

                if (uploadStatus == 'FAILED') {
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
     * @param projectArn project ARN.
     * @param devicePoolArn device pool ARN.
     * @param runType Device Farm run type.
     * @param uploadArtifactArn application binaries upload ARN.
     * @param testPackageArn test binaries upload ARN.
     * @return scheduled run ARN.
     */
    protected final scheduleRun(
            String projectArn, String devicePoolArn, String runType, String uploadArtifactArn, String testPackageArn
    ) {
        def runArn
        String successMessage = 'Run scheduled successfully'
        String errorMessage = 'Failed to schedule run'

        script.catchErrorCustom(errorMessage, successMessage) {
            String runScript = [
                    'aws devicefarm schedule-run',
                    "--project-arn ${projectArn}",
                    "--app-arn ${uploadArtifactArn}",
                    "--device-pool-arn ${devicePoolArn}",
                    "--test type=${runType},testPackageArn=${testPackageArn}",
                    "--query run.arn"
            ].join(' ')
            /* Schedule the run */
            runArn = script.shellCustom(runScript, true, [returnStdout: true]).trim() ?: null
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
        String successMessage = 'Test run results fetched successfully'
        String errorMessage = 'Failed to fetch test run results'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.waitUntil {
                String runResultScript = "aws devicefarm get-run --arn ${testRunArn}"
                String runResultOutput = script.shellCustom(runResultScript, true, [returnStdout: true]).trim()
                def runResultJSON = script.readJSON text: runResultOutput
                testRunStatus = runResultJSON.run.status
                testRunResult = runResultJSON.run.result

                testRunStatus == 'COMPLETED'
            }
        }

        testRunResult
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
                        queryScript                : "aws devicefarm list-jobs --arn ${arn} --no-paginate",
                        queryProperty              : 'jobs',
                        resultStructure            : ['result', 'device', 'totalSuites'],
                        resultStructureNextProperty: 'suites'
                ]
                break
            case ~/^.*job.*$/:
                queryParameters = [
                        queryScript                : "aws devicefarm list-suites --arn ${arn} --no-paginate",
                        queryProperty              : 'suites',
                        resultStructure            : ['name', 'totalTests'],
                        resultStructureNextProperty: 'tests'
                ]
                break
            case ~/^.*suite.*$/:
                queryParameters = [
                        queryScript                : "aws devicefarm list-tests --arn ${arn} --no-paginate",
                        queryProperty              : 'tests',
                        resultStructure            : ['name', 'result'],
                        resultStructureNextProperty: 'artifacts'
                ]
                break
            case ~/^.*test.*$/:
                queryParameters = [
                        queryScript    : "aws devicefarm list-artifacts --arn ${arn} --no-paginate --type FILE",
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
                    script.echo 'Failed to find query property!'
                }
            } else {
                script.echo 'Failed to query Device Farm!'
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
            script.shellCustom("aws devicefarm delete-upload --arn ${artifactArn}", true)
        }
    }

    /**
     * Delete device pool by ARN.
     *
     * @param devicePoolArn device pool ARN.
     */
    protected final void deleteDevicePool(devicePoolArn) {
        script.catchErrorCustom('Failed to delete device pool') {
            script.shellCustom("aws devicefarm delete-device-pool --arn ${devicePoolArn}", true)
        }
    }
}
