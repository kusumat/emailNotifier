def call(String printMsg, String logType = 'INFO') {
    def ANSI_PREFIX="noVal"
    def ANSI_PREFIX_NO_COLOR='\033[0m'
    switch (logType) {
        case 'INFO':
            ANSI_PREFIX = '\033[0;32m' // ansi Green color code
            break
        case 'WARN':
            ANSI_PREFIX = '\033[0;33m' // ansi Yellow color code
            break
        case 'ERROR':
            ANSI_PREFIX = '\033[0;31m' // ansi Red color code
            echo "$ANSI_PREFIX [$logType] $printMsg $ANSI_PREFIX_NO_COLOR"
            error()
        default:
            ANSI_PREFIX = ANSI_PREFIX_NO_COLOR // ansi No color code
            break
    }
    echo "$ANSI_PREFIX [$logType] $printMsg $ANSI_PREFIX_NO_COLOR"
}