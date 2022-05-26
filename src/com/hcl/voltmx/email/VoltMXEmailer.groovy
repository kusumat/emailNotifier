package com.hcl.voltmx.email

import com.hcl.voltmx.helper.NotificationsHelper
import com.hcl.voltmx.helper.BuildHelper
/**
 * Implements logic for flyway builds.
 */
class VoltMXEmailer implements Serializable {
    /* Pipeline object */
    private script
    def scmMeta = [:]
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
            /* Wrapper for injecting timestamp to the build console output */
            script.timestamps {
                /* Wrapper for colorize the console output in a pipeline build */
                script.ansiColor('xterm') {

                    script.node('Fabric_Slave') {
                        script.stage('Source checkout') {
                            String branchName = script.params.BRANCH_NAME;
                            isUnixNode = script.isUnix()
                            separator = isUnixNode ? '/' : '\\'

                            def checkoutRelativeTargetFolder = [projectWorkspacePath, "$TARGET_DIR"].join(separator)
                            scmMeta = BuildHelper.checkoutProject script: script,
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: "${BRANCH_NAME}",
                                    scmCredentialsId: "${SCM_CREDENTIALS}",
                                    scmUrl: "$REPO_URL"
                        }
                        NotificationsHelper.sendEmail(script,   [scmMeta               : scmMeta,
                                                                 artifacts              :])
                    }
                }
            }
        }

}