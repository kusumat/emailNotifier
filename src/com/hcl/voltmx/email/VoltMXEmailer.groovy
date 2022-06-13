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
                                script.echoCustom("vars "+ varsList)
                                script.echoCustom("kms "+ Eval.me(script.env.kmsvars))
                                script.echoCustom("tenant "+ script.env.tenantvars)
                                script.echoCustom("ten "+ varsList)

//                                script.env.kmsvars.each {
//                                    script.echoCustom ("${it.key} = ${it.value}\n")
//                                }
//                                def tenantmap = evaluate(script.env.tenantvars.inspect())
//                                tenantmap.each {
//                                    script.echoCustom ("${it.key} = ${it.value}\n")
//                                }
//                                script.env.varmap.each {
//                                    script.echoCustom ("${it.key} = ${it.value}\n")
//                                }
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