def call(String errorMsg, String successMsg = '', Closure closure) {
    try {
        closure()
        if (successMsg) {
            echo "$successMsg"
        }
    } catch(Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echo "$errorMsg"
        error(exceptionMessage)
    }
}
