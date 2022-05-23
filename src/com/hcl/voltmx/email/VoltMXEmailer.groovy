package com.hcl.voltmx.email

import com.hcl.voltmx.helper.NotificationsHelper

/**
 * Implements logic for flyway builds.
 */
class VoltMXEmailer implements Serializable {
    /* Pipeline object */
    private script
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

                    script.node('j1mac2') {
                        NotificationsHelper.sendEmail(script)
                    }
                }
            }
        }
}