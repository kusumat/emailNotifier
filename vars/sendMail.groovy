def call(resourceBasePath, templateName, recipients) {
    String emailTemplateFolder = 'email/templates/'
    String emailTemplateName = templateName
    String emailRecipientsList = recipients
    String emailBody = '${JELLY_SCRIPT, template="' + emailTemplateName + '"}'
    String emailSubject = String.valueOf(env.BUILD_TAG) + '-' + String.valueOf(currentBuild.currentResult)

    /* Load email template */
    String emailTemplate = loadLibraryResource(resourceBasePath + emailTemplateFolder + emailTemplateName)
    
    catchError {
        /* Store template to the current workspace */
        writeFile file: emailTemplateName, text: emailTemplate
        /* Sending email */
        emailext body: emailBody, subject: emailSubject, to: emailRecipientsList
    }

}