package org.archicontribs.servicenow;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.florianingerl.util.regex.Matcher;
import com.florianingerl.util.regex.Pattern;

public class MyVariable {
	private static String variableSeparator = ":";

	public static void setVariableSeparator(String separator) {
		variableSeparator = separator;
	}

	/**
	 * Expands an expression containing variables<br>
	 * It may return an empty string, but never a null value
	 */
	public static String expand(Logger logger, String expression, EObject eObject) throws MyException {
		if ( expression == null )
			return "";

		StringBuffer sb = new StringBuffer(expression.length());

		Pattern pattern = Pattern.compile("(\\$\\{([^${}]|(?1))+\\})");
		Matcher matcher = pattern.matcher(expression);

		while (matcher.find()) {
			String variable = matcher.group(1);
			//if ( logger.isTraceEnabled() ) logger.trace("   matching "+variable);
			String variableValue = getVariable(logger, variable, eObject);
			if ( variableValue == null )
				variableValue = "";
			matcher.appendReplacement(sb, Matcher.quoteReplacement(variableValue));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Gets the value of the variable<br>
	 * can return a null value in case the property does not exist. This way it is possible to distinguish between empty value and null value
	 */
	public static String getVariable(Logger logger, String variable, EObject selectedEObject) throws MyException  {
		EObject eObject = selectedEObject;

		if ( logger.isTraceEnabled() ) logger.trace("         getting variable \""+variable+"\"");

		// we check that the variable provided is a string enclosed between "${" and "}"
		if ( !variable.startsWith("${") || !variable.endsWith("}") )
			throw new MyException("The expression \""+variable+"\" is not a variable (it should be enclosed between \"${\" and \"}\")");

		// we expand variables that may exist in the variable name itself
		String variableName = expand(logger, variable.substring(2, variable.length()-1), eObject);

		//TODO : add a preference to choose between silently ignore or raise an error
		switch ( variableName.toLowerCase() ) {
			case "class" :
				if ( eObject != null ) {
					String result;
					if (eObject instanceof IDiagramModelArchimateObject)
						result = ((IDiagramModelArchimateObject)eObject).getArchimateElement().getClass().getSimpleName();
					else if (eObject instanceof IDiagramModelArchimateConnection)
						result = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship().getClass().getSimpleName();
					else
						result = eObject.getClass().getSimpleName();
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ result +"\"");
					return result;
				}
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get class as the object is null)");
				return null;

			case "id" :
				if ( eObject != null ) {
					if (eObject instanceof IIdentifier) {
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ ((IIdentifier)eObject).getId() +"\"");
						return ((IIdentifier)eObject).getId();
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get id as the object does not have any)");
					return null;
				}
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get id as the object is null)");
				return null;

			case "documentation" :
				if ( eObject != null ) {
					String result;
					if (eObject instanceof IDiagramModelArchimateObject)
						result =  ((IDiagramModelArchimateObject)eObject).getArchimateElement().getDocumentation();
					else if (eObject instanceof IDiagramModelArchimateConnection)
						result = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship().getDocumentation();
					else if (eObject instanceof IDocumentable)
						result = ((IDocumentable)eObject).getDocumentation();
					else {
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get documentation as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ result +"\"");
					return result;
				}
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get documentation as the object is null)");
				return null;

			case "purpose" :
				if ( eObject != null ) {
					if (eObject instanceof IArchimateModel) {
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ ((IArchimateModel)eObject).getPurpose() +"\"");
						return ((IArchimateModel)eObject).getPurpose();
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get purpose as the object is not a model)");
					return null;
				}
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get purpoe as the object is null)");
				return null;

			case "void":
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \"\"");
				return "";

			case "name" :
				if ( eObject != null ) {
					if (eObject instanceof INameable) {
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ ((INameable)eObject).getName() +"\"");
						return ((INameable)eObject).getName();
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get name as the object does not have any)");
					return null;
				}
				if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get name as the object is null)");
				return null;

			case "username":
				return System.getProperty("user.name");

			default :
				// check for ${date:format}
				if ( variableName.toLowerCase().startsWith("date"+variableSeparator)) {
					String format = variableName.substring(4+variableSeparator.length());
					DateFormat df = new SimpleDateFormat(format);
					Date now = Calendar.getInstance().getTime();
					String result = df.format(now);
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ result +"\"");
					return result;
				}

				// check for ${property:xxx}
				if ( variableName.toLowerCase().startsWith("property"+variableSeparator) ) {
					if ( eObject != null ) {
						if ( eObject instanceof IDiagramModelArchimateObject )
							eObject = ((IDiagramModelArchimateObject)eObject).getArchimateElement();
						if ( eObject instanceof IDiagramModelArchimateConnection )
							eObject = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship();
						if ( eObject instanceof IProperties ) {
							String propertyName = variableName.substring(8+variableSeparator.length());
							for ( IProperty property: ((IProperties)eObject).getProperties() ) {
								if ( MyImporter.areEquals(property.getKey(),propertyName) ) {
									if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+ property.getValue() +"\"");
									return property.getValue();
								}
							}
							if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (the object does not have any property \""+propertyName+"\"");
							return null;
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}
				
				// check for ${properties:xxx}
				if ( variableName.toLowerCase().startsWith("properties"+variableSeparator) ) {
					if ( eObject != null ) {
						if ( eObject instanceof IDiagramModelArchimateObject )
							eObject = ((IDiagramModelArchimateObject)eObject).getArchimateElement();
						if ( eObject instanceof IDiagramModelArchimateConnection )
							eObject = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship();
						if ( eObject instanceof IProperties ) {
							// the syntax is ${properties:separator:regexp}
							Pattern pattern = Pattern.compile("(|[^"+variableSeparator+"]*[^\\\\])"+variableSeparator+"(.+)");
							Matcher matcher = pattern.matcher(variableName.substring(10+variableSeparator.length()));
							matcher.find();
							String separator = matcher.group(1);
							String propertyRegexp = matcher.group(2);
							
				            if ( logger.isTraceEnabled() ) logger.trace("Separator="+separator+"   propertyRegexp="+propertyRegexp);
				            
							// we replace \n by new lines, \t by tab and \: by :
				            if ( separator.length() != 0 ) {
				                separator = separator.replace("\\n","\n");
				                separator = separator.replace("\\t","\t");
				                separator = separator.replace("\\"+variableSeparator,variableSeparator);
				            }
				            
				            StringBuilder result = new StringBuilder();						
							for ( IProperty property: ((IProperties)eObject).getProperties() ) {
								if ( property.getKey().matches(propertyRegexp) ) {
									if ( logger.isTraceEnabled() ) logger.trace("         ---> adding value \""+ property.getValue() +"\"");
									if ( result.length() != 0 )
										result.append(separator);
									result.append(property.getValue());
								}
							}
							if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+result.toString()+"\"");
							return result.toString();
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}
				
				// check for ${sortedproperties:xxx}
				if ( variableName.toLowerCase().startsWith("sortedproperties"+variableSeparator) ) {
					if ( eObject != null ) {
						if ( eObject instanceof IDiagramModelArchimateObject )
							eObject = ((IDiagramModelArchimateObject)eObject).getArchimateElement();
						if ( eObject instanceof IDiagramModelArchimateConnection )
							eObject = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship();
						if ( eObject instanceof IProperties ) {
							// the syntax is ${properties:separator:regexp}
							Pattern pattern = Pattern.compile("(|[^"+variableSeparator+"]*[^\\\\])"+variableSeparator+"(.+)");
							Matcher matcher = pattern.matcher(variableName.substring(16+variableSeparator.length()));
							matcher.find();
							String separator = matcher.group(1);
							String propertyRegexp = matcher.group(2);
							
				            if ( logger.isTraceEnabled() ) logger.trace("Separator="+separator+"   propertyRegexp="+propertyRegexp);
				            
							// we replace \n by new lines, \t by tab and \: by :
				            if ( separator.length() != 0 ) {
				                separator = separator.replace("\\n","\n");
				                separator = separator.replace("\\t","\t");
				                separator = separator.replace("\\"+variableSeparator,variableSeparator);
				            }
				            
				            if ( logger.isTraceEnabled() ) logger.trace("         ---> sorting properties");
				            List<IProperty> sortedProperties = new ArrayList<IProperty>(((IProperties)eObject).getProperties());
				            sortedProperties.sort(new Comparator<IProperty>() {
								@Override
								public int compare(IProperty o1, IProperty o2) {
									if ( o1.getKey() == null )
										return -1;
									if ( o2.getKey() == null )
										return 1;
									return o1.getKey().compareTo(o2.getKey());
								}
				            });
				            
				            StringBuilder result = new StringBuilder();
							for ( IProperty property: sortedProperties ) {
								if ( property.getKey().matches(propertyRegexp) ) {
									if ( logger.isTraceEnabled() ) logger.trace("         ---> adding value \""+ property.getValue() +"\"");
									if ( result.length() != 0 )
										result.append(separator);
									result.append(property.getValue());
								}
							}
							if ( logger.isTraceEnabled() ) logger.trace("         ---> value is \""+result.toString()+"\"");
							return result.toString();
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}

				// check for ${view:xxx}
				if ( variableName.toLowerCase().startsWith("view"+variableSeparator) ) {
					if ( eObject != null ) {
						String subVariable = "${"+variableName.substring(4+variableSeparator.length())+"}";
						
						if ( eObject instanceof IDiagramModel ) {
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from view.");
							return getVariable(logger, subVariable, eObject);
						}
						else if ( eObject instanceof IDiagramModelArchimateObject ) {
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from view.");
							return getVariable(logger, subVariable, ((IDiagramModelArchimateObject)eObject).getDiagramModel());
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get view as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}

				// check for ${model:xxx}
				if ( variableName.toLowerCase().startsWith("model"+variableSeparator) ) {
					if ( eObject != null ) {
						String subVariable = "${"+variableName.substring(5+variableSeparator.length())+"}";
						
						if ( eObject instanceof IArchimateModelObject ) {
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from model.");
							return getVariable(logger, subVariable, ((IArchimateModelObject)eObject).getArchimateModel());
						}
						else if ( eObject instanceof IDiagramModelComponent ) {
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from model.");
							return getVariable(logger, subVariable, ((IDiagramModelComponent)eObject).getDiagramModel().getArchimateModel());
						}
						else if ( eObject instanceof IArchimateModel ) {
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from model.");
							return getVariable(logger, subVariable, eObject);
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get view as the object does not have any)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}

				// check for ${source:xxx}
				if ( variableName.toLowerCase().startsWith("source"+variableSeparator) ) {
					if ( eObject != null ) {
						EObject obj = eObject;
						if ( eObject instanceof IDiagramModelArchimateObject) {
							obj = ((IDiagramModelArchimateObject)eObject).getArchimateElement();
						} else if (eObject instanceof IDiagramModelArchimateConnection) {
							obj = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship();
						} else {
							obj = eObject;
						}

						if ( obj instanceof IArchimateRelationship ) {
							String subVariable = "${"+variableName.substring(6+variableSeparator.length())+"}";
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from the source of the relationship.");
							return getVariable(logger, subVariable, ((IArchimateRelationship)obj).getSource());
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get source as the object is not a relationship)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}

				// check for ${target:xxx}
				if ( variableName.toLowerCase().startsWith("target"+variableSeparator) ) {
					if ( eObject != null ) {
						EObject obj = eObject;
						if ( eObject instanceof IDiagramModelArchimateObject) {
							obj = ((IDiagramModelArchimateObject)eObject).getArchimateElement();
						} else if (eObject instanceof IDiagramModelArchimateConnection) {
							obj = ((IDiagramModelArchimateConnection)eObject).getArchimateRelationship();
						} else {
							obj = eObject;
						}
	
						if ( obj instanceof IArchimateRelationship ) {
							String subVariable = "${"+variableName.substring(6+variableSeparator.length())+"}";
							if ( logger.isTraceEnabled() ) logger.trace("         ---> getting variable \""+subVariable+"\" from the target of the relationship.");
							return getVariable(logger, subVariable, ((IArchimateRelationship)obj).getTarget());
						}
						if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get target as the object is not a relationship)");
						return null;
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get property as the object is null)");
					return null;
				}

				// check for ${sum:xxx}
				if ( variableName.toLowerCase().startsWith("sum"+variableSeparator)) {
					if ( eObject != null ) {
						int sumValue = 0;
						if ( eObject instanceof IArchimateDiagramModel || eObject instanceof IDiagramModelContainer ) {
							String value = getVariable(logger, "${"+variableName.substring(3+variableSeparator.length())+"}", eObject);
							if ( value != null ) {
								try {
									sumValue += Integer.parseInt(value);
								} catch ( @SuppressWarnings("unused") NumberFormatException ign ) {
									// nothing to do
								}
							}
							for ( IDiagramModelObject child: ((IDiagramModelContainer)eObject).getChildren() ) {
								value = getVariable(logger, "${"+variableName+"}", child);
								if ( value != null ) {
									try {
										sumValue += Integer.parseInt(value);
									} catch ( @SuppressWarnings("unused") NumberFormatException ign ) {
										// nothing to do
									}
								}
							}
						} else {
							String value = getVariable(logger, "${"+variableName.substring(3+variableSeparator.length())+"}", eObject);
							try {
								sumValue += Integer.parseInt(value);
							} catch ( @SuppressWarnings("unused") NumberFormatException ign ) {
								// nothing to do
							}
						}
						return String.valueOf(sumValue);
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get sum as the object is null)");
					return null;
				}


				// check for ${sumx:xxx} (same as sum, but exclusive
				if ( variableName.toLowerCase().startsWith("sumx"+variableSeparator)) {
					if ( eObject != null ) {
						int sumValue = 0;
						if ( eObject instanceof IArchimateDiagramModel || eObject instanceof IDiagramModelContainer ) {
							String value;
							for ( IDiagramModelObject child: ((IDiagramModelContainer)eObject).getChildren() ) {
								value = getVariable(logger, "${sum"+variableSeparator+variableName.substring(4+variableSeparator.length())+"}", child);
								if ( value != null ) {
									try {
										sumValue += Integer.parseInt(value);
									} catch ( @SuppressWarnings("unused") NumberFormatException ign ) {
										// nothing to do
									}
								}
							}
						} else {
							String value = getVariable(logger, "${"+variableName.substring(4+variableSeparator.length())+"}", eObject);
							try {
								sumValue += Integer.parseInt(value);
							} catch ( @SuppressWarnings("unused") NumberFormatException ign ) {
								// nothing to do
							}
						}
						return String.valueOf(sumValue);
					}
					if ( logger.isTraceEnabled() ) logger.trace("         ---> value is null (cannot get sum as the object is null)");
					return null;
				}
		}
		logger.error("Unknown variable \""+variableName+"\" ("+variable+")");
		return null;
	}
}
