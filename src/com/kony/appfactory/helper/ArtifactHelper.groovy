package com.kony.appfactory.helper

import java.net.URLDecoder

/**
 * Implements logic related to channel build process.
 */
class ArtifactHelper implements Serializable {

    /**
     * Fetches artifact from artifactStorage (S3 or Master or other) using DownloadArtifact build step from storage plugin.
     * @param jobName job name
     * @param buildId build number
     * @param artifactName artifact name
     */
    static void retrieveArtifact(script, String jobName, String buildId, String artifactName) {
        script.withStorageEnv {
            script.downloadArtifact(job: jobName, buildId: buildId, artifactName: artifactName)
        }
    }

    /**
     * Fetches artifact from artifactStorage (S3 or Master or other) using DownloadArtifact build step from storage plugin.
     * @param artifactName artifact name
     */
    static void retrieveArtifact(script, String artifactName) {
        script.withStorageEnv {
            script.downloadArtifact(artifactName: artifactName)
        }
    }

    /**
     * Fetches artifact via provided URL.
     * Checks if URL contains S3 bucket path, then simply fetches from S3 using aws s3 utility.
     * Similarly if URL is a master archive link (local storage artifact link), then fetches using DownloadArtifact build step from storage plugin.
     * If URL is not S3 nor master archive link, then fetches using curl command line utility.
     * @param script pipeline object.
     * @param artifactName artifact name.
     * @param artifactUrl artifact URL.
     */
    protected static void retrieveArtifact(script, def artifactName, def artifactUrl) {
        if (!artifactUrl.startsWith(script.env.JENKINS_URL) && !artifactUrl.contains("/artifact/")) {
            String successMessage = 'Artifact ' + artifactName + ' fetched successfully'
            String errorMessage = 'Failed to fetch artifact ' + artifactName
            artifactUrl = artifactUrl.replace(' ', '%20')
            script.catchErrorCustom(errorMessage, successMessage) {
                /* We need to check artifactUrl link is containing S3 bucket name that appfactory instance is pointing,
                 * if so instead of downloading through https URL we can simply copy directly from S3.
                 * This eliminates unnecessary burden of processing signed URLs for downloading artifact passed by Facade job.
                 **/
                artifactUrl = (artifactUrl) ? (artifactUrl.contains(script.env.S3_BUCKET_NAME) ?
                        artifactUrl.replaceAll('https://'+script.env.S3_BUCKET_NAME+'(.*)amazonaws.com',
                                's3://'+script.env.S3_BUCKET_NAME) : artifactUrl) : ''
                if (artifactUrl.startsWith('http://') || artifactUrl.startsWith('https://')) {
                    script.shellCustom("curl -k -s -S -f -L -o \'${artifactName}\' \'${artifactUrl}\'", true)
                }
                else {
                    /* copy from S3 bucket without printing expansion of command on console */
                    String artifactUrlDecoded = URLDecoder.decode(artifactUrl, "UTF-8")
                    def s3uri = 's3://'+script.env.S3_BUCKET_NAME+'/'
                    def artifactPath =  artifactUrlDecoded.replaceAll(s3uri, "")
                    retrieveArtifact(script, artifactPath)
                    def artifactNameFromURL = artifactUrl.substring(artifactUrl.lastIndexOf("/") + 1)
                    script.shellCustom("mv \'${artifactNameFromURL}\' \'${artifactName}\'", true)
                }
            }
        }
        else {
            /**
             * Check the URL is from the current jenkins master artifact URL, if so retrive exact jobName, buildID and artifactName from the given link.
             * Eg - artifactURL passed: https://a100000005001.ci.dev-temenos-cloud.net/job/Test2599/job/Iris/job/Builds/job/Channels/job/buildAndroid/59/artifact/100000005/Test2599/Builds/AppFactoryServer/Android/Mobile/Native/59/Test2599_59.apk
             * retrieve buildJob: Test2599/Iris/Builds/Channels/buildAndroid
             * retrieve buildID: 59
             * retrieve buildArtifactName: 100000005/Test2599/Builds/AppFactoryServer/Android/Mobile/Native/59/Test2599_59.apk
             */
            if (artifactUrl.startsWith(script.env.JENKINS_URL) && artifactUrl.contains("/artifact/")) {
                def artifactString = artifactUrl.replaceAll(script.env.JENKINS_URL+'job/', "")
                String[] jobWithBuildIDString = artifactString.split("/artifact/")
                def jobWithBuildID = jobWithBuildIDString[0]
                def buildArtifactName = jobWithBuildIDString[1]
                def buildJob = jobWithBuildID.substring(0, jobWithBuildID.lastIndexOf("/")).replaceAll("/job/", "/")
                def buildID = jobWithBuildID.substring(jobWithBuildID.lastIndexOf("/") + 1)
                retrieveArtifact(script, buildJob, buildID, buildArtifactName)
                def artifactNameFromURL = artifactUrl.substring(artifactUrl.lastIndexOf("/") + 1)
                script.shellCustom("mv \'${artifactNameFromURL}\' \'${artifactName}\'", true)
            } else {
                if (artifactUrl.startsWith('http://') || artifactUrl.startsWith('https://')) {
                    script.shellCustom("curl -k -s -S -f -L -o \'${artifactName}\' \'${artifactUrl}\'", true)
                }
            }
        }
    }


    /**
     * Deletes artifact from artifactStorage (S3 or Master or other) for the given job and it's buildID using DeleteArtifact build step from storage plugin.
     * @param script pipeline object.
     * @param jobName full job name from where artifact to be deleted.
     * @param buildId build number for the given job name from where artifact to be deleted.
     * @param artifactName the exact artifact to be deleted.
     */
    static void deleteArtifact(script, String jobName, String buildId, String artifactName) {
        script.withStorageEnv {
            script.deleteArtifact(job: jobName, buildId: buildId, artifactName: artifactName)
        }
    }

    /**
     * Calls publishArtifact with destination path.
     *
     * @param args method named arguments.
     * @param script pipeline object.
     * @param exposeUrl flag to expose artifact URL.
     * @return URL for the uploaded artifact.
     */
    protected static String publishArtifact(Map args, script, boolean exposeUrl = false) {
        String destinationPath = (args.destinationPath) ?: script.echoCustom("bucketPath argument can't be null!", 'ERROR')
        String projectName = (script.env.PROJECT_NAME) ?: script.echoCustom("Project name value can't be null!", 'ERROR')
        String accountId = (script.env.CLOUD_ACCOUNT_ID) ?: script.echoCustom("Account ID value can't be null!", 'ERROR')

        // We prefix the destinationPath with a static name constructed with accountId/ProjectName for easy to browse through,
        // specially in S3 and maintaining same across all storage types.
        destinationPath = [accountId, projectName, destinationPath].join('/')

        /* Return uploaded artifact  */
        publishArtifact(args, destinationPath, script, exposeUrl)
    }


    /**
     * Uploads artifact to artifactStorage (S3 or Master or other) using UploadArtifact build step from storage plugin.
     *
     * @param args the map which contains source details that needs to be uploaded.
     * If map contains both sourceFileName and sourceFilePath, then it uploads the specified file to artifact Storage .
     * If map contains only the sourceFilePath, then it uploads the files & directories in the specified path to artifact Storage .
     * @param archivePath is the location where the artifacts stored on artifact Storage  type (S3 or Master or other).
     * @param script pipeline object.
     * @param exposeUrl flag to expose artifact URL.
     */
    protected static String publishArtifact(Map args, String archivePath, script, boolean exposeUrl = false) {
        String fileName = args.sourceFileName
        String artifactFolder = (args.sourceFilePath) ?: script.echoCustom("sourceFilePath argument can't be null!", 'ERROR')

        String successMessage = fileName + ' published successfully.'
        String errorMessage = 'Failed to publish ' + fileName + '!'
        def artifactURL

        if(!fileName) {
            fileName = '*/**'
            artifactFolder = artifactFolder.endsWith('/') ? artifactFolder.substring(0, artifactFolder.length()-1) : artifactFolder
            String folderName = artifactFolder.substring(artifactFolder.lastIndexOf("/") + 1)
            successMessage = folderName + ' published successfully.'
            errorMessage = 'Failed to publish ' + folderName + '!'
        }

        script.catchErrorCustom(errorMessage, successMessage) {
            script.dir(artifactFolder) {
                script.withStorageEnv {
                    archivePath = archivePath.endsWith('/') ?: archivePath + '/'
                    artifactURL = script.uploadArtifact(file:fileName, path:archivePath)
                }
                if(!fileName.endsWith('/')) {
                    def artifactInfo = ["artifact": archivePath + fileName]
                    script.statspublish artifactInfo.inspect()
                }
            }
        }

        String artifactUrl = getArtifactUrl(script, artifactURL, archivePath + fileName)

        if (exposeUrl) {
            script.echoCustom("Artifact($fileName) URL: ${artifactUrl}")
        }

        /* Return uploaded artifact URL */
        artifactUrl
    }


    /**
     * Generates artifact URL for the provided artifact path by referring the artifactStorage type.
     *
     * @param script pipeline object.
     * @param artifactPath artifact path on artifactStorage  (S3 or Master or other).
     * @return URL for artifact, all special characters in URL path will be escaped.
     */
    protected static String getArtifactUrl(script, artifactURL, String artifactPath) {
        if (!artifactURL.startsWith(script.env.JENKINS_URL) && !artifactURL.contains("/artifact/")) {
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
            return s3Uri.toString()
        }
        else {
            String filePath = ["artifact", artifactPath].join('/')
            return script.env.BUILD_URL + filePath
        }
    }

    /*
     * Checks the artifactURL is of S3 Storage type then provides the signed url (authenticated urls from manage console). For Master archived urls nothing is performed,
     * as the artifactUrl is already a protected url served through jenkins master url.
     * @param artifactUrl is the url which we want to convert as authenticated
     * @param script is default script parameter
     * @param exposeUrl is made as true if we want to display it in the console
     * @param action - (downloads or view): decides whether the url is direct download link or directly view from browser (such as HTML files), default value is "downloads"
     */
    protected final static createAuthUrl(artifactUrl, script, boolean exposeUrl = false, String action = "downloads") {

        def authArtifactUrl

        if (!artifactUrl.startsWith(script.env.JENKINS_URL) && !artifactUrl.contains("/artifact/")) {
            if (script.env['CLOUD_ENVIRONMENT_GUID'] && script.env['CLOUD_DOMAIN']) {
                String searchString = [script.env.CLOUD_ACCOUNT_ID, script.env.PROJECT_NAME].join("/")
                //artifactUrl is already encoded but only for space and double quote character. Avoid double encoding for these two special characters.
                def subStringIndex = 0
                if (artifactUrl.indexOf(searchString) > 0)
                    subStringIndex = artifactUrl.indexOf(searchString)
                def encodedArtifactUrl = artifactUrl
                        .substring(subStringIndex)
                        .replace('%20', ' ')
                        .replace('%22', '"')
                        .split("/")
                        .collect({ URLEncoder.encode(it, "UTF-8") })
                        .join('/')
                        .replace('+', '%20')
                        .replace('"', '%22')

                def externalAuthID = (script.env['URL_PATH_INFO']) ? "?url_path=" + URLEncoder.encode(script.env['URL_PATH_INFO'], "UTF-8") : ''
                authArtifactUrl = script.kony.FABRIC_CONSOLE_URL + "/console/" + externalAuthID + "#/environments/" + script.env['CLOUD_ENVIRONMENT_GUID'] + "/${action}?path=" + encodedArtifactUrl
            } else {
                script.echoCustom("Failed to generate the authenticated URLs. Unable to find the cloud environment guid.", 'WARN')
            }
        }
        else
            authArtifactUrl = artifactUrl

        if (exposeUrl) {
            script.echoCustom("Artifact URL: ${authArtifactUrl}")
        }
        authArtifactUrl
    }
}
