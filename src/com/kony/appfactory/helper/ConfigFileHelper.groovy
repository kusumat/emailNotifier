package com.kony.appfactory.helper

/* Required to get folder object */
import jenkins.model.Jenkins

/* Required to extract content from from ConfigFiles*/
import hudson.FilePath
import hudson.model.Run
import hudson.model.TaskListener

/* Config File Provider Plugin classes to manipulate config files  */
import org.jenkinsci.lib.configprovider.ConfigProvider
import org.jenkinsci.lib.configprovider.model.Config
import org.jenkinsci.plugins.configfiles.custom.CustomConfig
import org.jenkinsci.plugins.configfiles.folder.FolderConfigFileProperty


/* There are many cases, when we want to use config files. Existing pipeline steps for configs files
*  has lot of disadvantages.
*  This class provides static function to create files, get content etc.
* */
class ConfigFileHelper implements Serializable {

    static createConfigFile(folderName, fileId, content) {
        def folderObject = getFolderObject(folderName)
        def folderConfigFilesObject = getConfigPropertyObject(folderObject)
        def availableConfigs = getAvailableConfigs(folderConfigFilesObject)
        createConfig(fileId, content, folderConfigFilesObject, availableConfigs)
    }

    static getOlderContent(folderName, fileId){
        def folderObject = getFolderObject(folderName)
        def folderConfigFilesObject = getConfigPropertyObject(folderObject)
        def availableConfigs = getAvailableConfigs(folderConfigFilesObject)
        def olderContent = getConfigFileContent(fileId, availableConfigs);
        return olderContent
    }
    static getConfigFileContent(configFileName, availableConfigs) throws IOException {
        String olderContent = "";

        for(Config config : availableConfigs){
            Run<?, ?> build = null;
            FilePath workspace= null;
            ConfigProvider provider = config.getDescriptor();
            List<String> tempFiles = new ArrayList<>();
            tempFiles.add("Mukesh");
            System.out.println("Hooks Config File Are  : "+config.name+ "And Param " + configFileName);
            if((config.name).equals(configFileName)){
                olderContent = config.getDescriptor().supplyContent(config, build, workspace, TaskListener.NULL,tempFiles);
            }
        }
        return olderContent;
    }


    static getFolderObject(folderName) {
        def folderObject = null
        folderObject = Jenkins.instance.getItemByFullName(folderName);
        folderObject
    }

    /* Get Config File Provider property in provided project Folder for storing devices list */
    static getConfigPropertyObject(folderObject) {
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
    static getAvailableConfigs(folderConfigFilesObject) {
        def availableConfigs = null

        if (folderConfigFilesObject) {
            availableConfigs = folderConfigFilesObject.getConfigs()
        }

        availableConfigs
    }

    /* Create Config File object of CustomConfig type for provided device list */
    static createConfig(configFileName, content, folderConfigFilesObject, availableConfigs) {
        def unique = true
        def creationDate = new Date().format("yyyyMMdd_HH-mm-ss-SSS", TimeZone.getTimeZone('UTC'))
        def newConfigComments = "This config created at ${creationDate} for hook ${configFileName}"

        if (availableConfigs) {
            unique = (availableConfigs.find { config -> config.id == configFileName }) ? false : true
        }
        if (unique) {
            folderConfigFilesObject.save(new CustomConfig(configFileName, configFileName, newConfigComments, content))
            println "CustomFile ${configFileName} has been created successfully"
        }
        else{
            folderConfigFilesObject.save(new CustomConfig(configFileName, configFileName, newConfigComments, content))
            println "CustomFile ${configFileName} has been created successfully"
        }
    }
}