package com.kony.appfactory.helper

/**
 * Implements the logic related to cloud build process.
 *
 *
 */
class CloudBuildHelper implements Serializable {
    
    /**
     * Getting the AppViewer source version tag required to checkout based on
     * Visualizer project version of child App, checking the minimumVisualizer version supported for
     * FPreview release tag from mapper version json file of FPreview.
     * @param args its a map with arguments - script, quantumAppMapperJsonFileName, quantumChildAppVisualizerVersion
     * @return quantumAppCheckoutVersion its version/tag for quantum appviewer source
     */
    protected static String getQuantumAppCheckoutVersionTag(Map args) {
        def script = args.script
        String quantumAppMapperJsonFile= args.quantumAppMapperJsonFilePath
        String childAppVizPluginVersion= args.quantumChildAppVisualizerVersion
        def mapperJson = script.readJSON file: quantumAppMapperJsonFile
        def quantumVersionReleases= mapperJson.visualizer_appviewer.releases
        boolean isVersionFoundInMapper = false
        def quantumAppCheckoutVersion
        for (quantumVersion in quantumVersionReleases) {
            def compareVizVersions = ValidationHelper.compareVersions(childAppVizPluginVersion, quantumVersion.minVizVersionSupport)
            if(compareVizVersions == 0) {
                isVersionFoundInMapper = true
                quantumAppCheckoutVersion = quantumVersion.version
                break
            }
        }
        
        if(!isVersionFoundInMapper) {
            def vizVersionList = []
            quantumVersionReleases.each{ release ->
                vizVersionList.add(release.minVizVersionSupport)
            }
            vizVersionList.add(childAppVizPluginVersion)
            def vizVersionSortedList = []
            vizVersionSortedList = BuildHelper.getSortedVersionList(vizVersionList)
            def immediateLowerVizVersion = vizVersionSortedList.get(vizVersionSortedList.indexOf(childAppVizPluginVersion)-1)
            for (quantumVersion in quantumVersionReleases) {
                def compareVizVersions = ValidationHelper.compareVersions(immediateLowerVizVersion, quantumVersion.minVizVersionSupport)
                if(compareVizVersions == 0) {
                    isVersionFoundInMapper = true
                    quantumAppCheckoutVersion = quantumVersion.version
                    break
                }
            }
        }
      return quantumAppCheckoutVersion
        
    }
    
    /**
     * Get the mapper json fileName to be read for AppViewer source checkout version/tag
     * @param cloudEnv
     * @param nonProdMapperJsonFile
     * @param prodMapperJsonFile
     * @return mapper json file based on cloud environment
     */
    protected static String getMapperJsonFileForQuantumAppBuid(String cloudEnv, String nonProdMapperJsonFile, String prodMapperJsonFile) {
        if (cloudEnv.equalsIgnoreCase("kony.com") || cloudEnv.equalsIgnoreCase("stg-kony.com")) {
            return prodMapperJsonFile
        } else {
            return nonProdMapperJsonFile
        }
    }
    
    /**
     * Getting the quantum build child app visualizer version
     * @param script
     * @param quantumChildAppName
     * @param quantumChildAppTempPath
     * @param projectPropertiesJsonFile
     * @return konyVizVersion for quantum child app
     */
    protected static String getQuantumChildAppVizVersion(script, quantumChildAppTempPath, projectPropertiesJsonFile) {
        script.dir(quantumChildAppTempPath) {
            if(script.fileExists(projectPropertiesJsonFile)) {
                def projectPropertiesJsonContent = script.readJSON file: projectPropertiesJsonFile
                return projectPropertiesJsonContent['konyVizVersion']
            }
            else {
                throw new AppFactoryException("Failed to Read ${projectPropertiesJsonFile} file", 'ERROR')
            }
        }
    }

}
