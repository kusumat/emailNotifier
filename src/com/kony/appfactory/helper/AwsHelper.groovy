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
        String bucketUrl = (script.env.S3_BUCKET_URL) ?: script.error("S3 bucket URL is missing!")
        String projectName = (script.env.PROJECT_NAME) ?: script.error("Project name is missing!")
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

    protected static publishToS3(args) {
        def script = args.script
        String fileName = args.sourceFileName
        String bucketPath = [script.env.S3_BUCKET_NAME, script.env.PROJECT_NAME, args.bucketPath].join('/')
        String bucketRegion = script.env.S3_BUCKET_REGION
        String artifactFolder = args.sourceFilePath
        String artifactUrl = getS3ArtifactUrl(script, [args.bucketPath, fileName].join('/'))
        String successMessage = 'Artifact published successfully'
        String errorMessage = 'FAILED to publish artifact'

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifactFolder) {
                script.step([$class                              : 'S3BucketPublisher',
                             consoleLogLevel                     : 'INFO',
                             dontWaitForConcurrentBuildCompletion: false,
                             entries                             : [
                                     [bucket           : bucketPath,
                                      flatten          : true,
                                      keepForever      : true,
                                      managedArtifacts : false,
                                      noUploadOnFailure: true,
                                      selectedRegion   : bucketRegion,
                                      sourceFile       : fileName]
                             ],
                             pluginFailureResultConstraint       : 'FAILURE'])
                if (args.exposeURL) {
                    script.echo "Artifact($fileName) URL: ${artifactUrl}"
                }
            }
        }

        artifactUrl
    }
}
