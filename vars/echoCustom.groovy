import com.kony.appfactory.helper.AppFactoryException

def call(String printMsg, String logType = 'INFO', boolean isExit = true) {
    def ANSI_PREFIX = '';
    def ANSI_PREFIX_NO_COLOR = '\033[0m'
    switch (logType) {
        case 'INFO':
            ANSI_PREFIX = '\033[0;32m' // ansi Green color code
            break
        case 'WARN':
            ANSI_PREFIX = '\033[0;33m' // ansi Yellow color code
            break
        case 'ERROR':
            ANSI_PREFIX = '\033[0;31m' // ansi Red color code
            break
        default:
            ANSI_PREFIX = ANSI_PREFIX_NO_COLOR // ansi No color code
            break
    }
    if (logType.equals("ERROR") && isExit)
        throw new AppFactoryException(printMsg, 'ERROR')
    else
        echo "$ANSI_PREFIX [$logType] $printMsg $ANSI_PREFIX_NO_COLOR"
}
