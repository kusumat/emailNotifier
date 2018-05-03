package com.kony.appfactory.helper

/**
 * Implements logic related to Amazon Web Services.
 */
class AwsHelper implements Serializable {
    /**
     * Generates s3 URL for provided artifact path.
     *
     * @param script pipeline object.
     * @param artifactPath artifact path on s3 bucket.
     * @return s3 URL for artifact, all special characters in URL path will be escaped.
     */
    protected static String getS3ArtifactUrl(script, String artifactPath) {
        URL s3Url
        URI s3Uri
        /*
            S3_BUCKET_URL must contain s3 base URL in a virtual-hosted–style,
            the bucket name is part of the domain name in the URL.
            For example: http://bucket.s3.amazonaws.com
         */
        String bucketUrl = (script.env.S3_BUCKET_URL) ?: script.echoCustom("S3 bucket URL value can't be null!",'ERROR')
        String projectName = (script.env.PROJECT_NAME) ?: script.echoCustom("Project name value can't be null!",'ERROR')
        String s3Path = [projectName, artifactPath].join('/')
        String s3UrlString = [bucketUrl, s3Path].join('/')

        script.catchErrorCustom('Artifact S3 URL is not valid!') {
            /* Transform s3 URL into a URL object, to be able to get protocol, host, path for next step */
            s3Url = s3UrlString.toURL()
            /*
                Construct a URI by parsing scheme(protocol), host, path, fragment(null).
                This step been added, to be able to escape spaces and rest of specials characters in s3 bucket path.
            */
            s3Uri = new URI(s3Url.protocol, s3Url.host, s3Url.path, null)
        }

        /* Return escaped s3 artifact URL as a string */
        s3Uri.toString()
    }

    /**
     * Uploads file on S3 to provided location.
     *
     * @param args method named arguments.
     * @param script pipeline object.
     * @param exposeUrl flag to expose S3 artifact URL.
     * @return S3 URL for uploaded artifact.
     */
    protected static String publishToS3(Map args, script, boolean exposeUrl = false) {
        String fileName = (args.sourceFileName) ?: script.echoCustom("fileName argument can't be null!",'ERROR')
        String projectName = (script.env.PROJECT_NAME) ?: script.echoCustom("Project name value can't be null!",'ERROR')
        String bucketName = (script.env.S3_BUCKET_NAME) ?: script.echoCustom("Bucket name value can't be null!",'ERROR')
        String bucketPath = (args.bucketPath) ?: script.echoCustom("bucketPath argument can't be null!",'ERROR')
        String bucketRegion = (script.env.S3_BUCKET_REGION) ?: script.echoCustom("Bucket region value can't be null!",'ERROR')
        String fullBucketPath = [bucketName, projectName, bucketPath].join('/')
        String artifactFolder = (args.sourceFilePath) ?: script.echoCustom("artifactFolder argument can't be null!",'ERROR')
        String artifactUrl = getS3ArtifactUrl(script, [bucketPath, fileName].join('/'))
        String successMessage = fileName + ' published successfully.'
        String errorMessage = 'Failed to publish ' + fileName + '!' 

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifactFolder) {
                script.step([$class                              : 'S3BucketPublisher',
                             consoleLogLevel                     : 'WARNING',
                             dontWaitForConcurrentBuildCompletion: false,
                             entries                             : [
                                     [bucket           : fullBucketPath,
                                      flatten          : true,
                                      keepForever      : true,
                                      managedArtifacts : false,
                                      noUploadOnFailure: false,
                                      selectedRegion   : bucketRegion,
                                      sourceFile       : fileName]
                             ],
                             pluginFailureResultConstraint       : 'FAILURE'])

            }
        }

        if (exposeUrl) {
            script.echoCustom("Artifact($fileName) URL: ${artifactUrl}")
        }

        /* Return uploaded artifact S3 URL */
        artifactUrl
    }
}
