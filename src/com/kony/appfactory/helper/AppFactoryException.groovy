package com.kony.appfactory.helper

/**
 * AppFactory Exception class
 */
class AppFactoryException extends Exception {
    
    private String errorType = 'WARN'
    
    public AppFactoryException(){
        super()
    }
    
    public AppFactoryException(String message){
        this(message, 'WARN')
    }
    
    public AppFactoryException(String message, String errorType){
        super(message)
        this.errorType = errorType
    }
    
    public String getErrorType(){
        return errorType;
    }
}