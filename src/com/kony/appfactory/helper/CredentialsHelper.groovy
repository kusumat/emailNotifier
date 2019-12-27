package com.kony.appfactory.helper

import org.jenkinsci.plugins.plaincredentials.impl.*
import com.cloudbees.plugins.credentials.impl.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import com.cloudbees.plugins.credentials.domains.*

import com.kony.AppFactory.Jenkins.credentials.impl.ProtectedModeTriplet
import com.kony.AppFactory.Jenkins.credentials.impl.AppleSigningCertificateConfigImpl

import jenkins.model.Jenkins
import hudson.model.FileParameterValue
import hudson.util.Secret
import hudson.FilePath
import hudson.remoting.VirtualChannel

import org.apache.commons.fileupload.FileItem
import org.apache.commons.io.IOUtils

/**
 * This class helps in creating the credentials at run time
 */
class CredentialsHelper implements Serializable{

    private final String protectedModePublicKeyFileName = "publicKey.dat"
    private final String protectedModePrivateKeyFileName = "privateKey.pem"
    private final String protectedModeFinKeysFileName = "finKeys.zip"


    /**
     * This function helps in adding the credentials
     * @param credentials holds the credential object that has to be added
     */
    private void addCredentials(Credentials credentials) {
        if (credentials) {
            SystemCredentialsProvider.getInstance().getStore().addCredentials(Domain.global(), credentials)
        }
    }

    /**
     *  This function helps in removing the credentials
     *  @param credtials holds the credential object that has to be removed
     */
    private void removeCredentials(Credentials credentials) {
        if (credentials) {
            SystemCredentialsProvider.getInstance().getStore().removeCredentials(Domain.global(), credentials)
        }
    }

    /**
     * This function helps in getting the list of credentials
     */
    protected  List<Credentials> getCredentials() {
        List<Credentials> credentialsList = CredentialsProvider.lookupCredentials(
                Credentials.class,
                Jenkins.instance,
                null,
                null);

        return credentialsList
    }

    /**
     * This function helps in extracting credential object by id
     * @param id holds the value of the credential id which has to be extracted
     * @return Credentials object is returned by matching id with existing list of credentials
     */
    protected Credentials getCredentialsById(String id) {
        for (credential in getCredentials()) {
            if (credential.id.equals(id)) {
                return credential
            }
        }
        return null
    }



    /**
     * This will return the file item object for the given file
     * @param filePath  location of the file
     * @param fileName  This will be set as the file name for the FileItem object
     * @return FileItem This is the file object, which is expected by the add credentials api from jenkins
     */
    private FileItem getFileItem(String filePath, String fileName){
        File file = new File(filePath)
        FileItem resultFile = [ getName: { return fileName },  get: { return file.getBytes() } ] as FileItem
        return resultFile
    }

    /** This function helps in adding the protected mode triplet
     * @param id           This parameter refers to the credential ID
     * @param description  This parameter referes to the description for the credential description
     * @param publicKey    This is one of the protected mode file, *.dat is the extension supported
     * @param privateKey   This is one of the protected mode file, *.key is the extension supported
     * @param finKey       This is one of the protected mode file, *.zip is the extension supported
     */
    private void addProtectedModeTripletCredentials(String id, FileItem publicKey, FileItem privateKey, FileItem finKey, String description) {
        def checkParams = [id, publicKey, privateKey, finKey]
        def validParams = ValidationHelper.checkForNull(checkParams)
        if(validParams.size() != checkParams.size())
            return
        credentials = (Credentials) new ProtectedModeTriplet(CredentialsScope.GLOBAL, id, publicKey, privateKey, finKey, description)
        addCredentials(credentials)
    }

    /** This function helps in adding the protected mode triplet
     * @param id           This parameter refers to the credential ID
     * @param publicKeyFilePath    This is one of the protected mode file path, *.dat is the extension supported
     * @param privateKeyFilePath   This is one of the protected mode file path, *.key is the extension supported
     * @param finKeysFilePath       This is one of the protected mode filepath, *.zip is the extension supported
     * @param description  This parameter referes to the description for the credential description
     */
    void addProtectedModeTriplet(String id, String publicKeyFilePath, String privateKeyFilePath, String finKeysFilePath, String description) {
        def checkParams = [id, publicKeyFilePath, privateKeyFilePath, finKeysFilePath]
        ValidationHelper.checkForNull(checkParams)
        addProtectedModeTripletCredentials(id, getFileItem(publicKeyFilePath, protectedModePublicKeyFileName), getFileItem(privateKeyFilePath, protectedModePrivateKeyFileName), getFileItem(finKeysFilePath, protectedModeFinKeysFileName), description)
    }



    /**
     * This function helps in adding the username and password credential type
     * @param id This parameter refers to the credential ID
     * @param description This parameter refers to the description for the credential description
     * @param username This is the username for the credential
     * @param password This is the password for the credential
     */
    String addUsernamePassword(String id, String description, String username, String password) {
        Credentials credentials = (Credentials) new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, description, username, password)
        addCredentials(credentials)
        return id
    }


    /**
     * This function helps in adding the secret text credential type
     * @param id This parameter refers to the credential ID
     * @param description This parameter refers to the description for the credential description
     * @param secretText This is the text to be store as secretText
     */
    String addSecretText(String id, String description, String secretText) {
        addCredentials((Credentials) new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, description, Secret.fromString(secretText)))
        return id
    }


    /**
     * This function helps in adding the secret File as credential type
     * @param id This parameter refers to the credential ID
     * @param description This parameter refers to the description for the credential description
     * @param absFilePath This is the absolute location of file to be store as secret File
     * @param script This is the current build instance
     */
    String addSecretFile(id, description, absFilePath, script) {

        def currentComputerName = script.env['NODE_NAME']
        def currentComputer = getCurrentComputer(currentComputerName);

        FilePath filePath = new FilePath(currentComputer.getChannel(), absFilePath);

        byte[] bytes = IOUtils.toByteArray(filePath.read())
        def secretBytes = SecretBytes.fromBytes(bytes)

        def credentials = new FileCredentialsImpl(CredentialsScope.GLOBAL, id, description, 'file.txt', secretBytes)

        addCredentials((Credentials) credentials)
        return id
    }

    /**
     * Add apple manual cert as global jenkins credential
     * @param id - Credential id for apple cert credential
     * @param description - description for apple cert credential
     * @param provCertPwd - password for apple p12 cert
     * @param absProvCertPath - absolute path to apple p12 cert
     * @param absMobileProvisionPath - absolute path to apple provisioning profile
     * @param script - current build instance
     * @return
     */
    def addAppleCert(String id,
                     String description,
                     String provCertPwd,
                     String absProvCertPath,
                     String absMobileProvisionPath,
                     script) {

        def currentComputerName = script.env['NODE_NAME']
        def currentComputer = getCurrentComputer(currentComputerName)

        FileItem provCertKey = getFileItemFromChannelWSPath(
                currentComputer.getChannel(),
                absProvCertPath,
                'temp.p12')
        FileItem mobileProvision = getFileItemFromChannelWSPath(
                currentComputer.getChannel(),
                absMobileProvisionPath,
                'temp1.mobileprovision')

        addAppleCertToJenkins(id, description, new Secret(provCertPwd), provCertKey, mobileProvision)
    }

    /**
     * Helps in reading file from Slave workspace as FileItem
     * @param channel : this is channel using which jenkins talk to process running in agent
     * @param absFilePath : absolute target file path in agent file system
     * @param fileName : name of file with extension
     * @return
     */
    def getFileItemFromChannelWSPath(VirtualChannel channel, String absFilePath, String fileName) {

        FilePath filePath = new FilePath(channel, absFilePath)
        byte[] bytes = IOUtils.toByteArray(filePath.read())

        String[] splitFileName = fileName.split('\\.')
        String prefix = splitFileName[0]
        String suffix = '.' + splitFileName[1]

        File tempFile = File.createTempFile(prefix, suffix, null)
        FileOutputStream fos = new FileOutputStream(tempFile)
        fos.write(bytes)

        return new FileParameterValue.FileItemImpl(tempFile)
    }

    /**
     * This is helper method for addAppleCert method. Please use addAppleCert method to
     * add apple credential.
     * This is private method which is responsible for creating and uploading credential in Jenkins.
     */
    private final String addAppleCertToJenkins(String id, String description, Secret provCertPwd, FileItem provCertKey, FileItem mobileProvision) {
        Credentials appleCert = new AppleSigningCertificateConfigImpl(
                CredentialsScope.GLOBAL,
                id,
                provCertKey,
                provCertPwd,
                mobileProvision,
                description)
        addCredentials(appleCert)
        return id
    }

    /**
     * This function helps in getting current Computer(Slave) instance in which build is currently running
     * @param computerName This parameter refers to Computer name
     */
    def getCurrentComputer(computerName) {
        def myComputer = Jenkins.getInstance().getComputers().find { computer ->
            return computer.getDisplayName().equals(computerName)
        }
        return myComputer
    }

    /**
     * This function helps in deleting the list of credential id's passed
     * @param credentialIds This parameter is the id of the credential
     */
    void deleteUserCredentials(credentialIds) {

        for(credentialId in credentialIds) {
            Credentials credential = getCredentialsById(credentialId)
            if(credential) {
                removeCredentials(credential)
            }
        }
    }

    /**
     * This function return static credential metadata which is being used to create
     * credential dynamically
     * @param suffix This is option parameter. If specified credential Id will be suffixed by given string
     */
    def getSigningIdMap(suffix = "") {
        return [
                android: [
                        KSPASS   : [id: addSuffix("KSPASS_SECRET_TEXT_ID", suffix), description: "Keystore password"],
                        KEYPASS  : [id: addSuffix("KEYPASS_SECRET_TEXT_ID", suffix), description: "Key password"],
                        KEY_ALIAS: [id: addSuffix("KEY_ALIAS_SECRET_TEXT_ID", suffix), description: "Key alias of keystore"],
                        KSFILE   : [id: addSuffix("KSFILE_SECRET_TEXT_ID", suffix), description: "Key store file"]
                ],
                iOS : [
                        APPLE_SIGNING_CERT : [id: addSuffix("APPLE_SIGNING_CERT_ID", suffix), description: "Apple cert to sign ios binary"]
                ]
        ]
    }

    /**
     * This function helps in appending suffix to credential id
     * @param credentialId This parameter is the id of the credential
     * @param suffix This parameter is suffix which will be appended to credential id
     */
    def addSuffix(String credentialId, String suffix) {
        return (suffix) ? [credentialId, suffix].join('-') : credentialId
    }
}