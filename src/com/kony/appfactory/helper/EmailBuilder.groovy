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
        }
    }

    @NonCPS
    static void addSimpleArtifactTableRowFailed(htmlBuilder, binding) {
        htmlBuilder.tr {
            th(binding.channelPath.replaceAll('/', ' '))
            td(style: "border-right: 1px solid #e8e8e8; width: 65px;", binding.extension)
            td(style: "border-right: 1px solid #e8e8e8; width: 65px; color:red", "Build failed")
        }
    }

    @NonCPS
    static void addMultiSpanArtifactTableRow(htmlBuilder, binding) {
        int countRows = binding.artifacts.size()
        for (int i = 0; i < countRows; i++) {
            htmlBuilder.tr {
                if (i == 0)
                    th(rowspan: countRows, binding.channelPath.replaceAll('/', ' '))
                td(style: "text-align:left ,border-right: 1px dotted #e8e8e8; width: 65px;", binding.artifacts[i].extension)
                td {
                    if(binding.artifacts[i].url)
                        a(href: binding.artifacts[i].url, target: '_blank', binding.artifacts[i].name)
                    else
                        mkp.yield("Build failed")
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
