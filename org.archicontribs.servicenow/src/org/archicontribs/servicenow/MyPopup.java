package org.archicontribs.servicenow;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

public class MyPopup {
    /**
     * Shows up an on screen popup, displaying the message (and the exception message if any) and wait for the user to click on the "OK" button<br>
     * The exception stacktrace is also printed on the standard error stream
     */
    public MyPopup(Logger logger, Level level, String msg, Exception e) {
        String popupMessage = msg;
        logger.log(level, msg.replace("\n", " "), e);

        Throwable cause = e;
        while ( cause != null ) {
            if ( cause.getMessage() != null ) {
                if ( !popupMessage.endsWith(cause.getMessage()) ) {
                    popupMessage += "\n\n" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    logger.log(level, cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
                }
            } else {
                popupMessage += "\n\n" + cause.getClass().getSimpleName();
                logger.log(level, cause.getClass().getSimpleName(), e);
            }
            cause = cause.getCause();
        }

        switch ( level.toInt() ) {
            case Priority.FATAL_INT:
            case Priority.ERROR_INT:
                MessageDialog.openError(Display.getDefault().getActiveShell(), MyImporter.title, popupMessage);
                break;
            case Priority.WARN_INT:
                MessageDialog.openWarning(Display.getDefault().getActiveShell(), MyImporter.title, popupMessage);
                break;
            default:
                MessageDialog.openInformation(Display.getDefault().getActiveShell(), MyImporter.title, popupMessage);
                break;
        }
    }
    
    /**
     * Shows up an on screen popup, displaying the message (and the exception message if any) and wait for the user to click on the "OK" button
     */
    public MyPopup(Logger logger,Level level, String msg) {
        this(logger, level, msg, null);
    }
}
