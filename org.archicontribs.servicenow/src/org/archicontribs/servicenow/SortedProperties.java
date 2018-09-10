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
    Logger logger = Logger.getLogger("SNowPlugin");

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
    
    public String getString(String propertyName, String defaultValue) {
        String value = getProperty(propertyName);
        
        if ( (value == null || value.equals("")) && defaultValue != null ) {
            this.logger.debug("--> "+propertyName+" = "+value+" (defaulting to +"+defaultValue+")");
            return defaultValue;
        }
        
        this.logger.debug("--> "+propertyName+" = "+value);
        return value;
    }
    
    public String getString(String propertyName) {
        return getString(propertyName, null);
    }
    
    public Boolean getBoolean(String propertyName, Boolean defaultValue) {
        String value = getProperty(propertyName);
        
        if ( value == null || value.equals("") ) {
            this.logger.debug("--> "+propertyName+" = "+value+" (defaulting to +"+defaultValue+")");
            return defaultValue;
        }
        
        this.logger.debug("--> "+propertyName+" = "+value);
        return Boolean.valueOf(value);
    }
    
    public String getBoolean(String propertyName) {
        return getString(propertyName, null);
    }
    
    public Integer getInt(String propertyName, Integer defaultValue) throws NumberFormatException {
        String value = getProperty(propertyName);
        
        if ( value == null || value.equals("") ) {
            this.logger.debug("--> "+propertyName+" = "+value+" (defaulting to +"+defaultValue+")");
            return defaultValue;
        }
        
        this.logger.debug("--> "+propertyName+" = "+value);
        return Integer.valueOf(value);
    }
    
    public String getInt(String propertyName) {
        return getString(propertyName, null);
    }
}
