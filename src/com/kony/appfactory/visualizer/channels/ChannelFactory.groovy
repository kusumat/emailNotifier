package com.kony.appfactory.visualizer.channels

final class ChannelFactory implements Serializable {
    def script

    ChannelFactory(script) {
        this.script = script
    }

    final getChannel(channelName) {
        def result

        switch (channelName) {
            case 'APPLE_MOBILE_NATIVE':
            case 'APPLE_MOBILE_SPA':
            case 'APPLE_TABLET_NATIVE':
            case 'APPLE_TABLET_SPA':
                result = new AppleChannel(script)
                break
            case 'ANDROID_MOBILE_NATIVE':
            case 'ANDROID_MOBILE_SPA':
            case 'ANDROID_TABLET_NATIVE':
            case 'ANDROID_TABLET_SPA':
                result = new AndroidChannel(script)
                break
            case 'WINDOWS_MOBILE_WINDOWSPHONE8':
            case 'WINDOWS_MOBILE_WINDOWSPHONE81S':
            case 'WINDOWS_MOBILE_WINDOWSPHONE10':
            case 'WINDOWS_DESKTOP_WINDOWS81':
            case 'WINDOWS_DESKTOP_WINDOWS10':
                result = new WindowsChannel(script)
                break
            default:
                result = null
                break
        }

        result
    }
}
