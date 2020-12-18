### v1.6: 18/12/2020
* fix filter field

### v1.5: 16/10/2018
* add "filter" option in ini file

### v1.4: 26/09/2018
* continue to process ServiceNow data in case a link cannot be followed

### v1.3: 16/09/2018
* make the progress bar application modal
* rewrite progress bar to be more readable on 4K displays
* rewrite popups to be more readable on 4K displays
* Can now follow reference links in in properties (with a cache mechanism to reduce the calls to ServiceNow)
* Can now use variables ${xxx} in ini properties
* Use CIs operational status to determine if Archi elements should be created/updated or removed
* Allow to specify the import mode: full, create_only, update_only, create_or_update_only or remove_only  

### v1.2.3: 07/09/2018    (version for Archi 4.x only)
* fix the name of the property used to get the relationships name from the INI file
* increase message detail in log file in case of exception

### v1.2.2: 30/08/2018    (version for Archi 4.x only)
* fix the name of the property used to get the relationships targets from the INI file

### v1.2.1: 24/07/2018    (version for Archi 4.x only)
* Fix update of existing properties when importing again from ServiceNow
* Fix memory leak
* Increase warning level of java compiler and fix all the warnings to improve code reliability* 
* Update classpath to compile with Java libraries

### v1.2: 22/07/2018    (version for Archi 4.x only)
* Adapt to Archi 4.x

### v1.1: 29/12/2015
* The plugin version is now mandatory in the iniFile as the structure changed
* Add remaining time in the progressBar even if it's not really reliable (as the import time is not linear) 
* Rewrite of the getFromURL() method to improve proxy management (the system properties are not modified anymore)
* Optimize relations download by getting only those that are really used
* Migrate logging to Log4J

### v1.0: 12/10/2015
* Plugin creation

