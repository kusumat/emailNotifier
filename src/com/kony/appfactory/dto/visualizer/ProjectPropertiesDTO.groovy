package com.kony.appfactory.dto.visualizer

import com.google.gson.annotations.SerializedName

class ProjectPropertiesDTO implements Serializable {

    @SerializedName("keyStorePassword")
    String keyStorePassword
    @SerializedName("keyPassword")
    String keyPassword
    @SerializedName("keyAlias")
    String keyAlias
    @SerializedName("keyStoreFilePath")
    String keyStoreFilePath

    @SerializedName("iOSP12Password")
    String iOSP12Password
    @SerializedName("iOSP12FilePath")
    String iOSP12FilePath
    @SerializedName("iOSMobileProvision")
    String iOSMobileProvision
    @SerializedName("developmentMethod")
    String developmentMethod

    @SerializedName("protectedModePublicKey")
    String protectedModePublicKey
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
