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
