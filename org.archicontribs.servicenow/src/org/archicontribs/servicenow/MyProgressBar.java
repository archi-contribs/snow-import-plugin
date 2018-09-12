package org.archicontribs.servicenow;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Rectangle;
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
    Label label;
    ProgressBar progressBar;
    String progressBarLabel;
    
    Date progressBarBegin;
    
    public MyProgressBar(String title, String msg) {
        this.shell = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
        this.shell.setText(title);
        this.shell.setSize(600, 100);
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
        
        this.label = new Label(this.composite, SWT.NO_BACKGROUND);
        fd = new FormData();
        fd.left= new FormAttachment(0, 20);
        fd.right = new FormAttachment(100, -20);
        fd.top = new FormAttachment(0, 10);
        this.label.setLayoutData(fd);
        this.label.setBackground(this.LIGHT_BLUE);
        this.label.setFont(this.TITLE_FONT);
        this.label.setText(msg);
        
        this.progressBar = new ProgressBar(this.composite, SWT.SMOOTH);
        fd = new FormData();
        fd.left= new FormAttachment(0, 20);
        fd.right = new FormAttachment(100, -20);
        fd.bottom = new FormAttachment(100, -10);
        this.progressBar.setLayoutData(fd);
        this.progressBar.setMinimum(0);
        this.progressBar.setMaximum(100);
        this.progressBar.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent e) {
                if (MyProgressBar.this.progressBarLabel == null || MyProgressBar.this.progressBarLabel.length() == 0)
                    return;
                e.gc.setFont(MyProgressBar.this.display.getSystemFont());
                e.gc.setForeground(MyProgressBar.this.shell.getForeground()); // set text color to widget foreground color

                Rectangle rec = ((ProgressBar)e.getSource()).getBounds();
                e.gc.drawString(MyProgressBar.this.progressBarLabel, rec.x + 20, (rec.height - e.gc.getFontMetrics().getHeight()) / 2, true);
            }
        });

        this.shell.layout();
        this.shell.open();
    }
    
    public void setMaximum(int value) {
        this.progressBar.setMaximum(value);
        this.progressBarBegin = new Date();
    }
    
    public void increase() {
        Date now = new Date();
        int newProgressBarValue = this.progressBar.getSelection()+1;
        float percentComplete = (float)newProgressBarValue / this.progressBar.getMaximum();
        long estimatedDuration = ((now.getTime() - this.progressBarBegin.getTime()) * (this.progressBar.getMaximum() - newProgressBarValue)) / (newProgressBarValue * 1000) + 1;
        
        // if the estimated duration is greater than 1 hour
        if ( estimatedDuration > 3600 ) {
            long h = estimatedDuration/3600;
            long m = (estimatedDuration%3600)*60;
            this.progressBarLabel = String.format("%2.1f%% completed, %dh%02dm remaining", percentComplete*100, h, m);
        }
        
        // if the estimated duration is greater than 1 minute
        else if ( estimatedDuration > 60 ) {
            long m = estimatedDuration/60;
            long s = estimatedDuration%60;
            this.progressBarLabel = String.format("%2.1f%% completed, %dm%02ds remaining", percentComplete*100, m, s);
        }
        
        // if the estimated duration is less than 1 minute
        else
            this.progressBarLabel = String.format("%2.1f%% completed, %02ds remaining", percentComplete*100, estimatedDuration);
        
        this.progressBar.setSelection(newProgressBarValue);
        this.progressBar.redraw();
        this.progressBar.update();
    }
    
    public void setLabel(String message) {
        this.label.setText(message);
    }
    
    public void setProgressBarLabel(String message) {
        this.progressBarLabel = message;
        this.progressBar.redraw();
        this.progressBar.update();
    }
    
    @Override
    public void close() {
        this.shell.dispose();
    }
}
