snow-import-plugin
==================

### ServiceNow import Plugin
This plugin imports CI and relations from the ServiceNow CMDB REST web service.

#### What is ServiceNow ?
ServiceNow is a Platform-As-A-Service (PAAS) solution of IT Service Management (ITSM).

### How to install it ?
Download the org.archicontribs.servicenow_0.1.0.jar file to the Archi plugins folder.
Download the servicenow.ini file to your local drive (like "My documents") and edit it to adapt it to your needs.

#### Use-cases
This plugin is useful when one wishes to import all the existing CIs and use Archi to describe the existing technical base.

#### How it works ?
An existing model must be created and selected. The "import from ServiceNow" option becomes available in the file/Import menu.

When ran, the plugin asks for an INI file that contains all the required options:
   - ServiceNow URL and credentials
   - Proxy information and credentials
   - Mapping between the ServiceNow CIs and the Archi elements
   - Organization of the Arhi elements (folder names, properties, ...)
   - Mapping between the ServiceNow relations and the Archi relations
   - ...

The plugin generates the REST request, connects to the web services and GET the data. The request is generated to only download the required data.

The plugin may be ran several times.