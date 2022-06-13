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
            def SCM_META = [:]
            script.timestamps {
                script.ansiColor('xterm') {
                    script.node('Fabric_Slave') {
                        try {
                            script.stage('Preparing Email') {
                                def varsList = []
                                varsList = script.env.varslist
                                script.echoCustom("kms meta"+ varsList)
                                script.env.varmap.each {
                                    k, v -> script.echoCustom ("${k}:${v}")
                                }
//                                kmsmeta.each {
//                                    script.echoCustom(${it.key})
//                                    script.echoCustom(${it.value})

//                                }
//
//                                SCM_META = BuildHelper.prepareScmDetails script: script,
//                                          scmVars: kmsmeta.get("KMS")
                            }
                        }
                        finally {
                          //  NotificationsHelper.sendEmail(script, [scmMeta: SCM_META])
                        }
                    }
                }
            }
        }

}