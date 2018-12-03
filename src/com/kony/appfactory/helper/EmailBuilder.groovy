package com.kony.appfactory.helper

class EmailBuilder {

    @NonCPS
    static void addNotificationHeaderRow(htmlBuilder, notificationHeader) {
        htmlBuilder.tr {
            td(style: "text-align:center", class: "text-color") {
                h2 notificationHeader
            }
        }
    }

    @NonCPS
    static void addBuildSummaryRow(htmlBuilder, key, value) {
        htmlBuilder.tr {
            td(style: "width:22%;text-align:right", key)
            td(class: "table-value", value)
        }
    }

    @NonCPS
    static void addBuildSummaryAnchorRow(htmlBuilder, key, url) {
        htmlBuilder.tr {
            td(style: "width:22%;text-align:right", key)
            td {
                a(href: url, url)
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
                td(style: "border-right: 1px dotted #e8e8e8; width: 65px;", binding.artifacts[i].extension)
                td {
                    a(href: binding.artifacts[i].url, target: '_blank', binding.artifacts[i].name)
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
