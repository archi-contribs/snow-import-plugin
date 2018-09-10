package org.archicontribs.servicenow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

public class SortedProperties extends Properties {
    private static final long serialVersionUID = -7764236508910777813L;
    Logger logger = null;

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Enumeration<Object> keys() {
        Set<?> set = keySet();
        return (Enumeration<Object>) sortKeys((Set<String>) set);
    }

    static public Enumeration<?> sortKeys(Set<String> keySet) {
        List<String> sortedList = new ArrayList<String>();
        sortedList.addAll(keySet);
        Collections.sort(sortedList);
        return Collections.enumeration(sortedList);
    }

    @Override
    public Set<String> stringPropertyNames() {
        Set<String> tmpSet = new TreeSet<String>();
        for (Object key : keySet()) {
            tmpSet.add(key.toString());
        }
        return tmpSet;
    }
    
	public String getProperty(String propertyName, String defaultValue, boolean hideValueInLogFile) {
        String value = super.getProperty(propertyName);
        
        if ( (value == null || value.equals("")) && defaultValue != null ) {
            if ( hideValueInLogFile )
            	debug("--> "+propertyName+" = "+value+" (defaulting to @@@@@@@@@@)");
            else
            	debug("--> "+propertyName+" = "+value+" (defaulting to "+defaultValue+")");
            return defaultValue;
        }
        
        if ( hideValueInLogFile )
        	debug("--> "+propertyName+" = @@@@@@@@@@");
        else
        	debug("--> "+propertyName+" = "+value);
        return value;
    }
    
    @Override
	public String getProperty(String propertyName, String defaultValue) {
        return this.getProperty(propertyName, defaultValue, false);
    }
    
    @Override
	public String getProperty(String propertyName) {
        return this.getProperty(propertyName, null, false);
    }
    
    public Boolean getBoolean(String propertyName, Boolean defaultValue) {
        String value = super.getProperty(propertyName);
        
        if ( value == null || value.equals("") ) {
            debug("--> "+propertyName+" = "+value+" (defaulting to "+defaultValue+")");
            return defaultValue;
        }
        
        debug("--> "+propertyName+" = "+value);
        return Boolean.valueOf(value);
    }
    
    public String getBoolean(String propertyName) {
        return this.getProperty(propertyName, null);
    }
    
    public Integer getInt(String propertyName, Integer defaultValue) throws NumberFormatException {
        String value = super.getProperty(propertyName);
        
        if ( value == null || value.equals("") ) {
            debug("--> "+propertyName+" = "+value+" (defaulting to "+defaultValue+")");
            return defaultValue;
        }
        
        debug("--> "+propertyName+" = "+value);
        return Integer.valueOf(value);
    }
    
    public String getInt(String propertyName) {
        return this.getProperty(propertyName, null);
    }
    
    void setLogger(Logger logger) {
    	this.logger = logger;
    }
    
    void debug(String debugString) {
    	if ( this.logger != null )
    		this.logger.debug(debugString);
    }
}
