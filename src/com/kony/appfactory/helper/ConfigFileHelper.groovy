package com.kony.appfactory.helper

import hudson.FilePath
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import org.jenkinsci.lib.configprovider.ConfigProvider
import org.jenkinsci.lib.configprovider.model.Config
import org.jenkinsci.plugins.configfiles.custom.CustomConfig
import org.jenkinsci.plugins.configfiles.folder.FolderConfigFileProperty


class ConfigFileHelper {

    def createConfigFile(folderName, fileId, content) {
        def folderObject = getFolderObject(folderName)
        FolderConfigFileProperty folderConfigFilesObject = getConfigPropertyObject(folderObject)
        Collection<Config> availableConfigs = getAvailableConfigs(folderConfigFilesObject)
        createConfig(fileId, content, folderConfigFilesObject, availableConfigs)
    }

    def getOlderContent(folderName, fileId){
        def folderObject = getFolderObject(folderName)
        FolderConfigFileProperty folderConfigFilesObject = getConfigPropertyObject(folderObject)
        Collection<Config> availableConfigs = getAvailableConfigs(folderConfigFilesObject)
        def olderContent = getConfigFileContent(fileId, availableConfigs);
        return olderContent
    }
    String getConfigFileContent(String configFileName, Collection<Config> availableConfigs) throws IOException {
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
    /*
    To be able to store devices for the test with Config File Provider,
    we need to get Folder object where we want to store devices list first.
    */
    def getFolderObject(folderName) {
        def folderObject = null
        folderObject = Jenkins.instance.getItemByFullName(folderName);
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
    def createConfig(configFileName, content, folderConfigFilesObject, availableConfigs) {
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