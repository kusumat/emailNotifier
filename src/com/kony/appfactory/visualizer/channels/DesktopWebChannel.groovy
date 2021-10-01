package com.kony.appfactory.visualizer.channels
import com.kony.appfactory.visualizer.channels.WebChannel
import com.kony.appfactory.helper.BuildHelper

/**
 * Implements logic for Responsive_Web channel builds.
 */
class DesktopWebChannel extends WebChannel {
    /**
     * Class constructor.
     *
     * @param script pipeline object.
     */
    DesktopWebChannel(script) {
        super(script, BuildHelper.getCurrentParamName(script, 'RESPONSIVE_WEB', 'DESKTOP_WEB'))
        script.env[webChannelParameterName] = "true"
    }

    /**
     * Creates job pipeline.
     * This method is called from the job and contains whole job's pipeline logic.
     */
    protected final void createPipeline() {
        pipelineWrapperForWebChannels(webChannelParameterName)
    }
}

