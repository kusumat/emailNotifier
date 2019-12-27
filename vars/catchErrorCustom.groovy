import com.kony.appfactory.helper.AppFactoryException

def call(String errorMsg, String successMsg = '', Closure closure) {
    try {
        closure()
        if (successMsg) {
            echoCustom(successMsg)

        }
    } catch (AppFactoryException e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage, e.getErrorType())
        throw new AppFactoryException(errorMsg, e.getErrorType())
    } catch (Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage, 'ERROR', false)
        throw new AppFactoryException(errorMsg, 'ERROR')
    }
}
