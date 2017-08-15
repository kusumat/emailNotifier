package com.kony.appfactory.helper

/**
 * Implements logic related to Amazon Web Services.
 */
class AWSHelper implements Serializable {
    @NonCPS
    protected static getS3ArtifactURL(script, artifactPath) {
        def bucketName = script.env.S3_BUCKET_NAME
        def bucketRegion = (script.env.S3_BUCKET_REGION == 'us-east-1') ? '' : "-${script.env.S3_BUCKET_REGION}"
        def projectName = script.env.PROJECT_NAME
        def s3URL = "https://s3${bucketRegion}.amazonaws.com"

        return [s3URL, bucketName, projectName, artifactPath].join('/')
    }

    protected static void publishToS3(args) {
        def script = args.script
        String fileName = args.sourceFileName
        String bucketPath = [script.env.S3_BUCKET_NAME, script.env.PROJECT_NAME, args.bucketPath].join('/')
        String bucketRegion = script.env.S3_BUCKET_REGION
        String artifactFolder = args.sourceFilePath
        String artifactURL = getS3ArtifactURL(script, [args.bucketPath, fileName].join('/'))
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
                    script.echo "Artifact($fileName) URL: $artifactURL"
                }
            }
        }
    }
}
