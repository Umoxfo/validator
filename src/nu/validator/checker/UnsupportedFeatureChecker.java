package nu.validator.checker;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

public class UnsupportedFeatureChecker extends Checker {

    /**
     * @see nu.validator.checker.Checker#startElement(java.lang.String,
     *      java.lang.String, java.lang.String, org.xml.sax.Attributes)
     */
    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes atts) throws SAXException {
        if ("http://www.w3.org/1999/xhtml" != uri) {
            return;
        }
        if ("dialog" == localName || "bdi" == localName) {
            warnAboutElement(localName);
        } else if ("textarea" == localName) {
            if (atts.getIndex("", "dirname") > -1) {
                warnAboutAttributeOnElement("dirname", "textarea");
            }
            if (atts.getIndex("", "inputmode") > -1) {
                warnAboutAttribute("inputmode");
            }
        } else if ("input" == localName) {
            if (atts.getIndex("", "dirname") > -1) {
                warnAboutAttributeOnElement("dirname", "input");
            }
            if (atts.getIndex("", "inputmode") > -1) {
                warnAboutAttribute("inputmode");
            }
            String type = atts.getValue("", "type");
            if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "date", type)) {
                warnAboutInputType("date");
            } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "month", type)) {
                warnAboutInputType("month");
            } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "week", type)) {
                warnAboutInputType("week");
            } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "time", type)) {
                warnAboutInputType("time");
            } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "datetime-local", type)) {
                warnAboutInputType("datetime-local");
            } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                    "color", type)) {
                warnAboutInputType("color");
            }
        }
    }

    private void warnAboutInputType(String name) throws SAXException {
        warn(String.format(Messages.getString("UnsupportedFeatureChecker.33"), name)); //$NON-NLS-1$
    }

    private void warnAboutAttribute(String name) throws SAXException {
        warn(String.format(Messages.getString("UnsupportedFeatureChecker.37"), name)); //$NON-NLS-1$
    }

    private void warnAboutElement(String name) throws SAXException {
        warn(String.format(Messages.getString("UnsupportedFeatureChecker.41"), name)); //$NON-NLS-1$
    }

    private void warnAboutAttributeOnElement(String attributeName, String elementName) throws SAXException {
      warn(String.format(Messages.getString("UnsupportedFeatureChecker.45"), attributeName, elementName)); //$NON-NLS-1$
    }

}
