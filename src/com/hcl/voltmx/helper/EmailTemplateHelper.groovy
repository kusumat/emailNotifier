package com.hcl.voltmx.helper

import groovy.xml.MarkupBuilder
import org.apache.commons.lang.StringUtils

/**
 * Implements logic of creation content for job result notifications.
 * This class uses groovy.xml.MarkupBuilder for creation HTML content for specific job.
 *
 * Because of the logic in methods of this class and Jenkins pipeline plugin requirements,
 * @NonCPS annotation been added for every method.
 */
class EmailTemplateHelper implements Serializable {
    /**
     * Wrapper for creation of HTML content.
     */
    private static markupBuilderWrapper = { closure ->
        Writer writer = new StringWriter()
        MarkupBuilder htmlBuilder = new MarkupBuilder(writer)

        /* HTML markup closure call, passing MarkupBuild object to the closure */
        closure(htmlBuilder)

        /* Return created HTML markup as a string */
        writer.toString()
    }


    /**
     * Creates HTML content for Flyway run.
     *
     * @param binding provides data for HTML.
     * @return HTML content as a string.
     */
    @NonCPS
    protected static String emailContent(Map binding) {
        markupBuilderWrapper { MarkupBuilder htmlBuilder ->
            htmlBuilder.table(style: "width:100%") {
                EmailBuilder.addNotificationHeaderRow(htmlBuilder, binding.notificationHeader)
                tr {
                    td(style: "text-align:left", class: "text-color") {
                        h4(class: "subheading", "Run Details")
                    }
                }
                tr {
                    td {
                        table(role :"presentation", cellspacing :"0", cellpadding :"0", style: "width:100%", class: "text-color table-border cell-spacing") {
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project:', binding.projectName)
                            if (binding.triggeredBy)
                                EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Triggered by:', binding.triggeredBy)

                            //EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Project Branch:', binding.branch)
                            EmailBuilder.addBuildSummaryAnchorRow(htmlBuilder, 'Build URL:', binding.build.url, binding.build.number)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build number:', "#" + binding.build.number)

                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Date of build:', binding.build.started)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'Build duration:', binding.build.duration)
                            EmailBuilder.addBuildSummaryRow(htmlBuilder, 'SCM Branch:', binding.build.branch)

                        }
                    }
                }
                htmlBuilder.br()
                htmlBuilder.table(style: "width:100%") {
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            h4(class: "subheading", 'Source Code Details')
                        }
                    }
                    tr {
                        td {
                            if (binding.scmMeta != null && !binding.scmMeta.isEmpty()) {
                                table(role: "presentation", cellspacing: "0", cellpadding: "0", style: "width:100%;text-align:left", class: "text-color table-border-channels") {
                                    thead(class: "table-border-channels") {
                                        tr {

                                            th(style: "text-align:center", width: "20%", 'COMMIT ID')
                                            th(style: "text-align:center", width: "55%", 'COMMIT LOGS')
                                        }
                                    }
                                    tbody(class: "table-border-channels") {
                                        EmailBuilder.addScmTableRow(htmlBuilder, binding.scmMeta)
                                    }
                                }
                            } else {
                                p(style: "font-size:14px;", "ERROR!! Failed at checkout or pre-checkout stage. Please refer to the log.")
                            }
                        }
                    }
                }

                if (binding.build.result == 'FAILURE') {
                    tr {
                        td(style: "text-align:left;padding-top:20px; padding-bottom:0;", class: "text-color") {
                            h4(style: "margin-bottom:0", 'Console Output')
                            binding.build.log.each { line ->
                                p(style:"width:950px;",line)
                            }
                        }
                    }
                } else {
                    tr {
                        td(style: "text-align:left", class: "text-color") {
                            h4(class: "subheading", 'Build Information')

                        }
                    }
//                    tr {
//                        td {
//                            if(binding.flywayResults != null && !binding.flywayResults.isEmpty()) {
//                                table(role :"presentation", cellspacing :"0", cellpadding :"0", style: "width:100%;text-align:left", class: "text-color table-border-channels") {
//                                    thead(class:"table-border-channels") {
//                                        tr {
//                                            th(style: "text-align:center", 'FLYWAY COMMANDS')
//                                            th(style: "text-align:center",'RESULT')
//                                        }
//                                    }
//                                    tbody(class:"table-border-channels") {
//                                        EmailBuilder.addFlywayDataRows(htmlBuilder, binding.flywayResults)
//                                    }
//                                }
//                            }
//                        }
//                    }
                }
            }
        }
    }

    /**
     * Converts the given time difference into hours, minutes, seconds
     * @param Time difference between two times
     * @returns value with a specific time format
     * */
    @NonCPS
    protected static def changeTimeFormat(timeDifference){
        def value = ""
        Map mapWithTimeFormat = [:]
        if(timeDifference) {
            timeDifference = timeDifference / 1000
            mapWithTimeFormat.seconds = timeDifference.remainder(60)
            timeDifference = (timeDifference - mapWithTimeFormat.seconds) / 60
            mapWithTimeFormat.minutes = timeDifference.remainder(60)
            timeDifference = (timeDifference - mapWithTimeFormat.minutes) / 60
            mapWithTimeFormat.hours = timeDifference.remainder(24)
            if (mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.hours.setScale(0, BigDecimal.ROUND_HALF_UP) + " hrs "
            if (mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.minutes.setScale(0, BigDecimal.ROUND_HALF_UP) + " mins "
            if (mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP))
                value += mapWithTimeFormat.seconds.setScale(0, BigDecimal.ROUND_HALF_UP) + " secs "
        }
        return value
    }
}
