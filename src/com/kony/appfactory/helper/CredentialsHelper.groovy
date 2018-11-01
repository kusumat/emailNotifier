package com.kony.appfactory.helper

import com.cloudbees.plugins.credentials.impl.*;
import com.cloudbees.plugins.credentials.*
import org.jenkinsci.plugins.plaincredentials.*
import org.jenkinsci.plugins.plaincredentials.impl.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import com.kony.AppFactory.Jenkins.credentials.impl.ProtectedModeTriplet
import com.kony.AppFactory.Jenkins.credentials.impl.MobileFabricAppTriplet
import org.apache.commons.fileupload.FileItem
import com.cloudbees.plugins.credentials.domains.*;

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
    private void addUserCredentials(Credentials credentials) {
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
        addUserCredentials(credentials)
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
        addUserCredentials(credentials)
        return id
    }

    /**
     *   This function helps in deleting the list of credential id's passed
     *   @param credentialIds   This parameter is the id of the credential
     */
    void deleteUserCredentials(credentialIds) {

        for(credentialId in credentialIds) {
            Credentials credential = getCredentialsById(credentialId)
            if(credential) {
                removeCredentials(credential)
            }
        }
    }
}