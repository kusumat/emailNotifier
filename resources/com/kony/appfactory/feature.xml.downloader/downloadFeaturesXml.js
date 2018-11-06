/*
    This script is developed by Tools team. As per discussion with tools
    team these downloader script isn't going to host with CI tools dependency
    zip.
*/

const fs = require('fs'),
    needle = require('needle'),
    xml2js = require('xml2js'),
    admzip = require('adm-zip'),
    path = require('path'),
    parser = new xml2js.Parser(),
    UTF8 = 'utf8';

var downloadTempLocation,
    proxyParam,
    baseUrl;

var ns = {};

(function(ns) {

    /**
    * Download required file from the given url.
    * @param {Object} params
    * @param {String} params.url - url of the file to download
    * @param {String} params.filename - path to download file.
    * @param {Function} cb - callback function
    */
    function downloadFile(params, cb) {
        var tempFile = path.resolve(downloadTempLocation, path.basename(params.filename));

        try {
            var outStream = fs.createWriteStream(tempFile),
                data,
                streamObj,
                reqOptions = {
                    decode : false,
                    parse : false,
                    follow_max : 10
                };

            if(proxyParam) {
                reqOptions.proxy = proxyParam;
            }
            
            streamObj = needle.get(params.url, reqOptions, function(error, response) {
                if(error) {
                    outStream.close();
                    cb('Error while downloading plugins: ' + JSON.stringify(error, null, 4));
                    return;
                }
                var pluginID = params.pluginID || path.basename(params.filename);

                if(response.statusCode !== 200) {
                    console.error(`Download failed '${pluginID}' response code: ${response.statusCode}`);
                    outStream.close();
                    cb('Error while downloading file'); 
                } else {
                    outStream.end();
                    console.log('Download finished :', pluginID);
                    fs.rename(tempFile, params.filename, function(err) {
                        if (err) {
                            console.error('Error while moving file', err);
                            cb('Error while moving file ' + err);
                        } else {
                            cb();   
                        }
                    }); 
                }
            });

            streamObj.on('readable', function() {
                while(data = this.read()) {
                    outStream.write(data);
                }
            });

            streamObj.on('error', function(error) {
                outStream.close();
                cb(error);
            });

        } catch(e) {
            console.error('Exception while downloading file', e);
            cb(e);            
        }
    }

    /**
    * Parse site.xml and download features plugins from site.xml file.
    * @param {Object} params
    * @param {String} params.filename - path to site.xml file
    * @param {Function} cb - callback function
    */
    function downloadFeaturesPlugin(params, cb) {
        var data = fs.readFileSync(params.filename, UTF8);

        parser.parseString(data, function(error, result) {
            if (error) {
                cb(error);
                return;
            }

            var filePath = result.site.feature[0].$.url,
                jarFileName = filePath.replace('features/', ''),
                jarFilePath = path.resolve(params.tempLocation, jarFileName),
                jarFileUrl = baseUrl.replace('8.', '8');
                jarFileUrl = jarFileUrl.concat(filePath);
                
            var data = {
                'url' : jarFileUrl,
                'filename' : jarFilePath,
                'pluginID' : result.site.feature[0].$.id + ' (features plugin)'
            };
            downloadFile(data, function(error) {
                if (error) {
                    cb(error);
                } else {
                    var unzipper = new admzip(path.resolve(jarFilePath));
                    unzipper.extractAllTo(params.tempLocation, true);
                    cb();
                }
            });
            
        });
    }

    function __assertDir__(filePath) {
        var dirname = path.dirname(filePath);
        if (fs.existsSync(dirname)) {
            return true;
        }
        __assertDir__(dirname);
        fs.mkdirSync(dirname);
    }

    function deleteFolder(folderpath){
        var deleteFolderRecursive = function(folderpath) {
            if( fs.existsSync(folderpath) ) {
                fs.readdirSync(folderpath).forEach(function(file,index){
                    var curPath = path.resolve(folderpath ,file);
                    if(fs.lstatSync(curPath).isDirectory()) { // recurse
                        deleteFolderRecursive(curPath);
                    }
                    else { // delete file
                        fs.unlinkSync(curPath);
                    }
                });
                try{
                    fs.rmdirSync(folderpath);
                }
                catch(err){
                    if(err.code == 'ENOTEMPTY')
                        deleteFolderRecursive(folderpath);
                }
            }
        };
        deleteFolderRecursive(folderpath);
    }

    // start method to download feature.xml file.
    function downloadFeatureXmlFile() {
        var cmdArguments = process.argv.slice(2),
            version = cmdArguments[0],
            downloadLocation = cmdArguments[2];
        baseUrl = cmdArguments[1];
            
        if(cmdArguments.indexOf('help') >= 0) {
            console.log('\nUsage: node downloadFeaturesXml.js\n');
            console.log('node downloadFeaturesXml.js <version> <base url> <download location> <proxy params>', '\nProxy param is optional.');
            return;
        }

        var tempLocation = path.resolve(downloadLocation, '__temp');

        downloadTempLocation = path.resolve(tempLocation, 'plugins');

        if(!fs.existsSync(downloadTempLocation)) {
             __assertDir__(downloadTempLocation);
            fs.mkdirSync(downloadTempLocation);
        }

        if(cmdArguments[3]) {
            proxyParam = cmdArguments[3];
        }

        console.log('Feature.Xml File download has started...');

        var xmlFileName = process.platform === 'win32' ? 'site' : 'macsite', 
            siteUrl = baseUrl + xmlFileName + '-' + version + '.xml',
            siteDotXmlFilePath = path.resolve(tempLocation, xmlFileName + '-' + version + '.xml'),
            params = {
                'url' : siteUrl,
                'filename' : siteDotXmlFilePath
            };

        // Step 1: Download site.xml file from base url.

        downloadFile(params, function(error) {
            if (error) {
                var message = error.indexOf('Error while downloading plugins:') >= 0 ? 'internet connection' : 'version';
                console.error(error + ', please check the ' + message + ' and retry.');
                return;
            }

            // Step 2: Parse site.xml and download features.jar file from the site.xml file.

            params = {
                'filename' : siteDotXmlFilePath,
                'tempLocation' : tempLocation
            };

            downloadFeaturesPlugin(params, function(error) {
                if (error) {
                    console.error(error);
                    return;
                }

                if(fs.existsSync(path.resolve(downloadLocation, 'feature.xml'))) {
                    fs.unlinkSync(path.resolve(downloadLocation, 'feature.xml'));
                }

                fs.rename(path.resolve(tempLocation, 'feature.xml'), path.resolve(downloadLocation, 'feature.xml'), function(err) {
                    deleteFolder(tempLocation);
                    if (err) {
                        console.error('Error while moving file', err);
                        console.error('Error while moving file ' + err);
                    } else {
                        console.log('Feature.Xml File download has completed.');
                        console.log('Feature.Xml File will be available in ::', path.resolve(downloadLocation));
                    } 
                }); 
            });
        });
    }

    ns.downloadFeatureXmlFile = downloadFeatureXmlFile;

})(ns);

var downloadFeatureXmlFile = ns.downloadFeatureXmlFile();

module.exports = ns;