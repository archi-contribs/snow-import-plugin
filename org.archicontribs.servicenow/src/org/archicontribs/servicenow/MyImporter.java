/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */

package org.archicontribs.servicenow;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.apache.log4j.PropertyConfigurator;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;

import com.archimatetool.editor.model.ISelectedModelImporter;
import com.archimatetool.model.FolderType;
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
 * version 1.3: 13/09/2018
 *      rewrite progress bar to be more readable on 4K displays
 *      rewrite popups to be more readable on 4K displays
 *      slashes can now be escaped using backslashes
 *      TODO: add option to add backslash before slashes in fields got from ServiceNow for folder. 
 * 
 * 
 * TODO: change the progressBar to application modal
 * TODO: retrieve the applications and business services
 * TODO: rework all the error messages as it's not clear what to do in case they happen
 * TODO: use commands to allow rollback
 */

public class MyImporter implements ISelectedModelImporter {
	private String SNowPluginVersion = "1.3";

	private Logger logger;
	private String title = "ServiceNow import plugin v" + this.SNowPluginVersion;
	MySortedProperties iniProperties;

	private int created = 0;
	private int updated = 0;
	private int totalCreated = 0;
	private int totalUpdated = 0;

	private final int OPERATIONAL = 1;
	private final int NON_OPERATIONAL = 2;
	
	@Override
	public void doImport(IArchimateModel model) throws IOException {
		// ServiceNow site and credentials
		String serviceNowSite = null;
		String serviceNowUser = null;
		String serviceNowPassword = null;


		// proxy information
		String proxyHost = null;
		int proxyPort = 0;
		String proxyUser = null;
		String proxyPassword = null;

		// ServiceNow sysparm_limit (allow to increase or reduce the number of components sent by ServiceNow)
		int serviceNowSysparmLimit = 0;

		// general elements properties
		String generalArchiElementsId = null;
		String generalArchiElementsName = null;
		String generalArchiElementsDocumentation = null;
		String generalArchiElementsFolder = null;
		String generalArchiElementsExcludeRefLink = null;
		String generalArchiElementsImportMode = null;

		// we ask for the name of the ini file that contains the configuration of the plugin
		String iniFilename;
		if ( (iniFilename = askForIniFile()) == null )
			return;

		// we initialize the logger
		this.logger = Logger.getLogger("SNowPlugin");
		
		// we read properties from INI file
		this.iniProperties = new MySortedProperties(this.logger);
		try ( FileInputStream iniFileStream = new FileInputStream(iniFilename) ) {
			this.iniProperties.load(iniFileStream);
		} catch ( FileNotFoundException e) {
			popup(Level.FATAL, "Cannot open ini file.", e);
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
		if ( isSet(iniVersion) ) {
			if ( !iniVersion.equals(this.SNowPluginVersion) ) {
				popup(Level.FATAL, "The \"SNowPlugin.version\" property ("+iniVersion+") differs from the plugin version.\n\nThe actual plugin version is '" + this.SNowPluginVersion + "'.");
				return;
			}
		} else {
			popup(Level.FATAL, "The \"SNowPlugin.version\" property is mandatory. You must add it in your INI file.\n\nThe actual plugin version is '" + this.SNowPluginVersion +"'.");
			return;
		}

		// we get the proxy information from the ini file
		proxyHost = this.iniProperties.getString("http.proxyHost");
		proxyPort = this.iniProperties.getInt("http.proxyPort", 0);
		proxyUser = this.iniProperties.getString("http.proxyUser");
		proxyPassword = this.iniProperties.getString("http.proxyPassword", null, true);

		// we get the site and credential to access ServiceNow
		serviceNowSite = this.iniProperties.getString("servicenow.site");
		if ( !isSet(serviceNowSite) ) {
			popup(Level.FATAL, "The \"servicenow.site\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		serviceNowUser = this.iniProperties.getString("servicenow.user");
		if ( !isSet(serviceNowUser) ) {
			popup(Level.FATAL, "The \"servicenow.user\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		serviceNowPassword = this.iniProperties.getString("servicenow.pass", null, true);
		if ( !isSet(serviceNowPassword) ) {
			popup(Level.FATAL, "The \"servicenow.pass\" property is not found in the ini file, but it is mandatory.\n\nPlease ensure you set this property in the ini file.");
			return;
		}

		// we get the sysparm_limit
		serviceNowSysparmLimit = this.iniProperties.getInt("servicenow.sysparm_limit", 0);


		// ***************************
		// ***                     ***
		// *** Retrieving elements ***
		// ***                     ***  
		// ***************************
		this.logger.info("Getting elements ...");

		// we get general properties, i.e.:
		//      properties archi.elements.*.id
		//      properties archi.elements.*.name
		//      properties archi.elements.*.documentation
		//      properties archi.elements.*.folder

		generalArchiElementsId = this.iniProperties.getString("archi.elements.*.id", "sys_id");
		generalArchiElementsName = this.iniProperties.getString("archi.elements.*.name", "sys_class_name");
		generalArchiElementsDocumentation = this.iniProperties.getString("archi.elements.*.documentation", "short_description");
		generalArchiElementsFolder = this.iniProperties.getString("archi.elements.*.folder", "sys_class_name");
		generalArchiElementsExcludeRefLink = this.iniProperties.getString("archi.elements.*.exclude_ref_link", "true");
		generalArchiElementsImportMode = this.iniProperties.getString("archi.elements.*.import_mode", "full");
		if ( !generalArchiElementsImportMode.equals("full") && !generalArchiElementsImportMode.equals("create_or_update_only") && !generalArchiElementsImportMode.equals("create_only") && !generalArchiElementsImportMode.equals("update_only") && !generalArchiElementsImportMode.equals("remove_only") ) {
			popup(Level.FATAL, "Unrecognized value for property \"archi.elements.*.import_mode\".\n\nValid values are full, create_or_update_only, create_only, update_only and remove_only.");
			return;
		}

		//      properties archi.elements.*.property.xxxx
		MySortedProperties generalProperties = new MySortedProperties(this.logger);
		for (String propertyKey: this.iniProperties.stringPropertyNames()) {
			if ( propertyKey.startsWith("archi.elements.*.property.") ) {
				String[] subkey = propertyKey.split("\\.");
				if ( subkey.length == 5 ) {
					String propertyValue = this.iniProperties.getString(propertyKey);
					generalProperties.put(subkey[4], propertyValue);
				}
			}
		}
	
		try (MyProgressBar progressBar = new MyProgressBar(this.title, "Connecting to ServiceNow webservice ...") ) {
			// We get each table described in properties like archi.elements.<table>.archi_class
			for (String iniKey: this.iniProperties.stringPropertyNames()) {
				String[] iniSubKeys = iniKey.split("\\.");
				if ( iniSubKeys.length == 4 && iniSubKeys[0].equals("archi") && iniSubKeys[1].equals("elements") && /* whatever subKeyx[2] */ iniSubKeys[3].equals("archi_class") ) {
					String archiClass = this.iniProperties.getString(iniKey);
	
					String fieldsToRetreiveFromServiceNow;
	
					//
					// the table name is in third position of the property (so second position in the string array)
					//
					String tableName = iniSubKeys[2];
	
					this.logger.info("Mapping ServiceNow CIs from table " + tableName + " to Archi " + archiClass);
	
					//
					// we construct the ServiceNow URL
					//
					StringBuilder urlBuilder = new StringBuilder(serviceNowSite);
					urlBuilder.append("/api/now/table/");
					urlBuilder.append(tableName);
	
					// sysparm_exclude_reference_link
					urlBuilder.append("?sysparm_exclude_reference_link=");
					urlBuilder.append(this.iniProperties.getString("archi.elements."+tableName+".exclude_ref_link", generalArchiElementsExcludeRefLink));
	
					// serviceNowSysparmLimit : number of elements that ServiceNow should send
					urlBuilder.append("&sysparm_limit=");
					urlBuilder.append(serviceNowSysparmLimit);
	
					// We collect all fields that ServiceNow should send us
					MySortedProperties propertiesToGetFromServiceNow = new MySortedProperties(null);
					urlBuilder.append("&sysparm_fields=operational_status");
	
					String archiElementsId = this.iniProperties.getString("archi.elements."+tableName+".id", generalArchiElementsId);
					fieldsToRetreiveFromServiceNow = getFields(archiElementsId);
					if ( isSet(fieldsToRetreiveFromServiceNow) ) {
						urlBuilder.append(",");
						urlBuilder.append(fieldsToRetreiveFromServiceNow);
					}
	
					String archiElementsName = this.iniProperties.getString("archi.elements."+tableName+".name", generalArchiElementsName);
					fieldsToRetreiveFromServiceNow = getFields(archiElementsName);
					if ( isSet(fieldsToRetreiveFromServiceNow) ) {
						urlBuilder.append(",");
						urlBuilder.append(fieldsToRetreiveFromServiceNow);
					}
	
					String archiEmentsDocumentation = this.iniProperties.getString("archi.elements."+tableName+".documentation", generalArchiElementsDocumentation);
					fieldsToRetreiveFromServiceNow = getFields(archiEmentsDocumentation);
					if ( isSet(fieldsToRetreiveFromServiceNow) ) {
						urlBuilder.append(",");
						urlBuilder.append(fieldsToRetreiveFromServiceNow);
					}
	
					String archiElementsFolder = this.iniProperties.getString("archi.elements."+tableName+".folder", generalArchiElementsFolder);
					fieldsToRetreiveFromServiceNow = getFields(archiElementsFolder);
					if ( isSet(fieldsToRetreiveFromServiceNow) ) {
						urlBuilder.append(",");
						urlBuilder.append(fieldsToRetreiveFromServiceNow);
					}
	
					String archiElementsImportMode = this.iniProperties.getString("archi.elements."+tableName+".importMode", generalArchiElementsImportMode);
					if ( !archiElementsImportMode.equals("full") && !archiElementsImportMode.equals("create_or_update_only") && !archiElementsImportMode.equals("create_only") && !archiElementsImportMode.equals("update_only") && !generalArchiElementsImportMode.equals("remove_only") ) {
						popup(Level.FATAL, "Unrecognized value for property \"archi.elements."+tableName+".import_mode\".\n\nValid values are full, create_or_update_only, create_only, update_only and remove_only.");
						return;
					}
	
					// we get all the properties specified by a archi.elements.<table>.property.xxx
					for (String propertyKey: this.iniProperties.stringPropertyNames()) {
						if ( propertyKey.startsWith("archi.elements."+tableName+".property.") ) {
							String[] subkeys = propertyKey.split("\\.");
							if ( subkeys.length == 5 ) {
								String propertyValue = this.iniProperties.getString(propertyKey);
			                    this.logger.debug("   Found " + propertyKey + " = " + propertyValue);
								propertiesToGetFromServiceNow.put(subkeys[4], propertyValue);
								fieldsToRetreiveFromServiceNow = getFields(propertyValue);
								if ( isSet(fieldsToRetreiveFromServiceNow) ) {
									urlBuilder.append(",");
									urlBuilder.append(fieldsToRetreiveFromServiceNow);
								}
							}
						}
					}
	
					// we add all the general properties from the archi.elements.property.xxx
					@SuppressWarnings("unchecked")
					Enumeration<String> e = (Enumeration<String>) generalProperties.propertyNames();
					while (e.hasMoreElements()) {
						String propertyKey = e.nextElement();
						String propertyValue = generalProperties.getString(propertyKey);
						// we check if the property is not yet part of the properties list
						if ( !propertiesToGetFromServiceNow.containsKey(propertyKey) ) {
							this.logger.debug("   Found archi.elements.*.property." + propertyKey + " = " + propertyValue);
							propertiesToGetFromServiceNow.put(propertyKey, propertyValue);
							fieldsToRetreiveFromServiceNow = getFields(propertyValue);
							if ( isSet(fieldsToRetreiveFromServiceNow) ) {
								urlBuilder.append(",");
								urlBuilder.append(fieldsToRetreiveFromServiceNow);
							}
						}
					}
					
					// We apply a filter depending of the requested import mode
					//     operational_status = 1        if create or update only
					//     operational_status = 2        if remove_only
					//     and no filter                 if create, update and remove
					if ( generalArchiElementsImportMode.equals("create_or_update_only") || generalArchiElementsImportMode.equals("create_only") || generalArchiElementsImportMode.equals("update_only") )
					    urlBuilder.append("&sysparm_query=operational_status="+this.OPERATIONAL);
					else if ( generalArchiElementsImportMode.equals("remove_only") )
					    urlBuilder.append("&sysparm_query=operational_status="+this.NON_OPERATIONAL);
	
					this.logger.debug("   Generated URL is " + urlBuilder.toString());

					int count = 0;
					// we invoke the ServiceNow web service
					String jsonString = getFromUrl(progressBar, iniSubKeys[2], urlBuilder.toString(), serviceNowUser, serviceNowPassword, proxyHost, proxyPort, proxyUser, proxyPassword);

					//progressBar.setLabel("Parsing "+iniSubKeys[2]+" table ...");

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
					progressBar.setLabel("Parsing "+iniSubKeys[2]+" table ("+count+" elements) ...");
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
							String requestedId = expand(archiElementsId, jsonNode, null);
							if ( requestedId == null )
								throw new MyException("Cannot retrieve element's ID (check \"properties archi.elements.*.id\" and \"archi.elements."+tableName+".id\")");
							this.logger.debug("   Got new CI with ID "+requestedId);
							
							String requestedArchiClass = expand(archiClass, jsonNode, null);
							if ( requestedArchiClass == null )
								throw new MyException("Cannot retrieve element's class in Archi (check properties \"archi.elements.*.archi_class\" and \"archi.elements."+tableName+".archi_class\")");
							this.logger.debug("   Mapping to Archi class "+requestedArchiClass);
							
							int operationalStatus;
							if ( getJsonField(jsonNode, "operational_status") == null )
								throw new MyException("Cannot retrieve element's operational status (field operational_status in ServiceNow)");
							operationalStatus = Integer.valueOf(getJsonField(jsonNode, "operational_status"));
							
							IArchimateElement element;
							try {
								element = createOrRemoveArchimateElement(model, requestedArchiClass, archiElementsImportMode, operationalStatus, requestedId);
			    			} catch (Exception err) {
			    				popup(Level.FATAL, "Canno't create element of class "+requestedArchiClass, err);
			    				return;
			    			}

						    // if the element is not null, this means that we must update its properties
					        if ( element != null ) {
					            // if the element is not in the correct folder, we move it
                                IFolder currentFolder = (IFolder)element.eContainer();
					            String currentFolderPath = getFolderPath(currentFolder);
					            String requestedFolderPath = expandPath(archiElementsFolder, jsonNode, element);
					            if ( requestedFolderPath == null )
					            	throw new MyException("Cannot retrieve element's folder (check properties \"archi.elements.*.folder\" and \"archi.elements."+tableName+".folder\")");
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
				                
				                String requestedName = expand(archiElementsName, jsonNode, element);
					            if ( requestedName == null )
					            	throw new MyException("Cannot retrieve element's name (check properties \"archi.elements.*.name\" and \"archi.elements."+tableName+".name\")");
				                if ( !element.getName().equals(requestedName) ) {
				                    this.logger.trace("      Setting name to " + requestedName);
				                    element.setName(requestedName);
				                }
					    
				                String requestedDocumentation = expand(archiEmentsDocumentation, jsonNode, element);
					            if ( requestedDocumentation == null )
					            	throw new MyException("Cannot retrieve element's documentation (check properties \"archi.elements.*.documentation\" and \"archi.elements."+tableName+".decumentation\")");
				                if ( !element.getDocumentation().equals(requestedDocumentation) ) {
				                    this.logger.trace("      Setting documentation to " + requestedDocumentation);
				                    element.setDocumentation(requestedDocumentation);
				                }

								for (String propertyName: propertiesToGetFromServiceNow.stringPropertyNames()) {
								    String propertyValue = expand(propertiesToGetFromServiceNow.getProperty(propertyName), jsonNode, element);
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
						this.logger.info(Integer.toString(this.created+this.updated) + " elements have be imported from ServiceNow ("+this.created + " created + " + this.updated + " updated).");
						this.totalCreated += this.created;
						this.totalUpdated += this.updated;
						this.created = this.updated = 0;
					}
				}
			}
		} catch (Exception err) {
				popup(Level.FATAL,"Cannot get CIs from ServiceNow web service: " + err);
				return;
		}
		this.logger.info(Integer.toString(this.totalCreated+this.totalUpdated) + " elements imported in total from ServiceNow ("+Integer.toString(this.totalCreated) + " created + " + Integer.toString(this.totalUpdated) + " updated).");


		// ****************************
		// ***                      ***
		// *** Retrieving relations ***
		// ***                      ***  
		// ****************************

		StringBuilder urlBuilder = new StringBuilder(serviceNowSite);
		urlBuilder.append("/api/now/table/cmdb_rel_ci");

		// sysparm_exclude_reference_link
		urlBuilder.append("?sysparm_exclude_reference_link=true");	

		// We set the maximum number of elements that ServiceNow should send
		if ( isSet(this.iniProperties.getString("servicenow.sysparm_limit")) )
			urlBuilder.append("&sysparm_limit="+this.iniProperties.getString("servicenow.sysparm_limit"));

		// We collect all fields that ServiceNow should send us
		String sysparmFields = "&sysparm_fields=";
		MySortedProperties props = new MySortedProperties(this.logger);
		if ( isSet(this.iniProperties.getString("archi.relations.id")) ) 		 props.put("id", this.iniProperties.getString("archi.relations.id"));
		if ( isSet(this.iniProperties.getString("archi.relations.type")) ) 	 	 props.put("type", this.iniProperties.getString("archi.relations.type"));
		if ( isSet(this.iniProperties.getString("archi.relations.source")) ) 	 props.put("source", this.iniProperties.getString("archi.relations.source"));
		if ( isSet(this.iniProperties.getString("archi.relations.target")) ) 	 props.put("target", this.iniProperties.getString("archi.relations.target"));
		// TODO: allow to specify folder

		String sep = "";
		for ( String f: props.stringPropertyNames() ) {
			this.logger.trace("      Required property = "+f);
			String p = props.getString(f);
			if ( p != null ) {
				// if the fieldName contains '/' (like a path) then we iterate on each subfolder
				//		only values not surrounded by double quotes are field names
				Matcher value = Pattern.compile("([^\"](?<!\\\\)[^/]*|\".+?\")\\s*").matcher(p);
				while (value.find()) {
					String str = value.group(1);
					if ( value.group(1).substring(0,1).equals("/") ) {
						str = value.group(1).substring(1);
					} 
					if ( str.length() > 0 ) {
						if ( ! str.substring(0,1).equals("\"") ) {
							sysparmFields += sep+str;
							sep = ",";
							this.logger.trace("      --> field = "+str);
						} else {
							this.logger.trace("      --> constant = \""+str+"\"");
						}
					}
				}
			}
		}
		urlBuilder.append(sysparmFields);

		// we retrieve only the relations of managed types
		urlBuilder.append("&sysparm_query=");
		sep = "";
		for (String key: this.iniProperties.stringPropertyNames()) {
			String[] subkeys = key.split("\\.");
			if ( subkeys.length == 4 && subkeys[0].equals("archi") && subkeys[1].equals("relations") && subkeys[3].equals("mapping") ) {
				urlBuilder.append(sep + "type=" + subkeys[2]);
				sep = "%5EOR";
			}
		}

		this.logger.debug("   Generated URL is " + urlBuilder.toString());

		try ( MyProgressBar progressBar = new MyProgressBar(this.title, "Connecting to ServiceNow webservice ...") ) {
			// import relations
			String jsonString = getFromUrl(progressBar, "relations", urlBuilder.toString(), serviceNowUser, serviceNowPassword, proxyHost, proxyPort, proxyUser, proxyPassword);

			//progressBar.setLabel("Parsing data ...");
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

			progressBar.setLabel("Parsing "+count+" relations from ServiceNow webservice ...");
			this.created = this.totalCreated = 0;
			this.updated = this.totalUpdated = 0;
			progressBar.setMaximum(count); 

			// now we create relations
			try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
				jsonParser.nextToken();	// START_OBJECT
				jsonParser.nextToken();	// "result"
				jsonParser.nextToken();	// START_ARRAY
				while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
					progressBar.increase();
					JsonNode jsonNode = jsonParser.readValueAsTree();
					String typeId = getJsonField(jsonNode, this.iniProperties.getString("archi.relations.type"));
					if ( ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getString("archi.relations.id"))) == null ) {
						String relationType = this.iniProperties.getString("archi.relations."+typeId+".mapping");
						if ( isSet(relationType) ) {
							IArchimateElement source = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getString("archi.relations.source")));
							if ( source == null )
								this.logger.trace("      Unknown element (ID = "+getJsonField(jsonNode, "child")+") ... ignoring relation.");
							else {
								IArchimateElement target = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getString("archi.relations.target")));
								if ( target == null )
									this.logger.trace("      Unknown element (ID = "+getJsonField(jsonNode, "parent")+") ... ignoring relation.");
								else {
									if(!ArchimateModelUtils.isValidRelationship(source.eClass(), target.eClass(), (EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType))) {
										this.logger.error("Invalid relation " + relationType + " between " + source.getName() + " and " + target.getName() + ".");
									} else { 
										IArchimateRelationship relation = (IArchimateRelationship)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType));
										relation.setId(getJsonField(jsonNode, this.iniProperties.getString("archi.relations.id")));
										relation.setSource(source);
										relation.setTarget(target);
										String name = getJsonField(jsonNode, this.iniProperties.getString("archi.relations."+typeId+".name"));
										if ( isSet(name) ) relation.setName(name);
										model.getDefaultFolderForObject(relation).getElements().add(relation);
										this.logger.trace("      Creating "+relationType+" relation from "+source.getName()+" to "+target.getName()+" and named " + name + " (id = " + getJsonField(jsonNode, this.iniProperties.getString("archi.relations.id")) + ").");
										this.created++;
									}
								}
							}
						}
					} else {
						this.logger.debug("   Relation " + getJsonField(jsonNode, this.iniProperties.getString("archi.relations.id")) + " already exists ...");
					}
					// TODO: verify what is done when relation already exists. Eventually, create method createOrReplaceArchiRelation
				}

				this.logger.info("Imported " + (this.created+this.updated) + " relations from "+count+" relations received from ServiceNow ("+this.created + " created + " + this.updated + " updated).");
			}
		} catch (Exception err) {
			popup(Level.FATAL,"Cannot get relations from ServiceNow web service: " + err);
			return;
		}
		this.logger.info("All done ...");
	}

	private IArchimateElement createOrRemoveArchimateElement(IArchimateModel model, String archiClassName, String importMode, int operationalStatus, String id) {
		boolean mustCreate = false;
		boolean mustUpdate = false;
		boolean mustRemove = false;

		this.logger.trace("      Operational status is " + (operationalStatus == this.OPERATIONAL ? "OPERATIONAL" : "NON OPERATIONAL") );
		
	    IArchimateElement element = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, id);
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
    			this.created++;
    			this.logger.trace("      Creating new " + archiClassName + " with ID = " + id + ")");
    			element = (IArchimateElement)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(archiClassName));
    			element.setId(id);
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
			}
		}
		
        if ( mustUpdate ) {
            if ( element == null ) {
                // should never be here, but just in case ...
               this.logger.error("   We must update this element, but it does not exist !!!");
           } else {
               // if the element has just been created, we do not increase the updated counter
               this.logger.trace("      Updating existing "+element.getClass().getSimpleName()+" "+element.getName());
               this.updated++;
           }
        }
		
		return element;
	}

	private String getFromUrl(MyProgressBar progressBar, String what, String location, String username, String Password, String proxyHost, int proxyPort, String proxyUser, String proxyPassword) throws Exception {
		URL url = new URL(location);
		HttpURLConnection c;

		if ( isSet(proxyHost) ) {
			if ( isSet(proxyUser) ) {
				Authenticator.setDefault( new Authenticator() {
					@Override
					public PasswordAuthentication getPasswordAuthentication() {	return (new PasswordAuthentication(proxyUser, proxyPassword.toCharArray())); }
				});
			}
			c = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
		} else {
			c = (HttpURLConnection) url.openConnection();
		}

		String userpass = username + ":" + Password;		
		c.setRequestProperty("Authorization",  "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes()));
		c.setRequestProperty("Accept", "application/json");
		int status = -1;
		try {
			this.logger.debug("   Connecting to ServiceNow website ...");
			status = c.getResponseCode();
			this.logger.debug("   Connected to ServiceNow website");
			if ( status != 200) {
				throw new MyException("Error reported by ServiceNow website : code " + Integer.toString(status)); 
			}
		} catch (Exception e) {
			throw new MyException("Cannot connect to web site (" + e.getMessage() + ")"); 
		}

		StringBuilder data = new StringBuilder();
		try ( InputStream in = c.getInputStream() ) {
			this.logger.debug("   Getting table " + what + " from ServiceNow webservice ...");
			progressBar.setLabel("Getting " + what + " from ServiceNow webservice ...");

			int nb=0, total=0;
			byte[] buffer = new byte[10240];	// 10 KB
			while ( (nb=in.read(buffer,0,buffer.length)) > 0 ) {
				data.append(new String(buffer,0,nb));
				total+=nb;
				if ( total < 1048576 ) {
				    progressBar.setProgressBarLabel("read "+String.format("%d", total/1024) + " KB");
				} else {
				    progressBar.setProgressBarLabel("read "+String.format("%.2f", (float)total/1048576) + " MB");
				}
			}
			this.logger.debug("   Read " + total + " bytes from ServiceNow webservice.");
			progressBar.setProgressBarLabel("");
		}
		return data.toString();
	}
	
    /**
     * Shows up an on screen popup, displaying the message (and the exception message if any) and wait for the user to click on the "OK" button<br>
     * The exception stacktrace is also printed on the standard error stream
     */
    public void popup(Level level, String msg, Exception e) {
        String popupMessage = msg;
        this.logger.log(level, msg, e);

        Throwable cause = e;
        while ( cause != null ) {
            if ( cause.getMessage() != null ) {
                if ( !popupMessage.endsWith(cause.getMessage()) )
                    popupMessage += "\n\n" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } else 
                popupMessage += "\n\n" + cause.getClass().getSimpleName();
            cause = cause.getCause();
        }

        switch ( level.toInt() ) {
            case Priority.FATAL_INT:
            case Priority.ERROR_INT:
                MessageDialog.openError(Display.getDefault().getActiveShell(), this.title, popupMessage);
                break;
            case Priority.WARN_INT:
                MessageDialog.openWarning(Display.getDefault().getActiveShell(), this.title, popupMessage);
                break;
            default:
                MessageDialog.openInformation(Display.getDefault().getActiveShell(), this.title, popupMessage);
                break;
        }
    }
    
    /**
     * Shows up an on screen popup, displaying the message (and the exception message if any) and wait for the user to click on the "OK" button
     */
    public void popup(Level level, String msg) {
    	popup(level, msg, null);
    }

	private String askForIniFile() {
		FileDialog dialog = new FileDialog(Display.getCurrent().getActiveShell());
		dialog.setText(this.title+" - Please select your INI file ...");
		String[] ext = {"*.ini","*"};
		dialog.setFilterExtensions(ext);
		return dialog.open();
	}

	/**
	 *  Gets a field from a Json record
	 * @param node
	 * @param fieldName
	 * @return
	 */
	private static String getJsonField(JsonNode node, String fieldName) {
		return getJsonField(node, fieldName, null);
	}
	
	/**
	 * Gets a field from a Json record
	 * @param node
	 * @param fieldName
	 * @param defaultValue
	 * @return
	 */
	private static String getJsonField(JsonNode node, String fieldName, String defaultValue) {
		if ( !isSet(fieldName) )
			return defaultValue;

		JsonNode field = node.get(fieldName);
		
		if ( field == null )
			return defaultValue;
		return field.asText();
	}

	String getPathFields(String field) {
		String sep = "";
		String result = "";

		if ( isSet(field) ) {
			// if the fieldName contains '/' (like a path) then we iterate on each subfolder
		    //      values starting by double quotes are constants
		    //      values starting by a dollar sign are variables
			//      only values not surrounded by double quotes are field names
			Matcher value = Pattern.compile("([^\"](?<!\\\\)[^/]*|\".+?\")\\s*").matcher(field);
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
	                        result += sep + str;
	                        sep = ",";
				    }
				}
			}
		}

		return result;
	}
	
	String getFields(String field) {
	    switch ( field.substring(0,1) ) {
	        case "\"":
	            this.logger.trace("      --> found constant = "+field);
	            break;
	        case "$":
	            this.logger.trace("      --> found variable = "+field);
	            break;
	        default:
	            this.logger.trace("      --> found field = "+field);
	            return field;
	    }
	    return "";
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
	
	static IFolder getFolder(IArchimateModel model, IArchimateElement element, String folderPath) {
	    IFolder currentFolder= model.getDefaultFolderForObject(element);
	    
	    Matcher m = Pattern.compile("([^/\"](?<!\\\\)[^/]*|\".+?\")\\s*").matcher(folderPath);
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
	
	static Boolean isSet(String s) {
		return s!=null && !s.equals("");
	}
    
    static boolean areEquals(Object obj1, Object obj2) {
        if ( (obj1 == null) && (obj2 == null) )
            return true;
        
        if ( (obj1 == null) || (obj2 == null) )
            return false;
        
        return obj1.equals(obj2);
    }
    
    /**
     * This method checks the string given in parameter and manages it with following rules:<br><ul>
     * <li><b>"constant"</b> strings between quotes are constants and taken as is</li>
     * <li><b>${variable}</b> strings starting with a dollar sign and around brackets are replaced by the corresponding variable</li>
     * <li><b>SNowField</b> all other strings will be replaced by the corresponding ServiceNow field
     * </ul>
     * @param string
     * @param jsonNode
     * @return the resulting string (may be empty but never null)
     * @throws MyException 
     */
    String expand(String inputString, JsonNode jsonNode, EObject eObject) throws MyException {
    	StringBuilder resultBuilder = new StringBuilder();
		
    	switch ( inputString.substring(0, 1) ) {
    		case "\"":
    			resultBuilder.append(inputString.substring(1, inputString.length()-1));
    			break;
    		case "$":
    			try {
    				String varContent = MyVariable.expand(this.logger, inputString, eObject);
    				if ( varContent != null )
    					resultBuilder.append(varContent);
    			} catch (MyException e) {
    				this.logger.error(e.getMessage());
    			}
    			break;
    		default:
    			String field = getJsonField(jsonNode, inputString);
    			if ( field != null )
    				resultBuilder.append(field);
    	}
    	return resultBuilder.toString();
    }
    
    /**
     * This method checks the path given in parameter and replace each folder name with the following rules:<br><ul>
     * <li><b>"constant"</b> strings between quotes are constants and taken as is</li>
     * <li><b>${variable}</b> strings starting with a dollar sign and around brackets are replaced by the corresponding variable</li>
     * <li><b>SNowField</b> all other strings will be replaced by the corresponding ServiceNow field
     * </ul>
     * @param string
     * @param jsonNode
     * @return the resulting string (may be empty but never null)
     * @throws MyException 
     */
    String expandPath(String pathname, JsonNode jsonNode, EObject eObject) throws MyException {
    	StringBuilder resultBuilder = new StringBuilder();
    	String sep="";
    	
    	// if the inputString has got slashes in it, then we loop on each part between slashes
    	Matcher value = Pattern.compile("([^\"](?<!\\\\)[^/]*|\".+?\")\\s*").matcher(pathname);
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
				resultBuilder.append(expand(folderName, jsonNode, eObject));
				sep = "/";
			}
		}
		
    	return resultBuilder.toString();
    }
}
