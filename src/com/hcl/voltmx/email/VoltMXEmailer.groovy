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

    private getKMSMap(Closure closure){
   Closure map = Eval.me(kmsmap)
        return map
      // return (kmsmap) ? Eval.me(kmsmap) : [GIT_BRANCH:'',GIT_CHECKOUT_DIR:'',GIT_COMMIT:'',GIT_PREVIOUS_COMMIT:'',GIT_PREVIOUS_SUCCESSFUL_COMMIT:'',GIT_URL:'']
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

                             def kmsmap = script.env.varmap
                              String value = kmsmap
                                value = value.substring(1, value.length()-1);
                                String mapString = value.replace("}, ","}# ")
                                script.echoCustom("mapString"+mapString)

                                String[] keyValuePairs = mapString.split("#")              //split the string to creat key-value pairs
                                Map<String,Map<String,String>> map = new HashMap<>();

                                for(String pair : keyValuePairs)
                                {
                                   String mapEntry = pair.replace("={","#{")

                                    String[] entry = mapEntry.split("#");

                                    map.put(entry[0].trim(), entry[1].trim());          //add them to the hashmap and trim whitespaces
                                }
                                map.each {
                                    script.echoCustom ("${it.key} = ${it.value}\n")
                                }

//                                script.echoCustom("tenant "+ script.env.tenantvars)
//                                script.echoCustom("ten "+ varsList)

//                                script.env.kms.each {
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