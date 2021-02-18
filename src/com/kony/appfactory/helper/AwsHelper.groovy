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
        String bucketUrl = (script.env.S3_BUCKET_URL) ?: script.echoCustom("S3 bucket URL value can't be null!", 'ERROR')
        String s3UrlString = [bucketUrl, artifactPath].join('/')
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
     * Calls S3 Upload with destination path.
     *
     * @param args method named arguments.
     * @param script pipeline object.
     * @param exposeUrl flag to expose S3 artifact URL.
     * @return S3 URL for uploaded artifact.
     */
    /**
     * Calls S3 Upload with destination path.
     *
     * @param args method named arguments.
     * @param script pipeline object.
     * @param exposeUrl flag to expose S3 artifact URL.
     * @return S3 URL for uploaded artifact.
     */
    protected static String publishToS3(Map args, script, boolean exposeUrl = false) {
        String bucketPath = (args.bucketPath) ?: script.echoCustom("bucketPath argument can't be null!", 'ERROR')
        String projectName = (script.env.PROJECT_NAME) ?: script.echoCustom("Project name value can't be null!", 'ERROR')
        String accountId = (script.env.CLOUD_ACCOUNT_ID) ?: script.echoCustom("Account ID value can't be null!", 'ERROR')

        String fullBucketPath = [accountId, projectName, bucketPath].join('/')

        /* Return uploaded artifact S3 URL */
        publishToS3(args, fullBucketPath, script, exposeUrl)
    }

    /**
     * Uploads file/directory to S3 destination path.
     *
     * @param args the map which contains source details that needs to be uploaded.
     * If map contains both sourceFileName and sourceFilePath, then it uploads the specified file to S3.
     * If map contains only the sourceFilePath, then it uploads the files & directories in the specified path to S3.
     * @param finalBucketPath destination S3 bucket url.
     * @param script pipeline object.
     * @param exposeUrl flag to expose S3 artifact URL.
     */
    protected static String publishToS3(Map args, String finalBucketPath, script, boolean exposeUrl = false) {
        String fileName = args.sourceFileName
        String artifactFolder = (args.sourceFilePath) ?: script.echoCustom("artifactFolder argument can't be null!", 'ERROR')
        String s3BucketRegion = (script.env.S3_BUCKET_REGION) ?: script.echoCustom("Bucket region value can't be null!", 'ERROR')
        String bucketName = (script.env.S3_BUCKET_NAME) ?: script.echoCustom("Bucket name value can't be null!", 'ERROR')
        boolean flatten = true

        String S3BucketPath = [bucketName, finalBucketPath].join('/')

        String successMessage = fileName + ' published successfully.'
        String errorMessage = 'Failed to publish ' + fileName + '!'

        if(!fileName) {
            fileName = '*/**'
            flatten = false
            artifactFolder = artifactFolder.endsWith('/') ? artifactFolder.substring(0, artifactFolder.length()-1) : artifactFolder
            String folderName = artifactFolder.substring(artifactFolder.lastIndexOf("/") + 1)
            successMessage = folderName + ' published successfully.'
            errorMessage = 'Failed to publish ' + folderName + '!'
        }

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifactFolder) {
                /* While uploading of KAR file to S3 the content-type is being set as audio/midi so the same format is being downloaded. So setting the content-type from audio/midi to binary/octet-stream.*/
                if (fileName.contains('.KAR')) {
                    script.withAWS(region: s3BucketRegion, role: script.env.S3_CONFIG_BUCKET_IAM_ROLE) {
                        script.s3Upload(bucket: bucketName, path: finalBucketPath, includePathPattern: '*.KAR', contentType: 'binary/octet-stream')
                    }
                } else {
                    script.step([$class                              : 'S3BucketPublisher',
                                 consoleLogLevel                     : 'WARNING',
                                 dontWaitForConcurrentBuildCompletion: false,
                                 entries                             : [
                                         [bucket               : S3BucketPath,
                                          flatten              : flatten,
                                          keepForever          : true,
                                          managedArtifacts     : false,
                                          noUploadOnFailure    : false,
                                          selectedRegion       : s3BucketRegion,
                                          sourceFile           : fileName]
                                 ],
                                 pluginFailureResultConstraint       : 'FAILURE'])
                }
                // collecting artifact for metrics
                def artifact = ["artifact": [finalBucketPath, fileName].join('/')]
                script.statspublish artifact.inspect()
            }
        }
        String artifactUrl = getS3ArtifactUrl(script, [finalBucketPath, fileName].join('/'))

        if (exposeUrl) {
            script.echoCustom("Artifact($fileName) URL: ${artifactUrl}")
        }

        /* Return uploaded artifact S3 URL */
        artifactUrl
    }

    /**
     * Downloads the child job artifacts and then deletes them from S3 while preparing musthaves.
     * @param mustHaveArtifacts Artifacts that are to be captured in musthaves zip file.
     */
    static void downloadChildJobMustHavesFromS3(script, mustHaveArtifacts) {
        mustHaveArtifacts.each { mustHaveArtifact ->
            if (mustHaveArtifact.path.trim().length() > 0) {
                s3Download(script, mustHaveArtifact.name, [mustHaveArtifact.path, mustHaveArtifact.name].join('/'));
                s3Delete(script, [mustHaveArtifact.path, mustHaveArtifact.name].join('/'))
            }
        }
    }

    /**
     * Publish MustHaves artifacts to s3, create authenticated Url and sets the environment variable MUSTHAVE_ARTIFACTS.
     * @param script current build instance
     * @param s3ArtifactPath Path where we are going to publish the S3 artifacts
     * @param mustHaveFile The file for which we are going to create a zip
     * @param projectFullPath The full path of the project for which we are creating the MustHaves
     * @param upstreamJob Indicates whether there is any parent job or not
     * @param isRebuild Indicates whether this is a rebuilt job or a fresh job
     * @param channelVariableName The channel for which we are creating the MustHaves
     * @param mustHaves Collection which contains all the artifacts that you want to collect in MustHaves
     * @return s3MustHaveAuthUrl The authenticated URL of the S3 url
     */
    protected static def publishMustHavesToS3(script, s3ArtifactPath, mustHaveFile, projectFullPath, upstreamJob, isRebuild, channelVariableName, mustHaves) {
        String s3FullMustHavePath = [script.env.CLOUD_ACCOUNT_ID, script.env.PROJECT_NAME, s3ArtifactPath].join('/')
        String s3MustHaveUrl = publishToS3 sourceFileName: mustHaveFile,
                sourceFilePath: projectFullPath, s3FullMustHavePath, script
        def s3MustHaveAuthUrl = BuildHelper.createAuthUrl(s3MustHaveUrl, script, false)
        /* We will be keeping the s3 url of the must haves into the collection only if the
         * channel job is triggered by the parent job that is buildVisualiser job.
         * Handling the case where we rebuild a child job, from an existing job which was
         * triggered by the buildVisualiser job.
         */
        if (upstreamJob != null && !isRebuild) {
            mustHaves.add([
                    channelVariableName: channelVariableName, name: mustHaveFile, path: s3FullMustHavePath
            ])
            script.env['MUSTHAVE_ARTIFACTS'] = mustHaves?.inspect()
        }

        s3MustHaveAuthUrl
    }

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

    /**
     * This is utility method to delete artifacts from s3 from a specified path.
     *  -> This method always refers to build bucket configured for AppFactory.
     *  -> It used environment variables configured in Jenkins configure screen.
     * @param script
     * @param filePath S3 Path to file to be Deleted 
     *
     * Documentation: https://jenkins.io/doc/pipeline/steps/pipeline-aws/#s3delete-delete-file-from-s3
     */
    static void s3Delete(script, String filePath) {
        script.withAWS(region: script.env.S3_BUCKET_REGION, role: script.env.S3_BUCKET_IAM_ROLE) {
            script.s3Delete(bucket: script.env.S3_BUCKET_NAME, path: filePath)
        }
    }
}