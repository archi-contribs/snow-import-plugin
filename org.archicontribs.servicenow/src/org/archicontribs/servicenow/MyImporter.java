/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */

package org.archicontribs.servicenow;

//TODO: migrate to log4j
//TODO: change the progressbar to application modal


import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.DateFormat;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;

import com.archimatetool.editor.model.ISelectedModelImporter;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.IRelationship;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Import from ServiceNow
 * 
 * @author Hervé JOUIN
 */
public class MyImporter implements ISelectedModelImporter {
	private Logger logger;
	private String title = "ServiceNow plugin";
	private FileHandler logFileHandler = null;
	private Properties props = null;
	private String serviceNowCmdbCiWebService = null;
	private String serviceNowCmdbRelCiWebService = null;

	private int countCreated = 0;
	private int countUpdated = 0;

	private final static int ERROR=0;
	private final static int WARNING=1;
	private final static int INFO=2;
	private final static int FINE=3;
	private final static int FINER=4;
	private final static int FINEST=5;

	private JProgressBar progressBar = null;
	private JLabel progressBarLabel = null;
	private JDialog progressBarDialog = null;

	public void doImport(IArchimateModel model) throws IOException {
		String ini_file;
		String jsonString;
		TreeSet<String> archiProperties; 

		if ( (ini_file = askForIniFile()) == null ) return;

		// read properties from INI file
		try {
			if ( (archiProperties = readPropertiesFromIniFile(ini_file)) == null ) return ;
		} catch (Exception e) {
			message(ERROR, "Failed to get properties from ini file "+ini_file+".",e.getMessage());
			return;
		}
		
		 	
		try {
			createProgressBar("Connecting to ServiceNow webservice ...");
			try {
					// we invoke the ServiceNow web service 
				jsonString = getFromUrl(serviceNowCmdbCiWebService, getProperty("servicenow.user", props), getProperty("servicenow.pass", props));
			} catch (Exception e) {
				throw new MyException("Cannot get CIs from ServiceNow web service (" + e.getMessage() + ")");
			}
	
			setProgressBar("Parsing data ...");
	
			JsonFactory jsonFactory = new MappingJsonFactory();
			JsonParser jsonParser;
			try {
				jsonParser = jsonFactory.createJsonParser(jsonString);
			} catch (Exception e) {
				throw new MyException("Cannot parse result from ServiceNow web service (" + e.getMessage() + ")");
			}
	
			// we first count the number of elements received
			int count = 0;
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
							case START_OBJECT : if ( sub++ == 0 ) count++; ; break;
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
						throw new MyException("Error while retrieving data from the ServiceNow webservice (" + node.get("message").asText() + ")");
					} else {
						logger.severe("Here is what we recieved from the server :");
						logger.severe(jsonString);
						throw new MyException("Error while retrieving data from the ServiceNow webservice\n\nThe data receied is in an unknow format.");
					}
				}
			}
	
			setProgressBar("Parsing "+count+" CI from ServiceNow webservice ...");
			countCreated = 0;
			countUpdated = 0;
			setProgressBar(count); 
	
			// now we create elements
			try {
				jsonParser = jsonFactory.createJsonParser(jsonString);
			} catch (Exception e) {
				throw new MyException("Cannot parse result from ServiceNow web service (" + e.getMessage() + ")");
			}
	
			jsonParser.nextToken();	// START_OBJECT
			jsonParser.nextToken();	// "results"
			jsonParser.nextToken();	// START_ARRAY
			while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
				updateProgressBar();
				JsonNode jsonNode = jsonParser.readValueAsTree();
				String servicenowClassName, archiElementId, archiElementName, archiElementType, archiElementFolder;
	
				// servicenowClassName is used to map the ServiceNow CI to an Archi elment.
				if ( !isSet(servicenowClassName = getJsonField(jsonNode, getProperty("archi.elements.mapping", "sys_class_name", props))) ) {
					throw new MyException("Cannot import element as we cannot retrieve its class (unknown field "+servicenowClassName+")\n\nPlease verify the 'archi.elements.mapping' property in your INI file.");
				}			
				// archiElementId is the element ID is Archi. For convenience, it is the same as the ServiceNow ID.
				if ( (archiElementId = getJsonField(jsonNode, getProperty("archi.elements.id ", "sys_id", props))) == null ) {
					throw new MyException("Cannot import element as we cannot retrieve its ID.\n\nPlease verify the 'archi.elements.id' property in your INI file.");
				}			
				//  archiElementType is the type of element to create in Archi (Node, Device, SystemSoftware, ...)
				if ( !isSet((archiElementType = getJsonField(jsonNode, getProperty("archi.elements.mapping", props, servicenowClassName, null)))) ) {
					throw new MyException("Cannot import element as we cannot map it to an Archi element.\n\nPlease verify the 'archi.elements.mapping." + servicenowClassName + "' property in your INI file.");
				}
	
				// archiElementName is the name of the element to create in Archi.
				if ( !isSet((archiElementName = getJsonField(jsonNode, getProperty("archi.elements.name", "name", props)))) ) {
					throw new MyException("Cannot import element as we cannot retrieve its name (unknown field '" + getProperty("archi.elements.name", "name", props) + "').\n\nPlease update your INI file to indicate the correct field.");
				}
	
				// archiElementFolder is the name of the folder in which store the element in Archi
				archiElementFolder = getJsonField(jsonNode, getProperty("archi.elements.folder", props, servicenowClassName, archiElementType));
	
				IArchimateElement element = createOrReplacedArchimateElement(model, jsonNode, archiElementType, archiElementName, archiElementId, archiElementFolder);
	
				if ( element != null ) {
					// we retrieve the list of properties to set for the element type ...
					nextProperty:
						for (String propertyName: archiProperties) {
							String jsonField = getProperty(propertyName, null, "archi.elements.property", props, servicenowClassName, archiElementType);
							if ( isSet(jsonField) ) {
								String propertyValue = getJsonField(jsonNode, jsonField);
								// if the property already exists, we update it
								for (Iterator<IProperty> i = element.getProperties().iterator(); i.hasNext(); ) {
									IProperty elementProperty = i.next();
									if ( elementProperty.getKey().equals(propertyName) ) {
										if ( propertyValue.equals(elementProperty.getValue()) )
											message(FINER,"   existing property " + propertyName + " is not updated as value \"" + propertyValue + "\" is unchanged.");
										else 
											if ( isSet(propertyValue) ) {
												message(FINER,"   existing property " + propertyName + " is updated from value \"" + elementProperty.getValue() + "\" to value \"" + propertyValue + "\"");
												elementProperty.setValue(propertyValue);
											} else
												message(FINER,"   existing property " + propertyName + " is not updated from value \"" + elementProperty.getValue() + "\" to empty value.");
										break nextProperty;
									}
								}
								// if we're here, the property does't exists. Therefore, we create it.
								IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
								prop.setKey(propertyName);
								prop.setValue(propertyValue);
								message(FINER,"   new property " + propertyName + " is created with value = \"" + propertyValue + "\"");
								element.getProperties().add(prop);
							}
						}
				}
			}

			message(FINE, "Done.", "Imported " + (countCreated+countUpdated) + " elements from "+count+" CI recieved from ServiceNow ("+countCreated + " created + " + countUpdated + " updated).");
	
			dismissProgressBar();
			createProgressBar("Connecting to ServiceNow webservice ...");
			
			try {
					// import relations
				jsonString = getFromUrl(serviceNowCmdbRelCiWebService, getProperty("servicenow.user", props), getProperty("servicenow.pass", props));
			} catch (Exception e) {
				throw new MyException("Cannot get relations from ServiceNow web service (" + e.getMessage() + ")");
			}
	
			setProgressBar("Parsing data ...");
	
			jsonFactory = new MappingJsonFactory();
			try {
				jsonParser = jsonFactory.createJsonParser(jsonString);
			} catch (Exception e) {
				throw new MyException("Cannot parse result from ServiceNow web service (" +e.getMessage() + ")");
			}
	
			// we first count the number of elements received
			count = 0;
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
							case START_OBJECT : if ( sub++ == 0 ) count++; ; break;
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
					} else {
						logger.severe("Here is what we recieved from the server :");
						logger.severe(jsonString);
						throw new MyException("Error while retrieving data from the ServiceNow webservice.\n\nThe data receied is in an unknow format.");
					}
				}
			}
	
			setProgressBar("Parsing "+count+" relations from ServiceNow webservice ...");
			countCreated = 0;
			countUpdated = 0;
			setProgressBar(count); 
	
			// now we create relations
			try {
				jsonParser = jsonFactory.createJsonParser(jsonString);
			} catch (Exception e) {
				throw new MyException("Cannot parse result from ServiceNow web service (" + e.getMessage() + ")");
			}
	
			jsonParser.nextToken();	// START_OBJECT
			jsonParser.nextToken();	// "result"
			jsonParser.nextToken();	// START_ARRAY
			while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
				updateProgressBar();
				JsonNode jsonNode = jsonParser.readValueAsTree();
				String typeId = getJsonField(jsonNode, getProperty("archi.relations.type", "type", props));
				if ( ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, getProperty("archi.relations.id", "sys_id", props))) == null ) {
					String relationType = getProperty("archi.relations.mapping."+typeId, props);
					if ( isSet(relationType) ) {
						IArchimateElement source = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, getProperty("archi.relations.source", "child", props)));
						if ( source == null )
							message(FINE, "unknown element (ID = "+getJsonField(jsonNode, "child")+") ... ignoring relation.");
						else {
							IArchimateElement target = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, getProperty("archi.relations.dest", "parent", props)));
							if ( target == null )
								message(FINE, "unknown element (ID = "+getJsonField(jsonNode, "parent")+") ... ignoring relation.");
							else {
								if(!ArchimateModelUtils.isValidRelationship(source.eClass(), target.eClass(), (EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType))) {
									message(FINE, "invalid relation " + relationType + " between " + source.getName() + "and" + target.getName() + ".");
					            } else { 
					            	IRelationship relation = (IRelationship)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType));
								   	relation.setId(getJsonField(jsonNode, getProperty("archi.relations.id", "sys_id", props)));
								   	relation.setSource(source);
								   	relation.setTarget(target);
								   	String name = getJsonField(jsonNode, getProperty(typeId, props));
								   	if ( isSet(name) ) relation.setName(name);
					   			   	model.getDefaultFolderForElement(relation).getElements().add(relation);
					            	message(FINE, "importing "+relationType+" relation from "+source.getName()+" to "+target.getName()+" and named " + name + ".");
					            	countCreated++;
					            }
							}
						}
					}
				} else {
					message(FINEST,"unknown mapping for relation "+typeId+" ... relation ignored ...");
				}
			}
	
			message(FINE, "Done.", "Imported " + (countCreated+countUpdated) + " relations from "+count+" relactions recieved from ServiceNow ("+countCreated + " created + " + countUpdated + " updated).");
		} catch (Exception e) {
			message(ERROR, e.getMessage());
		}
		dismissProgressBar();
		logger.removeHandler(logFileHandler);
		logFileHandler.close();		
	}
	protected IArchimateElement createOrReplacedArchimateElement(IArchimateModel model, JsonNode jsonNode, String type, String name, String id, String folderName) {
		IArchimateElement element = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, id);

		if ( element == null ) {
			countCreated++;
			message(FINE,"creating new " + type + " " + name);
			element = (IArchimateElement)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(type));
			element.setId(id);

			// placing the element in the required folder ...
			// in fact, placing the element if the required subfolder of the default folder
			if ( isSet(folderName) ) {
				IFolder currentFolder = model.getDefaultFolderForElement(element); 
				Matcher m = Pattern.compile("([^/\"][^/]*|\".+?\")\\s*").matcher(folderName);
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
				currentFolder.getElements().add(element);
			}
			else
				model.getDefaultFolderForElement(element).getElements().add(element);
		} else {
			countUpdated++;
			message(FINE,"updating element " + name);
		}

		element.setName(name);
		return element;
	}
	protected String getFromUrl(String url, String username, String Password) throws Exception {
		CredentialsProvider credsProvider = new BasicCredentialsProvider();
		credsProvider.setCredentials(new AuthScope((new URL(url)).getHost(),(new URL(url)).getPort()), new UsernamePasswordCredentials(username, Password));
		
		CloseableHttpClient httpclient = HttpClients.custom()
											//.setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()))
											.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
											.setDefaultCredentialsProvider(credsProvider)
											.useSystemProperties()
											.build();
		
		try {
			HttpGet httpget = new HttpGet(url);
			httpget.addHeader("Accept", "application/json");

			CloseableHttpResponse response = httpclient.execute(httpget);
			StringBuilder data =new StringBuilder();
			try {
				setProgressBar("Getting data from ServiceNow webservice ...");
				InputStreamReader reader = new InputStreamReader(response.getEntity().getContent(),"UTF-8");
				BufferedReader br = new BufferedReader(reader);
				int nb=0, total=0;
				char[] buffer = new char[10240];	// 10 KB
				while ( (nb=br.read(buffer,0,buffer.length)) > 0 ) {
					data.append(buffer,0,nb);
					total+=nb;
					if ( total < 1048576 ) {
						setProgressBar("Getting data from ServiceNow webservice ... (read "+String.format("%d", (int)total/1024) + " KB)");
					} else {
						setProgressBar("Getting data from ServiceNow webservice ... (read "+String.format("%.2f", (float)total/1048576) + " MB)");
					}
				}
				message(FINE, "Read " + total + " bytes from ServiceNow webservice.");
				return data.toString();
			} finally {
				response.close();
			}
		} finally {
			httpclient.close();
		}
	}
	protected void inializeLogger(String logFile, String logLevel) throws SecurityException, IOException {
		logFileHandler = new FileHandler(logFile, true);
		logger = Logger.getLogger("service_now");
		logFileHandler.setFormatter(new java.util.logging.Formatter() {
			public String format(LogRecord record) {
				return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.DEFAULT).format(record.getMillis()) + " : " + record.getLevel() + " - " + record.getSourceMethodName() + " - " + record.getMessage() + "\r\n";
			}
		});
		logger.addHandler(logFileHandler);
		if ( isSet(logLevel) ) {
			logger.setLevel(Level.parse(logLevel.toUpperCase()));
		}
	}
	protected TreeSet<String> readPropertiesFromIniFile(String iniFile) throws FileNotFoundException, IOException {
		// we reload the properties each time the plugin is called, in case some property changed.
		props = new Properties();
		props.load(new FileInputStream(iniFile));

		try {
			inializeLogger(getProperty("log.file", props), getProperty("log.level", props));
		} catch (Exception e) {
			logFileHandler = null;		// we do not really care if there is no log file
		}

			// Now that we'got a logger (or not), we can trace he actions done
		message(FINE, "=========================================");
		message(FINE, "importing elements from ServiceNow");
		message(FINE, "Getting properties from file " + iniFile);

			// checking for mandatory properties
		String[] mandatory = {"servicenow.site", "servicenow.user", "servicenow.pass"};
		for ( String p: mandatory ) {
			if ( isSet(getProperty(p, props)) ) {
				if ( p.equals("servicenow.pass")) message(FINER, "found property " + p + " = xxxxxxxx");
				else message(FINER, "found property " + p + " = " + getProperty(p, props));
			} else {
				throw new IOException("The '"+p+"' property must be set in your INI file.");
			}
		}

			// if proxy information is specified, the we set the system properties accordingly
		String[] propArray = {"proxyUser", "proxyPassword", "proxyHost", "proxyPort"};
		for ( String pp: propArray ) {
			if ( isSet(getProperty("http."+pp, props)) ) {
				message(FINER, "found property http." + pp + " = " + getProperty("http."+pp, props));
				message(FINEST, "     --> setting http." + pp + " and https." + pp + " properties.");
				System.setProperty("http."+pp, getProperty("http."+pp, props));
				System.setProperty("https."+pp, getProperty("http."+pp, props));
			}
		}
		
			// we construct the cmdb_rel_ci webservice URL
		serviceNowCmdbRelCiWebService = getProperty("servicenow.site", props) + getProperty("servicenow.cmdb_rel_ci.table_api", "/api/now/table/cmdb_rel_ci", props);
		// we configure the maximum number of CI that ServiceNow should send
		serviceNowCmdbRelCiWebService += "?sysparm_limit=" + getProperty("servicenow.cmdb_rel_ci.sysparm_limit", "0", props);
		// we remove the reference links that we do not manage
		serviceNowCmdbRelCiWebService += "&sysparm_exclude_reference_link=true";
		message(FINE,"generated relations webservice URL is " + serviceNowCmdbRelCiWebService);

		// we check the archi relation type
		for (String key: props.stringPropertyNames()) {
			String[] subKeys = key.split("\\.");
			if ( subKeys.length == 4 && subKeys[0].equals("archi") && subKeys[1].equals("relations") && subKeys[2].equals("mapping") ) {
				message(FINER, "found property " + key + " = " + getProperty(key, props));
				try {
					IArchimatePackage.eINSTANCE.getRelationship().isSuperTypeOf((EClass)IArchimatePackage.eINSTANCE.getEClassifier(getProperty(key, props)));
				} catch (Exception e) {
					message (ERROR, "cannot map " + subKeys[3] + " relations to " + getProperty(key, props) + " as it is not an Archi relation type.");
					return null;
				}
				message(FINEST, "     --> will map " + subKeys[3] + " relations to " + getProperty(key, props) + " Archi relations.");
			}
		}

		// we construct the cmdb_ci webservice URL
		serviceNowCmdbCiWebService = getProperty("servicenow.site", props) + getProperty("servicenow.cmdb_ci.table_api", "/api/now/table/cmdb_ci", props);

		// we configure the maximum number of CI that ServiceNow should send
		serviceNowCmdbCiWebService += "?sysparm_limit=" + getProperty("servicenow.cmdb_ci.sysparm_limit", "0", props);

		// we remove the reference links that we do not manage
		serviceNowCmdbCiWebService += "&sysparm_exclude_reference_link=true";


		// we set the "sysparm_query" web service parameter to only retrieve the classes that are mapped to Archi elements
		TreeSet<String>	set = new TreeSet<String>();
		for (String key: props.stringPropertyNames()) {
			String[] subKeys = key.split("\\.");
			if ( subKeys.length == 4 && subKeys[0].equals("archi") && subKeys[1].equals("elements") && subKeys[2].equals("mapping") ) {
				message(FINER, "found property " + key + " = " + getProperty(key, props));
				message(FINEST, "     --> will retrieve " + subKeys[3] + " CIs from ServiceNow.");
				set.add(subKeys[3]);
			}
		}
		String sep="&sysparm_query=" + getProperty("servicenow.class", "sys_class_name", props) + "=";
		for ( Iterator<String> i = set.iterator() ; i.hasNext(); ) {
			serviceNowCmdbCiWebService += sep + i.next();
			sep = "%5EOR" + getProperty("servicenow.class", "sys_class_name", props) + "=";
		}


		// we limit the fields returned by ServiceNow to those that are mapped to Archi
		set = new TreeSet<String>();
		for (String key: props.stringPropertyNames()) {
			String[] subKeys = key.split("\\.");
			if ( subKeys.length == 3 && subKeys[0].equals("archi") && subKeys[1].equals("elements") ) {
				message(FINER, "found property " + key + " = " + getProperty(key, props));
				String value = null;
				switch ( subKeys[2] ) {
					case "id"	:			message(FINEST, "     --> will use ServiceNow " + getProperty(key, props) + " field as the ID for imported Archi elements.");
											value = getProperty(key, props);
											break;
					case "documentation" :	message(FINEST, "     --> will use ServiceNow " + getProperty(key, props) + " field as the documentation of imported Archi elements.");
											value = getProperty(key, props);
											break;
					case "mapping"	:		message(FINEST, "     --> will use ServiceNow " + getProperty(key, props) + " field to map ServiceNow CIs to Archi elements.");
											value = getProperty(key, props);
											break;
					case "name" :			message(FINEST, "     --> will ServiceNow " + getProperty(key, props) + " field as the name of the Archi elements.");
											value = getProperty(key, props);
											break;
					case "property" :		message(FINEST, "     --> will create a " + subKeys[3] + " property in all imported elements with " + getProperty(key, props) + " Servicenow field.");
											value = getProperty(key, props);
											break;
					case "folder" :			message(FINEST, "     --> will create all elements in folder " + getProperty(key, props) + ".");
											value = getProperty(key, props);
											break;
					default :				message(FINEST, "     --> unrecognised property ... ignored ...");
				}
				if ( value != null ) {
					Matcher m = Pattern.compile("([^/\"][^/]*|\".+?\")\\s*").matcher(value);
					while (m.find()) {
						if ( !m.group(1).substring(0,1).equals("\"") ) set.add(m.group(1));
					}
				}
			} else if ( subKeys.length == 4 && subKeys[0].equals("archi") && subKeys[1].equals("elements") ) {
				message(FINER, "found property " + key + " = " + getProperty(key, props));
				String value = null;
				switch ( subKeys[2] ) {
					case "mapping"	:		String p = getProperty(key, props);
											if ( p.substring(0,1).equals("\"") ) p = p.substring(1,p.length()-1);
											try {
												IArchimatePackage.eINSTANCE.getArchimateElement().isSuperTypeOf((EClass)IArchimatePackage.eINSTANCE.getEClassifier(p));
											} catch (Exception e) {
												message (ERROR, "cannot map " + subKeys[3] + " CI to " + p + " as it is not an Archi element type.");
												return null;
											}
											message(FINEST, "     --> will map " + subKeys[3] + " CI to " + getProperty(key, props) + " Archi elements.");
											break;
					case "property" :		message(FINEST, "     --> will create a " + subKeys[3] + " property in all imported elements with " + getProperty(key, props) + " Servicenow field.");
											value = getProperty(key, props);
											break;
					case "folder" :			message(FINEST, "     --> will create " + subKeys[3] + " elements in folder " + getProperty(key, props) + ".");
											value = getProperty(key, props);
					default :				// we can have cmdb_ci field in position [2] but we do not list them as we may not know how to map them to Archi elements
											message(FINEST, "     --> unrecognised property ... ignored ...");
				}
				if ( value != null ) {
					Matcher m = Pattern.compile("([^/\"][^/]*|\".+?\")\\s*").matcher(value);
					while (m.find()) {
						if ( !m.group(1).substring(0,1).equals("\"") ) set.add(m.group(1));
					}
				}
			} else if ( subKeys.length == 5 && subKeys[0].equals("archi") && subKeys[1].equals("elements") && subKeys[2].equals("property") ) {
				message(FINER, "found property " + key + " = " + getProperty(key, props));
				message(FINEST, "     --> will create a " + subKeys[4] + " property in all imported elements with " + subKeys[3] + " elements.");
				Matcher m = Pattern.compile("([^/\"][^/]*|\".+?\")\\s*").matcher(getProperty(key, props));
				while (m.find()) {
					if ( !m.group(1).substring(0,1).equals("\"") ) set.add(m.group(1));
				}
			}
		}
			// we then construct the webservice parameter
		sep = "&sysparm_fields=";
		for ( Iterator<String> i = set.iterator() ; i.hasNext(); ) {
			serviceNowCmdbCiWebService += sep + i.next();
			sep = ",";
		}

		message(FINE,"generated CIs webservice URL is " + serviceNowCmdbCiWebService);

		// To ease the Archi elements creation, we construct a list with all the properties to set
		set = new TreeSet<String>();
		for (String key: props.stringPropertyNames()) {
			String[] subKeys = key.split("\\.");
			if ( subKeys.length == 4 && subKeys[0].equals("archi") && subKeys[1].equals("elements") && subKeys[2].equals("property")) set.add(subKeys[3]);
			if ( subKeys.length == 5 && subKeys[0].equals("archi") && subKeys[1].equals("elements") && subKeys[2].equals("property")) set.add(subKeys[4]);
		}
		return set;
	}
	private void message(int status, String msg1, String msg2) {
		if ( status <= INFO ) {
			JOptionPane.showMessageDialog(null, msg1 + "\n\n" + msg2, title, status);
		}
		if ( logFileHandler != null ) {
			switch (status) {
			case ERROR:  	logger.severe(msg1+" ("+msg2+")"); break;
			case WARNING:	logger.warning(msg1+" ("+msg2+")"); break;
			case INFO:		logger.info(msg1+" ("+msg2+")"); break;
			case FINE:		logger.fine(msg1+" ("+msg2+")"); break;
			case FINER:		logger.finer(msg1+" ("+msg2+")"); break;
			case FINEST:	logger.finest(msg1+" ("+msg2+")"); break;
			}
		}
	}
	private void message(int status, String msg) {
		if ( status <= INFO ) {
			JOptionPane.showMessageDialog(null, msg, title, status);
		}
		if ( logFileHandler != null ) {
			switch (status) {
			case ERROR:  	logger.severe(msg); break;
			case WARNING:	logger.warning(msg); break;
			case INFO:		logger.info(msg); break;
			case FINE:		logger.fine(msg); break;
			case FINER:		logger.finer(msg); break;
			case FINEST:	logger.finest(msg); break;
			}
		}
	}
	private void createProgressBar(String msg) {
		Window[] w = Frame.getWindows();
		Window root = w[0];
		JDialog frame = new JDialog(root, title, Dialog.ModalityType.APPLICATION_MODAL);
		frame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		progressBar = new JProgressBar(0, 100);
		progressBarLabel = new JLabel(msg);
		progressBarDialog = new JDialog(frame, title, true);
		progressBarDialog.add(BorderLayout.CENTER, progressBar);
		progressBarDialog.add(BorderLayout.NORTH, progressBarLabel );
		progressBarDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		progressBarDialog.setSize(400, 75);
		progressBarDialog.setLocationRelativeTo(frame);

		Thread t = new Thread(new Runnable() { public void run() { progressBarDialog.setVisible(true); } });
		t.start();	
	}
	private void setProgressBar(int value) {
		progressBar.setMaximum(value);
	}
	private void updateProgressBar() {
		progressBar.setValue(progressBar.getValue()+1);
	}
	private void setProgressBar(String message) {
		progressBarLabel.setText(message);
	}
	private void dismissProgressBar() {
		if ( progressBarDialog != null )
			progressBarDialog.setVisible(false);
	}
	private String askForIniFile() {
		FileDialog dialog = new FileDialog(Display.getCurrent().getActiveShell());
		dialog.setText(title+" - Please select your INI file ...");
		String[] ext = {"*.ini","*"};
		dialog.setFilterExtensions(ext);
		return dialog.open();
	}
	private Boolean isSet(String s) {
		return s!=null && !s.equals("");
	}

		// Gets a property from the property file
	String getProperty(String propertyName, Properties prop) {
		return getProperty(propertyName, null, null, prop, null, null);
	}
	String getProperty(String propertyName, String defaultValue, Properties prop) {
		return getProperty( propertyName, defaultValue, null, prop, null, null);
	}
	String getProperty(String propertyName, Properties prop, String servicenowClassName, String archiElementType) {
		return getProperty(propertyName, null, null, prop, servicenowClassName, archiElementType);
	}
	String getProperty(String propertyName, String defaultValue, String propertyPrefix, Properties prop, String servicenowClassName, String archiElementType) {
		if ( !isSet(propertyName) )
			return defaultValue;
		
		String value = null;
		
		if ( isSet(propertyPrefix) ) {
			if ( !propertyPrefix.endsWith(".") ) propertyPrefix += ".";
			if ( !isSet(value) && isSet(servicenowClassName) ) {
				if ( !servicenowClassName.endsWith(".") ) servicenowClassName += ".";
				value = prop.getProperty(propertyPrefix + servicenowClassName + propertyName + ".");
			}
			if ( !isSet(value) && isSet(archiElementType) )	{
				if ( !archiElementType.endsWith(".") ) archiElementType += ".";
				value = prop.getProperty(propertyPrefix + archiElementType + propertyName);
			}
			if ( !isSet(value) ) value = prop.getProperty(propertyPrefix + propertyName);
			if ( !isSet(value) ) return defaultValue;
		} else {
			if ( !isSet(value) && isSet(servicenowClassName) ) 	if ( propertyName.endsWith(".") ) value = prop.getProperty(propertyName + servicenowClassName); else value = prop.getProperty(propertyName + "." + servicenowClassName);
			if ( !isSet(value) && isSet(archiElementType) )	    if ( propertyName.endsWith(".") ) value = prop.getProperty(propertyName + archiElementType);    else value = prop.getProperty(propertyName + "." + archiElementType);
			if ( !isSet(value) ) value = prop.getProperty(propertyName);
			if ( !isSet(value) ) return defaultValue;
		}
		return value;
	}
	
		// Gets a field from a Json record
	String getJsonField(JsonNode node, String fieldName) {
		return getJsonField(node, fieldName, null);
	}
	String getJsonField(JsonNode node, String fieldName, String defaultValue) {
		if ( !isSet(fieldName) )
			return defaultValue;
		
			// if the fieldName contains '/' (like a path) then we iterate on each subfolder
			//		if value is surrounded by double quotes are kept as they are
			//		else, values are considered as Json fields
		Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(fieldName);
		String sep="", result="";
		while (value.find()) {
			String str = value.group(1);
			if ( value.group(1).substring(0,1).equals("/") ) {
				result += "/"; sep = "";
				str = value.group(1).substring(1);
			} 
			if ( str.length() > 0 ) {
				if ( str.substring(0,1).equals("\"") )
					result += sep + str.substring(1, str.length()-1);
				else {
					if ( node == null )
						result += sep + str;
					else
						if ( node.get(str) == null)	result += sep ;
						else 						result += sep + node.get(str).asText();
				}
			}
			sep="/";
		}
		return result;
	}

	private class MyException extends Exception {
		private static final long serialVersionUID = -8060893532800513872L;
		public MyException(String msg) {
	        super(msg);
	    }
	}
}
