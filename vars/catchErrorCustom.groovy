def call(successMsg, errorMsg, closure) {
    try {
        closure()
        echo successMsg
    } catch(Exception e) {
        error errorMsg
    }
}
