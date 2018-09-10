/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */

package org.archicontribs.servicenow;

import java.awt.BorderLayout;
import java.awt.Frame;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;

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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;

import com.archimatetool.editor.model.ISelectedModelImporter;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Archimate Tool Plugin : Import from ServiceNow
 * 
 * @author Hervé JOUIN
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
 * TODO: change the progressBar to application modal
 * TODO: retrieve the applications and business services
 * TODO: validate iniFile before importing data ...
 * TODO: rework all the error messages as it's not clear what to do in case they happen
 */

public class MyImporter implements ISelectedModelImporter {
    private String SNowPluginVersion = "1.2.3";

    private Logger logger;
    private String title = "ServiceNow import plugin v" + this.SNowPluginVersion;
    SortedProperties iniProperties = new SortedProperties();

    private JProgressBar progressBar = null;
    private JLabel progressBarLabel = null;
    JDialog progressBarDialog = null;

    // proxy information
    String proxyHost = null;
    String proxyUSer = null;
    String proxyPassword = null;
    Integer proxyPort = null;

    // ServiceNow site and credentials
    String serviceNowSite = null;
    String serviceNowUser = null;
    String serviceNowPassword = null;
    
    // ServiceNow sysparm_limit (allow to increase or reduce the number of components sent by ServiceNow)
    int serviceNowSysparmLimit = 0;

    // general elements properties
    String archiElementsId = null;
    String archiElementsName = null;
    String archiElementsDocumentation = null;
    String archiElementsFolder = null;
    Boolean archiElementsExcludeRefLink = null;

    private int created = 0;
    private int updated = 0;
    private int totalCreated = 0;
    private int totalUpdated = 0;

    private Date progressBarBegin;

    @Override
    public void doImport(IArchimateModel model) throws IOException {
        String iniFilename;         // name of the ini file that contains the configuration of the plugin
        String jsonString;          // json string retreived from ServiceNow
        String sysparmFields;
        String URL;

        // We ask for the name of the ini file. 
        if ( (iniFilename = askForIniFile()) == null )
            return;

        // read properties from INI file
        try ( FileInputStream iniFileStream = new FileInputStream(iniFilename) ) {
            this.iniProperties.load(iniFileStream);
        } catch ( FileNotFoundException e) {
            message(Level.FATAL, "Cannot open ini file.", e.getMessage());
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
                message(Level.FATAL, "The \"SNowPlugin.version\" property ("+iniVersion+") differs from the plugin version.","The actual plugin version is '" + this.SNowPluginVersion + "'.");
                return;
            }
        } else {
            message(Level.FATAL, "The \"SNowPlugin.version\" property is mandatory. You must add it in your INI file.","The actual plugin version is '" + this.SNowPluginVersion +"'.");
            return;
        }

        // we get the proxy information from the ini file
        this.proxyHost = this.iniProperties.getInt("http.proxyHost");
        this.proxyUSer = this.iniProperties.getString("http.proxyUser");
        this.proxyPassword = this.iniProperties.getString("http.proxyPassword");

        // we get the site and credential to access ServiceNow
        this.serviceNowSite = this.iniProperties.getString("servicenow.site");
        if ( !isSet(this.serviceNowSite) ) {
            message(Level.FATAL, "The \"servicenow.site\" property is not found in the ini file, but it is mandatory.", "Please ensure you set this property in the ini file.");
            return;
        }

        this.serviceNowUser = this.iniProperties.getString("servicenow.user");
        if ( !isSet(this.serviceNowUser) ) {
            message(Level.FATAL, "The \"servicenow.user\" property is not found in the ini file, but it is mandatory.", "Please ensure you set this property in the ini file.");
            return;
        }

        this.serviceNowPassword = this.iniProperties.getProperty("servicenow.pass");        // do not use getString(), else the password will be in the log file !
        if ( !isSet(this.serviceNowPassword) ) {
            message(Level.FATAL, "The \"servicenow.pass\" property is not found in the ini file, but it is mandatory.", "Please ensure you set this property in the ini file.");
            return;
        }
        this.logger.debug("--> servicenow.pass = xxxxxxxxxx");

        // we get the sysparm_limit
        this.serviceNowSysparmLimit = this.iniProperties.getInt("servicenow.sysparm_limit", 0);


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
        
        this.archiElementsId = this.iniProperties.getString("archi.elements.*.id", "sys_id");
        this.archiElementsName = this.iniProperties.getProperty("archi.elements.*.name", "sys_class_name");
        this.archiElementsDocumentation = this.iniProperties.getProperty("archi.elements.*.documentation", "short_description");
        this.archiElementsFolder = this.iniProperties.getProperty("archi.elements.*.folder", "sys_class_name");
        this.archiElementsExcludeRefLink = this.iniProperties.getBoolean("archi.elements.*.exclude_ref_link", true);

        //      properties archi.elements.*.property.xxxx
        SortedProperties generalProperties = new SortedProperties();
        for (String propertyKey: this.iniProperties.stringPropertyNames()) {
            if ( propertyKey.startsWith("archi.elements.*.property.") ) {
                String[] subkey = propertyKey.split("\\.");
                if ( subkey.length == 5 ) {
                    String propertyValue = this.iniProperties.getString(propertyKey);
                    generalProperties.put(subkey[4], propertyValue);
                }
            }
        }

        // We get each table described in properties like archi.elements.<table>.mapping
        for (String iniKey: this.iniProperties.stringPropertyNames()) {
            String[] iniSubKeys = iniKey.split("\\.");
            if ( iniSubKeys.length == 4 && iniSubKeys[0].equals("archi") && iniSubKeys[1].equals("elements") && iniSubKeys[3].equals("mapping") ) {
                String fieldsToRetreiveFromServiceNow;
                String sep;
                
                //
                // the tablename is in third position of the property (so second position in the string array)
                //
                String tableName = iniSubKeys[2];

                this.logger.info("Retrieving table " + tableName + " from ServiceNow ...");
                this.logger.debug("--> " + iniKey + " = " + this.iniProperties.getProperty(iniKey));
                
                //
                // we construct the ServiceNow URL
                //
                URL = this.serviceNowSite + "/api/now/table/" + tableName;
                
                // we check if we must follow the reference link
                Boolean excludeRefLink = this.iniProperties.getBoolean("archi.elements."+tableName+".exclude_ref_link", this.archiElementsExcludeRefLink);
                if ( excludeRefLink )
                    URL += "?sysparm_exclude_reference_link=true";

                // We set the maximum number of elements that ServiceNow should send
                if ( this.serviceNowSysparmLimit != 0 )
                    URL += "&sysparm_limit="+this.serviceNowSysparmLimit;

                // We collect all fields that ServiceNow should send us
                SortedProperties props = new SortedProperties();
                sysparmFields = "&sysparm_fields=";
                sep = "";
                
                fieldsToRetreiveFromServiceNow = getFields(this.iniProperties.getProperty("archi.elements."+tableName+".id", this.archiElementsId));
                if ( isSet(fieldsToRetreiveFromServiceNow) ) {
                    sysparmFields += sep + fieldsToRetreiveFromServiceNow;
                    sep = ",";
                }
                
                fieldsToRetreiveFromServiceNow = getFields(this.iniProperties.getProperty("archi.elements."+tableName+".name", this.archiElementsName));
                if ( isSet(fieldsToRetreiveFromServiceNow) ) {
                    sysparmFields += sep + fieldsToRetreiveFromServiceNow;
                    sep = ",";
                }
                
                fieldsToRetreiveFromServiceNow = getFields(this.iniProperties.getProperty("archi.elements."+tableName+".documentation", this.archiElementsDocumentation));
                if ( isSet(fieldsToRetreiveFromServiceNow) ) {
                    sysparmFields += sep + fieldsToRetreiveFromServiceNow;
                    sep = ",";
                }
                
                fieldsToRetreiveFromServiceNow = getFields(this.iniProperties.getProperty("archi.elements."+tableName+".folder", this.archiElementsFolder));
                if ( isSet(fieldsToRetreiveFromServiceNow) ) {
                    sysparmFields += sep + fieldsToRetreiveFromServiceNow;
                    sep = ",";
                }

                // we get all the properties specified by a archi.elements.<table>.property.xxx
                for (String propertyKey: this.iniProperties.stringPropertyNames()) {
                    if ( propertyKey.startsWith("archi.elements."+tableName+".property.") ) {
                        String[] subkeys = propertyKey.split("\\.");
                        if ( subkeys.length == 5 ) {
                            String propertyValue = this.iniProperties.getProperty(propertyKey);
                            props.put(subkeys[4], propertyValue);
                            this.iniProperties.getProperty(propertyKey);
                            fieldsToRetreiveFromServiceNow = getFields(propertyValue);
                            if ( isSet(fieldsToRetreiveFromServiceNow) ) {
                                sysparmFields += sep + fieldsToRetreiveFromServiceNow;
                                sep = ",";
                            }
                        }
                    }
                }
                
                // we add all the general properties from the archi.elements.property.xxx
                for ( String propertyKey: generalProperties.elements() )
                
                sep = "";
                for ( String f: props.stringPropertyNames() ) {
                    this.logger.trace("   required property = "+f);
                    String p = props.getProperty(f);
                    if ( p != null ) {
                        // if the fieldName contains '/' (like a path) then we iterate on each subfolder
                        //		only values not surrounded by double quotes are field names
                        Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(p);
                        while (value.find()) {
                            String str = value.group(1);
                            if ( value.group(1).substring(0,1).equals("/") ) {
                                str = value.group(1).substring(1);
                            } 
                            if ( str.length() > 0 ) {
                                if ( ! str.substring(0,1).equals("\"") ) {
                                    sysparmFields += sep+str;
                                    sep = ",";
                                    this.logger.trace("         --> field = "+str);
                                } else {
                                    this.logger.trace("         --> constant = "+str);
                                }
                            }
                        }
                    }
                }
                URL += sysparmFields;
                this.logger.debug("Generated URL is " + URL);

                try {
                    int count = 0;
                    createProgressBar("Connecting to ServiceNow webservice ...");
                    // we invoke the ServiceNow web service 
                    jsonString = getFromUrl(iniSubKeys[2], URL, this.iniProperties.getProperty("servicenow.user"), this.iniProperties.getProperty("servicenow.pass"));

                    setProgressBar("Parsing "+iniSubKeys[2]+" table ...");

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
                    setProgressBar("Parsing "+iniSubKeys[2]+" table ("+count+" elements) ...");
                    setProgressBar(count); 

                    // now we create elements

                    try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
                        jsonParser.nextToken();	// START_OBJECT
                        jsonParser.nextToken();	// "results"
                        jsonParser.nextToken();	// START_ARRAY
                        while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
                            updateProgressBar();
                            JsonNode jsonNode = jsonParser.readValueAsTree();
                            String id, name, folder, mapping, documentation;
                            if ( (id = getJsonField(jsonNode, props.getProperty("id"))) == null ) {
                                throw new MyException("Cannot retrieve element's ID (field "+props.getProperty("id")+")");
                            }
                            if ( (name = getJsonField(jsonNode, props.getProperty("name"))) == null ) {
                                throw new MyException("Cannot retrieve element's name");
                            }
                            if ( (folder = getJsonField(jsonNode, props.getProperty("folder"))) == null ) {
                                throw new MyException("Cannot retrieve element's folder");
                            }
                            if ( (mapping = getJsonField(jsonNode, props.getProperty("mapping"))) == null ) {
                                throw new MyException("Cannot retrieve element's mapping");
                            }
                            if ( (documentation = getJsonField(jsonNode, props.getProperty("documentation"))) == null ) {
                                throw new MyException("Cannot retrieve element's documentation");
                            }

                            IArchimateElement element = createOrReplacedArchimateElement(model, mapping, name, id, folder);

                            if ( element.getDocumentation().equals(documentation) ) {
                                this.logger.trace("      documentation is not updated as value \"" + element.getDocumentation() + "\" is unchanged.");
                            } else {
                                this.logger.trace("      documentation is updated from value \"" + element.getDocumentation() + "\" to value \"" + documentation + "\"");
                                element.setDocumentation(documentation);
                            }

                            nextProperty:
                                for (String propertyName: props.stringPropertyNames()) {
                                    if ( !propertyName.equals("id") && !propertyName.equals("name") && !propertyName.equals("mapping") && !propertyName.equals("folder") && !propertyName.equals("documentation")) {
                                        String propertyValue = getJsonField(jsonNode, props.getProperty(propertyName));
                                        if ( propertyValue == null ) propertyValue = "";
                                        for (Iterator<IProperty> i = element.getProperties().iterator(); i.hasNext(); ) {
                                            IProperty elementProperty = i.next();
                                            if ( elementProperty.getKey().equals(propertyName) ) {
                                                if ( propertyValue.equals(elementProperty.getValue()) )
                                                    this.logger.trace("      existing property " + propertyName + " is not updated as value \"" + propertyValue + "\" is unchanged.");
                                                else 
                                                    if ( isSet(propertyValue) ) {
                                                        this.logger.trace("      existing property " + propertyName + " is updated from value \"" + elementProperty.getValue() + "\" to value \"" + propertyValue + "\"");
                                                        elementProperty.setValue(propertyValue);
                                                    } else
                                                        this.logger.trace("      existing property " + propertyName + " is not updated from value \"" + elementProperty.getValue() + "\" to empty value.");
                                                continue nextProperty;
                                            }
                                        }
                                        // if we're here, it means the property does'nt exists. Therefore, we create it.
                                        IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                                        prop.setKey(propertyName);
                                        prop.setValue(propertyValue);
                                        this.logger.trace("      new property " + propertyName + " is created with value = \"" + propertyValue + "\"");
                                        element.getProperties().add(prop);
                                    }
                                }
                        }
                        this.logger.info("   " + Integer.toString(this.created+this.updated) + " elements imported from ServiceNow ("+this.created + " created + " + this.updated + " updated).");
                        this.totalCreated += this.created;
                        this.totalUpdated += this.updated;
                        this.created = this.updated = 0;
                        dismissProgressBar();
                    }
                } catch (Exception e) {
                    dismissProgressBar();
                    String cause = (e.getCause() != null && e.getCause().getMessage() != null) ? ("\n\n"+e.getCause().getMessage()) : "";
                    if ( e.getMessage() != null )
                        message(Level.FATAL,"Cannot get "+iniSubKeys[2]+" table from ServiceNow web service: " + e.getMessage()+cause);
                    else
                        message(Level.FATAL,"Cannot get "+iniSubKeys[2]+" table from ServiceNow web service."+cause);
                    if ( e.getMessage()!=null)
                        this.logger.fatal("   ---> " + e.getMessage());
                    for(StackTraceElement stackTraceElement : e.getStackTrace())                     
                        this.logger.fatal("   ---> " + stackTraceElement.toString());
                    if ( e.getCause()!=null ) {
                        this.logger.fatal("      ---> Caused by");
                        if ( e.getCause().getMessage()!=null)
                            this.logger.fatal("      ---> " + e.getCause().getMessage());
                        for(StackTraceElement stackTraceElement : e.getStackTrace())                      
                            this.logger.fatal("      ---> " + stackTraceElement.toString());
                    }
                    return;
                }
            }
        }
        dismissProgressBar();
        this.logger.info(Integer.toString(this.totalCreated+this.totalUpdated) + " elements imported in total from ServiceNow ("+Integer.toString(this.totalCreated) + " created + " + Integer.toString(this.totalUpdated) + " updated).");


        /* ****************************
         ***                      ***
         *** Retrieving relations ***
         ***                      ***  
         **************************** */

        URL = this.iniProperties.getProperty("servicenow.site") + "/api/now/table/cmdb_rel_ci?sysparm_exclude_reference_link=true";	

        // We set the maximum number of elements that ServiceNow should send
        if ( isSet(this.iniProperties.getProperty("servicenow.sysparm_limit")) )
            URL += "&sysparm_limit="+this.iniProperties.getProperty("servicenow.sysparm_limit");

        // We collect all fields that ServiceNow should send us
        sysparmFields = "&sysparm_fields=";
        SortedProperties props = new SortedProperties();
        if ( isSet(this.iniProperties.getProperty("archi.relations.id")) ) 			 props.put("id", this.iniProperties.getProperty("archi.relations.id"));
        if ( isSet(this.iniProperties.getProperty("archi.relations.type")) ) 	 	 props.put("type", this.iniProperties.getProperty("archi.relations.type"));
        if ( isSet(this.iniProperties.getProperty("archi.relations.source")) ) 		 props.put("source", this.iniProperties.getProperty("archi.relations.source"));
        if ( isSet(this.iniProperties.getProperty("archi.relations.target")) ) 		 props.put("target", this.iniProperties.getProperty("archi.relations.target"));
        // TODO: validate that we've got all the mandatory properties : id, name, source, target
        // TODO: allow to specify folder

        sep = "";
        for ( String f: props.stringPropertyNames() ) {
            this.logger.trace("   required property = "+f);
            String p = props.getProperty(f);
            if ( p != null ) {
                // if the fieldName contains '/' (like a path) then we iterate on each subfolder
                //		only values not surrounded by double quotes are field names
                Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(p);
                while (value.find()) {
                    String str = value.group(1);
                    if ( value.group(1).substring(0,1).equals("/") ) {
                        str = value.group(1).substring(1);
                    } 
                    if ( str.length() > 0 ) {
                        if ( ! str.substring(0,1).equals("\"") ) {
                            sysparmFields += sep+str;
                            sep = ",";
                            this.logger.trace("         --> field = "+str);
                        } else {
                            this.logger.trace("         --> constant = \""+str+"\"");
                        }
                    }
                }
            }
        }
        URL += sysparmFields;

        // we retrieve only the relations of managed types
        URL += "&sysparm_query=";
        sep = "";
        for (String key: this.iniProperties.stringPropertyNames()) {
            String[] subkeys = key.split("\\.");
            if ( subkeys.length == 4 && subkeys[0].equals("archi") && subkeys[1].equals("relations") && subkeys[3].equals("mapping") ) {
                URL += sep + "type=" + subkeys[2];
                sep = "%5EOR";
            }
        }

        this.logger.debug("Generated URL is " + URL);

        createProgressBar("Connecting to ServiceNow webservice ...");

        try {
            // import relations
            jsonString = getFromUrl("relations", URL, this.iniProperties.getProperty("servicenow.user"), this.iniProperties.getProperty("servicenow.pass"));

            setProgressBar("Parsing data ...");
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

            setProgressBar("Parsing "+count+" relations from ServiceNow webservice ...");
            this.created = this.totalCreated = 0;
            this.updated = this.totalUpdated = 0;
            setProgressBar(count); 

            // now we create relations
            try ( JsonParser jsonParser = jsonFactory.createJsonParser(jsonString) ) {
                jsonParser.nextToken();	// START_OBJECT
                jsonParser.nextToken();	// "result"
                jsonParser.nextToken();	// START_ARRAY
                while ( jsonParser.nextToken() != JsonToken.END_ARRAY ) {
                    updateProgressBar();
                    JsonNode jsonNode = jsonParser.readValueAsTree();
                    String typeId = getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.type"));
                    if ( ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.id"))) == null ) {
                        String relationType = this.iniProperties.getProperty("archi.relations."+typeId+".mapping");
                        if ( isSet(relationType) ) {
                            IArchimateElement source = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.source")));
                            if ( source == null )
                                this.logger.trace("   unknown element (ID = "+getJsonField(jsonNode, "child")+") ... ignoring relation.");
                            else {
                                IArchimateElement target = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.target")));
                                if ( target == null )
                                    this.logger.trace("   unknown element (ID = "+getJsonField(jsonNode, "parent")+") ... ignoring relation.");
                                else {
                                    if(!ArchimateModelUtils.isValidRelationship(source.eClass(), target.eClass(), (EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType))) {
                                        this.logger.debug("   invalid relation " + relationType + " between " + source.getName() + " and " + target.getName() + ".");
                                    } else { 
                                        IArchimateRelationship relation = (IArchimateRelationship)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(relationType));
                                        relation.setId(getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.id")));
                                        relation.setSource(source);
                                        relation.setTarget(target);
                                        String name = getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations."+typeId+".name"));
                                        if ( isSet(name) ) relation.setName(name);
                                        model.getDefaultFolderForObject(relation).getElements().add(relation);
                                        this.logger.debug("   creating "+relationType+" relation from "+source.getName()+" to "+target.getName()+" and named " + name + " (id = " + getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.id")) + ").");
                                        this.created++;
                                    }
                                }
                            }
                        }
                    } else {
                        this.logger.debug("   relation " + getJsonField(jsonNode, this.iniProperties.getProperty("archi.relations.id")) + " already exists ...");
                    }
                    // TODO: verify what is done when relation already exists. Eventually, create method createOrReplaceArchiRelation
                }

                dismissProgressBar();
                this.logger.info("Imported " + (this.created+this.updated) + " relations from "+count+" relations received from ServiceNow ("+this.created + " created + " + this.updated + " updated).");
            }
        } catch (Exception e) {
            dismissProgressBar();
            String cause = (e.getCause() != null && e.getCause().getMessage() != null) ? ("\n\n"+e.getCause().getMessage()) : "";
            if ( e.getMessage() != null )
                message(Level.FATAL,"Cannot get relations from ServiceNow web service: " + e.getMessage() + cause);
            else
                message(Level.FATAL,"Cannot get relations from ServiceNow web service."+cause);
            for(StackTraceElement stackTraceElement : e.getStackTrace())           
                this.logger.fatal("   ---> " + stackTraceElement.toString());
            if ( e.getMessage()!=null)
                this.logger.fatal("   ---> " + e.getMessage());
            for(StackTraceElement stackTraceElement : e.getStackTrace())                     
                this.logger.fatal("   ---> " + stackTraceElement.toString());
            if ( e.getCause()!=null ) {
                this.logger.fatal("      ---> Caused by");
                if ( e.getCause().getMessage()!=null)
                    this.logger.fatal("      ---> " + e.getCause().getMessage());
                for(StackTraceElement stackTraceElement : e.getStackTrace())                      
                    this.logger.fatal("      ---> " + stackTraceElement.toString());
            }
            return;
        }
        this.logger.info("All done ...");
    }

    private IArchimateElement createOrReplacedArchimateElement(IArchimateModel model, String type, String name, String id, String folderName) {
        IArchimateElement element = (IArchimateElement)ArchimateModelUtils.getObjectByID(model, id);

        if ( element == null ) {
            this.created++;
            this.logger.debug("creating new " + type + " " + name + "(ID = " + id + ")");
            element = (IArchimateElement)IArchimateFactory.eINSTANCE.create((EClass)IArchimatePackage.eINSTANCE.getEClassifier(type));
            element.setId(id);

            // placing the element in the required folder ...
            if ( isSet(folderName) ) {
                IFolder currentFolder = model.getDefaultFolderForObject(element); 
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
                this.logger.trace("   placing element in folder " + folderName);
                currentFolder.getElements().add(element);
            }
            else
                model.getDefaultFolderForObject(element).getElements().add(element);
        } else {
            this.updated++;
            this.logger.debug("updating element " + name);
        }

        element.setName(name);
        return element;
    }

    private String getFromUrl(String what, String location, String username, String Password) throws Exception {
        URL url = new URL(location);
        HttpURLConnection c;

        if ( isSet(this.proxyHost) ) {
            if ( isSet(this.proxyUSer) ) {
                Authenticator.setDefault( new Authenticator() {
                    @Override
                    public PasswordAuthentication getPasswordAuthentication() {	return (new PasswordAuthentication(MyImporter.this.proxyUSer, MyImporter.this.proxyPassword.toCharArray())); }
                });
            }
            c = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(this.proxyHost, this.proxyPort)));
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
            setProgressBar("Getting " + what + " from ServiceNow webservice ...");

            int nb=0, total=0;
            byte[] buffer = new byte[10240];	// 10 KB
            while ( (nb=in.read(buffer,0,buffer.length)) > 0 ) {
                data.append(new String(buffer,0,nb));
                total+=nb;
                if ( total < 1048576 ) {
                    setProgressBar("Getting " + what + " from ServiceNow webservice ... (read "+String.format("%d", total/1024) + " KB)");
                } else {
                    setProgressBar("Getting " + what + " from ServiceNow webservice ... (read "+String.format("%.2f", (float)total/1048576) + " MB)");
                }
            }
            this.logger.debug("   Read " + total + " bytes from ServiceNow webservice.");
        }
        return data.toString();
    }

    private void message(Level level, String msg1, String msg2) {
        switch ( level.toInt() ) {
            case Priority.ERROR_INT :
            case Priority.FATAL_INT :
                JOptionPane.showMessageDialog(null, msg1 + "\n\n" + msg2, this.title, JOptionPane.ERROR_MESSAGE);
                break;
            case Priority.WARN_INT :
                JOptionPane.showMessageDialog(null, msg1 + "\n\n" + msg2, this.title, JOptionPane.WARNING_MESSAGE);
                break;
            default :
                JOptionPane.showMessageDialog(null, msg1 + "\n\n" + msg2, this.title, JOptionPane.PLAIN_MESSAGE);
        }
        this.logger.log(level, msg1+" ("+msg2+")");
    }
    private void message(Level level, String msg) {
        switch ( level.toInt() ) {
            case Priority.ERROR_INT :
            case Priority.FATAL_INT :
                JOptionPane.showMessageDialog(null, msg, this.title, JOptionPane.ERROR_MESSAGE);
                break;
            case Priority.WARN_INT :
                JOptionPane.showMessageDialog(null, msg, this.title, JOptionPane.WARNING_MESSAGE);
                break;
            default :
                JOptionPane.showMessageDialog(null, msg, this.title, JOptionPane.PLAIN_MESSAGE);
        }
        this.logger.log(level, msg);
    }

    private void createProgressBar(String msg) {
        JDialog frame = new JDialog((Frame)null, this.title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        this.progressBar = new JProgressBar(0, 100);
        this.progressBarLabel = new JLabel(msg);
        this.progressBarDialog = new JDialog(frame, this.title, true);
        this.progressBarDialog.add(BorderLayout.CENTER, this.progressBar);
        this.progressBarDialog.add(BorderLayout.NORTH, this.progressBarLabel );
        this.progressBarDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.progressBarDialog.setSize(500, 75);
        this.progressBarDialog.setLocationRelativeTo(frame);

        Thread t = new Thread(new Runnable() { @Override
            public void run() { MyImporter.this.progressBarDialog.setVisible(true); } });
        t.start();
    }
    private void setProgressBar(int value) {
        this.progressBar.setMaximum(value);
        this.progressBarBegin = new Date();
    }
    private void updateProgressBar() {
        this.progressBar.setValue(this.progressBar.getValue()+1);
        Date now = new Date();
        if ( (this.progressBar.getPercentComplete()*100) >= 1 ) {
            long estimatedDuration = ((now.getTime() - this.progressBarBegin.getTime()) * (this.progressBar.getMaximum() - this.progressBar.getValue())) / (this.progressBar.getValue() * 1000);
            if ( estimatedDuration > 3600 ) {		// more than one hour
                long h = estimatedDuration/3600;
                long m = (estimatedDuration%3600)*60;
                this.progressBar.setString(String.format("%2.1f%% completed, %dh%02dm remaining", this.progressBar.getPercentComplete()*100, h, m));
            } else if ( estimatedDuration > 60 ) {	// more than 1 minute
                long m = estimatedDuration/60;
                long s = estimatedDuration%60;
                this.progressBar.setString(String.format("%2.1f%% completed, %dm%02ds remaining", this.progressBar.getPercentComplete()*100, m, s));
            } else {
                this.progressBar.setString(String.format("%2.1f%% completed, %02ds remaining", this.progressBar.getPercentComplete()*100, estimatedDuration));
            }
            this.progressBar.setStringPainted(true);
        }
    }
    private void setProgressBar(String message) {
        this.progressBarLabel.setText(message);
    }
    private void dismissProgressBar() {
        if ( this.progressBarDialog != null )
            this.progressBarDialog.setVisible(false);
    }

    private String askForIniFile() {
        FileDialog dialog = new FileDialog(Display.getCurrent().getActiveShell());
        dialog.setText(this.title+" - Please select your INI file ...");
        String[] ext = {"*.ini","*"};
        dialog.setFilterExtensions(ext);
        return dialog.open();
    }
    private static Boolean isSet(String s) {
        return s!=null && !s.equals("");
    }

    // Gets a property from the property file
    //private String getProperty(String propertyName, Properties prop) {
    //	return prop.getProperty(propertyName);
    //}

    // Gets a field from a Json record
    private static String getJsonField(JsonNode node, String fieldName) {
        return getJsonField(node, fieldName, null);
    }
    private static String getJsonField(JsonNode node, String fieldName, String defaultValue) {
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
    
    String getFields(String field) {
        String sep = "";
        String result = "";
        
        if ( isSet(field) ) {
            // if the fieldName contains '/' (like a path) then we iterate on each subfolder
            //      only values not surrounded by double quotes are field names
            Matcher value = Pattern.compile("([^\"][^/]*|\".+?\")\\s*").matcher(field);
            while (value.find()) {
                String str = value.group(1);
                if ( value.group(1).substring(0,1).equals("/") ) {
                    str = value.group(1).substring(1);
                } 
                if ( str.length() > 0 ) {
                    if ( ! str.substring(0,1).equals("\"") ) {
                        result += sep + str;
                        sep = ",";
                        this.logger.trace("         --> field = "+str);
                    } else {
                        this.logger.trace("         --> constant = "+str);
                    }
                }
            }
        }
        
        return result;
    }
}
