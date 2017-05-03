package com.kony.appfactory.visualizer.channels

final class ChannelFactory implements Serializable {
    def script

    ChannelFactory(script) {
        this.script = script
    }

    final getChannel(channelName) {
        def result

        switch (channelName) {
            case 'APPLE_MOBILE':
                result = new AppleChannel(script)
                break
            case 'ANDROID_MOBILE':
                result = new AndroidChannel(script)
                break
            case 'APPLE_TABLET':
                result = new AppleChannel(script)
                break
            case 'ANDROID_TABLET':
                result = new AndroidChannel(script)
                break
            default:
                result = null
                break
        }

        result
    }
}
