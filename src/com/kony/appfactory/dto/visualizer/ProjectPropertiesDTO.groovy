package com.kony.appfactory.dto.visualizer

import com.google.gson.annotations.SerializedName
import com.kony.appfactory.annotations.Path

class ProjectPropertiesDTO implements Serializable {

    @SerializedName("keyStorePassword")
    String keyStorePassword
    @SerializedName("keyPassword")
    String keyPassword
    @SerializedName("keyAlias")
    String keyAlias
    @Path
    @SerializedName("keyStoreFilePath")
    String keyStoreFilePath

    @SerializedName("iOSP12Password")
    String iOSP12Password
    @Path
    @SerializedName("iOSP12FilePath")
    String iOSP12FilePath
    @Path
    @SerializedName("iOSMobileProvision")
    String iOSMobileProvision
    @SerializedName("developmentMethod")
    String developmentMethod

    @Path
    @SerializedName("protectedModePublicKey")
    String protectedModePublicKey
    @Path
    @SerializedName("protectedModePrivateKey")
    String protectedModePrivateKey

    @Override
    String toString() {
        return "keyStorePassword : $keyStorePassword \n" +
                "keyPassword : $keyPassword \n" +
                "keyAlias : $keyAlias \n" +
                "keyStoreFilePath : $keyStoreFilePath\n" +
                "iOSP12Password : $iOSP12Password \n" +
                "iOSP12FilePath : $iOSP12FilePath \n" +
                "iOSMobileProvision: $iOSMobileProvision \n" +
                "developmentMethod : $developmentMethod \n" +
                "protectedModePublicKey : $protectedModePublicKey \n" +
                "protectedModePrivateKey : $protectedModePrivateKey \n"
    }
}
