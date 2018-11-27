package com.kony.appfactory.visualizer.channels
import com.kony.appfactory.visualizer.channels.WebChannel

/**
 * Implements logic for SPA channel builds.
 */
class SpaChannel extends WebChannel {
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    SpaChannel(script) {
        super(script, 'SPA')
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        pipelineWrapperForWebChannels("SPA")
    }
}
