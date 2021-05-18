-----------------------------------------------------------------
Steps to execute the file
-----------------------------------------------------------------
1. Open command prompt / terminal change dir to extracted location
2. Do 'npm install'
3. Run the below command to download feature.xml file for given version.
	'node downloadFeaturesXml.js <version> <baseUrl> <download location> <proxy params>'

Ex: node downloadFeaturesXml.js 8.3.0 'C:\Users\Volt MX\Desktop\featurexml_file'

if proxy is there then follow the bellow command (proxy param is optional)

Ex: node downloadFeaturesXml.js 8.3.0 'C:\Users\Volt MX\Desktop\featurexml_file' 'http://user_name:password@proxy.server.com:proxy_port'

4. For help run 'node downloadFeaturesXml.js help'