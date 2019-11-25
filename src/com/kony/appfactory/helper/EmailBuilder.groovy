package com.kony.appfactory.helper

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
            td(style: "width:22%;text-align:right;color: #858484;padding: 15px 10px;", key)
            td(class: "table-value", value)
        }
    }

    @NonCPS
    static void addBuildSummaryAnchorRow(htmlBuilder, key, url, buildNo) {
        htmlBuilder.tr {
            td(style: "width:22%;text-align:right;color: #858484;padding: 15px 10px;", key)
            td {
                a(href: url, buildNo)
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
                if (binding."${binding.channelPath}")
                    binding."${binding.channelPath}"[0]['version']?.each{ k, v -> p(style: "font-size:12px;", "${k}: ${v}") }
            }
        }
    }

    @NonCPS
    static void addMultiSpanArtifactTableRow(htmlBuilder, binding) {
        int countRows = binding.artifacts.size()
        for (int rownum = 0; rownum < countRows; rownum++) {
            htmlBuilder.tr {
                if (rownum == 0)
                    th(rowspan: countRows, binding.channelPath.replaceAll('/', ' '))
                td(style: "text-align:left ,border-right: 1px dotted #e8e8e8; width: 65px;", binding.artifacts[rownum].extension)
                td {
                    if(binding.artifacts[rownum].url)
                        a(href: binding.artifacts[rownum].url, target: '_blank', binding.artifacts[rownum].name)
                    else
                        mkp.yield("Build failed")
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
}
