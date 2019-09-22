package org.archicontribs.servicenow;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import java.awt.Toolkit;
import java.util.Date;

public class MyProgressBar implements AutoCloseable {
    final Display display = Display.getDefault();
    
    final FontData SYSTEM_FONT = this.display.getSystemFont().getFontData()[0];
    final Color    LIGHT_BLUE  = new Color(this.display, 240, 248, 255);
    final Font     TITLE_FONT  = new Font(this.display, this.SYSTEM_FONT.getName(), this.SYSTEM_FONT.getHeight(), SWT.BOLD);
    
    Shell shell;
    Composite composite;
    Label progressBarLabel;
    Label progressBarDetailLabel;
    ProgressBar progressBar;

    
    long progressBarBegin;
    
    public MyProgressBar(String title, String msg) {
        this.shell = new Shell(Display.getDefault(), SWT.APPLICATION_MODAL | SWT.SHELL_TRIM);
        this.shell.setText(title);
        this.shell.setSize(600, 150);
        this.shell.setLocation((Toolkit.getDefaultToolkit().getScreenSize().width - this.shell.getSize().x) / 4, (Toolkit.getDefaultToolkit().getScreenSize().height - this.shell.getSize().y) / 4);
        this.shell.setLayout(new FormLayout());
        
        this.composite = new Composite(this.shell, SWT.NONE);
        this.composite.setBackground(this.LIGHT_BLUE);
        FormData fd = new FormData();
        fd.left= new FormAttachment(0);
        fd.right = new FormAttachment(100);
        fd.top = new FormAttachment(0);
        fd.bottom = new FormAttachment(100);
        this.composite.setLayoutData(fd);
        this.composite.setLayout(new FormLayout());
        
        this.progressBarLabel = new Label(this.composite, SWT.NO_BACKGROUND);
        fd = new FormData();
        fd.left= new FormAttachment(0, 20);
        fd.right = new FormAttachment(100, -20);
        fd.top = new FormAttachment(0, 10);
        this.progressBarLabel.setLayoutData(fd);
        this.progressBarLabel.setBackground(this.LIGHT_BLUE);
        this.progressBarLabel.setFont(this.TITLE_FONT);
        this.progressBarLabel.setText(msg);
        
        this.progressBarDetailLabel = new Label(this.composite, SWT.NO_BACKGROUND);
        fd = new FormData();
        fd.left= new FormAttachment(0, 50);
        fd.right = new FormAttachment(100, -20);
        fd.top = new FormAttachment(this.progressBarLabel);
        this.progressBarDetailLabel.setLayoutData(fd);
        this.progressBarDetailLabel.setBackground(this.LIGHT_BLUE);
        
        this.progressBar = new ProgressBar(this.composite, SWT.SMOOTH);
        this.progressBar.setSelection(0);
        fd = new FormData();
        fd.top = new FormAttachment(this.progressBarDetailLabel);
        fd.left= new FormAttachment(0, 20);
        fd.right = new FormAttachment(100, -20);
        fd.bottom = new FormAttachment(100, -10);
        this.progressBar.setLayoutData(fd);
        this.progressBar.setMinimum(0);
        this.progressBar.setMaximum(100);

        this.shell.layout();
        this.shell.open();
    }
    
    public void setMaximum(int value) {
    	this.progressBar.setSelection(0);
        this.progressBar.setMaximum(value);
        this.progressBarBegin = new Date().getTime();
    }
    
    public void increase() {
        long now = new Date().getTime();
        int newProgressBarValue = this.progressBar.getSelection()+1;
        
        long elapsedTime = now - this.progressBarBegin + 300;
        
        // the estimatedDuration is the elapsed time * 
        float estimatedDuration = ((this.progressBar.getMaximum()-newProgressBarValue) * ((float)elapsedTime/1000)) / newProgressBarValue;

        float percentComplete = (float)newProgressBarValue / this.progressBar.getMaximum();
        
        // if the estimated duration is greater than 1 hour
        if ( estimatedDuration > 3600 ) {
            int h = (int) (estimatedDuration/3600);
            int m = (int) ((estimatedDuration%3600)*60);
            this.progressBarDetailLabel.setText(String.format("%2.1f%% completed, %dh%02dm remaining", percentComplete*100, h, m));
        }
        
        // if the estimated duration is greater than 1 minute
        else if ( estimatedDuration > 60 ) {
            int m = (int) (estimatedDuration/60);
            int s = (int) (estimatedDuration%60);
            this.progressBarDetailLabel.setText(String.format("%2.1f%% completed, %dm%02ds remaining", percentComplete*100, m, s));
        }
        
        // if the estimated duration is less than 1 minute
        else
            this.progressBarDetailLabel.setText(String.format("%2.1f%% completed, %02ds remaining", percentComplete*100, (int)estimatedDuration));
        
        this.progressBar.setSelection(newProgressBarValue);
        this.progressBar.redraw();
        this.progressBar.update();
        refreshDisplay();
    }
    
    public void setLabel(String message) {
        this.progressBarLabel.setText(message);
        refreshDisplay();
    }
    
    public void setDetailLabel(String message) {
        this.progressBarDetailLabel.setText(message);
    }
    
    @Override
    public void close() {
        this.shell.dispose();
    }
    
    /**
     * Refreshes the display
     */
    void refreshDisplay() {
        while ( this.display.readAndDispatch() ) {
            // nothing to do
        }
    }
}
