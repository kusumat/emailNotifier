package com.kony.appfactory.visualizer

import com.kony.appfactory.tests.FacadeTests
import com.kony.appfactory.helper.AppFactoryException

/**
 * Contains createPipeline method which will call the actual implementation of createPipeline for Native/DesktopWeb channels respectively.
 */
class TestAutomation implements Serializable {
    /* Pipeline object */
    private script

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    TestAutomation(script) {
        this.script = script
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and calls the actual method which implements createPipeline logic for the respective channels.
     */
    protected void createPipeline() {
        /* Wrapper for colorize the console output in a pipeline build */
        script.ansiColor('xterm') {
            script.properties([[$class: 'CopyArtifactPermissionProperty', projectNames: '/*']])
            try {
                /* Calls createPipeline method for the Native/DesktopWeb channels respectively */
                new FacadeTests(script).createPipeline()
            }
            catch (AppFactoryException e) {
                String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
                script.echoCustom(exceptionMessage, e.getErrorType(), false)
                script.currentBuild.result = 'FAILURE'
            }
        }
    }
}