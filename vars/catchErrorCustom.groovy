def call(String errorMsg, String successMsg = '', Closure closure) {
    try {
        closure()
        if (successMsg) {
            echoCustom(successMsg)
        }
    } catch (Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage, e.getErrorType())
        throw new Exception(errorMsg, e.getErrorType())
    } catch (Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage, 'ERROR', false)
        throw new Exception(errorMsg, 'ERROR')
    }
}
