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
    private final projectName = script.env.JOB_NAME
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
//                                def paramsList = ['Branch_Name','GIT_BRANCH']
//                                def branchName = BuildHelper.getParamNameOrDefaultFromProbableParamList(script, paramsList, 'BRANCH_NAME')
                                //script.echoCustom("branch param is $branchName",'INFO')
                                String branch = script.env.BRANCH_NAME || script.env.GIT_BRANCH || script.env.Branch_Name
                                script.echoCustom("branch  is $branch",'INFO')
                                String credentialID = script.params.SCM_CREDENTIALS
                                String repoURL = script.env.REPO_URL

                                isUnixNode = script.isUnix()
                                separator = isUnixNode ? '/' : '\\'

                                def checkoutRelativeTargetFolder = [projectWorkspacePath, "$script.env.TARGET_DIR"].join(separator)
                                scmMeta = BuildHelper.checkoutProject script: script,
                                        projectRelativePath: checkoutRelativeTargetFolder,
                                        scmBranch: branch,
                                        scmCredentialsId: credentialID,
                                        scmUrl: repoURL
                            }
                        }
                        finally {
                            def branchinfo = [:]
                           if(projectName.contains("VisualizerStarter")){
                              def addedfileBranch = "Added-file"
                               branchinfo.put("KONYIQ_BRANCH", script.env.KONYIQ_BRANCH)
                               branchinfo.put("Branch_Name_Installer", script.env.Branch_Name_Installer)
                               branchinfo.put("HIKES_BRANCH", script.env.HIKES_BRANCH)
                               branchinfo.put("AUTOMATIONRECORDERADDON_BRANCH", script.env.AUTOMATIONRECORDERADDON_BRANCH)
                               branchinfo.put("KONYCOP_BRANCH", script.env.KONYCOP_BRANCH)
                               branchinfo.put("Added-file", script.env.addedfileBranch)
                           }

                            NotificationsHelper.sendEmail(script, [scmMeta: scmMeta, branchinfo : branchinfo])
                        }
                    }
                }
            }
        }

}