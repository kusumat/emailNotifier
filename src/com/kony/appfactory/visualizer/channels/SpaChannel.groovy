package com.kony.appfactory.visualizer.channels
import com.kony.appfactory.visualizer.channels.WebChannel

/**
 * Implements logic for SPA channel builds.
 */
class SpaChannel extends Channel {
    /* WebChannel object */
    protected webChannel
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    SpaChannel(script) {
        super(script)
        channelOs = channelFormFactor = channelType = 'SPA'
        this.webChannel = new WebChannel(script)
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        webChannel.pipelineWrapperForWebChannels("SPA")
    }
}
