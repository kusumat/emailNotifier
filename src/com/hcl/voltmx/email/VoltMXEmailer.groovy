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

    private getMap(String scmVars){

        scmVars = scmVars.substring(1, scmVars.length()-1);           //remove curly brackets
        String[] keyValuePairs = scmVars.split(",");              //split the string to creat key-value pairs
        Map<String,String> gitMap = new HashMap<>();

        for(String pair : keyValuePairs)                        //iterate over the pairs
        {
            String[] entry = pair.split("=");                   //split the pairs to get key and value
            gitMap.put(entry[0].trim(), entry[1].trim());          //add them to the hashmap and trim whitespaces
        }
        gitMap.each {
            script.echoCustom ("${it.key} = ${it.value}\n")
        }
        return gitMap
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



                                    map.put(entry[0].trim(), getMap(entry[1].trim()));          //add them to the hashmap and trim whitespaces
                                }
                                def branchInfo = [:]
                                map.each {k,v ->
                                    branchInfo.put(k, v["GIT_BRANCH"])
                                    script.echoCustom ("$k = $v\n")
                                }
//                                SCM_META = BuildHelper.prepareScmDetails script: script,
//                                          scmVars: map



                            }
                        }
                        finally {
                           // NotificationsHelper.sendEmail(script, [branch: branches,
//                                                                   scmMeta: SCM_META])
                        }
                    }
                }
            }
        }

}