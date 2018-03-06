package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.BuildHelper
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import com.kony.appfactory.helper.AwsHelper
import org.jenkinsci.plugins.configfiles.custom.CustomConfig
import org.jenkinsci.plugins.configfiles.folder.FolderConfigFileProperty
import com.kony.appfactory.helper.ValidationHelper

class CustomHook implements Serializable {
    /* Pipeline object */
    private final script

    /* Library configuration */
    private libraryProperties
    protected artifactName

    public artifactUrl
    /* DSL variables */
    protected final scmUrl= script.env.REPOSITORY_URL
    protected final scmCredentialsId = script.env.REPOSITORY_CREDENTIALS_ID
    protected final scmBranch = script.env.JOB_DSL_SCRIPTS_BRANCH
    protected final checkoutTo = script.env.CHECKOUT_TO
    protected final dslScriptName = script.env.DSL_SCRIPT_NAME

    /* Commons */
    protected final projectName = script.env.PROJECT_NAME
    /* parameters */
    protected final hookName = script.params.HOOK_NAME
    protected final buildStep = script.params.BUILD_STEP
    protected final buildAction = script.params.BUILD_ACTION
    protected final buildScript = script.params.BUILD_SCRIPT
    protected final scriptArguments = script.params.SCRIPT_ARGUMENTS
    protected final blocking = script.params.BLOCKING
    protected final hookSlave = script.params.HOOK_SLAVE
    protected final buildSlave =script.params.BUILD_SLAVE

    def projectWorkspaceFolderName
    def isUnixNode
    def separator
    def pathSeparator
    def checkoutRelativeTargetFolder

    CustomHook(script) {
        this.script = script
        /* Load library configuration */
        libraryProperties = BuildHelper.loadLibraryProperties(
                this.script, 'com/kony/appfactory/configurations/common.properties'
        )
    }


    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final createPipeline() {
        script.timestamps {
            script.stage('Validate Parameters') {

                def mandatoryParameters = ['HOOK_NAME', 'BUILD_STEP', 'BUILD_ACTION','HOOK_ARCHIVE_FILE']

                ValidationHelper.checkBuildConfiguration(script, mandatoryParameters)
            }
            script.node(libraryProperties.'test.automation.node.label') {
                script.cleanWs deleteDirs: true

                script.stage("prepare script"){
                    createConfigFile()
                    if(buildAction == 'Execute Ant'){
                        script.writeFile file: "build.xml", text: "${buildScript}"
                        artifactName = 'build.xml'
                    }
                    else{
                        script.writeFile file: "pom.xml", text: "${buildScript}"
                        artifactName= 'pom.xml'
                    }
                }

                script.stage('Checkout DSL') {
                    BuildHelper.checkoutProject script: script,
                            projectRelativePath: checkoutTo,
                            scmBranch: scmBranch,
                            scmCredentialsId: scmCredentialsId,
                            scmUrl: scmUrl
                }

                script.stage('Publish Hook script') {

                    String artifactPath = "."
                    String s3ArtifactPath = ['CustomHook',buildStep,"${script.env.BUILD_NUMBER}"].join('/')

                    artifactUrl = AwsHelper.publishToS3 bucketPath: s3ArtifactPath,
                            sourceFileName: artifactName, sourceFilePath: artifactPath, script
                    script.echo("Downlaod url : ${artifactUrl}" )
                    script.env.ARTIFACT_URL = artifactUrl
                    script.echo("Downlaod url : ${script.env.ARTIFACT_URL}" )
                    def input = [:]
                    input['ARTIFACT_URL'] = artifactUrl

                    def jsonOut = script.readJSON text: groovy.json.JsonOutput.toJson(input)
                    script.writeJSON file: 'output.json', json: jsonOut
                }
                script.stage('Process DSL') {

                    script.withEnv(["ARTIFACT_URL=${artifactUrl}"]) {
                        script.echo("Download url (inside) : ${script.env.ARTIFACT_URL}" )
                        script.jobDsl  targets: "${checkoutTo}/${dslScriptName}"
                    }
                }
            }
        }
    }

    protected final void processPipeline(){

        def outputFile = "Hook.zip"
        String hookDir = projectName + "/Hook"

        String upstreamJobName = getUpstreamJobName(script)


        script.node(buildSlave) {
            script.ws([script.params.UPSTREAM_JOB_WORKSPACE, libraryProperties.'project.workspace.folder.name'].join('/')) {

                script.stage("Download Hook Scripts") {
                    script.dir(hookDir){
                        script.deleteDir()
                    }
                    script.sh "set +e; mkdir -p $projectName/Hook";
                    script.dir(hookDir){
                        script.httpRequest url: script.params.BUILD_SCRIPT, outputFile: outputFile, validResponseCodes: '200'
                    }
                }
                script.stage("Extract Hook") {
                    script.dir(hookDir){
                        script.unzip zipFile: "Hook.zip"
                    }
                }

                script.stage('Change permissions'){
                    script.sh 'pwd'
                    script.sh 'chmod -R +a "hookslave allow list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" ../vis_ws'
                    //script.sh 'find vis_ws -type f -exec chmod -R +a "hookslave read,write,append,readattr,writeattr,readextattr,writeextattr,readsecurity" {} \\ ;'
                    /*This is to get change permission for upstream folder which will be same as Jenkins job name*/
                    script.sh "chmod 710 ../../$upstreamJobName"
                }
            }
        }

        script.node(hookSlave){
            script.ws([script.params.UPSTREAM_JOB_WORKSPACE, libraryProperties.'project.workspace.folder.name'].join('/')) {
                script.stage("Run Script") {
                    script.dir(hookDir) {
                        if (script.params.BUILD_ACTION == "Execute Ant") {
                            script.sh "export JAVA_HOME='/Appfactory/Jenkins/tools/jdk1.8.0_112.jdk' && /Appfactory/Jenkins/tools/ant-1.8.2/bin/ant -f build.xml ${scriptArguments}"
                        } else if (script.params.BUILD_ACTION == "Execute Maven") {
                            script.sh "mvn ${scriptArguments}"
                        } else {
                            script.echo("unknown build script ")
                        }
                    }

                    script.dir(hookDir){
                        script.sh 'set +e && find . -user hookslave -exec chmod -R +a "buildslave allow read,write,delete,list,add_file,search,add_subdirectory,delete_child,readattr,writeattr,readextattr,writeextattr,readsecurity,writesecurity,chown,limit_inherit,only_inherit" {} \\;'
                    }

                }
            }
        }
    }
    def createConfigFile() {
        def customhooksConfigFolder = projectName + "/Visualizer/Builds/CustomHook"
        def folderObject = getFolderObject(customhooksConfigFolder)
        def folderConfigFilesObject = getConfigPropertyObject(folderObject)
        def availableConfigs = getAvailableConfigs(folderConfigFilesObject)


        /*
            Check provided parameters, because of this job has two purposes: creating and deleting device pools,
            we need stick to one operation at a time.
         */


            createConfig(hookName, "$blocking", folderConfigFilesObject, availableConfigs)

        }

/*
    To be able to store devices for the test with Config File Provider,
    we need to get Folder object where we want to store devices list first.
 */
    def getFolderObject(folderName) {
        def folderObject = null


            folderObject = Jenkins.instance.getItemByFullName("${folderName}", Folder)


        folderObject
    }

/* Get Config File Provider property in provided project Folder for storing devices list */
    def getConfigPropertyObject(folderObject) {
        def folderConfigFilesObject = null
        def folderProperties


            folderProperties = folderObject.getProperties()

            folderProperties.each { property ->
                if (property instanceof FolderConfigFileProperty) {
                    folderConfigFilesObject = property
                }
            }

        folderConfigFilesObject
    }

/* Get all device pools that been created before, for remove step */
    def getAvailableConfigs(folderConfigFilesObject) {
        def availableConfigs = null

        if (folderConfigFilesObject) {
            availableConfigs = folderConfigFilesObject.getConfigs()
        }

        availableConfigs
    }

/* Create Config File object of CustomConfig type for provided customhooks */
    def createConfig(hookName, blocking, folderConfigFilesObject, availableConfigs) {
        def unique = true
        def creationDate = new Date().format("yyyyMMdd_HH-mm-ss-SSS", TimeZone.getTimeZone('UTC'))
        def newConfigComments = "This config created at ${creationDate} for hook ${hookName}"

        if (availableConfigs) {
            unique = (availableConfigs.find { config -> config.id == hookName }) ? false : true
        }

        if (unique) {
            folderConfigFilesObject.save(new CustomConfig(hookName, hookName, newConfigComments, blocking))
            println "Pool ${hookName} has been created successfully"
        }
    }
    def getUpstreamJobName(script){
        String upstreamJobName = null
        script.currentBuild.rawBuild.actions.each { action ->
            if(action.hasProperty("causes")) {
                action.causes.each { cause ->
                    if(cause instanceof hudson.model.Cause$UpstreamCause && cause.hasProperty("shortDescription") && cause.shortDescription.contains("Started by upstream project")) {
                        upstreamJobName = cause.upstreamRun.getEnvironment(TaskListener.NULL).get("JOB_BASE_NAME")
                    }
                }
            }
        }
        upstreamJobName
    }
//
///* Remove already existing device pool */
//    def removeConfig(configID, folderConfigFilesObject, availableConfigs) {
//        def poolExists
//        try {
//            if (availableConfigs) {
//                poolExists = (availableConfigs.find { config -> config.id == configID }) ? true : false
//            }
//
//            if (poolExists) {
//                folderConfigFilesObject.remove(configID)
//                println "Pool has been removed successfully"
//            } else {
//                println "Pool with ${configID} ID was not found"
//            }
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to remove pool ${configID}", e)
//        }
//    }

}
