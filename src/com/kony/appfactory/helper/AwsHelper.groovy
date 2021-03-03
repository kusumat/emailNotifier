package com.kony.appfactory.helper

/**
 * Implements logic related to Amazon Web Services.
 */
class AwsHelper implements Serializable {
    /**
     * This is utility method to download artifacts from s3 from a specified path.
     *  -> This method always refers to build bucket configured for AppFactory.
     *  -> It used environment variables configured in Jenkins configure screen.
     * @param script
     * @param fileName Name of file needs to be downloaded
     * @param filePath S3 Path to file to be downloaded
     */
    static void s3Download(script, String fileName, String filePath) {
        script.withAWS(region: script.env.S3_BUCKET_REGION, role: script.env.S3_BUCKET_IAM_ROLE) {
            script.s3Download bucket: script.env.S3_BUCKET_NAME,
                    file: fileName,
                    force: true,
                    path: filePath
        }
    }
}