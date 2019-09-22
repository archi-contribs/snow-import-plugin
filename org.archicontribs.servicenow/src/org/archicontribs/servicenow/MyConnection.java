package org.archicontribs.servicenow;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.log4j.Logger;

public class MyConnection {
	String proxyHost = null;
	int proxyPort = 0;
	String proxyUser = null;
	String proxyPassowrd = null;

	MyProgressBar progressBar = null;
	Logger logger = null;

	public MyConnection(String theProxyHost, int theProxyPort, String theProxyUser, String theProxyPassword) {
		this.proxyHost = theProxyHost;
		this.proxyPort = theProxyPort;
		this.proxyUser = theProxyUser;
		this.proxyPassowrd = theProxyPassword;
	}

	public void setProgressBar(MyProgressBar bar) {
		this.progressBar = bar;
	}
	
	public void setLogger(Logger log) {
		this.logger = log;
	}

	public String get(String what, String location, String username, String Password) throws MyException, IOException {
		URL url = new URL(location);
		HttpURLConnection c;
        StringBuilder data = new StringBuilder();

		if ( MyUtils.isSet(this.proxyHost) ) {
			if ( MyUtils.isSet(this.proxyUser) ) {
				Authenticator.setDefault( new Authenticator() {
					@Override
					public PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(MyConnection.this.proxyUser, MyConnection.this.proxyPassowrd.toCharArray());
						}
				});
			}
			c = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(this.proxyHost, this.proxyPort)));
		} else {
			c = (HttpURLConnection) url.openConnection();
		}

		String userpass = username + ":" + Password;        
		c.setRequestProperty("Authorization",  "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()), StandardCharsets.UTF_8));
		c.setRequestProperty("Accept", "application/json");
		int status = -1;
		try {
			status = c.getResponseCode();
			if ( status != 200) {
				throw new MyException("Error reported by ServiceNow website : code " + Integer.toString(status)); 
			}
	
    		try ( InputStreamReader reader = new InputStreamReader(c.getInputStream()) ) {
    	        if ( this.logger != null ) this.logger.trace("      Getting " + what + " from ServiceNow webservice ...");
    	        if ( this.progressBar != null ) this.progressBar.setLabel("Getting " + what + " from ServiceNow webservice ...");
    	        
    	        int nb=0, total=0;
    	        char[] buffer = new char[10240];    // 10 KB
    	        while ( (nb=reader.read(buffer,0,buffer.length)) > 0 ) {
                    data.append(buffer,0,nb);
                    total+=nb;
                    if ( this.progressBar != null ) {
                        if ( total < 1048576 ) {
                            this.progressBar.setDetailLabel("read "+String.format("%d", total/1024) + " KB");
                        } else {
                            this.progressBar.setDetailLabel("read "+String.format("%.2f", (float)total/1048576) + " MB");
                        }
                    }
                }
                if ( this.logger != null ) this.logger.trace("      Read " + total + " bytes from ServiceNow webservice.");
                if ( this.progressBar != null ) this.progressBar.setLabel("");
            }
		} finally {
		    c.disconnect();
		}
		
		return data.toString();
	}
}
