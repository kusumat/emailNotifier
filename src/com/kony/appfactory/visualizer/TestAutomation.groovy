package com.kony.appfactory.visualizer

import com.kony.appfactory.tests.FacadeTests

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
        /* Calls createPipeline method for the Native/DesktopWeb channels respectively */
        new FacadeTests(script).createPipeline()
    }
}