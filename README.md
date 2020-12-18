snow-import-plugin
==================

### ServiceNow import Plugin
This plugin imports CI and relations from the ServiceNow CMDB REST web service.

#### What is ServiceNow ?
ServiceNow is a Platform-As-A-Service (PAAS) solution of IT Service Management (ITSM).

#### Archi version compatibility
The version 1.7 of the plugin has been tested with Archi 4.4 and Archi 4.5.

### How to install or update it ?
On older version of Archi, download the JAR file and manually copy it to:
- Archi 4.4: your Archi plugins folder (usually c:\program files\Archi\plugins on Windows).
- Archi 4.5 and 4.6: a “dropins” folder located in the user’s home directory

On Archi version 4.7 and newer, download the ARCHIPLUGIN file and install it through the Help / Install plug-ins menu.

Please refer to the Archi documentation for more information.

#### Use-cases
This plugin is useful when one wishes to import all the existing CIs from SericeNow and use Archi to describe the existing technical base.

#### How it works ?
An existing model must be created and selected. The "import from ServiceNow" option becomes available in Archi's File/Import menu.

When ran, the plugin asks for an INI file that contains all the required options:
   - ServiceNow URL and credentials
   - Proxy information and credentials if any
   - Logging requirements (Log4j)
   - Mapping between the ServiceNow CIs and the Archi elements
   - Organization of the Arhi elements (folder names, properties, ...)
   - Mapping between the ServiceNow relations and the Archi relations
   - ...

You may download the *sample_snow-import_plugin_ini_file.ini* file to your local drive (like "My documents") and update it to your needs. The file is self documented.
   
The plugin generates the REST request and connects to the ServiceNow web services to download and parse the data. The request is optimised to reduce the quantity of data downloaded (only the fields described in the ini file are downloaded).

The plugin may be ran several times. Only the changes detected in ServiceNow since the last run will be applied to the Archi objects. A full log of what is done can be generated through Log4j.
