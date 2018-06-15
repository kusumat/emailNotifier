package com.kony.appfactory.visualizer.channels
import com.kony.appfactory.visualizer.channels.WebChannel
/**
 * Implements logic for Desktop_Web channel builds.
 */
class DesktopWebChannel extends Channel {
    /* WebChannel object */
    protected webChannel
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebChannel(script) {
        super(script)
        this.webChannel = new WebChannel(script)
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        webChannel.pipelineWrapperForWebChannels("DESKTOP_WEB")
    }
}

