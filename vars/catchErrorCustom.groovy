import com.kony.appfactory.helper.AppFactoryException

def call(String errorMsg, String successMsg = '', Closure closure) {
    try {
        closure()
        if (successMsg) {
            echoCustom(successMsg)

        }
    } catch (Exception e) {
        String exceptionMessage = (e.getLocalizedMessage()) ?: 'Something went wrong...'
        echoCustom(exceptionMessage,'WARN')
        throw new AppFactoryException(errorMsg)
    }
}
