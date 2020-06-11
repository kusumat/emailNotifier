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
        catchError(stageResult: 'FAILURE') {
            throw new AppFactoryException("Error occurred...!")
        }
        throw new AppFactoryException(errorMsg, e.getErrorType())
    } catch (Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage, 'ERROR', false)
        catchError(stageResult: 'FAILURE') {
            throw new AppFactoryException("Error occurred...!")
        }
        throw new AppFactoryException(errorMsg, 'ERROR')
    }
}
