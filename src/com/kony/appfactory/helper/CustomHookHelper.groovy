package com.kony.appfactory.helper

import com.kony.appfactory.helper.ConfigFileHelper
import jenkins.model.Jenkins
import com.kony.appfactory.enums.BuildType
import hudson.slaves.EnvironmentVariablesNodeProperty;


/**
 * Implements logic related to CustomHooks execution process.
 */
class CustomHookHelper implements Serializable {
    /* Pipeline object */
    private script

    /* Library configuration */
    protected libraryProperties

    /* Build Type for running the custom hooks. */
    protected BuildType buildType
    
    /* customhooks hook definitions */
    protected hookDir
    protected final hookScriptFileName
    protected defaultParams = ""

    protected CustomHookHelper(script, buildType) {
        this.script = script
        this.buildType = buildType
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
        hookScriptFileName = libraryProperties.'customhooks.hookzip.name'
    }

    /*
    * Extract hook list to run from Config File Content
    * @param script
    * @param hookStage
    * @param pipelineBuildStage
    * @param jsonContent
    * @return hookList
    */

    protected getHookList(hookStage, pipelineBuildStage, jsonContent){
        def stageContent = jsonContent[hookStage]

        String[] hookList = new String[stageContent.size()]
        stageContent.each{
            if((it["status"]).equals("enabled")
                    && (((it['parameter']['HOOK_CHANNEL']).equals(pipelineBuildStage))
                    || (it['parameter']['HOOK_CHANNEL']).equals("ALL"))){
                hookList[(it['index']).toInteger()] = it['hookName']
            }
        }
        def updatedIndexHookList = [];
        hookList.each{
            if(it){
                updatedIndexHookList.push(it)
            }
        }
        if(updatedIndexHookList){
            script.echoCustom("Hooks found in $hookStage stage for $pipelineBuildStage channel. List: " + updatedIndexHookList.toString())
        }

        return updatedIndexHookList;
    }

    /*Get boolean isPropagate status of a hook
    * @param hookName
    * @param hookStage
    * @param jsonContent
    * @return isPropagateBuildResult
    */
    protected isPropagateBuildStatus(hookName, hookStage, jsonContent){
        def isPropagateBuildResult = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                isPropagateBuildResult =  it['propagateBuildStatus']
            }
        }
        return isPropagateBuildResult
    }

    protected getbuildScriptURL(hookName, hookStage, jsonContent){
        def buildScriptURL = null
        def stageContent = jsonContent[hookStage]
        stageContent.each{
            if((it["hookName"]).equals(hookName)){
                buildScriptURL =  it['hookUrl']
            }
        }
        return buildScriptURL
    }

    /**
     * Fetches Hook zip file for running it locally from S3.
     */
    protected final void fetchHook(String buildScriptUrl){
        def hookScriptFileName = libraryProperties.'customhooks.hookzip.name'
        def hookScriptFileBucketPath = (buildScriptUrl - script.env.S3_BUCKET_URL).substring(1)

        script.catchErrorCustom('Failed to fetch Hook zip file') {
            AwsHelper.s3Download(script, hookScriptFileName, hookScriptFileBucketPath)
        }
    }


    /* Trigger hook
    * @param script
    * @param projectName
    * @param hookStage
    * @param pipelineBuildStage
    */
    protected triggerHooks(projectName, hookStage, pipelineBuildStage){
        def customhooksConfigFolder = [projectName, buildType.toString(), libraryProperties.'customhooks.folder.name'].join('/')
        def content = ConfigFileHelper.getContent(customhooksConfigFolder, projectName)
        def hookReturnStatus = true

        if(content) {
            String currentComputer = "${script.env.NODE_NAME}"
            String hookSlave = getHookSlaveForCurrentBuildSlave(currentComputer)
            hookSlave ?: script.echoCustom("Not able to find hookSlave to run CustomHooks","ERROR")

            script.writeFile file: "${projectName}.json", text: content
            def hookProperties = script.readJSON file: "${projectName}.json"

            def hookList = getHookList(hookStage, pipelineBuildStage, hookProperties)
            hookList ?: script.echoCustom("Hooks are either not defined or disabled in $hookStage stage for $pipelineBuildStage channel.")

            for (hookName in hookList) {
                def isPropagateBuildResult = isPropagateBuildStatus(hookName, hookStage, hookProperties)
                def hookJobName = getHookJobName(projectName, hookName, hookStage)
                def buildScriptUrl = getbuildScriptURL(hookName, hookStage, hookProperties)
                String hookLabel = script.env.NODE_LABELS

                script.stage('Clean Hook Environment') {
                    if (hookLabel.contains(libraryProperties.'test.automation.node.label') && buildType.toString().equalsIgnoreCase('Visualizer')) {
                        hookDir = [getWorkspaceLocation(),projectName,"deviceFarm","Hook"].join('/')
                    } else {
                        hookDir = getWorkspaceLocation() + "/" + projectName + "/Hook"
                    }
                    script.dir(hookDir) {
                        script.deleteDir()
                    }
                    script.shellCustom("set +ex; rm -rf $hookDir; mkdir -p $hookDir", true)
                }

                script.stage("Download Hook Script") {
                    script.dir(hookDir) {
                        fetchHook(buildScriptUrl)
                    }
                }

                script.stage("Extract Hook Archive") {
                    script.dir(hookDir) {
                        script.unzip zipFile: hookScriptFileName
                    }
                }

                /* Setting permissions to hookslave user to read/write/modify in project workspace folder */
                /* Sample workspace: Visualizer/Builds/Channels/buildAndroid/vis_ws/ProjectName */
                /* Hook is extracted at: Visualizer/Builds/Channels/buildAndroid/vis_ws/ProjectName/Hook */
                /* Pass Current Job Parameters details to Child Job. These params can be passed later to ANT and Maven scripts */
                script.stage('Prepare Environment for Run') {
                    /** Construct a String with current Job Parameters key-pair list with -Dkey=value format.
                     * We will send this string to customhook child job to pass as argument to ANT/Maven program.
                     * Below line gets all Jenkins job Parameters defined in UpperCase since we follow same convention
                     * while defining Parameters.
                     */
                    script.params.findAll { propkey, propvalue -> propkey.equals(propkey.toUpperCase()) }.each {
                        defaultParams = [defaultParams,"-D${it.key}=\"${it.value}\""].join(' ')
                    }

                    /* To store the parent job build number and expose this to custom hook */
                    def parentJobBuildNumber = BuildHelper.getUpstreamJobNumber(script)

                    /* Append parent job build_number, Channel job build number and exact hook channel names environment variables to defaultParams string. */
                    defaultParams += " -DPROJECT_BUILDNUMBER=${parentJobBuildNumber} -DBUILDNUMBER=${script.env.BUILD_NUMBER} -DHOOK_CHANNEL=${pipelineBuildStage}"

                    /* Applying ACLs, allow hookslave user permissions */
                    if(hookLabel.contains(libraryProperties.'ios.node.label')) {
                        macACLbeforeRun()
                    }
                    else if(hookLabel.contains(libraryProperties.'test.automation.node.label')) {
                        linuxACLbeforeRun()
                    }
                    else {
                        throw new AppFactoryException("Something went wrong.. unable to run hook",'ERROR')
                    }
                }

                script.stage("Run " + hookName ) {
                    script.echoCustom("Hook execution for $hookName hook is being initiated...", 'INFO')
                    def hookJob = script.build job: hookJobName,
                            propagate: false,
                            parameters: [[$class: 'WHideParameterValue',
                                          name  : 'UPSTREAM_JOB_WORKSPACE',
                                          value : "$script.env.WORKSPACE"],

                                         [$class: 'WHideParameterValue',
                                          name  : 'HOOK_SLAVE',
                                          value : "$hookSlave"],

                                         [$class: 'WHideParameterValue',
                                          name  : 'BUILD_SLAVE',
                                          value : "$currentComputer"],
                                      
                                         [$class: 'WHideParameterValue',
                                          name  : 'BUILD_TYPE',
                                          value : "$buildType"],

                                         [$class: 'WHideParameterValue',
                                          name  : 'PARENTJOB_PARAMS',
                                          value : "$defaultParams"]]

                    /* Build Stats */
                    def buildStats = [:]
                    def runListStats = [:]
                    runListStats.put(hookJob.fullProjectName + "/" + hookJob.number, hookJob.fullProjectName)
                    buildStats.put("pipeline-run-jobs", runListStats)
                    // Publish Facade metrics keys to build Stats Action class.
                    script.statspublish buildStats.inspect()

                    if (hookJob.currentResult == 'SUCCESS') {
                        script.echoCustom("Hook execution for $hookName hook is SUCCESS, continuing with next build step..", 'INFO')
                    } else if (!(Boolean.valueOf(isPropagateBuildResult)) && hookJob.currentResult != 'SUCCESS') {
                        script.echoCustom("Build is completed for the Hook $hookName. Hook build status: $hookJob.currentResult", 'WARN')
                        script.echoCustom("Since Hook setting is set with Propagate_Build_Status flag as false, " +
                                "continuing with next build step..", 'INFO')
                    } else if (Boolean.valueOf(isPropagateBuildResult) && hookJob.currentResult != 'SUCCESS'){
                        hookReturnStatus = false
                        throw new AppFactoryException("Build is completed for the Hook $hookName. Hook build status: $hookJob.currentResult." +
                            "Since Hook setting is set with Propagate_Build_Status flag as true, exiting the build...")
                    }
                }

            }
        }
        else{
            script.echoCustom("Hooks are not defined in $hookStage");
        }
        return hookReturnStatus
    }

    /* return hook full path */
    protected final getHookJobName(projectName, hookName, hookType) {
        def hookFullName = [projectName, buildType.toString(), libraryProperties.'customhooks.folder.name', hookType, hookName].join('/')
        hookFullName
    }


    //  @NonCPS
    protected runCustomHooks(String folderName, String hookBuildStage, String pipelineBuildStage) {
        script.echoCustom("Trying to fetch $hookBuildStage $pipelineBuildStage hooks. ")
        /* If CloudBuild, the folder name will be CloudBuildService (project in AppFactory CloudBuild Jenkins Console) */
        folderName = (buildType.toString().equalsIgnoreCase('Visualizer') && script.params.IS_SOURCE_VISUALIZER) ? libraryProperties.'cloudbuild.project.name' : folderName

        /* Execute available hooks */
        def executionStatus = triggerHooks(folderName, hookBuildStage, pipelineBuildStage)

        executionStatus
    }

    protected getHookSlaveForCurrentBuildSlave(currentComputer){

        String hookSlaveForCurrentComputer = null;
        Jenkins instance = Jenkins.getInstance()

        instance.computers.each { comp ->
            /*Below hookslave must come from config file */
            if(comp.displayName.equals(currentComputer)){
                hookSlaveForCurrentComputer = comp.getNode()
                        .getNodeProperties()
                        .get(EnvironmentVariablesNodeProperty.class)
                        .getEnvVars()
                        .get("hookSlave")

            }
        }
        hookSlaveForCurrentComputer
    }
    
    protected final getWorkspaceLocation() {
        def projWorkspace
        if(BuildType.Visualizer.toString().equalsIgnoreCase(buildType.toString())) {
            projWorkspace = [script.env.WORKSPACE, libraryProperties.'project.workspace.folder.name'].join('/')
        } else {
            projWorkspace = [script.env.WORKSPACE, libraryProperties.'fabric.project.workspace.folder.name'].join('/')
        }
        projWorkspace
    }

    /* applying ACLs - allow hookslave user with read, write permissions on builduser owned files.*/
    def macACLbeforeRun()
    {
        def projworkspace = getWorkspaceLocation()

        script.dir(projworkspace) {
            def hookSlaveACLapply_fordirs = 'set +xe; find . -user buildslave -type d -print0 | xargs -0 chmod +a "hookslave allow list,add_file,search,delete,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit"'
            def hookSlaveACLapply_forfiles = 'set +xe; find . -user buildslave -type f -print0 | xargs -0 chmod +a "hookslave allow read,write,append,delete,readattr,writeattr,readextattr,writeextattr,readsecurity"'

            script.shellCustom("$hookSlaveACLapply_fordirs", true)
            script.shellCustom("$hookSlaveACLapply_forfiles", true)

            /* This is to restrict no other hook job to enter the project workspace folder */
            script.shellCustom("set +xe; chmod 710 .", true)
            def ACLGroupPerms = 'set +xe; find . -user buildslave -print0 | xargs -0 chmod 770'
            script.shellCustom("$ACLGroupPerms", true)
        }
    }

    /* applying ACLs - allow hookslave user with read, write permissions on builduser owned files.*/
    def linuxACLbeforeRun()
    {
        def projworkspace = getWorkspaceLocation()

        script.dir(projworkspace) {
            def cleanTmpFiles = 'set +xe && find . -type d -name "*@tmp" -empty -delete'
            def hookSlaveACLapply_fordirs = 'set +xe && find . -user jenkins -type d -exec setfacl -m u:hookslave:rwx "{}" \\+'
            def hookSlaveACLapply_forfiles = 'set +xe && find . -user jenkins -type f -exec setfacl -m u:hookslave:rwx "{}" \\+'

            script.shellCustom("$cleanTmpFiles", true)
            script.shellCustom("$hookSlaveACLapply_fordirs", true)
            script.shellCustom("$hookSlaveACLapply_forfiles", true)

            /* This is to restrict no other hook job to enter the project workspace folder */
            script.shellCustom("set +xe && chmod 710 .", true)
        }
    }
}
