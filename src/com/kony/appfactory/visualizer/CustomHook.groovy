package com.kony.appfactory.visualizer

import com.kony.appfactory.helper.BuildHelper
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import com.kony.appfactory.helper.AwsHelper
import org.jenkinsci.plugins.configfiles.custom.CustomConfig
import org.jenkinsci.plugins.configfiles.folder.FolderConfigFileProperty

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
            script.stage('Check provided parameters') {
                script.echo("Logic not yet implemented")
                script.echo(hookName + buildStep + buildAction)
                script.echo(script.env.toString())
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
                        script.echo("Downlaod url (inside) : ${script.env.ARTIFACT_URL}" )
                        script.jobDsl  targets: "${checkoutTo}/${dslScriptName}"
                    }
                }
            }
        }
    }

    protected final void processPipeline(){
        def outputFile = "Hook.zip"
        script.node(libraryProperties.'test.automation.node.label'){
            script.stage("Clean"){
                script.cleanWs deleteDirs: true
            }
            script.stage("Download Hook Scripts"){
                script.httpRequest url: script.params.BUILD_SCRIPT, outputFile: outputFile, validResponseCodes: '200'
            }
            script.stage("Extract Hook"){
                script.sh "mkdir Hook"
                script.unzip zipFile:"Hook.zip"
            }

            script.stage("Run Script"){
                if(script.params.BUILD_ACTION == "Execute Ant"){
                    script.sh "/home/jenkins/apache-ant-1.9.9/bin/ant -f build.xml ${scriptArguments}"
                }
                else if(script.params.BUILD_ACTION == "Execute Maven"){
                    script.sh "mvn ${scriptArguments}"
                }
                else{
                    script.echo("unknown build script ")
                }
            }
        }
    }
    def createConfigFile() {
        def folderObject = getFolderObject(projectName)
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

/* Create Config File object of CustomConfig type for provided device list */
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
