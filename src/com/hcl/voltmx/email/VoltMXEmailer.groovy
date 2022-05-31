package com.hcl.voltmx.email

import com.hcl.voltmx.helper.NotificationsHelper
import com.hcl.voltmx.helper.BuildHelper
/**
 * Implements logic for flyway builds.
 */
class VoltMXEmailer implements Serializable {
    /* Pipeline object */
    private script

    private boolean isUnixNode
    private separator
    private final projectName = script.env.PROJECT_NAME
    String projectWorkspacePath = script.env.WORKSPACE

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    VoltMXEmailer(script) {
        this.script = script
    }
        /**
         * Creates job pipeline.
         * This method is called from the job and contains whole job's pipeline logic.
         */
        protected final void runPipeline() {
            def scmMeta = [:]
            /* Wrapper for injecting timestamp to the build console output */
            script.timestamps {
                /* Wrapper for colorize the console output in a pipeline build */
                script.ansiColor('xterm') {

                    script.node('Fabric_Slave') {
                        try {
                            script.stage('Source checkout') {
                                String branchName = script.params.BRANCH_NAME;
                                String credentialID = script.params.SCM_CREDENTIALS
                                String repoURL = script.env.REPO_URL

                                isUnixNode = script.isUnix()
                                separator = isUnixNode ? '/' : '\\'

                                def checkoutRelativeTargetFolder = [projectWorkspacePath, "$script.env.TARGET_DIR"].join(separator)
                                script.currentBuild.getChangeSets().clear()
                                scmMeta = BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: branchName,
                                        scmCredentialsId: credentialID,
                                        scmUrl: repoURL
                            }
                        }
                        finally {
                           NotificationsHelper.sendEmail(script, [scmMeta: scmMeta])
                        }
                    }
                }
            }
        }

}