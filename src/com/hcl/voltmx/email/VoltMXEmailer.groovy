package com.hcl.voltmx.email

import com.hcl.voltmx.helper.NotificationsHelper

/**
 * Implements logic for flyway builds.
 */
class VoltMXEmailer implements Serializable {
    /* Pipeline object */
    private script
    def scmMeta = [:]
    private final projectName = script.env.PROJECT_NAME
    String projectWorkspacePath = script.env.WORKSPACE
    def checkoutRelativeTargetFolder = [projectWorkspacePath, projectName].join(separator)
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

                            scmMeta = BuildHelper.checkoutProject script: script,
                                    projectRelativePath: checkoutRelativeTargetFolder,
                                    scmBranch: ${branchName},
                                    scmCredentialsId: 'c401aa36-3cb9-4849-ad29-ee79196bd286',
                                    scmUrl: 'https://github.com/kusumat/emailNotifier.git'
                        }
                        NotificationsHelper.sendEmail(script, scmMeta)
                    }
                }
            }
        }

}