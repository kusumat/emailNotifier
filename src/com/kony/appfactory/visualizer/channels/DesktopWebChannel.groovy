package com.kony.appfactory.visualizer.channels
import com.kony.appfactory.visualizer.channels.WebChannel

/**
 * Implements logic for Desktop_Web channel builds.
 */
class DesktopWebChannel extends WebChannel {

    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebChannel(script) {
        super(script, 'DESKTOP_WEB')
        script.env.DESKTOP_WEB = "true"
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        pipelineWrapperForWebChannels("DESKTOP_WEB")
    }
}

