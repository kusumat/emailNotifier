package com.hcl.voltmx.helper

class EmailBuilder {

    @NonCPS
    static void addNotificationHeaderRow(htmlBuilder, notificationHeader) {
        htmlBuilder.tr {
            td(style: "text-align:center;border-top: 1px dashed #e8e8e8;border-bottom: 1px dashed #e8e8e8;padding: 10px;font-size: 18px;", class: "text-color",notificationHeader)
        }
    }

    @NonCPS
    static void addBuildSummaryRow(htmlBuilder, key, value) {
        htmlBuilder.tr {
            td(style: "width:280px;text-align:left;color: #858484;padding-bottom: 3px !important;", key)
            td(class: "table-value", style:"padding-bottom: 3px !important;",value)
        }
    }

    @NonCPS
    static void addBuildSummaryAnchorRow(htmlBuilder, key, url, linkLabel) {
        htmlBuilder.tr {
            td(style: "width:280px;text-align:left;color: #858484;padding-bottom: 3px !important;", key)
            td(style:"padding-bottom: 3px !important;") {
                a(href: url, linkLabel)
            }
        }
    }

    @NonCPS
    static void addSimpleArtifactTableRowSuccess(htmlBuilder, binding) {
        htmlBuilder.tr {
            th(binding.channelPath.replaceAll('/', ' '))
            td(style: "border-right: 1px solid #e8e8e8; width: 65px;", binding.extension)
            td {
                a(href: binding.url, target: '_blank', binding.name)
            }
            td(style: "border-right: 1px solid #e8e8e8"){
                if (binding."${binding.channelPath}" && binding."${binding.channelPath}"[0] && binding."${binding.channelPath}"[0]['version'])
                    binding."${binding.channelPath}"[0]['version']?.each{ k, v -> p(style: "font-size:12px;", "${k}: ${v}") }
            }
        }
    }

    @NonCPS
    static void addSimpleArtifactTableRowFailed(htmlBuilder, binding) {
        htmlBuilder.tr {
            th(binding.channelPath.replaceAll('/', ' '))
            if (binding?.extension) {
                td(style: "border-right: 1px solid #e8e8e8; width: 65px;", binding?.extension)
                td(style: "border-right: 1px solid #e8e8e8; width: 65px; color:red", "Build failed")
            }
            else
                td(style: "border-right: 1px solid #e8e8e8; width: 65px; color:red", colspan: "2") { mkp.yield("Build failed") }
            td(style: "border-right: 1px solid #e8e8e8"){
                if (binding."${binding.channelPath}" && binding."${binding.channelPath}"[0] && binding."${binding.channelPath}"[0]['version'])
                    binding."${binding.channelPath}"[0]['version']?.each{ k, v -> p(style: "font-size:12px;", "${k}: ${v}") }
            }
        }
    }

    @NonCPS
    static void addScmTableRow(htmlBuilder, scmMeta) {
        htmlBuilder.tr {
            if (scmMeta && scmMeta.scmUrl && scmMeta.commitID && scmMeta.commitLogs) {
                td(style: "text-align:center; border-right: 1px solid #e8e8e8; width: 65px") {
                    String commitIdUrl = scmMeta.scmUrl.replace(".git", "/commit/") + scmMeta.commitID
                    a(href: commitIdUrl, target: '_blank', scmMeta.commitID.substring(0, 7))
                }
                td(style: "border-right: 1px solid #e8e8e8") {
                    def logsList = scmMeta.commitLogs
                    for (def pathIndex = 0; pathIndex < 10 && pathIndex < logsList.size(); pathIndex++)
                        p(style: "font-size:12px;", logsList[pathIndex])
                }
            } else {
                td(style: "color:red", colspan: "2") {
                    mkp.yield("Checkout failed")
                }
            }
        }
    }

    @NonCPS
    static void addMultiSpanArtifactTableRow(htmlBuilder, binding) {
        int countRows = binding.artifacts?.size()
        for (int rownum = 0; rownum < countRows; rownum++) {
            htmlBuilder.tr {
                if (rownum == 0)
                    th(rowspan: countRows, binding.channelPath.replaceAll('/', ' '))
                td(style: "text-align:left ,border-right: 1px dotted #e8e8e8; width: 65px;", binding.artifacts[rownum].extension)
                td {
                    if (binding.artifacts[rownum].authurl)
                        a(href: binding.artifacts[rownum].authurl, target: '_blank', binding.artifacts[rownum].name)
                    else if (binding.artifacts[rownum].url)
                        a(href: binding.artifacts[rownum].url, target: '_blank', binding.artifacts[rownum].name)
                    else
                        p(style: "color:red") {
                            mkp.yield("Build failed")
                        }
                }
                if (rownum == 0) {
                    td(style: "border-right: 1px solid #e8e8e8", rowspan: countRows){
                        if (binding."${binding.channelPath}")
                            binding."${binding.channelPath}"[0]['version']?.each{ k, v -> p(style: "font-size:12px;", "${k}: ${v}") }
                    }
                }
            }
        }

    }

    @NonCPS
    static void addBuildConsoleLogFields(htmlBuilder, binding) {
        htmlBuilder.tr {
            td {
                p(style: "text-align:left") {
                    mkp.yield("Refer ")
                    a(href: binding.consolelogs, target: '_blank', 'this')
                    mkp.yield(" link to download build console logs. Note: Above links are valid only for 24 hours.")
                }
            }
        }
    }
    
    @NonCPS
    static void addFabricAppBuildTableRow(htmlBuilder, artifacts) {
        int countRows = artifacts.size()
        for (int rownum = 0; rownum < countRows; rownum++) {
            htmlBuilder.tr {
                if (rownum == 0)
                    td(style: "text-align:left ,border-right: 1px dotted #e8e8e8;", rowspan: countRows, artifacts[rownum].fabricAppName)
                td {
                    if(artifacts[rownum].authurl)
                        a(href: artifacts[rownum].authurl, target: '_blank', artifacts[rownum].name)
                    else
                        mkp.yield("Build failed")
                }
            }
        }
    }
    
    @NonCPS
    static void addFabricAppBuildScmTableRow(htmlBuilder, scmMeta) {

        htmlBuilder.tr {

                if (scmMeta && scmMeta.scmUrl && scmMeta.commitID && scmMeta.commitLogs) {
                    td(style: "text-align:center; border-right: 1px solid #e8e8e8; width: 65px") {
                        String commitIdUrl = scmMeta.scmUrl.replace(".git", "/commit/") + scmMeta.commitID
                        a(href: commitIdUrl, target: '_blank', scmMeta.commitID.substring(0, 7))
                    }
                    td(style: "border-right: 1px solid #e8e8e8") {
                        def logsList = scmMeta.commitLogs
                        for (def pathIndex = 0; pathIndex < 10 && pathIndex < logsList.size(); pathIndex++)
                            p(style: "font-size:12px;", logsList[pathIndex])
                    }
                } else {
                    td(style: "color:red", colspan: "2") {
                        mkp.yield("Checkout failed")
                    }
                }
        }
    }
    @NonCPS
    static void addFabricAppBuildScmTableRow(htmlBuilder, scmMeta) {
        htmlBuilder.tr {
            if (scmMeta && scmMeta.scmUrl && scmMeta.commitID && scmMeta.commitLogs) {
                td(style: "text-align:center; border-right: 1px solid #e8e8e8; width: 65px") {
                    String commitIdUrl = scmMeta.scmUrl.replace(".git", "/commit/") + scmMeta.commitID
                    a(href: commitIdUrl, target: '_blank', scmMeta.commitID.substring(0, 7))
                }
                td(style: "border-right: 1px solid #e8e8e8") {
                    def logsList = scmMeta.commitLogs
                    for (def pathIndex = 0; pathIndex < 10 && pathIndex < logsList.size(); pathIndex++)
                        p(style: "font-size:12px;", logsList[pathIndex])
                }
            } else {
                td(style: "color:red", colspan: "2") {
                    mkp.yield("Checkout failed")
                }
            }
        }
    }




    @NonCPS
    static void addFlywayDataRows(htmlBuilder, flywayResults) {
        flywayResults.each { command ->
            htmlBuilder.tr {
                td {
                    p(style: "font-size:12px;", command.name.toUpperCase())
                }
                td {
                    p(style: "font-size:12px;", command.status)
                }
            }
        }
    }
    
    @NonCPS
    static void addRunTestLogsAnchorRow(htmlBuilder, logFileName, logLink) {
        htmlBuilder.tr {
            td(style: "width:280px;text-align:left;color: #858484;padding-bottom: 3px !important;", logFileName)
            if(logFileName == 'Detailed Test Report') {
                td(style:"padding-bottom: 3px !important;") {
                    logLink?.each { tetsResultFileName, fileUrl ->
                        p(style: "font-size:12px;") {
                            a(href: fileUrl, tetsResultFileName)
                        }
                    }
                }
            } else {
                td(style:"padding-bottom: 3px !important;") {
                    a(href: logLink, logFileName)
                }
            }
        }
    }
    
    @NonCPS
    static void addiOSArtifactTableRow(htmlBuilder, binding) {
        def artifactsExpectedList = ['OTA','IPA','KAR']
        for (int rownum = 0; rownum < artifactsExpectedList.size(); rownum++) {
            htmlBuilder.tr {
                if (rownum == 0)
                    th(rowspan: artifactsExpectedList.size(), binding.channelPath.replaceAll('/', ' '))
                
                td(style: "text-align:left; border-right: 1px dotted #e8e8e8; width: 65px;", artifactsExpectedList[rownum])
                td {
                    def artifactFound = false
                    binding.artifacts.each { artifact ->
                        if(artifact.extension == artifactsExpectedList[rownum]) {
                            artifactFound = true
                            if (artifact.authurl)
                                a(href: artifact.authurl, target: '_blank', artifact.name)
                            else if (artifact.url)
                                a(href: artifact.url, target: '_blank', artifact.name)
                            else
                                p(style: "color:red") {
                                    mkp.yield(artifactsExpectedList[rownum] + " generation failed")
                                }
                        }
                    }
                    if(!artifactFound) {
                        p(style: "color:red") {
                            mkp.yield(artifactsExpectedList[rownum] +" generation failed")
                        }
                    }
                }
                if (rownum == 0) {
                    td(style: "border-right: 1px solid #e8e8e8", rowspan: artifactsExpectedList.size()){
                        if (binding."${binding.channelPath}")
                            binding."${binding.channelPath}"[0]['version']?.each{ k, v -> p(style: "font-size:12px;", "${k}: ${v}") }
                    }
                }
            }
        }
    }
}
