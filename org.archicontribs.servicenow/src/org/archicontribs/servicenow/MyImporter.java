/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */

package org.archicontribs.servicenow;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;

import com.archimatetool.editor.model.ISelectedModelImporter;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Archimate Tool Plugin : Import from ServiceNow
 * 
 * @author Herve JOUIN
 * 
 * version 1.0: 12/10/2015
 * 		plugin creation
 * 
 * version 1.1: 29/12/2015
 * 		The plugin version is now mandatory in the iniFile as the structure changed
 * 		Add remaining time in the progressBar even if it's not really reliable (as the import time is not linear) 
 *  	Rewrite of the getFromURL() method to improve proxy management (the system properties are not modified anymore)
 * 		Optimize relations download by getting only those that are really used
 * 		Migrate logging to Log4J
 * 		Set all methods to private (except the doImport that is called by Archi)
 * 		Get all the detailed tables from ServiceNow as the master table cmdb_ci doesn't allow to retrieve the properties
 * 
 * version 1.2: 22/07/2018
 *      Adapt to Archi 4.x
 * 
 * version 1.2.1: 24/07/2018
 *      Fix update of existing properties when importing again from ServiceNow
 *      Fix memory leak
 *      Increase warning level of java compiler and fix all the warnings to improve code reliability
 *      Update classpath to compile with Java libraries
 *      
 * version 1.2.2: 30/08/2018
 *      Fix name of the property used to get the relationships target from the ini file
 * 
 * version 1.2.3: 07/09/2018
 *      Fix name of the property used to get the relationships name from the ini file
 *      Increase message detail in log file in case of exception
 *
 * version 1.3: 16/09/2018
 *      make the progress bar application modal
 *      rewrite progress bar to be more readable on 4K displays
 *      rewrite popups to be more readable on 4K displays
 *      Can now follow reference links in in properties (with a cache mechanism to reduce the calls to ServiceNow)
 *      Can now use variables ${xxx} in ini properties
 *      Use CIs operational status to determine if Archi elements should be created/updated or removed
 *      Allow to specify the import mode: full, create_only, update_only, create_or_update_only and remove_only
 *      
 * version 1.4: 26/09/2018
 *      continue in case we cannot follow a link
 * 
 * version 1.5: 26/10/2018
 *      add "filter" option for elements
 *      
 * version 1.6: 15/11/2018
 *      allow to parse several times the same ServiceNow table
 *      
 * version 1.7: 22/09/2019
 * 		add "filter" option for relationships
 * 		add ini file backward compatibility
 * 		try to improve a bit the error message in case of Json parsing error
 * 		remove dependence to JAXB
 * 		increase progressbar size to adapt to 4K displays
 * 
 * TODO: retrieve the applications and business services
 * TODO: use commands to allow rollback
 * TODO: validate that relations are permitted before creating them
 * TODO: add an option to continue in case of error --> but count the number of errors and show this counter on the summary popup
 * TODO: transform progressbar window: show all tables/relationships with one progress bar in front of each table/relationship
 * TODO: transform progressbar window: show same look and feel that database plugin
 * TOTO: add cancel button
 * TODO: add variable ${on_error:xxxxxxx) ou ${on_empty:xxxxx}
 * 
 */

public class MyImporter implements ISelectedModelImporter {
	static String SNowPluginVersion = "1.7";
	static List<String> SNowPluginVersionCompatibility = Arrays.asList("1.6", "1.7");
	static String title = "ServiceNow import plugin v" + SNowPluginVersion;

	Logger logger;
	MySortedProperties iniProperties;

	boolean mustFollowRefLink;

	// Number of elements or relations created or updated
	int created = 0;
	int updated = 0;
	int removed = 0;

	// ServiceNow information
	String serviceNowUser = null;
	String serviceNowPassword = null;

	// proxy information
	String proxyHost = null;
	int proxyPort = 0;
	String proxyUser = null;
	String proxyPassword = null;

	final int OPERATIONAL = 1;
	final int NON_OPERATIONAL = 2;

	HashMap<String, JsonNode> referenceLinkCache = null;

	@Override
	public void doImport(IArchimateModel model) throws IOException {
		// ServiceNow site and credentials
		String serviceNowSite = null;

		// ServiceNow sysparm_limit (allow to increase or reduce the number of components sent by ServiceNow)
		int serviceNowSysparmLimit = 0;

		// general elements properties
		String generalArchiElementsId = null;
		String generalArchiElementsName = null;
		String generalArchiElementsDocumentation = null;
		String generalArchiElementsFolder = null;
        String generalArchiElementsFilter = null;
		String generalArchiElementsImportMode = null;

		// general relations properties
		String generalArchiRelationsId = null;
		String generalArchiRelationsName = null;
		String generalArchiRelationsDocumentation = null;
		String generalArchiRelationsType = null;
		String generalArchiRelationsFolder = null;
		String generalArchiRelationsSource = null;
		String generalArchiRelationsTarget = null;
		String generalArchiRelationsFilter = null;
		String generalArchiRelationsImportMode = null;

		// we ask for the name of the ini file that contains the configuration of the plugin
		String iniFilename;
		if ( (iniFilename = askForIniFile()) == null )
			return;

		// we initialize the logger
		this.logger = Logger.getLogger("SNowPlugin");

		// ****************************************
		// ***                                  ***
		// *** Getting properties from INI file ***
		// ***                                  ***  
		// ****************************************

		this.iniProperties = new MySortedProperties(this.logger);
		try ( FileInputStream iniFileStream = new FileInputStream(iniFilename) ) {
			this.iniProperties.load(iniFileStream);
		} catch ( FileNotFoundException e) {
			@SuppressWarnings("unused")
			MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Cannot open ini file.", e);
			return;
		}

		// we configure the logger using the log4j properties contained in the ini file
		//    log4j.rootLogger
		//    log4j.appender.SNowPlugin
		//    log4j.appender.SNowPlugin.File
		//    log4j.appender.SNowPlugin.layout
		//    log4j.appender.SNowPlugin.encoding
		//    log4j.appender.SNowPlugin.Append
		//    log4j.appender.SNowPlugin. ...
		try {
			this.logger = Logger.getLogger("SNowPlugin");
			PropertyConfigurator.configure(this.iniProperties);
		} catch (@SuppressWarnings("unused") Exception e) {
			// do nothing ... we do not really care if the logging is disabled ...
		}

		this.logger.info("=====================================");
		this.logger.info("Starting ServiceNow import plugin ...");
		this.logger.info("Getting properties from " + iniFilename);

		// we check if the INI file is a ServiceNow Plugin INI file
		String iniVersion = this.iniProperties.getString("SNowPlugin.version");
		if ( MyUtils.isSet(iniVersion) ) {
			if ( !MyImporter.SNowPluginVersionCompatibility.contains(iniVersion) ) {
				@SuppressWarnings("unused")
				MyPopup popup = new MyPopup(this.logger, Level.FATAL, "The \"SNowPlugin.version\" property ("+iniVersion+") differs from the plugin version.\n\nThe actual plugin version is '" + MyImporter.SNowPluginVersion + "'.");
				return;
			}
		} else {
			@SuppressWarnings("unused")
			MyPopup popup = new MyPopup(this.logger, Level.FATAL, "The \"SNowPlugin.version\" property is mandatory. You must add it in your INI file.\n\nThe actual plugin version is '" + MyImporter.SNowPluginVersion +"'.");
			return;
		}

		// we get the proxy information from the ini file
		this.proxyHost = this.iniProperties.getString("http.proxyHost");
		this.proxyPort = this.iniProperties.getInt("http.proxyPort", 0);
		this.proxyUser = this.iniProperties.getString("http.proxyUser");
		this.proxyPassword = this.iniProperties.getString("http.proxyPassword", null, true);

		// we get the site and credential to access ServiceNow
		serviceNowSite = this.iniProperties.getString("servicenow.site");
		if ( !MyUtils.isSet(serviceNowSite) ) {
			@SuppressWarnings("unused")
			MyPopup popup = new MyPopup(this.logger, Level.FATAL, "The \"servicenow.site\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		this.serviceNowUser = this.iniProperties.getString("servicenow.user");
		if ( !MyUtils.isSet(this.serviceNowUser) ) {
			@SuppressWarnings("unused")
			MyPopup popup = new MyPopup(this.logger, Level.FATAL, "The \"servicenow.user\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		this.serviceNowPassword = this.iniProperties.getString("servicenow.pass", null, true);
		if ( !MyUtils.isSet(this.serviceNowPassword) ) {
			@SuppressWarnings("unused")
			MyPopup popup = new MyPopup(this.logger, Level.FATAL, "The \"servicenow.pass\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		// we get the sysparm_limit
		serviceNowSysparmLimit = this.iniProperties.getInt("servicenow.sysparm_limit", 0);

		// ***************************
		// ***                     ***
		// *** Retrieving elements ***
		// ***                     ***  
		// ***************************
		//
		// ServiceNow keeps CI in separate tables, one table per CI class
		// 
		// we use the snow_table to know which ServiceNow table to retrive
		// We use the archi_class properties to map ServiceNow class to Archi class.
		//
		// Let's take an example:
        //    --> archi.elements.rooms.snow_table = "cmdb_ci_computer_room"
		//    --> archi.elements.rooms.archi_class = "Location"
		// The plugin will get the content of the cmdb_ci_computer_room table from ServiceNow, and create Location elements in Archi
		//
		// So, for elements, the plugin loops on archi.elements.xxxxx.snow_table lines.
		//
		
		boolean mustImportElements = false;
		for (String iniKey: this.iniProperties.stringPropertyNames()) {
            if ( iniKey.startsWith("archi.elements.") ) {
                mustImportElements = true;
                break;
            }
		}
		
		if ( mustImportElements ) { 
    		this.logger.info("Getting elements from ServiceNow ...");
    		
    	    // we get general properties for elements:
            //      properties archi.elements.*.id
            //      properties archi.elements.*.name
            //      properties archi.elements.*.documentation
            //      properties archi.elements.*.folder
            //      properties archi.elements.*.import_mode
            generalArchiElementsId = this.iniProperties.getString("archi.elements.*.id", "sys_id");
            generalArchiElementsName = this.iniProperties.getString("archi.elements.*.name", "sys_class_name");
            generalArchiElementsDocumentation = this.iniProperties.getString("archi.elements.*.documentation", "short_description");
            generalArchiElementsFolder = this.iniProperties.getString("archi.elements.*.folder", "sys_class_name");
            generalArchiElementsFilter = this.iniProperties.getString("archi.elements.*.filter", "");
            generalArchiElementsImportMode = this.iniProperties.getString("archi.elements.*.import_mode", "full");
            if ( !generalArchiElementsImportMode.equals("full") && !generalArchiElementsImportMode.equals("create_or_update_only") && !generalArchiElementsImportMode.equals("create_only") && !generalArchiElementsImportMode.equals("update_only") && !generalArchiElementsImportMode.equals("remove_only") ) {
                @SuppressWarnings("unused")
                MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Unrecognized value for property \"archi.elements.*.import_mode\".\n\nValid values are full, create_or_update_only, create_only, update_only and remove_only.");
                return;
            }
    
            //      properties archi.elements.*.property.xxxx
            MySortedProperties generalElementsProperties = new MySortedProperties(this.logger);
            for (String propertyKey: this.iniProperties.stringPropertyNames()) {
                if ( propertyKey.startsWith("archi.elements.*.property.") ) {
                    String[] subkey = propertyKey.split("\\.");
                    if ( subkey.length == 5 ) {
                        String propertyValue = this.iniProperties.getString(propertyKey);
                        generalElementsProperties.put(subkey[4], propertyValue);
                    }
                }
            }
    
    		try (MyProgressBar progressBar = new MyProgressBar(MyImporter.title, "Connecting to ServiceNow webservice ...") ) {
    			this.created = 0;
    			this.updated = 0;
    			this.removed = 0;
    
    			// We get each table described in properties like archi.elements.<keyword>.snow_table
    			for (String iniKey: this.iniProperties.stringPropertyNames()) {
    				String[] iniSubKeys = iniKey.split("\\.");
    				if ( iniSubKeys.length == 4 && iniSubKeys[0].equals("archi") && iniSubKeys[1].equals("elements") && /* keyword in subKeyx[2] */ iniSubKeys[3].equals("snow_table") ) {
    				    String keyword = getServiceNowField(iniSubKeys[2]);
    					String tableName = this.iniProperties.getString(iniKey);
    					String serviceNowField;
    
    					// we reset the need to follow the reference links
    					this.mustFollowRefLink = false;
    					
    					String archiClass = this.iniProperties.getString("archi.elements."+keyword+".archi_class", null);
    					if ( archiClass == null ) {
    	                   @SuppressWarnings("unused")
                           MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Property \"archi.elements."+keyword+".archi_class\" not found.");
    	                   return;
    					}
    
    					this.logger.info("Found keyword \""+keyword+"\": mapping ServiceNow CIs from table " + tableName + " to Archi " + archiClass);
    
    					//
    					// we construct the ServiceNow URL
    					//
    					StringBuilder urlBuilder = new StringBuilder(serviceNowSite);
    					urlBuilder.append("/api/now/table/");
    					urlBuilder.append(tableName);
    
    					// serviceNowSysparmLimit : number of elements that ServiceNow should send
    					urlBuilder.append("?sysparm_limit=");
    					urlBuilder.append(serviceNowSysparmLimit);
    
    					// We collect all fields that ServiceNow should send us
    					MySortedProperties propertiesToGetFromServiceNow = new MySortedProperties(null);
    					urlBuilder.append("&sysparm_fields=operational_status");
    
    					String archiElementsId = this.iniProperties.getString("archi.elements."+keyword+".id", generalArchiElementsId);
    					serviceNowField = getServiceNowField(archiElementsId);
    					if ( MyUtils.isSet(serviceNowField) ) {
    						urlBuilder.append(",");
    						urlBuilder.append(serviceNowField);
    					}
    
    					String archiElementsName = this.iniProperties.getString("archi.elements."+keyword+".name", generalArchiElementsName);
    					serviceNowField = getServiceNowField(archiElementsName);
    					if ( MyUtils.isSet(serviceNowField) ) {
    						urlBuilder.append(",");
    						urlBuilder.append(serviceNowField);
    					}
    
    					String archiEmentsDocumentation = this.iniProperties.getString("archi.elements."+keyword+".documentation", generalArchiElementsDocumentation);
    					serviceNowField = getServiceNowField(archiEmentsDocumentation);
    					if ( MyUtils.isSet(serviceNowField) ) {
    						urlBuilder.append(",");
    						urlBuilder.append(serviceNowField);
    					}
    
    					String archiElementsFolder = this.iniProperties.getString("archi.elements."+keyword+".folder", generalArchiElementsFolder);
    					HashSet<String> fieldsToRetreive = getPathFields(archiElementsFolder);
    					for ( String field: fieldsToRetreive ) {
    						urlBuilder.append(",");
    						urlBuilder.append(field);
    					}
    					
    					String archiElementsImportMode = this.iniProperties.getString("archi.elements."+keyword+".importMode", generalArchiElementsImportMode);
    					if ( !archiElementsImportMode.equals("full") && !archiElementsImportMode.equals("create_or_update_only") && !archiElementsImportMode.equals("create_only") && !archiElementsImportMode.equals("update_only") && !generalArchiElementsImportMode.equals("remove_only") ) {
    						@SuppressWarnings("unused")
    						MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Unrecognized value for property \"archi.elements."+keyword+".import_mode\".\n\nValid values are full, create_or_update_only, create_only, update_only and remove_only.");
    						return;
    					}
    
    					// we get all the properties specified by a archi.elements.<table>.property.xxx
    					for (String propertyKey: this.iniProperties.stringPropertyNames()) {
    						if ( propertyKey.startsWith("archi.elements."+keyword+".property.") ) {
    							String[] subkeys = propertyKey.split("\\.");
    							if ( subkeys.length == 5 ) {
    								String propertyValue = this.iniProperties.getString(propertyKey);
    								propertiesToGetFromServiceNow.put(subkeys[4], propertyValue);
    								serviceNowField = getServiceNowField(propertyValue);
    								if ( MyUtils.isSet(serviceNowField) ) {
    									urlBuilder.append(",");
    									urlBuilder.append(serviceNowField);
    								}
    							}
    						}
    					}
    
    					// we add all the general properties from the archi.elements.*.property.xxx
    					@SuppressWarnings("unchecked")
    					Enumeration<String> e = (Enumeration<String>) generalElementsProperties.propertyNames();
    					while (e.hasMoreElements()) {
    						String propertyKey = e.nextElement();
    						String propertyValue = generalElementsProperties.getString(propertyKey);
    						// we check if the property is not yet part of the properties list
    						if ( !propertiesToGetFromServiceNow.containsKey(propertyKey) ) {
    							this.logger.debug("   Found archi.elements.*.property." + propertyKey + " = " + propertyValue);
    							propertiesToGetFromServiceNow.put(propertyKey, propertyValue);
    							serviceNowField = getServiceNowField(propertyValue);
    							if ( MyUtils.isSet(serviceNowField) ) {
    								urlBuilder.append(",");
    								urlBuilder.append(serviceNowField);
    							}
    						}
    					}
    
    					// we indicate to ServiceNow if we want to follow the reference links or not
    					urlBuilder.append("&sysparm_exclude_reference_link="+String.valueOf(this.mustFollowRefLink));
    
    					// We apply a filter depending of the requested import mode
    					//     operational_status = 1        if create or update only
    					//     operational_status = 2        if remove_only
    					//     and no filter                 if create, update and remove
    					StringBuilder sysparmQuery = new StringBuilder();
    					if ( generalArchiElementsImportMode.equals("create_or_update_only") || generalArchiElementsImportMode.equals("create_only") || generalArchiElementsImportMode.equals("update_only") )
    					    sysparmQuery.append("operational_status="+this.OPERATIONAL);
    					else if ( generalArchiElementsImportMode.equals("remove_only") )
    					    sysparmQuery.append("operational_status="+this.NON_OPERATIONAL);
    					
    	                String archiElementsFilter = this.iniProperties.getString("archi.elements."+keyword+".filter", generalArchiElementsFilter);
    	                if ( archiElementsFilter.length() != 0 ) {
    	                    if ( sysparmQuery.length() != 0 )
    	                        sysparmQuery.append(",");
    	                    sysparmQuery.append(archiElementsFilter);
    	                }
    
    					if ( sysparmQuery.length() != 0 ) {
    					    urlBuilder.append("&sysparm_query=");
    					    urlBuilder.append(sysparmQuery);
    					}
    					
    					this.logger.debug("   Generated URL is " + urlBuilder.toString());
    
    					// we invoke the ServiceNow web service
    					MyConnection connection = new MyConnection(this.proxyHost, this.proxyPort, this.proxyUser, this.proxyPassword);
    					connection.setProgressBar(progressBar);
    					connection.setLogger(this.logger);
    					String jsonString = connection.get(iniSubKeys[2], urlBuilder.toString(), this.serviceNowUser, this.serviceNowPassword);
    
    					progressBar.setLabel("Counting elements recieved from ServiceNow...");
    					int count = 0;
    
    					JsonFactory jsonFactory = new MappingJsonFactory();
    
    					try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
    						// we first validate the JSON structure and count the number of elements received
    						if ( !jsonParser.nextToken().equals(JsonToken.START_OBJECT) ) {
    							throw new MyException("We did not receive JSON data !!!");
    						}
    						while ( !jsonParser.nextToken().equals(JsonToken.END_OBJECT) ) {
    							if ( jsonParser.getCurrentName().equals("result") ) {
    								if ( jsonParser.nextToken().equals(JsonToken.START_ARRAY) ) {
    									// if an array, then we count the number of elements
    									JsonToken current;
    									int sub = 0;
    									while ( !(current = jsonParser.nextToken()).equals(JsonToken.END_ARRAY) ) {
    										switch (current) {
    											case START_OBJECT : if ( sub++ == 0 ) count++; break;
    											case END_OBJECT : sub--; break;
    											default : break;
    										}
    									}
    								} else {
    									throw new MyException("Error, we did not received the expected JSON array.");
    								}
    							} else {
    								if ( jsonParser.getCurrentName().equals("error") ) {
    									jsonParser.nextToken();
    									JsonNode node = jsonParser.readValueAsTree();
    									throw new MyException("Error while retrieving data from the ServiceNow webservice (" + node.get("message").asText() + ")");
    								}
    								this.logger.error("Here is what we received from the server :");
    								this.logger.error(jsonString);
    								throw new MyException("Error while retrieving data from the ServiceNow webservice\n\nThe data receied is in an unknow format.");
    							}
    						}
    					}
    
    					this.logger.debug("   Received " + count + " elements.");
    					progressBar.setLabel(keyword+": parsing "+tableName+" table ("+count+" elements) ...");
    					progressBar.setMaximum(count); 
    
    					// now we manage elements
    
    					try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
    						jsonParser.nextToken();	// START_OBJECT
    						jsonParser.nextToken();	// "results"
    						jsonParser.nextToken();	// START_ARRAY
    						while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
    							JsonNode jsonNode = jsonParser.readValueAsTree();
    
    							progressBar.increase();
    
    							// the ID and import mode and is quite specific as the element is not yet known
    							String requestedId = expand(jsonNode, archiElementsId, null);
    							if ( requestedId == null )
    								throw new MyException("Cannot retrieve element's ID (check \"properties archi.elements.*.id\" and \"archi.elements."+keyword+".id\")");
    							this.logger.debug("   Got new CI with ID "+requestedId);
    
    							String requestedArchiClass = expand(jsonNode, archiClass, null);
    							if ( requestedArchiClass == null )
    								throw new MyException("Cannot retrieve element's class in Archi (check properties \"archi.elements.*.archi_class\" and \"archi.elements."+keyword+".archi_class\")");
    							this.logger.debug("   Mapping to Archi class "+requestedArchiClass);
    
    							int operationalStatus;
    							if ( getJsonField(jsonNode, "operational_status") == null )
    								throw new MyException("Cannot retrieve element's operational status (field operational_status in ServiceNow)");
    							operationalStatus = Integer.valueOf(getJsonField(jsonNode, "operational_status"));
    
    							IArchimateElement element = null;
    							try {
    								element = createOrRemoveArchimateElement(model, requestedArchiClass, archiElementsImportMode, operationalStatus, requestedId);
    							} catch (Exception ex) {
    								@SuppressWarnings("unused")
    								MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Canno't create element of class "+requestedArchiClass, ex);
    								return;
    							}
    
    							// if the element is not null, this means that we must update its properties
    							if ( element != null ) {
    								// if the element is not in the correct folder, we move it
    								IFolder currentFolder = (IFolder)element.eContainer();
    								String currentFolderPath = getFolderPath(currentFolder);
    								String requestedFolderPath = expandPath(jsonNode, archiElementsFolder, element);
    								if ( requestedFolderPath == null )
    									throw new MyException("Cannot retrieve element's folder (check properties \"archi.elements.*.folder\" and \"archi.elements."+keyword+".folder\")");
    								if ( !currentFolderPath.equals(requestedFolderPath) ) {
    									IFolder requestedFolder = getFolder(model, element, requestedFolderPath);
    									if ( requestedFolder == null )
    										this.logger.error("Failed to get folder for path "+requestedFolderPath);
    									else {
    										this.logger.trace("      Moving to folder " + requestedFolderPath);
    
    										// if the element is already in a folder, we remove it
    										if ( currentFolder != null )
    											currentFolder.getElements().remove(element);
    
    										requestedFolder.getElements().add(element);
    									}
    								}
    
    								String requestedName = expand(jsonNode, archiElementsName, element);
    								if ( requestedName == null )
    									throw new MyException("Cannot retrieve element's name (check properties \"archi.elements.*.name\" and \"archi.elements."+keyword+".name\")");
    								if ( !element.getName().equals(requestedName) ) {
    									this.logger.trace("      Setting name to " + requestedName);
    									element.setName(requestedName);
    								}
    
    								String requestedDocumentation = expand(jsonNode, archiEmentsDocumentation, element);
    								if ( requestedDocumentation == null )
    									throw new MyException("Cannot retrieve element's documentation (check properties \"archi.elements.*.documentation\" and \"archi.elements."+keyword+".decumentation\")");
    								if ( !element.getDocumentation().equals(requestedDocumentation) ) {
    									this.logger.trace("      Setting documentation to " + requestedDocumentation);
    									element.setDocumentation(requestedDocumentation);
    								}
    
    								for (String propertyName: propertiesToGetFromServiceNow.stringPropertyNames()) {
    									String propertyValue = expand(jsonNode, propertiesToGetFromServiceNow.getProperty(propertyName), element);
    									if ( propertyValue == null )
    										propertyValue = "";
    
    									// we now need to find the element's property with the corresponding name
    									boolean propHasBeenFound = false;
    									for (Iterator<IProperty> i = element.getProperties().iterator(); i.hasNext(); ) {
    										IProperty elementProperty = i.next();
    
    										if ( elementProperty.getKey().equals(propertyName) ) {
    											propHasBeenFound = true;
    
    											if ( !propertyValue.equals(elementProperty.getValue()) ) {
    												this.logger.trace("      Setting property " + propertyName + " to " + propertyValue);
    												elementProperty.setValue(propertyValue);
    												break;
    											}
    										}
    									}
    
    									if ( !propHasBeenFound ) {
    										// if we're here, it means the property doesn't exists. Therefore, we create it.
    										this.logger.trace("      Adding property " + propertyName + " to " + propertyValue);
    
    										IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
    										prop.setKey(propertyName);
    										prop.setValue(propertyValue);
    										element.getProperties().add(prop);
    									}
    
    								}
    							}
    						}
    					}
    				}
    			}
    		} catch (Exception error) {
    			@SuppressWarnings("unused")
    			MyPopup popup = new MyPopup(this.logger, Level.FATAL,"Cannot get CIs from ServiceNow web service: ", error);
    			return;
    		} finally {
    			this.logger.info(Integer.toString(this.created+this.updated+this.removed) + " elements have been modified: "+this.created+" created, "+this.updated+" updated, "+this.removed+" removed.");
    			this.referenceLinkCache = null;
    		}
		} else {
		    this.logger.info("No element to import from ServiceNow ...");
		}


		// ****************************
		// ***                      ***
		// *** Retrieving relations ***
		// ***                      ***  
		// ****************************
		//
		// ServiceNow keeps relations in single table called cmdb_rel_ci, the relation type being a numerical ID that must be guessed manually from ServiceNow.
		// 
		// We use the archi_class properties to map ServiceNow class to Archi class.
		//
		// Let's take an example:
		//    --> archi.elements.<snow type>.archi_class = <archi class>
		// The plugin will parse the relations table and when a <snow type> relation is found, create <archi class> elements in Archi
		//
		// So, for relations, the plugin loops the cmdb_rel_ci table content.
		//
		
        boolean mustImportRelations = false;
        for (String iniKey: this.iniProperties.stringPropertyNames()) {
           if ( iniKey.startsWith("archi.relations.") ) {
               mustImportRelations = true;
               break;
            }
        }
        
        if ( mustImportRelations ) {
    		this.logger.info("Getting relations from ServiceNow ...");
    		
    	    // we get general properties for relations:
            //      properties archi.relations.*.id
            //      properties archi.relations.*.name
            //      properties archi.relations.*.documentation
            //      properties archi.relations.*.type
            //      properties archi.relations.*.folder
            //      properties archi.relations.*.source
            //      properties archi.relations.*.target
            //      properties archi.relations.*.import_mode
            generalArchiRelationsId = this.iniProperties.getString("archi.relations.*.id", "sys_id");
            generalArchiRelationsName = this.iniProperties.getString("archi.relations.*.name", "sys_class_name");
            generalArchiRelationsDocumentation = this.iniProperties.getString("archi.relations.*.documentation", "short_description");
            generalArchiRelationsType = this.iniProperties.getString("archi.relations.*.type", "type");
            generalArchiRelationsFolder = this.iniProperties.getString("archi.relations.*.folder", "/");
            generalArchiRelationsSource = this.iniProperties.getString("archi.relations.*.source", "child");
            generalArchiRelationsTarget = this.iniProperties.getString("archi.relations.*.target", "parent");
            generalArchiRelationsFilter = this.iniProperties.getString("archi.relations.*.filter", "");
            generalArchiRelationsImportMode = this.iniProperties.getString("archi.relations.*.import_mode", "full");
            if ( !generalArchiRelationsImportMode.equals("full") && !generalArchiRelationsImportMode.equals("create_or_update_only") && !generalArchiRelationsImportMode.equals("create_only") && !generalArchiRelationsImportMode.equals("update_only") ) {
                @SuppressWarnings("unused")
                MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Unrecognized value for property \"archi.elements.*.import_mode\".\n\nValid values are full, create_or_update_only, create_only and update_only.");
                return;
            }
    
            //      properties archi.relations.*.property.xxxx
            MySortedProperties generalRelationsProperties = new MySortedProperties(this.logger);
            for (String propertyKey: this.iniProperties.stringPropertyNames()) {
                if ( propertyKey.startsWith("archi.relations.*.property.") ) {
                    String[] subkey = propertyKey.split("\\.");
                    if ( subkey.length == 5 ) {
                        String propertyValue = this.iniProperties.getString(propertyKey);
                        generalRelationsProperties.put(subkey[4], propertyValue);
                    }
                }
            }
    
    		//
    		// we construct the ServiceNow URL
    		//
    		StringBuilder urlBuilder = new StringBuilder(serviceNowSite);
    		urlBuilder.append("/api/now/table/cmdb_rel_ci");
    
    		// serviceNowSysparmLimit : number of elements that ServiceNow should send
    		urlBuilder.append("?sysparm_limit=");
    		urlBuilder.append(serviceNowSysparmLimit);
    
    		// we reset the need to follow the reference links
    		this.mustFollowRefLink = false;
    
    		// We collect all fields and relations that ServiceNow should send us
    		Set<String> fieldsToGetFromServiceNow = new HashSet<String>();			// order is not important
    		Set<String> propertiesToGetFromServiceNow = new TreeSet<String>();		// we sort by alphabetical order, not really important but a personal preference ;-)
    		Set<String> relationsToGetFromServiceNow = new HashSet<String>();		// order is not important
    		for (String iniKey: this.iniProperties.stringPropertyNames()) {
    			String[] iniSubKeys = iniKey.split("\\.");
    			if ( iniSubKeys[0].equals("archi") && iniSubKeys[1].equals("relations") && ( (iniSubKeys.length == 4) || ((iniSubKeys.length == 5) && iniSubKeys[3].equals("property")) ) ) {
    				if ( iniSubKeys[3].equals("folder") ) {
    					fieldsToGetFromServiceNow.addAll(getPathFields(this.iniProperties.getString(iniKey)));
    				} else {
    					String serviceNowField = getServiceNowField(this.iniProperties.getString(iniKey));
    					if ( MyUtils.isSet(serviceNowField) )
    						fieldsToGetFromServiceNow.add(serviceNowField);
    				}
    
    				if ( iniSubKeys.length == 5 )
    					propertiesToGetFromServiceNow.add(iniSubKeys[4]);
    
    				relationsToGetFromServiceNow.add(iniSubKeys[2]);
    			}
    		}
    		// we add the default values, just in case
    		fieldsToGetFromServiceNow.add("sys_id");
    		fieldsToGetFromServiceNow.add("sys_class_name");
    		fieldsToGetFromServiceNow.add("child");
    		fieldsToGetFromServiceNow.add("parent");
    		fieldsToGetFromServiceNow.add("type");
    
    		// we specify the list of ServiceNow fields to retrieve
    		urlBuilder.append("&sysparm_fields=operational_status");
    		for (String field: fieldsToGetFromServiceNow ) {
    			urlBuilder.append(",");
    			urlBuilder.append(field);
    		}
    
    		// we specify the list of ServiceNow relations to retrieve
    		urlBuilder.append("&sysparm_query=typeIN");
    		String sep="";
    		for (String relation: relationsToGetFromServiceNow ) {
    			urlBuilder.append(sep);
     			urlBuilder.append(relation);
    			sep=",";
    		}
    		if ( (generalArchiRelationsFilter != null) && !generalArchiRelationsFilter.isEmpty() ) {
    			urlBuilder.append("%5E");
    			urlBuilder.append(generalArchiRelationsFilter);
    		}
    
    		// we indicate to ServiceNow if we want to follow the reference links or not
    		urlBuilder.append("&sysparm_exclude_reference_link="+String.valueOf(this.mustFollowRefLink));
    
    		this.logger.debug("   Generated URL is " + urlBuilder.toString());
    
    		try ( MyProgressBar progressBar = new MyProgressBar(MyImporter.title, "Connecting to ServiceNow webservice ...") ) {
    			// import relations
    			MyConnection connection = new MyConnection(this.proxyHost, this.proxyPort, this.proxyUser, this.proxyPassword);
    			connection.setProgressBar(progressBar);
    			connection.setLogger(this.logger);
    			String jsonString = connection.get("relations", urlBuilder.toString(), this.serviceNowUser, this.serviceNowPassword);
    
    			progressBar.setLabel("Counting relations recieved from ServiceNow...");
    			int count = 0;
    
    			JsonFactory jsonFactory = new MappingJsonFactory();
    			try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
    				// we first count the number of elements received
    				if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
    					throw new MyException("We did not receive JSON data !!!");
    				}
    
    				while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
    					if ( jsonParser.getCurrentName().equals("result") ) {
    						if (jsonParser.nextToken() == JsonToken.START_ARRAY) {
    							// if an array, then we count the number of elements
    							JsonToken current;
    							int sub = 0;
    							while ( (current = jsonParser.nextToken()) != JsonToken.END_ARRAY ) {
    								switch (current) {
    									case START_OBJECT : if ( sub++ == 0 ) count++; break;
    									case END_OBJECT : sub--; break;
    									default : break;
    								}
    							}
    						} else {
    							throw new MyException("should't be here as always array !!!");
    						}
    					} else {
    						if ( jsonParser.getCurrentName().equals("error") ) {
    							jsonParser.nextToken();
    							JsonNode node = jsonParser.readValueAsTree();
    							throw new MyException("Error while retrieving data from the ServiceNow webservice (" + getJsonField(node, "message") + ")");
    						}
    						this.logger.error("Here is what we received from the server :");
    						this.logger.error(jsonString);
    						throw new MyException("Error while retrieving data from the ServiceNow webservice.\n\nThe data receied is in an unknow format.");
    					}
    				}
    			}
    
    			// Setting the ProgressBar maximum
    			progressBar.setLabel("Parsing "+count+" relations from ServiceNow webservice ...");
    			this.created = 0;
    			this.updated = 0;
    			this.removed = 0;
    			progressBar.setMaximum(count);
    
    			// now we create relations
    			try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
    				jsonParser.nextToken(); // START_OBJECT
    				jsonParser.nextToken(); // "result"
    				jsonParser.nextToken(); // START_ARRAY
    				while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
    					progressBar.increase();
    					JsonNode jsonNode = jsonParser.readValueAsTree();
    
    					// here, in each loop, we've got a difference relation from ServiceNow.
    					// Instead of match the ini file properties with the fields got from ServiceNow, we need to match the fields got from ServiceNow with the properties in the ini file
    
    					// we get the type of the relationship from the ServiceNow "type" field
    					String servicenowRelationType = getJsonField(jsonNode, generalArchiRelationsType);
    					if ( !MyUtils.isSet(servicenowRelationType) ) {
    						@SuppressWarnings("unused")
    						MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Cannot get relation's type.\n\n Please check the \"archi.relations.*.type\" property in the ini file.");
    						break;
    					}
    
    					// we get the Id of the ServiceNow relation
    					String requestedId = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".id", generalArchiRelationsId), null);
    					if ( requestedId == null ) {
    						this.logger.error("Cannot get relation's id, ignoring relation. Please check the \"archi.relations."+servicenowRelationType+".id\" and \"archi.relations.*.id\" properties in the ini file.");
    						continue;
    					}
    					
    					this.logger.debug("   Got new relation with ID "+requestedId);
    
    					// we get the requested Archi class of the relation
    					String requestedArchiClass = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".archi_class"), null);
    					if ( requestedArchiClass == null ) {
    						this.logger.error("Cannot get relation's class, ignoring relation. Please check the \"archi.relations."+servicenowRelationType+".archi_class\" property in the ini file.");
    						continue;
    					}
    
    					// we get the ServiceNow relation source and target IDs
    					String relationSourceId = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".source", generalArchiRelationsSource), null);
    					if ( relationSourceId == null ) {
    						this.logger.error("Cannot get relation's source, ignoring relation. Please check the \"archi.relations."+servicenowRelationType+".source\" and \"archi.relations.*.source\" properties in the ini file.");
    						continue;
    					}
    
    					String relationTargetId = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".target", generalArchiRelationsTarget), null);
    					if ( relationTargetId == null ) {
    						this.logger.error("Cannot get relation's target, ignoring relation. Please check the \"archi.relations."+servicenowRelationType+".target\" and \"archi.relations.*.target\" properties in the ini file.");
    						continue;
    					}
    
    					// we get the requested import mode
    					String requestedImportMode = expand(null, this.iniProperties.getString("archi.relations."+servicenowRelationType+".import_mode", generalArchiRelationsImportMode), null);
    					if ( !requestedImportMode.equals("full") && !requestedImportMode.equals("create_or_update_only") && !requestedImportMode.equals("create_only") && !requestedImportMode.equals("update_only") ) {
    						@SuppressWarnings("unused")
    						MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Unrecognized value for property \"archi.elements."+servicenowRelationType+".import_mode\", ignoring relation.\n\nValid values are full, create_or_update_only, create_only and update_only.");
    						break;
    					}
    
    					IArchimateRelationship relation = null;
    					try {
    						relation = createOrRemoveArchimateRelation(model, requestedArchiClass, requestedImportMode, requestedId, relationSourceId, relationTargetId);
    					} catch (Exception e) {
    						@SuppressWarnings("unused")
    						MyPopup popup = new MyPopup(this.logger, Level.FATAL, "Canno't create element of class "+requestedArchiClass, e);
    						break;
    					}
    
    					// if the relation is not null, this means that we must update its properties
    					if ( relation != null ) {
    						// if the relation is not in the correct folder, we move it
    						IFolder currentFolder = (IFolder)relation.eContainer();
    						String currentFolderPath = getFolderPath(currentFolder);
    						String requestedFolderPath = expandPath(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".folder", generalArchiRelationsFolder), relation);
    						if ( requestedFolderPath == null )
    							throw new MyException("Cannot retrieve relation's folder (check properties \"archi.relations.*.folder\" and \"archi.relations."+servicenowRelationType+".folder\")");
    						if ( !currentFolderPath.equals(requestedFolderPath) ) {
    							IFolder requestedFolder = getFolder(model, relation, requestedFolderPath);
    							if ( requestedFolder == null )
    								this.logger.error("Failed to get folder for path "+requestedFolderPath);
    							else {
    								this.logger.trace("      Moving to folder " + requestedFolderPath);
    
    								// if the relation is already in a folder, we remove it
    								if ( currentFolder != null )
    									currentFolder.getElements().remove(relation);
    
    								requestedFolder.getElements().add(relation);
    							}
    						}
    
    						String requestedName = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".name", generalArchiRelationsName), relation);
    						if ( requestedName == null )
    							throw new MyException("Cannot retrieve relation's name (check properties \"archi.relations.*.name\" and \"archi.relations."+servicenowRelationType+".name\")");
    						if ( !relation.getName().equals(requestedName) ) {
    							this.logger.trace("      Setting name to " + requestedName);
    							relation.setName(requestedName);
    						}
    
    						String requestedDocumentation = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".documentation", generalArchiRelationsDocumentation), relation);
    						if ( (requestedDocumentation != null) && !relation.getDocumentation().equals(requestedDocumentation) ) {
    							this.logger.trace("      Setting documentation to " + requestedDocumentation);
    							relation.setDocumentation(requestedDocumentation);
    						}
    
    						for (String propertyName: propertiesToGetFromServiceNow) {
    							// we check if the property is required for this relation
    							String propertyValue = expand(jsonNode, this.iniProperties.getString("archi.relations."+servicenowRelationType+".property."+propertyName, this.iniProperties.getString("archi.relations.*.property."+propertyName)), relation);
    							if ( propertyValue != null ) {
    								// we now need to find the relations's property with the corresponding name
    								boolean propHasBeenFound = false;
    								for (Iterator<IProperty> i = relation.getProperties().iterator(); i.hasNext(); ) {
    									IProperty relationProperty = i.next();
    
    									if ( relationProperty.getKey().equals(propertyName) ) {
    										propHasBeenFound = true;
    
    										if ( !propertyValue.equals(relationProperty.getValue()) ) {
    											this.logger.trace("      Setting property " + propertyName + " to " + propertyValue);
    											relationProperty.setValue(propertyValue);
    											break;
    										}
    									}
    								}
    
    								if ( !propHasBeenFound ) {
    									// if we're here, it means the property doesn't exists. Therefore, we create it.
    									this.logger.trace("      Adding property " + propertyName + " to " + propertyValue);
    
    									IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
    									prop.setKey(propertyName);
    									prop.setValue(propertyValue);
    									relation.getProperties().add(prop);
    								}
    							}
    						}
    					}
    				}
    			}
    		} catch (Exception err) {
    			@SuppressWarnings("unused")
    			MyPopup popup = new MyPopup(this.logger, Level.FATAL,"Cannot get relations from ServiceNow web service: ", err);
    			return;
    		} finally {
    			this.logger.info(Integer.toString(this.created+this.updated+this.removed) + " relations have been modified: "+this.created+" created, "+this.updated+" updated, "+this.removed+" removed.");
    			this.referenceLinkCache = null;
    		}
        } else {
            this.logger.info("No relation to import from ServiceNow ...");
        }

		this.logger.info("All done ...");
	}

	private IArchimateElement createOrRemoveArchimateElement(IArchimateModel model, String archiClassName, String importMode, int operationalStatus, String id) throws MyException {
		boolean mustCreate = false;
		boolean mustUpdate = false;
		boolean mustRemove = false;

		this.logger.trace("      Operational status is " + (operationalStatus == this.OPERATIONAL ? "OPERATIONAL" : "NON OPERATIONAL") );

		IArchimateElement element = null;
		EObject eObject = ArchimateModelUtils.getObjectByID(model, id);

		if ( eObject != null ) {
			if ( !(eObject instanceof IArchimateElement) )
				throw new MyException("Element with ID already exists in the model, but it is not an element (it is a "+eObject.getClass().getSimpleName()+").");
			element = (IArchimateElement)eObject;
		}

		this.logger.trace("      Corresponding element does "+(element==null ? "not " : "")+"exist in the model.");

		switch ( importMode ) {
			case "create_only":
				mustCreate = ((operationalStatus == this.OPERATIONAL) && (element == null));
				break;

			case "update_only":
				mustUpdate = ((operationalStatus == this.OPERATIONAL) && (element != null));
				break;

			case "remove_only":
				mustRemove = ((operationalStatus == this.NON_OPERATIONAL) && (element != null));
				break;

			case "create_or_update_only":
				if ( operationalStatus == this.OPERATIONAL ) {
					mustCreate = (element == null);
					mustUpdate = (element != null);
				}
				break;

			default: // case "full"
				if ( operationalStatus == this.OPERATIONAL ) {
					// we must either create the element if it does not exist, or update it if it does exist.
					mustCreate = (element == null);
					mustUpdate = (element != null);
				} else
					mustRemove = (element != null);
		}

		if ( !mustCreate && !mustUpdate && !mustRemove ) {
			this.logger.trace("      Nothing to do");
			return null;
		}


		// if we must create the element
		if ( mustCreate ) {
			if ( element != null ) {
				// should never be here, but just in case ...
				this.logger.error("   We must create the element, but it does already exist !!!");
			} else {
				this.logger.trace("      Creating new " + archiClassName + " with ID = " + id);
				element = (IArchimateElement)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(archiClassName));
				element.setId(id);
				++this.created;
			}
		}

		// if the element must be removed from the model
		if ( mustRemove ) {
			if ( element == null ) {
				// should never be here, but just in case ...
				this.logger.error("   We must remove this element, but it does not exist !!!");
			} else {
				this.logger.trace("      Removing element from the model");
				// we remove the element from all the views
				for ( IDiagramModelArchimateObject obj: element.getReferencingDiagramObjects() ) {
					IDiagramModelContainer parentDiagram = (IDiagramModelContainer)obj.eContainer();
					parentDiagram.getChildren().remove(obj);
				}

				// we remove the element from its folder
				IFolder parentFolder = (IFolder)element.eContainer();
				parentFolder.getElements().remove(element);

				// we return a null element
				element = null;

				++this.removed;
			}
		}

		if ( mustUpdate ) {
			if ( element == null ) {
				// should never be here, but just in case ...
				this.logger.error("   We must update this element, but it does not exist !!!");
			} else {
				// if the element has just been created, we do not increase the updated counter
				this.logger.trace("      Updating existing "+element.getClass().getSimpleName()+" "+element.getName());
				++this.updated;
			}
		}

		return element;
	}

	private IArchimateRelationship createOrRemoveArchimateRelation(IArchimateModel model, String archiClassName, String importMode, String id, String sourceId, String targetId) {
		boolean mustCreate = false;
		boolean mustUpdate = false;
		//boolean mustRemove = false;

		IArchimateRelationship relation = null;
		IArchimateConcept source = null;
		IArchimateConcept target = null;

		EObject eObject = ArchimateModelUtils.getObjectByID(model, id);

		if ( eObject != null ) {
			if ( !(eObject instanceof IArchimateRelationship) ) {
				this.logger.error("Object with ID "+id+" already exists in the model, but it is not a relationship (it is a "+eObject.getClass().getSimpleName()+").");
				return null;
			}
			relation = (IArchimateRelationship)eObject;
		}

		eObject = ArchimateModelUtils.getObjectByID(model, sourceId);
		if ( (eObject != null) && !(eObject instanceof IArchimateConcept) ) {
			this.logger.error("Object with ID "+sourceId+" already exists in the model, but it is not an Archimate Concept so it cannot be the source of the connection (it is a "+eObject.getClass().getSimpleName()+").");
			return null;
		}
		source = (IArchimateConcept) eObject;

		eObject = ArchimateModelUtils.getObjectByID(model, targetId);
		if ( (eObject != null) && !(eObject instanceof IArchimateConcept) ) {
			this.logger.error("TObject with ID "+targetId+" already exists in the model, but it is not an Archimate Concept so it cannot be the source of the connection (it is a "+eObject.getClass().getSimpleName()+").");
			return null;
		}
		target = (IArchimateConcept) eObject;

		this.logger.trace("      Corresponding relation does "+(relation==null ? "not " : "")+"exist in the model.");

		switch ( importMode ) {
			case "create_only":
				mustCreate = (relation == null);
				break;

			case "update_only":
				mustUpdate = (relation != null);
				break;

			case "remove_only":
				// cannot remove relationships as they do not have operational status in ServiceNow
				break;

			case "create_or_update_only":
				mustCreate = (relation == null);
				mustUpdate = (relation != null);
				break;

			default: // case "full"
				mustCreate = (relation == null);
				mustUpdate = (relation != null);
		}

		if ( !mustCreate && !mustUpdate ) {
			this.logger.trace("      Nothing to do");
			return null;
		}

		// if we must create the relation
		if ( mustCreate ) {
			if ( relation != null ) {
				// should never be here, but just in case ...
				this.logger.error("   We must create the relation, but it does already exist !!!");
			} else {
				if ( (source == null) || (target == null) ) {
					this.logger.trace("      Cannot create the relation as the source ("+sourceId+") or the target ("+targetId+") do not exist.");
				} else {
					this.logger.trace("      Creating new " + archiClassName + " with ID = " + id);
					relation = (IArchimateRelationship)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(archiClassName));
					relation.setId(id);
					relation.setSource(source);
					relation.setTarget(target);
					++this.created;
				}
			}
		}

		if ( mustUpdate ) {
			if ( relation == null ) {
				// should never be here, but just in case ...
				this.logger.error("   We must update this relation, but it does not exist !!!");
			} else {
				// if the element has just been created, we do not increase the updated counter
				this.logger.trace("      Updating existing "+relation.getClass().getSimpleName()+" "+relation.getName());
				++this.updated;
			}
		}

		return relation;
	}

	private static String askForIniFile() {
		FileDialog dialog = new FileDialog(Display.getCurrent().getActiveShell());
		dialog.setText(MyImporter.title+" - Please select your INI file ...");
		String[] ext = {"*.ini","*"};
		dialog.setFilterExtensions(ext);
		return dialog.open();
	}

	/**
	 * Gets a field from a Json record<br>
	 * <br>
	 * If the field is under the form field#subfield[#subfield...], then the field is supposed to be a container and the method follows the link field to get the subfield value.
	 * @param node
	 * @param fieldName
	 * @return the field value, or null if the field is not found
	 * @throws IOException 
	 * @throws MyException 
	 */
	private String getJsonField(JsonNode node, String fieldName) throws MyException, IOException {
		return getJsonField(node, fieldName, null);
	}

	/**
	 * Gets a field from a Json record<br>
	 * <br>
	 * If the field is under the form field#subfield[#subfield...], then the field is supposed to be a container and the method follows the link field to get the subfield value.
	 * @param node
	 * @param fieldName
	 * @param defaultValue
	 * @return the field value, or the default value if the field is not found
	 * @throws IOException 
	 * @throws MyException 
	 */
	private String getJsonField(JsonNode node, String fieldName, String defaultValue) throws MyException, IOException {
		if ( !MyUtils.isSet(fieldName) )
			return defaultValue;

		JsonNode jsonNode = node;
		String subFields[] = fieldName.split("#");
		if ( subFields.length > 1) {
			// if there is a hash tag in the field, then it means that we must follow a reference link
			for ( int column = 0; column < subFields.length-1; ++column) {
				JsonNode containerNode = jsonNode.get(subFields[column]);
				if ( containerNode == null )
					this.logger.error("Cannot get field "+fieldName+" because the field "+subFields[column]+" does not exist.");
				else {
					if ( !containerNode.isContainerNode() )
						this.logger.error("Cannot get field "+fieldName+" because the field "+subFields[column]+" is not a container.");
					else {
						if ( (column == subFields.length-2) && subFields[subFields.length-1].equals("value")) {
							// if the last field to get is "value" then there is no need to get the URL from ServiceNow as the value is directly available in the container
							jsonNode = jsonNode.get(subFields[column]);
							break;
						}
						String linkURL = getJsonField(containerNode, "link");
						if ( linkURL == null )
							this.logger.error("Cannot get field "+fieldName+" because the field link has not been found in the container "+subFields[0]);
						else {
							// we check if the json node is already in the cache
							JsonNode nodeFromCache = null;
							if ( this.referenceLinkCache == null )
								this.referenceLinkCache = new HashMap<String,JsonNode>();
							else
								nodeFromCache = this.referenceLinkCache.get(linkURL);
							if ( nodeFromCache != null )
								jsonNode = nodeFromCache;
							else {
								// we invoke the ServiceNow web service
								this.logger.trace("      Following reference link to URL "+linkURL);
								try {
								    MyConnection connection = new MyConnection(this.proxyHost, this.proxyPort, this.proxyUser, this.proxyPassword);
    								connection.setLogger(this.logger);
    								String linkContent = connection.get(subFields[column], linkURL, this.serviceNowUser, this.serviceNowPassword);
    								JsonFactory jsonFactory = new MappingJsonFactory();
    								try ( JsonParser jsonParser = jsonFactory.createJsonParser(linkContent) ) {
    									jsonNode = jsonParser.readValueAsTree().get("result");
    									this.referenceLinkCache.put(linkURL, jsonNode);
    								} catch (JsonParseException err) {
    									this.logger.error("Failed to parse JSON got from ServiceNow.", err);
    									jsonNode = null;
    									//TODO: ++error_count;
    									break;
    								}
								} catch (MyException | IOException err2) {
                                    this.logger.error("Failed to get URL from ServiceNow.", err2);
                                    jsonNode = null;
                                    //TODO: ++error_count;
                                    break;
								}
							}
						}
					}
				}
			}
		}
        if ( jsonNode != null ) {
            JsonNode result = jsonNode.get(subFields[subFields.length-1]);
            if ( result != null ) {
            	if ( result.isContainerNode() ) {
            		JsonNode result2 = result.get("value");
            		if ( result2 != null )
            			return result2.asText();
            	} else
            		return result.asText();
            }
        }

        return defaultValue;
	}

	HashSet<String> getPathFields(String field) {
		HashSet<String> result = new HashSet<String>();

		if ( MyUtils.isSet(field) ) {
			// we remove the first char if it is a slash
			String path;
			if ( field.startsWith("/") )
				path = field.substring(1);
			else
				path = field;

			// if the fieldName contains '/' (like a path) then we iterate on each subfolder
			//      values starting by double quotes are constants
			//      values starting by a dollar sign are variables
			//      only values not surrounded by double quotes are field names
			Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(path);
			while (value.find()) {
				String str = value.group(1);
				if ( value.group(1).substring(0,1).equals("/") ) {
					str = value.group(1).substring(1);
				} 
				if ( str.length() > 0 ) {
					switch ( str.substring(0,1) ) {
						case "\"":
							this.logger.trace("      --> found constant = "+str);
							break;
						case "$":
							this.logger.trace("      --> found variable = "+str);
							break;
						default:
							this.logger.trace("      --> found field = "+str);
							result.add(str);
					}
				}
			}
		}

		return result;
	}

	String getServiceNowField(String field) {
		if ( field == null )
			return null;

		int length = field.length();

		if ( (length >= 2) && field.substring(0,1).equals("\"") && field.substring(length-1,length).equals("\"") ) {
			// if the field is enclosed into double quotes, then it is a constant
			this.logger.trace("      --> found constant = "+field);
			return null;
		}

		if ( (length >= 3) && field.substring(0,2).equals("${") && field.substring(length-1,length).equals("}") ) {
			// if the field is enclosed between ${ and }, then it is a variable
			this.logger.trace("      --> found variable = "+field);
			return null;
		}

		// in all other cases, the field is assumed to be a ServiceNow field
		String subFields[] = field.split("#");
		if ( subFields.length >= 2 ) {
			// if there is a hash tag in the field, then it means that we must follow a reference link
			// Only the 1st field has to be retrieved from ServiceNow 
			this.logger.trace("      --> found field = "+subFields[0]+" (then field "+subFields[1]+" in the reference link)");
			return subFields[0];
		}

		// Else, the whole string is the ServiceNow field
		this.logger.trace("      --> found field = "+field);
		return field;
	}

	static String getFolderPath(IFolder folder) {
		StringBuilder result = new StringBuilder();

		if ( folder != null ) {
			IFolder f = folder;
			while ( f.getType().getValue() == FolderType.USER_VALUE ) {
				if ( result.length() != 0 )
					result.insert(0, "/");
				result.insert(0, folder.getName());

				f = (IFolder)f.eContainer();
			}
		}

		return result.toString();
	}

	static IFolder getFolder(IArchimateModel model, IArchimateConcept concept, String folderPath) {
		IFolder currentFolder= model.getDefaultFolderForObject(concept);

		// we remove the first char if it is a slash
		String path;
		if ( folderPath.startsWith("/") )
			path = folderPath.substring(1);
		else
			path = folderPath;

		Matcher m = Pattern.compile("([^/\"][^/]*|\".+?\")\\s*").matcher(path);
		while (m.find()) {
			Boolean folderFound = false;
			for ( Iterator<IFolder> i = currentFolder.getFolders().iterator() ; i.hasNext() ; ) {
				IFolder f = i.next();
				if ( f.getName().equals(m.group(1)) ) { folderFound = true; currentFolder = f; break; }
			}
			if( !folderFound ) {
				IFolder newFolder = IArchimateFactory.eINSTANCE.createFolder();
				newFolder.setName(m.group(1));
				currentFolder.getFolders().add(newFolder);
				currentFolder = newFolder;
			}
		}

		return currentFolder;
	}

	/**
	 * This method checks the string given in parameter and manages it with following rules:<br><ul>
	 * <li><b>"constant"</b> strings between quotes are constants and taken as is</li>
	 * <li><b>${variable}</b> strings starting with a dollar sign and around brackets are replaced by the corresponding variable</li>
	 * <li><b>SNowField</b> all other strings will be replaced by the corresponding ServiceNow field
	 * </ul>
	 * @param jsonNode
	 * @param string
	 * @param eObject
	 * @return
	 * @throws IOException 
	 * @throws Exception 
	 */
	String expand(JsonNode jsonNode, String inputString, EObject eObject) throws MyException, IOException {
		if ( inputString == null )
			return null;

		int length = inputString.length();

		if ( (length >= 2) && inputString.substring(0,1).equals("\"") && inputString.substring(length-1,length).equals("\"") ) {
			// if the inputString is enclosed into double quotes, then it is a constant
			return inputString.substring(1, inputString.length()-1);
		}

		if ( (length >= 3) && inputString.substring(0,2).equals("${") && inputString.substring(length-1,length).equals("}") ) {
			// if the inputString is enclosed between ${ and }, then it is a variable
			try {
				return MyVariable.expand(this.logger, inputString, eObject);
			} catch (MyException e) {
				this.logger.error(e.getMessage());
				return "";
			}
		}

		// if the jsonNode is null, then we are not (yet) connected to ServiceNow and the expand method is used to expand variables
		if ( jsonNode == null )
			return inputString;

		// in all other cases, the field is assumed to be a ServiceNow field
		return getJsonField(jsonNode,  inputString);
	}

	/**
	 * This method checks the path given in parameter and replace each folder name with the following rules:<br><ul>
	 * <li><b>"constant"</b> strings between quotes are constants and taken as is</li>
	 * <li><b>${variable}</b> strings starting with a dollar sign and around brackets are replaced by the corresponding variable</li>
	 * <li><b>SNowField</b> all other strings will be replaced by the corresponding ServiceNow field
	 * </ul>
	 * @param jsonNode
	 * @param string
	 * param eObject
	 * @return
	 * @throws MyException 
	 * @throws IOException 
	 */
	String expandPath(JsonNode jsonNode, String pathname, EObject eObject) throws MyException, IOException {
		if ( pathname == null )
			return null;

		StringBuilder resultBuilder = new StringBuilder();
		String sep="";

		// we remove the first char if it is a slash
		String path;
		if ( pathname.startsWith("/") )
			path = pathname.substring(1);
		else
			path = pathname;

		// if the inputString has got slashes in it, then we loop on each part between slashes
		Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(path);
		while (value.find()) {
			String folderName = value.group(1);
			// if the substring begins with a slash, we copy this slash in the result string
			if ( value.group(1).substring(0,1).equals("/") ) {
				resultBuilder.append("/");
				sep = "";
				folderName = value.group(1).substring(1);
			}
			// if the folder name is not empty, the we expand its name
			if ( folderName.length() > 0 ) {
				resultBuilder.append(sep);
				resultBuilder.append(expand(jsonNode, folderName, eObject));
				sep = "/";
			}
		}

		return resultBuilder.toString();
	}
}