/*
 * Copyright (c) 2008-2019 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package nu.validator.checker.schematronequiv;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import nu.validator.checker.AttributeUtil;
import nu.validator.checker.Checker;
import nu.validator.checker.LocatorImpl;
import nu.validator.checker.TaintableLocatorImpl;
import nu.validator.checker.VnuBadAttrValueException;
import nu.validator.checker.VnuBadElementNameException;
import nu.validator.client.TestRunner;
import nu.validator.datatype.AutocompleteDetailsAny;
import nu.validator.datatype.AutocompleteDetailsDate;
import nu.validator.datatype.AutocompleteDetailsEmail;
import nu.validator.datatype.AutocompleteDetailsMonth;
import nu.validator.datatype.AutocompleteDetailsNumeric;
import nu.validator.datatype.AutocompleteDetailsPassword;
import nu.validator.datatype.AutocompleteDetailsTel;
import nu.validator.datatype.AutocompleteDetailsText;
import nu.validator.datatype.AutocompleteDetailsUrl;
import nu.validator.datatype.Color;
import nu.validator.datatype.CustomElementName;
import nu.validator.datatype.Html5DatatypeException;
import nu.validator.datatype.ImageCandidateStringsWidthRequired;
import nu.validator.datatype.ImageCandidateStrings;
import nu.validator.datatype.ImageCandidateURL;
import nu.validator.htmlparser.impl.NCName;
import nu.validator.messages.MessageEmitterAdapter;

import org.relaxng.datatype.DatatypeException;

import org.w3c.css.css.StyleSheetParser;
import org.w3c.css.parser.CssError;
import org.w3c.css.parser.CssParseException;
import org.w3c.css.parser.Errors;
import org.w3c.css.util.ApplContext;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import org.apache.log4j.Logger;

public class Assertions extends Checker {

    private static final Logger log4j = //
            Logger.getLogger(Assertions.class);

    private static boolean equalsIgnoreAsciiCase(String one, String other) {
        if (other == null) {
            return one == null;
        }
        if (one.length() != other.length()) {
            return false;
        }
        for (int i = 0; i < one.length(); i++) {
            char c0 = one.charAt(i);
            char c1 = other.charAt(i);
            if (c0 >= 'A' && c0 <= 'Z') {
                c0 += 0x20;
            }
            if (c1 >= 'A' && c1 <= 'Z') {
                c1 += 0x20;
            }
            if (c0 != c1) {
                return false;
            }
        }
        return true;
    }

    private static final String trimSpaces(String str) {
        return trimLeadingSpaces(trimTrailingSpaces(str));
    }

    private static final String trimLeadingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length(); i > 0; --i) {
            char c = str.charAt(str.length() - i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c
                    || '\r' == c)) {
                return str.substring(str.length() - i, str.length());
            }
        }
        return "";
    }

    private static final String trimTrailingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length() - 1; i >= 0; --i) {
            char c = str.charAt(i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c
                    || '\r' == c)) {
                return str.substring(0, i + 1);
            }
        }
        return "";
    }

    private static final Map<String, String[]> INPUT_ATTRIBUTES = new HashMap<>();

    static {
        INPUT_ATTRIBUTES.put("autocomplete",
                new String[] { "hidden", "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number", "range", "color" });
        INPUT_ATTRIBUTES.put("list",
                new String[] { "text", "search", "url", "tel", "email",
                        "date", "month", "week", "time",
                        "datetime-local", "number", "range", "color" });
        INPUT_ATTRIBUTES.put("maxlength", new String[] { "text", "search",
                "url", "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("minlength", new String[] { "text", "search",
                "url", "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("pattern", new String[] { "text", "search", "url",
                "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("placeholder", new String[] { "text", "search",
                "url", "tel", "email", "password", "number" });
        INPUT_ATTRIBUTES.put("readonly",
                new String[] { "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number" });
        INPUT_ATTRIBUTES.put("required",
                new String[] { "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number", "checkbox", "radio",
                        "file" });
        INPUT_ATTRIBUTES.put("size", new String[] { "text", "search", "url",
                "tel", "email", "password" });
    }

    private static final Map<String, String> OBSOLETE_ELEMENTS = new HashMap<>();

    static {
        OBSOLETE_ELEMENTS.put("keygen", "");
        OBSOLETE_ELEMENTS.put("center",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("font",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("big",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("strike",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("tt",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("acronym",
                String.format(Messages.getString("Assertions.UseElement"), "addr")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("dir",
                String.format(Messages.getString("Assertions.UseElement"), "ul")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("applet",
                String.format(Messages.getString("Assertions.UseElement"), "object")); //$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("basefont",
                Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
        String msg = String.format(Messages.getString("Assertions.UseElement"), "inflame");//$NON-NLS-1$
        OBSOLETE_ELEMENTS.put("frameset", msg);
        OBSOLETE_ELEMENTS.put("noframes", msg);
    }

    private static final Map<String, String[]> OBSOLETE_ATTRIBUTES = new HashMap<>();

    static {
        OBSOLETE_ATTRIBUTES.put("abbr", new String[] { "td" });
        OBSOLETE_ATTRIBUTES.put("archive", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("axis", new String[] { "td", "th" });
        OBSOLETE_ATTRIBUTES.put("charset", new String[] { "link", "a" });
        OBSOLETE_ATTRIBUTES.put("classid", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("code", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("codebase", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("codetype", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("coords", new String[] { "a" });
        OBSOLETE_ATTRIBUTES.put("datafld", new String[] { "span", "div",
                "object", "input", "select", "textarea", "button", "table" });
        OBSOLETE_ATTRIBUTES.put("dataformatas", new String[] { "span", "div",
                "object", "input", "select", "textarea", "button", "table" });
        OBSOLETE_ATTRIBUTES.put("datasrc", new String[] { "span", "div",
                "object", "input", "select", "textarea", "button", "table" });
        OBSOLETE_ATTRIBUTES.put("datapagesize", new String[] { "table" });
        OBSOLETE_ATTRIBUTES.put("declare", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("event", new String[] { "script" });
        OBSOLETE_ATTRIBUTES.put("for", new String[] { "script" });
        OBSOLETE_ATTRIBUTES.put("language", new String[] { "script" });
        OBSOLETE_ATTRIBUTES.put("longdesc", new String[] { "img", "iframe" });
        OBSOLETE_ATTRIBUTES.put("methods", new String[] { "link", "a" });
        OBSOLETE_ATTRIBUTES.put("name",
                new String[] { "img", "embed", "option" });
        OBSOLETE_ATTRIBUTES.put("nohref", new String[] { "area" });
        OBSOLETE_ATTRIBUTES.put("profile", new String[] { "head" });
        OBSOLETE_ATTRIBUTES.put("scheme", new String[] { "meta" });
        OBSOLETE_ATTRIBUTES.put("scope", new String[] { "td" });
        OBSOLETE_ATTRIBUTES.put("shape", new String[] { "a" });
        OBSOLETE_ATTRIBUTES.put("standby", new String[] { "object" });
        OBSOLETE_ATTRIBUTES.put("target", new String[] { "link" });
        OBSOLETE_ATTRIBUTES.put("type", new String[] { "param" });
        OBSOLETE_ATTRIBUTES.put("urn", new String[] { "a", "link" });
        OBSOLETE_ATTRIBUTES.put("usemap", new String[] { "input" });
        OBSOLETE_ATTRIBUTES.put("valuetype", new String[] { "param" });
        OBSOLETE_ATTRIBUTES.put("version", new String[] { "html" });
    }

    private static final Map<String, String> OBSOLETE_ATTRIBUTES_MSG = new HashMap<>();

    static {
        OBSOLETE_ATTRIBUTES_MSG.put("abbr", Messages.getString("Assertions.Obs.AddrMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("archive",
                String.format(Messages.getString("Assertions.Obs.UseParamElement"), "archive")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("axis",
                String.format(Messages.getString("Assertions.Obs.UseAttributeInstead"), "scope")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("charset",
                Messages.getString("Assertions.Obs.CharsetMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("classid",
                String.format(Messages.getString("Assertions.Obs.UseParamElement"), "classid")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("code",
                String.format(Messages.getString("Assertions.Obs.UseParamElement"), "code")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("codebase",
                String.format(Messages.getString("Assertions.Obs.UseParamElement"), "codebase")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("codetype",
                String.format(Messages.getString("Assertions.Obs.UseParamElement"), "codetype")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("coords", Messages.getString("Assertions.Obs.UseAreaForImageMaps")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("datapagesize", Messages.getString("Assertions.CanSafelyOmit")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("datafld", Messages.getString("Assertions.Obs.UseScriptAndMechanism")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("dataformatas", Messages.getString("Assertions.Obs.UseScriptAndMechanism")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("datasrc", Messages.getString("Assertions.Obs.UseScriptAndMechanism")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("for", Messages.getString("Assertions.Obs.UseDOMEvents")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("event", Messages.getString("Assertions.Obs.UseDOMEvents")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("declare", Messages.getString("Assertions.Obs.DeclareMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("language",
                String.format(Messages.getString("Assertions.Obs.UseAttributeInstead"), "type")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("longdesc", Messages.getString("Assertions.Obs.LongdescMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("methods", Messages.getString("Assertions.Obs.MethodsMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("name",
                String.format(Messages.getString("Assertions.Obs.UseAttributeInstead"), "id")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("nohref", Messages.getString("Assertions.Obs.NohrefMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("profile", Messages.getString("Assertions.Obs.ProfileMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("scheme", Messages.getString("Assertions.Obs.SchemeMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("scope", Messages.getString("Assertions.Obs.ScopeMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("shape", Messages.getString("Assertions.Obs.UseAreaForImageMaps")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("standby", Messages.getString("Assertions.Obs.StandbyMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("target", Messages.getString("Assertions.CanSafelyOmit")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("type", Messages.getString("Assertions.Obs.UseNameAndValueAttrs")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("urn", Messages.getString("Assertions.Obs.UrmMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("usemap", Messages.getString("Assertions.Obs.UsemapMsg")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("valuetype", Messages.getString("Assertions.Obs.UseNameAndValueAttrs")); //$NON-NLS-1$
        OBSOLETE_ATTRIBUTES_MSG.put("version", Messages.getString("Assertions.CanSafelyOmit")); //$NON-NLS-1$
    }

    private static final Map<String, String[]> OBSOLETE_STYLE_ATTRS = new HashMap<>();

    static {
        OBSOLETE_STYLE_ATTRS.put("align",
                new String[] { "caption", "iframe", "img", "input", "object",
                        "embed", "legend", "table", "hr", "div", "h1", "h2",
                        "h3", "h4", "h5", "h6", "p", "col", "colgroup", "tbody",
                        "td", "tfoot", "th", "thead", "tr" });
        OBSOLETE_STYLE_ATTRS.put("alink", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("allowtransparency",
                new String[] { "iframe" });
        OBSOLETE_STYLE_ATTRS.put("background", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("bgcolor",
                new String[] { "table", "tr", "td", "th", "body" });
        OBSOLETE_STYLE_ATTRS.put("cellpadding", new String[] { "table" });
        OBSOLETE_STYLE_ATTRS.put("cellspacing", new String[] { "table" });
        OBSOLETE_STYLE_ATTRS.put("char", new String[] { "col", "colgroup",
                "tbody", "td", "tfoot", "th", "thead", "tr" });
        OBSOLETE_STYLE_ATTRS.put("charoff", new String[] { "col", "colgroup",
                "tbody", "td", "tfoot", "th", "thead", "tr" });
        OBSOLETE_STYLE_ATTRS.put("clear", new String[] { "br" });
        OBSOLETE_STYLE_ATTRS.put("color", new String[] { "hr" });
        OBSOLETE_STYLE_ATTRS.put("compact",
                new String[] { "dl", "menu", "ol", "ul" });
        OBSOLETE_STYLE_ATTRS.put("frameborder", new String[] { "iframe" });
        OBSOLETE_STYLE_ATTRS.put("frame", new String[] { "table" });
        OBSOLETE_STYLE_ATTRS.put("height", new String[] { "td", "th" });
        OBSOLETE_STYLE_ATTRS.put("hspace",
                new String[] { "img", "object", "embed" });
        OBSOLETE_STYLE_ATTRS.put("link", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("bottommargin", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("marginheight",
                new String[] { "iframe", "body" });
        OBSOLETE_STYLE_ATTRS.put("leftmargin", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("rightmargin", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("topmargin", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("marginwidth",
                new String[] { "iframe", "body" });
        OBSOLETE_STYLE_ATTRS.put("noshade", new String[] { "hr" });
        OBSOLETE_STYLE_ATTRS.put("nowrap", new String[] { "td", "th" });
        OBSOLETE_STYLE_ATTRS.put("rules", new String[] { "table" });
        OBSOLETE_STYLE_ATTRS.put("scrolling", new String[] { "iframe" });
        OBSOLETE_STYLE_ATTRS.put("size", new String[] { "hr" });
        OBSOLETE_STYLE_ATTRS.put("text", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("type", new String[] { "li", "ul" });
        OBSOLETE_STYLE_ATTRS.put("valign", new String[] { "col", "colgroup",
                "tbody", "td", "tfoot", "th", "thead", "tr" });
        OBSOLETE_STYLE_ATTRS.put("vlink", new String[] { "body" });
        OBSOLETE_STYLE_ATTRS.put("vspace",
                new String[] { "img", "object", "embed" });
        OBSOLETE_STYLE_ATTRS.put("width", new String[] { "hr", "table", "td",
                "th", "col", "colgroup", "pre" });
    }

    private static final HashSet<String> JAVASCRIPT_MIME_TYPES = new HashSet<>();

    static {
        JAVASCRIPT_MIME_TYPES.add("application/ecmascript");
        JAVASCRIPT_MIME_TYPES.add("application/javascript");
        JAVASCRIPT_MIME_TYPES.add("application/x-ecmascript");
        JAVASCRIPT_MIME_TYPES.add("application/x-javascript");
        JAVASCRIPT_MIME_TYPES.add("text/ecmascript");
        JAVASCRIPT_MIME_TYPES.add("text/javascript");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.0");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.1");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.2");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.3");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.4");
        JAVASCRIPT_MIME_TYPES.add("text/javascript1.5");
        JAVASCRIPT_MIME_TYPES.add("text/jscript");
        JAVASCRIPT_MIME_TYPES.add("text/livescript");
        JAVASCRIPT_MIME_TYPES.add("text/x-ecmascript");
        JAVASCRIPT_MIME_TYPES.add("text/x-javascript");
    }

    private static final String[] INTERACTIVE_ELEMENTS = { "a", "button",
            "details", "embed", "iframe", "label", "select", "textarea" };

    private static final String[] INTERACTIVE_ROLES = { "button", "checkbox",
            "combobox", "grid", "gridcell", "listbox", "menu", "menubar",
            "menuitem", "menuitemcheckbox", "menuitemradio", "option", "radio",
            "scrollbar", "searchbox", "slider", "spinbutton", "switch", "tab",
            "textbox", "treeitem" };

    private static final String[] PROHIBITED_INTERACTIVE_ANCESTOR_ROLES = {
            "button", "link" };

    private static final String[] PROHIBITED_MAIN_ANCESTORS = { "a", "address",
            "article", "aside", "audio", "blockquote", "canvas", "caption",
            "dd", "del", "details", "dialog", "dt", "fieldset", "figure",
            "footer", "header", "ins", "li", "main", "map", "nav", "noscript",
            "object", "section", "slot", "td", "th", "video" };

    private static final String[] SPECIAL_ANCESTORS = { "a", "address", "body",
            "button", "caption", "dfn", "dt", "figcaption", "figure", "footer",
            "form", "header", "label", "map", "noscript", "th", "time",
            "progress", "meter", "article", "section", "aside", "nav", "h1",
            "h2", "h3", "h4", "h5", "h6" };

    private static int specialAncestorNumber(String name) {
        for (int i = 0; i < SPECIAL_ANCESTORS.length; i++) {
            if (name == SPECIAL_ANCESTORS[i]) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Integer> ANCESTOR_MASK_BY_DESCENDANT = new HashMap<>();

    private static void registerProhibitedAncestor(String ancestor,
            String descendant) {
        int number = specialAncestorNumber(ancestor);
        if (number == -1) {
            throw new IllegalStateException(
                    Messages.getString("Assertions.Exception.AncestorNotFound") + ancestor); //$NON-NLS-1$
        }
        Integer maskAsObject = ANCESTOR_MASK_BY_DESCENDANT.get(descendant);
        int mask = 0;
        if (maskAsObject != null) {
            mask = maskAsObject.intValue();
        }
        mask |= (1 << number);
        ANCESTOR_MASK_BY_DESCENDANT.put(descendant, Integer.valueOf(mask));
    }

    static {
        registerProhibitedAncestor("form", "form");
        registerProhibitedAncestor("progress", "progress");
        registerProhibitedAncestor("meter", "meter");
        registerProhibitedAncestor("dfn", "dfn");
        registerProhibitedAncestor("noscript", "noscript");
        registerProhibitedAncestor("label", "label");
        registerProhibitedAncestor("address", "address");
        registerProhibitedAncestor("address", "section");
        registerProhibitedAncestor("address", "nav");
        registerProhibitedAncestor("address", "article");
        registerProhibitedAncestor("header", "header");
        registerProhibitedAncestor("footer", "header");
        registerProhibitedAncestor("address", "header");
        registerProhibitedAncestor("header", "footer");
        registerProhibitedAncestor("footer", "footer");
        registerProhibitedAncestor("dt", "header");
        registerProhibitedAncestor("dt", "footer");
        registerProhibitedAncestor("dt", "article");
        registerProhibitedAncestor("dt", "nav");
        registerProhibitedAncestor("dt", "section");
        registerProhibitedAncestor("dt", "h1");
        registerProhibitedAncestor("dt", "h2");
        registerProhibitedAncestor("dt", "h2");
        registerProhibitedAncestor("dt", "h3");
        registerProhibitedAncestor("dt", "h4");
        registerProhibitedAncestor("dt", "h5");
        registerProhibitedAncestor("dt", "h6");
        registerProhibitedAncestor("dt", "hgroup");
        registerProhibitedAncestor("th", "header");
        registerProhibitedAncestor("th", "footer");
        registerProhibitedAncestor("th", "article");
        registerProhibitedAncestor("th", "nav");
        registerProhibitedAncestor("th", "section");
        registerProhibitedAncestor("th", "h1");
        registerProhibitedAncestor("th", "h2");
        registerProhibitedAncestor("th", "h2");
        registerProhibitedAncestor("th", "h3");
        registerProhibitedAncestor("th", "h4");
        registerProhibitedAncestor("th", "h5");
        registerProhibitedAncestor("th", "h6");
        registerProhibitedAncestor("th", "hgroup");
        registerProhibitedAncestor("address", "footer");
        registerProhibitedAncestor("address", "h1");
        registerProhibitedAncestor("address", "h2");
        registerProhibitedAncestor("address", "h3");
        registerProhibitedAncestor("address", "h4");
        registerProhibitedAncestor("address", "h5");
        registerProhibitedAncestor("address", "h6");
        registerProhibitedAncestor("caption", "table");

        for (String elementName : INTERACTIVE_ELEMENTS) {
            registerProhibitedAncestor("a", elementName);
            registerProhibitedAncestor("button", elementName);
        }
    }

    private static final int BODY_MASK = (1 << specialAncestorNumber("body"));

    private static final int A_BUTTON_MASK = (1 << specialAncestorNumber("a"))
            | (1 << specialAncestorNumber("button"));

    private static final int FIGCAPTION_MASK = (1 << specialAncestorNumber(
            "figcaption"));

    private static final int FIGURE_MASK = (1 << specialAncestorNumber(
            "figure"));

    private static final int H1_MASK = (1 << specialAncestorNumber("h1"));

    private static final int H2_MASK = (1 << specialAncestorNumber("h2"));

    private static final int H3_MASK = (1 << specialAncestorNumber("h3"));

    private static final int H4_MASK = (1 << specialAncestorNumber("h4"));

    private static final int H5_MASK = (1 << specialAncestorNumber("h5"));

    private static final int H6_MASK = (1 << specialAncestorNumber("h6"));

    private static final int MAP_MASK = (1 << specialAncestorNumber("map"));

    private static final int HREF_MASK = (1 << 30);

    private static final int LABEL_FOR_MASK = (1 << 29);

    private static final Map<String, Set<String>> REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT = new HashMap<>();

    private static final Map<String, Set<String>> ariaOwnsIdsByRole = new HashMap<>();

    private static void registerRequiredAncestorRole(String parent,
            String child) {
        Set<String> parents = REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.get(child);
        if (parents == null) {
            parents = new HashSet<>();
        }
        parents.add(parent);
        REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.put(child, parents);
    }

    static {
        registerRequiredAncestorRole("combobox", "option");
        registerRequiredAncestorRole("listbox", "option");
        registerRequiredAncestorRole("radiogroup", "option");
        registerRequiredAncestorRole("menu", "option");
        registerRequiredAncestorRole("menu", "menuitem");
        registerRequiredAncestorRole("menu", "menuitemcheckbox");
        registerRequiredAncestorRole("menu", "menuitemradio");
        registerRequiredAncestorRole("menubar", "menuitem");
        registerRequiredAncestorRole("menubar", "menuitemcheckbox");
        registerRequiredAncestorRole("menubar", "menuitemradio");
        registerRequiredAncestorRole("tablist", "tab");
        registerRequiredAncestorRole("tree", "treeitem");
        registerRequiredAncestorRole("tree", "option");
        registerRequiredAncestorRole("group", "treeitem");
        registerRequiredAncestorRole("group", "listitem");
        registerRequiredAncestorRole("group", "menuitemradio");
        registerRequiredAncestorRole("list", "listitem");
        registerRequiredAncestorRole("row", "cell");
        registerRequiredAncestorRole("row", "gridcell");
        registerRequiredAncestorRole("row", "columnheader");
        registerRequiredAncestorRole("row", "rowheader");
        registerRequiredAncestorRole("grid", "row");
        registerRequiredAncestorRole("grid", "rowgroup");
        registerRequiredAncestorRole("rowgroup", "row");
        registerRequiredAncestorRole("treegrid", "row");
        registerRequiredAncestorRole("treegrid", "rowgroup");
        registerRequiredAncestorRole("table", "rowgroup");
        registerRequiredAncestorRole("table", "row");
    }

    private static final Set<String> MUST_NOT_DANGLE_IDREFS = new HashSet<>();

    static {
        MUST_NOT_DANGLE_IDREFS.add("aria-controls");
        MUST_NOT_DANGLE_IDREFS.add("aria-describedby");
        MUST_NOT_DANGLE_IDREFS.add("aria-flowto");
        MUST_NOT_DANGLE_IDREFS.add("aria-labelledby");
        MUST_NOT_DANGLE_IDREFS.add("aria-owns");
    }

    private static final Map<String, String> ELEMENTS_WITH_IMPLICIT_ROLE = new HashMap<>();

    static {
        ELEMENTS_WITH_IMPLICIT_ROLE.put("a", "link");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("address", "contentinfo");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("area", "link");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("article", "article");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("aside", "complementary");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("body", "document");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("button", "button");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("datalist", "listbox");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("dd", "definition");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("details", "group");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("dialog", "dialog");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("dt", "term");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("fieldset", "group");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("figure", "figure");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("form", "form");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("footer", "contentinfo");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h1", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h2", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h3", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h4", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h5", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("h6", "heading");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("header", "banner");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("img", "img");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("li", "listitem");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("link", "link");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("main", "main");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("math", "math");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("menu", "menu");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("nav", "navigation");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("ol", "list");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("optgroup", "group");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("option", "option");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("output", "status");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("progress", "progressbar");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("section", "region");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("select", "listbox");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("summary", "button");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("table", "table");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("tbody", "rowgroup");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("textarea", "textbox");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("tfoot", "rowgroup");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("thead", "rowgroup");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("td", "cell");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("tr", "row");
        ELEMENTS_WITH_IMPLICIT_ROLE.put("ul", "list");
    }

    private static final Map<String, String[]> //
        ELEMENTS_WITH_IMPLICIT_ROLES = new HashMap<>();

    static {
        ELEMENTS_WITH_IMPLICIT_ROLES.put("th",
                new String[] { "columnheader", "rowheader" });
    }

    private static final Map<String, String> ELEMENTS_THAT_NEVER_NEED_ROLE = new HashMap<>();

    static {
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("body", "document");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("datalist", "listbox");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("details", "group");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("form", "form");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("hr", "separator");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("main", "main");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("math", "math");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("meter", "progressbar");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("nav", "navigation");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("option", "option");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("progress", "progressbar");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("select", "listbox");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("summary", "button");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("textarea", "textbox");
    }

    private static final Map<String, String> INPUT_TYPES_WITH_IMPLICIT_ROLE = new HashMap<>();

    static {
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("button", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("checkbox", "checkbox");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("image", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("number", "spinbutton");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("radio", "radio");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("range", "slider");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("reset", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("submit", "button");
    }

    private static final Set<String> ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY = new HashSet<>();

    static {
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("disabled");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("hidden");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("readonly");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("required");
    }

    private static final String h1WarningMessage =
            Messages.getString("Assertions.Warn.h1Message"); //$NON-NLS-1$

    private class IdrefLocator {
        private final Locator locator;

        private final String idref;

        private final String additional;

        /**
         * @param locator
         * @param idref
         */
        public IdrefLocator(Locator locator, String idref) {
            this.locator = new LocatorImpl(locator);
            this.idref = idref;
            this.additional = null;
        }

        public IdrefLocator(Locator locator, String idref, String additional) {
            this.locator = new LocatorImpl(locator);
            this.idref = idref;
            this.additional = additional;
        }

        /**
         * Returns the locator.
         *
         * @return the locator
         */
        public Locator getLocator() {
            return locator;
        }

        /**
         * Returns the idref.
         *
         * @return the idref
         */
        public String getIdref() {
            return idref;
        }

        /**
         * Returns the additional.
         *
         * @return the additional
         */
        public String getAdditional() {
            return additional;
        }
    }

    private class StackNode {
        private final int ancestorMask;

        private final String name; // null if not HTML

        private final StringBuilder textContent;

        private final String role;

        private final String activeDescendant;

        private final String forAttr;

        private Set<Locator> imagesLackingAlt = new HashSet<>();

        private Locator nonEmptyOption = null;

        private Locator locator = null;

        private boolean selectedOptions = false;

        private boolean labeledDescendants = false;

        private boolean trackDescendants = false;

        private boolean textNodeFound = false;

        private boolean imgFound = false;

        private boolean embeddedContentFound = false;

        private boolean figcaptionNeeded = false;

        private boolean figcaptionContentFound = false;

        private boolean headingFound = false;

        private boolean optionNeeded = false;

        private boolean optionFound = false;

        private boolean noValueOptionFound = false;

        private boolean emptyValueOptionFound = false;

        private boolean isCollectingCharacters = false;

        /**
         * @param ancestorMask
         */
        public StackNode(int ancestorMask, String name, String role,
                String activeDescendant, String forAttr) {
            this.ancestorMask = ancestorMask;
            this.name = name;
            this.role = role;
            this.activeDescendant = activeDescendant;
            this.forAttr = forAttr;
            this.textContent = new StringBuilder();
        }

        /**
         * Returns the ancestorMask.
         *
         * @return the ancestorMask
         */
        public int getAncestorMask() {
            return ancestorMask;
        }

        /**
         * Returns the name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the selectedOptions.
         *
         * @return the selectedOptions
         */
        public boolean isSelectedOptions() {
            return selectedOptions;
        }

        /**
         * Sets the selectedOptions.
         *
         * @param selectedOptions
         *            the selectedOptions to set
         */
        public void setSelectedOptions() {
            this.selectedOptions = true;
        }

        /**
         * Returns the labeledDescendants.
         *
         * @return the labeledDescendants
         */
        public boolean isLabeledDescendants() {
            return labeledDescendants;
        }

        /**
         * Sets the labeledDescendants.
         *
         * @param labeledDescendants
         *            the labeledDescendants to set
         */
        public void setLabeledDescendants() {
            this.labeledDescendants = true;
        }

        /**
         * Returns the trackDescendants.
         *
         * @return the trackDescendants
         */
        public boolean isTrackDescendant() {
            return trackDescendants;
        }

        /**
         * Sets the trackDescendants.
         *
         * @param trackDescendants
         *            the trackDescendants to set
         */
        public void setTrackDescendants() {
            this.trackDescendants = true;
        }

        /**
         * Returns the role.
         *
         * @return the role
         */
        public String getRole() {
            return role;
        }

        /**
         * Returns the activeDescendant.
         *
         * @return the activeDescendant
         */
        public String getActiveDescendant() {
            return activeDescendant;
        }

        /**
         * Returns the forAttr.
         *
         * @return the forAttr
         */
        public String getForAttr() {
            return forAttr;
        }

        /**
         * Returns the textNodeFound.
         *
         * @return the textNodeFound
         */
        public boolean hasTextNode() {
            return textNodeFound;
        }

        /**
         * Sets the textNodeFound.
         */
        public void setTextNodeFound() {
            this.textNodeFound = true;
        }

        /**
         * Returns the imgFound.
         *
         * @return the imgFound
         */
        public boolean hasImg() {
            return imgFound;
        }

        /**
         * Sets the imgFound.
         */
        public void setImgFound() {
            this.imgFound = true;
        }

        /**
         * Returns the embeddedContentFound.
         *
         * @return the embeddedContentFound
         */
        public boolean hasEmbeddedContent() {
            return embeddedContentFound;
        }

        /**
         * Sets the embeddedContentFound.
         */
        public void setEmbeddedContentFound() {
            this.embeddedContentFound = true;
        }

        /**
         * Returns the figcaptionNeeded.
         *
         * @return the figcaptionNeeded
         */
        public boolean needsFigcaption() {
            return figcaptionNeeded;
        }

        /**
         * Sets the figcaptionNeeded.
         */
        public void setFigcaptionNeeded() {
            this.figcaptionNeeded = true;
        }

        /**
         * Returns the figcaptionContentFound.
         *
         * @return the figcaptionContentFound
         */
        public boolean hasFigcaptionContent() {
            return figcaptionContentFound;
        }

        /**
         * Sets the figcaptionContentFound.
         */
        public void setFigcaptionContentFound() {
            this.figcaptionContentFound = true;
        }

        /**
         * Returns the headingFound.
         *
         * @return the headingFound
         */
        public boolean hasHeading() {
            return headingFound;
        }

        /**
         * Sets the headingFound.
         */
        public void setHeadingFound() {
            this.headingFound = true;
        }

        /**
         * Returns the imagesLackingAlt
         *
         * @return the imagesLackingAlt
         */
        public Set<Locator> getImagesLackingAlt() {
            return imagesLackingAlt;
        }

        /**
         * Adds to the imagesLackingAlt
         */
        public void addImageLackingAlt(Locator locator) {
            this.imagesLackingAlt.add(locator);
        }

        /**
         * Returns the optionNeeded.
         *
         * @return the optionNeeded
         */
        public boolean isOptionNeeded() {
            return optionNeeded;
        }

        /**
         * Sets the optionNeeded.
         */
        public void setOptionNeeded() {
            this.optionNeeded = true;
        }

        /**
         * Returns the optionFound.
         *
         * @return the optionFound
         */
        public boolean hasOption() {
            return optionFound;
        }

        /**
         * Sets the optionFound.
         */
        public void setOptionFound() {
            this.optionFound = true;
        }

        /**
         * Returns the noValueOptionFound.
         *
         * @return the noValueOptionFound
         */
        public boolean hasNoValueOption() {
            return noValueOptionFound;
        }

        /**
         * Sets the noValueOptionFound.
         */
        public void setNoValueOptionFound() {
            this.noValueOptionFound = true;
        }

        /**
         * Returns the emptyValueOptionFound.
         *
         * @return the emptyValueOptionFound
         */
        public boolean hasEmptyValueOption() {
            return emptyValueOptionFound;
        }

        /**
         * Sets the emptyValueOptionFound.
         */
        public void setEmptyValueOptionFound() {
            this.emptyValueOptionFound = true;
        }

        /**
         * Returns the nonEmptyOption.
         *
         * @return the nonEmptyOption
         */
        public Locator nonEmptyOptionLocator() {
            return nonEmptyOption;
        }

        /**
         * Sets the nonEmptyOption.
         */
        public void setNonEmptyOption(Locator locator) {
            this.nonEmptyOption = locator;
        }

        /**
         * Sets the collectingCharacters.
         */
        public void setIsCollectingCharacters(boolean isCollectingCharacters) {
            this.isCollectingCharacters = isCollectingCharacters;
        }

        /**
         * Gets the collectingCharacters.
         */
        public boolean getIsCollectingCharacters() {
            return this.isCollectingCharacters;
        }

        /**
         * Appends to the textContent.
         */
        public void appendToTextContent(char ch[], int start, int length) {
            this.textContent.append(ch, start, length);
        }

        /**
         * Gets the textContent.
         */
        public StringBuilder getTextContent() {
            return this.textContent;
        }

        /**
         * Returns the locator.
         *
         * @return the locator
         */
        public Locator locator() {
            return locator;
        }

        /**
         * Sets the locator.
         */
        public void setLocator(Locator locator) {
            this.locator = locator;
        }

    }

    private StackNode[] stack;

    private int currentPtr;

    private int currentSectioningDepth;

    public Assertions() {
        super();
    }

    private HttpServletRequest request;

    private boolean sourceIsCss;

    public void setSourceIsCss(boolean sourceIsCss) {
        this.sourceIsCss = sourceIsCss;
    }

    private boolean hasPageEmitterInCallStack() {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            if (el.getClassName().equals("nu.validator.servlet.PageEmitter")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAriaLabelMisuse(String ariaLabel, String localName,
            String role, Attributes atts) {
        if (ariaLabel == null) {
            return false;
        }
        if (Arrays.binarySearch(INTERACTIVE_ELEMENTS, localName) >= 0) {
            return false;
        }
        if (isLabelableElement(localName, atts)) {
            return false;
        }
        // https://developer.paciellogroup.com/blog/2017/07/short-note-on-aria-label-aria-labelledby-and-aria-describedby/
        if ("main" == localName //
                || "nav" == localName //
                || "table" == localName //
                || "td" == localName //
                || "th" == localName //
                || "aside" == localName //
                || "header" == localName //
                || "footer" == localName //
                || "section" == localName //
                || "article" == localName //
                || "form" == localName //
                || "img" == localName //
                || "audio" == localName //
                || "video" == localName //
                // https://github.com/validator/validator/issues/775#issuecomment-494455608
                || "area" == localName //
                || "fieldset" == localName //
                || "summary" == localName //
                || "figure" == localName //
        ) {
            return false;
        }
        if (role != null) {
            return false;
        }
        return true;
    }

    private boolean isLabelableElement(String localName, Attributes atts) {
        if ("button" == localName //
                || ("input" == localName && !AttributeUtil //
                        .lowerCaseLiteralEqualsIgnoreAsciiCaseString("hidden",
                                atts.getValue("", "type"))) //
                || "meter" == localName //
                || "output" == localName //
                || "progress" == localName //
                || "select" == localName //
                || "textarea" == localName) {
            return true;
        }
        return false;
    }

    private void incrementUseCounter(String useCounterName) {
        if (request != null) {
            request.setAttribute(
                    "http://validator.nu/properties/" + useCounterName, true);
        }
    }

    private void push(StackNode node) {
        currentPtr++;
        if (currentPtr == stack.length) {
            StackNode[] newStack = new StackNode[stack.length + 64];
            System.arraycopy(stack, 0, newStack, 0, stack.length);
            stack = newStack;
        }
        stack[currentPtr] = node;
    }

    private StackNode pop() {
        return stack[currentPtr--];
    }

    private StackNode peek() {
        return stack[currentPtr];
    }

    private Map<StackNode, Locator> openSingleSelects = new HashMap<>();

    private Map<StackNode, Locator> openLabels = new HashMap<>();

    private Map<StackNode, TaintableLocatorImpl> openMediaElements = new HashMap<>();

    private Map<StackNode, Locator> openActiveDescendants = new HashMap<>();

    private LinkedHashSet<IdrefLocator> formControlReferences = new LinkedHashSet<>();

    private LinkedHashSet<IdrefLocator> formElementReferences = new LinkedHashSet<>();

    private LinkedHashSet<IdrefLocator> needsAriaOwner = new LinkedHashSet<>();

    private Set<String> formControlIds = new HashSet<>();

    private Set<String> formElementIds = new HashSet<>();

    private LinkedHashSet<IdrefLocator> listReferences = new LinkedHashSet<>();

    private Set<String> listIds = new HashSet<>();

    private LinkedHashSet<IdrefLocator> ariaReferences = new LinkedHashSet<>();

    private Set<String> allIds = new HashSet<>();

    private int currentFigurePtr;

    private int currentHeadingPtr;

    private int currentSectioningElementPtr;

    private boolean hasVisibleMain;

    private boolean hasMetaCharset;

    private boolean hasMetaDescription;

    private boolean hasContentTypePragma;

    private boolean hasAutofocus;

    private boolean hasTopLevelH1;

    private int numberOfTemplatesDeep = 0;

    private Set<Locator> secondLevelH1s = new HashSet<>();

    private Map<Locator, Map<String, String>> siblingSources = new ConcurrentHashMap<>();

    private final void errContainedInOrOwnedBy(String role, Locator locator)
            throws SAXException {
        err(String.format(
                Messages.getString("Assertions.Error.ContainedInOrOwnedBy"), //$NON-NLS-1$
                role,
                renderRoleSet(REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.get(role))),
                locator);
    }

    private final void errObsoleteAttribute(String attribute, String element,
            String suggestion) throws SAXException {
        err(String.format(
                Messages.getString("Assertions.ObsoleteAttributeMessage"), //$NON-NLS-1$
                attribute, element, suggestion));
    }

    private final void warnObsoleteAttribute(String attribute, String element,
            String suggestion) throws SAXException {
        warn(String.format(
                Messages.getString("Assertions.ObsoleteAttributeMessage"), //$NON-NLS-1$
                attribute, element, suggestion));
    }

    private final void warnExplicitRoleUnnecessaryForType(String element,
            String role, String type) throws SAXException {
        warn(String.format(
                Messages.getString("Assertions.Warn.ExplicitRoleUnnecessaryForType"), //$NON-NLS-1$
                role, element, type));
    }

    private boolean currentElementHasRequiredAncestorRole(
            Set<String> requiredAncestorRoles) {
        for (String role : requiredAncestorRoles) {
            for (int i = 0; i < currentPtr; i++) {
                if (role.equals(stack[currentPtr - i].getRole())) {
                    return true;
                }
                String openElementName = stack[currentPtr - i].getName();
                if (ELEMENTS_WITH_IMPLICIT_ROLE.containsKey(openElementName)
                        && ELEMENTS_WITH_IMPLICIT_ROLE.get(openElementName) //
                                .equals(role)) {
                    return true;
                }
                if (ELEMENTS_WITH_IMPLICIT_ROLES.containsKey(openElementName)
                        && Arrays.binarySearch(ELEMENTS_WITH_IMPLICIT_ROLES //
                                .get(openElementName), role) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkForInteractiveAncestorRole(String descendantUiString)
            throws SAXException {
        for (int i = 0; i < currentPtr; i++) {
            String ancestorRole = stack[currentPtr - i].getRole();
            if (ancestorRole != null && ancestorRole != ""
                    && Arrays.binarySearch(
                            PROHIBITED_INTERACTIVE_ANCESTOR_ROLES,
                            ancestorRole) >= 0) {
                err(String.format(
                        Messages.getString("Assertions.Error.InteractiveAncestorRole"), //$NON-NLS-1$
                        descendantUiString, ancestorRole));
            }
        }
    }

    /**
     * @see nu.validator.checker.Checker#endDocument()
     */
    @Override
    public void endDocument() throws SAXException {
        // label for
        for (IdrefLocator idrefLocator : formControlReferences) {
            if (!formControlIds.contains(idrefLocator.getIdref())) {
                err(Messages.getString("Assertions.Error.Idref"), //$NON-NLS-1$
                        idrefLocator.getLocator());
            }
        }

        // references to IDs from form attributes
        for (IdrefLocator idrefLocator : formElementReferences) {
            if (!formElementIds.contains(idrefLocator.getIdref())) {
                err(Messages.getString("Assertions.Error.FormAttr"), //$NON-NLS-1$
                        idrefLocator.getLocator());
            }
        }

        // input list
        for (IdrefLocator idrefLocator : listReferences) {
            if (!listIds.contains(idrefLocator.getIdref())) {
                err(Messages.getString("Assertions.Error.InputListAttr"), //$NON-NLS-1$
                        idrefLocator.getLocator());
            }
        }

        // ARIA idrefs
        for (IdrefLocator idrefLocator : ariaReferences) {
            if (!allIds.contains(idrefLocator.getIdref())) {
                err(String.format(
                        Messages.getString("Assertions.Error.ARIAIdrefs"), idrefLocator.getAdditional()), //$NON-NLS-1$
                        idrefLocator.getLocator());
            }
        }

        // ARIA required owners
        for (IdrefLocator idrefLocator : needsAriaOwner) {
            boolean foundOwner = false;
            String role = idrefLocator.getAdditional();
            for (String ownerRole : REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.get(
                    role)) {
                if (ariaOwnsIdsByRole.size() != 0
                        && ariaOwnsIdsByRole.get(ownerRole) != null
                        && ariaOwnsIdsByRole.get(ownerRole).contains(
                                idrefLocator.getIdref())) {
                    foundOwner = true;
                    break;
                }
            }
            if (!foundOwner) {
                errContainedInOrOwnedBy(role, idrefLocator.getLocator());
            }
        }

        if (hasTopLevelH1) {
            for (Locator locator : secondLevelH1s) {
                warn(h1WarningMessage, locator);
            }
        }

        reset();
        stack = null;
    }

    private static double getDoubleAttribute(Attributes atts, String name) {
        String str = atts.getValue("", name);
        if (str == null) {
            return Double.NaN;
        } else {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
    }

    /**
     * @see nu.validator.checker.Checker#endElement(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    public void endElement(String uri, String localName, String name)
            throws SAXException {
        if ("http://www.w3.org/1999/xhtml" == uri
                && "template".equals(localName)) {
            numberOfTemplatesDeep--;
            if (numberOfTemplatesDeep != 0) {
                return;
            }
        } else if (numberOfTemplatesDeep > 0) {
            return;
        }
        StackNode node = pop();
        String systemId = node.locator().getSystemId();
        String publicId = node.locator().getPublicId();
        Locator locator = null;
        openSingleSelects.remove(node);
        openLabels.remove(node);
        openMediaElements.remove(node);
        if ("http://www.w3.org/1999/xhtml" == uri) {
            if ("figure" == localName) {
                if ((node.needsFigcaption() && !node.hasFigcaptionContent())
                        || node.hasTextNode() || node.hasEmbeddedContent()) {
                    for (Locator imgLocator : node.getImagesLackingAlt()) {
                        err(Messages.getString("Assertions.Error.Img.NoAltAttr"), imgLocator); //$NON-NLS-1$
                    }
                }
            } else if ("picture" == localName) {
                siblingSources.clear();
            } else if ("select" == localName && node.isOptionNeeded()) {
                if (!node.hasOption()) {
                    err(Messages.getString("Assertions.Error.Select.NoOptionElement")); //$NON-NLS-1$
                }
                if (node.nonEmptyOptionLocator() != null) {
                    err(Messages.getString("Assertions.Error.Select.EmptyOptionElement"), //$NON-NLS-1$
                            node.nonEmptyOptionLocator());
                }
            } else if ("section" == localName && !node.hasHeading()) {
                warn(Messages.getString("Assertions.Warn.Section"), node.locator()); //$NON-NLS-1$
            } else if ("article" == localName && !node.hasHeading()) {
                warn(Messages.getString("Assertions.Warn.Article"), node.locator()); //$NON-NLS-1$
            } else if (("h1" == localName || "h2" == localName
                    || "h3" == localName || "h4" == localName
                    || "h5" == localName || "h6" == localName)
                    && !node.hasTextNode() && !node.hasImg()) {
                warn(Messages.getString("Assertions.Warn.EmptyHeading"), node.locator()); //$NON-NLS-1$
            } else if ("option" == localName
                    && !stack[currentPtr].hasOption()) {
                stack[currentPtr].setOptionFound();
            } else if ("style" == localName) {
                String styleContents = node.getTextContent().toString();
                int lineOffset = 0;
                if (styleContents.startsWith("\n")) {
                    lineOffset = 1;
                }
                ApplContext ac = new ApplContext("en");
                ac.setCssVersionAndProfile("css3svg");
                ac.setMedium("all");
                ac.setSuggestPropertyName(false);
                ac.setTreatVendorExtensionsAsWarnings(true);
                ac.setTreatCssHacksAsWarnings(true);
                ac.setWarningLevel(-1);
                ac.setFakeURL("file://localhost/StyleElement");
                StyleSheetParser styleSheetParser = new StyleSheetParser();
                styleSheetParser.parseStyleSheet(ac,
                        new StringReader(styleContents.substring(lineOffset)),
                        null);
                styleSheetParser.getStyleSheet().findConflicts(ac);
                Errors errors = styleSheetParser.getStyleSheet().getErrors();
                if (errors.getErrorCount() > 0) {
                    incrementUseCounter("style-element-errors-found");
                }
                for (int i = 0; i < errors.getErrorCount(); i++) {
                    String message = "";
                    String cssProperty = "";
                    String cssMessage = "";
                    CssError error = errors.getErrorAt(i);
                    int beginLine = error.getBeginLine() + lineOffset;
                    int beginColumn = error.getBeginColumn();
                    int endLine = error.getEndLine() + lineOffset;
                    int endColumn = error.getEndColumn();
                    if (beginLine == 0) {
                        continue;
                    }
                    Throwable ex = error.getException();
                    if (ex instanceof CssParseException) {
                        CssParseException cpe = (CssParseException) ex;
                        if ("generator.unrecognize" //
                                .equals(cpe.getErrorType())) {
                            cssMessage = Messages.getString("Assertions.Error.ParseError"); //$NON-NLS-1$
                        }
                        if (cpe.getProperty() != null) {
                            cssProperty = String.format("\u201c%s\u201D: ", cpe.getProperty());
                        }
                        if (cpe.getMessage() != null) {
                            cssMessage = cpe.getMessage();
                        }
                        if (!"".equals(cssMessage)) {
                            message = cssProperty + cssMessage + ".";
                        }
                    } else {
                        message = ex.getMessage();
                    }
                    if (!"".equals(message)) {
                        int lastLine = node.locator.getLineNumber() //
                                + endLine - 1;
                        int lastColumn = endColumn;
                        int columnOffset = node.locator.getColumnNumber();
                        if (error.getBeginLine() == 1) {
                            if (lineOffset != 0) {
                                columnOffset = 0;
                            }
                        } else {
                            columnOffset = 0;
                        }
                        String prefix = sourceIsCss ? "" : "CSS: ";
                        SAXParseException spe = new SAXParseException( //
                                prefix + message, publicId, systemId, //
                                lastLine, lastColumn);
                        int[] start = {
                                node.locator.getLineNumber() + beginLine - 1,
                                beginColumn, columnOffset };
                        if ((getErrorHandler() instanceof MessageEmitterAdapter)
                                && !(getErrorHandler() instanceof TestRunner)) {
                            ((MessageEmitterAdapter) getErrorHandler()) //
                                    .errorWithStart(spe, start);
                        } else {
                            getErrorHandler().error(spe);
                        }
                    }
                }
            }
            if ("article" == localName || "aside" == localName
                    || "nav" == localName || "section" == localName) {
                currentSectioningElementPtr = currentPtr - 1;
                currentSectioningDepth--;
            }
        }
        if ((locator = openActiveDescendants.remove(node)) != null) {
            warn(Messages.getString("Assertions.Warn.AriaActivedescendantAttr"), locator); //$NON-NLS-1$
        }
    }

    /**
     * @see nu.validator.checker.Checker#startDocument()
     */
    @Override
    public void startDocument() throws SAXException {
        reset();
        request = getRequest();
        stack = new StackNode[32];
        currentPtr = 0;
        currentFigurePtr = -1;
        currentHeadingPtr = -1;
        currentSectioningElementPtr = -1;
        currentSectioningDepth = 0;
        stack[0] = null;
        hasVisibleMain = false;
        hasMetaCharset = false;
        hasMetaDescription = false;
        hasContentTypePragma = false;
        hasAutofocus = false;
        hasTopLevelH1 = false;
        numberOfTemplatesDeep = 0;
    }

    @Override
    public void reset() {
        openSingleSelects.clear();
        openLabels.clear();
        openMediaElements.clear();
        openActiveDescendants.clear();
        ariaOwnsIdsByRole.clear();
        needsAriaOwner.clear();
        formControlReferences.clear();
        formElementReferences.clear();
        formControlIds.clear();
        formElementIds.clear();
        listReferences.clear();
        listIds.clear();
        ariaReferences.clear();
        allIds.clear();
        siblingSources.clear();
        secondLevelH1s.clear();
    }

    /**
     * @see nu.validator.checker.Checker#startElement(java.lang.String,
     *      java.lang.String, java.lang.String, org.xml.sax.Attributes)
     */
    @Override
    public void startElement(String uri, String localName, String name,
            Attributes atts) throws SAXException {
        if ("http://www.w3.org/1999/xhtml" == uri
                && "template".equals(localName)) {
            numberOfTemplatesDeep++;
            if (numberOfTemplatesDeep != 1) {
                return;
            }
        } else if (numberOfTemplatesDeep > 0) {
            return;
        }
        Set<String> ids = new HashSet<>();
        String role = null;
        String inputTypeVal = null;
        String activeDescendant = null;
        String ariaLabel = null;
        String owns = null;
        String forAttr = null;
        boolean href = false;
        boolean activeDescendantWithAriaOwns = false;
        // see nu.validator.datatype.ImageCandidateStrings
        System.setProperty("nu.validator.checker.imageCandidateString.hasWidth",
                "0");

        StackNode parent = peek();
        int ancestorMask = 0;
        String parentRole = null;
        String parentName = null;
        if (parent != null) {
            ancestorMask = parent.getAncestorMask();
            parentName = parent.getName();
            parentRole = parent.getRole();
        }
        if ("http://www.w3.org/1999/xhtml" == uri) {
            boolean controls = false;
            boolean hidden = false;
            boolean toolbar = false;
            boolean usemap = false;
            boolean ismap = false;
            boolean selected = false;
            boolean itemid = false;
            boolean itemref = false;
            boolean itemscope = false;
            boolean itemtype = false;
            boolean tabindex = false;
            boolean languageJavaScript = false;
            boolean typeNotTextJavaScript = false;
            String xmlLang = null;
            String lang = null;
            String id = null;
            String list = null;

            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                String attUri = atts.getURI(i);
                if (attUri.length() == 0) {
                    String attLocal = atts.getLocalName(i);
                    if ("embed".equals(localName)) {
                        for (int j = 0; j < attLocal.length(); j++) {
                            char c = attLocal.charAt(j);
                            if (c >= 'A' && c <= 'Z') {
                                err(String.format(
                                        Messages.getString("Assertions.Error.BadAttrName.UpperASCIILetters"), //$NON-NLS-1$
                                        attLocal));
                            }
                        }
                        if (!NCName.isNCName(attLocal)) {
                            err(String.format(
                                    Messages.getString("Assertions.Error.BadAttrName.NotXMLCompatible"), //$NON-NLS-1$
                                    attLocal));
                        }
                    }
                    if ("style" == attLocal) {
                        String styleContents = atts.getValue(i);
                        ApplContext ac = new ApplContext("en");
                        ac.setCssVersionAndProfile("css3svg");
                        ac.setMedium("all");
                        ac.setSuggestPropertyName(false);
                        ac.setTreatVendorExtensionsAsWarnings(true);
                        ac.setTreatCssHacksAsWarnings(true);
                        ac.setWarningLevel(-1);
                        ac.setFakeURL("file://localhost/StyleAttribute");
                        StyleSheetParser styleSheetParser = //
                                new StyleSheetParser();
                        styleSheetParser.parseStyleAttribute(ac,
                                new ByteArrayInputStream(
                                        styleContents.getBytes()),
                                "", ac.getFakeURL(),
                                getDocumentLocator().getLineNumber());
                        styleSheetParser.getStyleSheet().findConflicts(ac);
                        Errors errors = //
                                styleSheetParser.getStyleSheet().getErrors();
                        if (errors.getErrorCount() > 0) {
                            incrementUseCounter("style-attribute-errors-found");
                        }
                        for (int j = 0; j < errors.getErrorCount(); j++) {
                            String message = "";
                            String cssProperty = "";
                            String cssMessage = "";
                            CssError error = errors.getErrorAt(j);
                            Throwable ex = error.getException();
                            if (ex instanceof CssParseException) {
                                CssParseException cpe = (CssParseException) ex;
                                if ("generator.unrecognize" //
                                        .equals(cpe.getErrorType())) {
                                    cssMessage = Messages.getString("Assertions.Error.ParseError"); //$NON-NLS-1$
                                }
                                if (cpe.getProperty() != null) {
                                    cssProperty = String.format(
                                            "\u201c%s\u201D: ",
                                            cpe.getProperty());
                                }
                                if (cpe.getMessage() != null) {
                                    cssMessage = cpe.getMessage();
                                }
                                if (!"".equals(cssMessage)) {
                                    message = cssProperty + cssMessage + ".";
                                }
                            } else {
                                message = ex.getMessage();
                            }
                            if (!"".equals(message)) {
                                err("CSS: " + message);
                            }
                        }
                    } else if ("tabindex" == attLocal) {
                        tabindex = true;
                    } else if ("href" == attLocal) {
                        href = true;
                    } else if ("controls" == attLocal) {
                        controls = true;
                    } else if ("type" == attLocal && "param" != localName
                            && "ol" != localName && "ul" != localName
                            && "li" != localName) {
                        if ("input" == localName) {
                            inputTypeVal = atts.getValue(i).toLowerCase();
                        }
                        String attValue = atts.getValue(i);
                        if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                "hidden", attValue)) {
                            hidden = true;
                        } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                "toolbar", attValue)) {
                            toolbar = true;
                        }

                        if (!AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                "text/javascript", attValue)) {
                            typeNotTextJavaScript = true;
                        }
                    } else if ("role" == attLocal) {
                        role = atts.getValue(i);
                    } else if ("aria-activedescendant" == attLocal) {
                        activeDescendant = atts.getValue(i);
                    } else if ("aria-label" == attLocal) {
                        ariaLabel = atts.getValue(i);
                    } else if ("aria-owns" == attLocal) {
                        owns = atts.getValue(i);
                    } else if ("list" == attLocal) {
                        list = atts.getValue(i);
                    } else if ("lang" == attLocal) {
                        lang = atts.getValue(i);
                    } else if ("id" == attLocal) {
                        id = atts.getValue(i);
                    } else if ("for" == attLocal && "label" == localName) {
                        forAttr = atts.getValue(i);
                        ancestorMask |= LABEL_FOR_MASK;
                    } else if ("ismap" == attLocal) {
                        ismap = true;
                    } else if ("selected" == attLocal) {
                        selected = true;
                    } else if ("usemap" == attLocal && "input" != localName) {
                        usemap = true;
                    } else if ("itemid" == attLocal) {
                        itemid = true;
                    } else if ("itemref" == attLocal) {
                        itemref = true;
                    } else if ("itemscope" == attLocal) {
                        itemscope = true;
                    } else if ("itemtype" == attLocal) {
                        itemtype = true;
                    } else if ("language" == attLocal
                            && AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                    "javascript", atts.getValue(i))) {
                        languageJavaScript = true;
                    } else if ("rev" == attLocal
                            && !("1".equals(System.getProperty(
                                    "nu.validator.schema.rdfa-full")))) {
                        errObsoleteAttribute("rev", localName,
                                Messages.getString("Assertions.Error.Misuse.OppositeMeaning")); //$NON-NLS-1$
                    } else if (OBSOLETE_ATTRIBUTES.containsKey(attLocal)
                            && "ol" != localName && "ul" != localName
                            && "li" != localName) {
                        String[] elementNames = OBSOLETE_ATTRIBUTES.get(
                                attLocal);
                        Arrays.sort(elementNames);
                        if (Arrays.binarySearch(elementNames, localName) >= 0) {
                            String suggestion = OBSOLETE_ATTRIBUTES_MSG.containsKey(
                                    attLocal)
                                            ? " " + OBSOLETE_ATTRIBUTES_MSG.get(
                                                    attLocal)
                                            : "";
                            errObsoleteAttribute(attLocal, localName,
                                    suggestion);
                        }
                    } else if (OBSOLETE_STYLE_ATTRS.containsKey(attLocal)) {
                        String[] elementNames = OBSOLETE_STYLE_ATTRS.get(
                                attLocal);
                        Arrays.sort(elementNames);
                        if (Arrays.binarySearch(elementNames, localName) >= 0) {
                            errObsoleteAttribute(attLocal, localName,
                                    Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
                        }
                    } else if (INPUT_ATTRIBUTES.containsKey(attLocal)
                            && "input" == localName) {
                        String[] allowedTypes = INPUT_ATTRIBUTES.get(attLocal);
                        Arrays.sort(allowedTypes);
                        inputTypeVal = inputTypeVal == null ? "text"
                                : inputTypeVal;
                        if (Arrays.binarySearch(allowedTypes,
                                inputTypeVal) < 0) {
                            err(String.format(
                                    Messages.getString("Assertions.Error.DenyInputType"), //$NON-NLS-1$
                                    attLocal, renderTypeList(allowedTypes)));
                        }
                    } else if ("autofocus" == attLocal) {
                        if (hasAutofocus) {
                            err(Messages.getString("Assertions.Error.ManyAutofocusAttrs")); //$NON-NLS-1$
                        }
                        hasAutofocus = true;
                    } else if (ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.contains(
                            attLocal)) {
                        String stateOrProperty = "aria-" + attLocal;
                        if (atts.getIndex("", stateOrProperty) > -1
                                && "true".equals(
                                        atts.getValue("", stateOrProperty))) {
                            warn(String.format(
                                    Messages.getString("Assertions.Warn.UnnecessaryElements"), //$NON-NLS-1$
                                    stateOrProperty, attLocal));
                        }
                    }
                } else if ("http://www.w3.org/XML/1998/namespace" == attUri) {
                    if ("lang" == atts.getLocalName(i)) {
                        xmlLang = atts.getValue(i);
                    }
                }

                if (atts.getType(i) == "ID" || "id" == atts.getLocalName(i)) {
                    String attVal = atts.getValue(i);
                    if (attVal.length() != 0) {
                        ids.add(attVal);
                    }
                }
            }

            if ("input".equals(localName)) {
                if (atts.getIndex("", "name") > -1
                        && "isindex".equals(atts.getValue("", "name"))) {
                    err(Messages.getString("Assertions.Error.Input.IsindexValueForNameAttr")); //$NON-NLS-1$
                }
                inputTypeVal = inputTypeVal == null ? "text" : inputTypeVal;
                if (atts.getIndex("", "autocomplete") > -1) {
                    Class<?> datatypeClass = null;
                    String autocompleteVal = atts.getValue("", "autocomplete");
                    try {
                        if (!"on".equals(autocompleteVal)
                                && !"off".equals(autocompleteVal)) {
                            if ("hidden".equals(inputTypeVal)) {
                                AutocompleteDetailsAny.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsAny.class;
                            } else if ("text".equals(inputTypeVal)
                                    || "search".equals(autocompleteVal)) {
                                AutocompleteDetailsText.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsText.class;
                            } else if ("password".equals(inputTypeVal)) {
                                AutocompleteDetailsPassword.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsPassword.class;
                            } else if ("url".equals(inputTypeVal)) {
                                AutocompleteDetailsUrl.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsUrl.class;
                            } else if ("email".equals(inputTypeVal)) {
                                AutocompleteDetailsEmail.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsEmail.class;
                            } else if ("tel".equals(inputTypeVal)) {
                                AutocompleteDetailsTel.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsTel.class;
                            } else if ("number".equals(inputTypeVal)) {
                                AutocompleteDetailsNumeric.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsNumeric.class;
                            } else if ("month".equals(inputTypeVal)) {
                                AutocompleteDetailsMonth.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsMonth.class;
                            } else if ("date".equals(inputTypeVal)) {
                                AutocompleteDetailsDate.THE_INSTANCE.checkValid(
                                        autocompleteVal);
                                datatypeClass = AutocompleteDetailsDate.class;
                            }
                        }
                    } catch (DatatypeException e) {
                        try {
                            if (getErrorHandler() != null) {
                                String msg = e.getMessage();
                                msg = msg.substring(msg.indexOf(": ") + 2);
                                VnuBadAttrValueException ex = new VnuBadAttrValueException(
                                        localName, uri, "autocomplete",
                                        autocompleteVal, msg,
                                        getDocumentLocator(), datatypeClass,
                                        false);
                                getErrorHandler().error(ex);
                            }
                        } catch (ClassNotFoundException ce) {
                        }
                    }
                }
            }
            if ("img".equals(localName) || "source".equals(localName)) {
                if (atts.getIndex("", "srcset") > -1) {
                    String srcsetVal = atts.getValue("", "srcset");
                    try {
                        if (atts.getIndex("", "sizes") > -1) {
                            ImageCandidateStringsWidthRequired.THE_INSTANCE.checkValid(
                                    srcsetVal);
                        } else {
                            ImageCandidateStrings.THE_INSTANCE.checkValid(
                                    srcsetVal);
                        }
                        // see nu.validator.datatype.ImageCandidateStrings
                        if ("1".equals(System.getProperty(
                                "nu.validator.checker.imageCandidateString.hasWidth"))) {
                            if (atts.getIndex("", "sizes") < 0) {
                                err(Messages.getString("Assertions.Error.Image.Srcset.NoSizesAttr")); //$NON-NLS-1$
                            }
                        }
                    } catch (DatatypeException e) {
                        Class<?> datatypeClass = ImageCandidateStrings.class;
                        if (atts.getIndex("", "sizes") > -1) {
                            datatypeClass = ImageCandidateStringsWidthRequired.class;
                        }
                        try {
                            if (getErrorHandler() != null) {
                                String msg = e.getMessage();
                                if (e instanceof Html5DatatypeException) {
                                    Html5DatatypeException ex5 = (Html5DatatypeException) e;
                                    if (!ex5.getDatatypeClass().equals(
                                            ImageCandidateURL.class)) {
                                        msg = msg.substring(
                                                msg.indexOf(": ") + 2);
                                    }
                                }
                                VnuBadAttrValueException ex = new VnuBadAttrValueException(
                                        localName, uri, "srcset", srcsetVal,
                                        msg, getDocumentLocator(),
                                        datatypeClass, false);
                                getErrorHandler().error(ex);
                            }
                        } catch (ClassNotFoundException ce) {
                        }
                    }
                    if ("picture".equals(parentName)
                            && !siblingSources.isEmpty()) {
                        for (Map.Entry<Locator, Map<String, String>> entry : siblingSources.entrySet()) {
                            Locator locator = entry.getKey();
                            Map<String, String> sourceAtts = entry.getValue();
                            String media = sourceAtts.get("media");
                            if (media == null
                                    && sourceAtts.get("type") == null) {
                                err(Messages.getString("Assertions.Error.MisssingAttres"), locator); //$NON-NLS-1$
                                siblingSources.remove(locator);
                            } else if (media != null
                                    && "".equals(trimSpaces(media))) {
                                err(Messages.getString("Assertions.Error.Media.EmptyValue"), locator); //$NON-NLS-1$
                            } else if (media != null
                                    && AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                            "all", trimSpaces(media))) {
                                err(Messages.getString("Assertions.Error.Media.AllValue"), locator); //$NON-NLS-1$
                            }
                        }
                    }
                } else if (atts.getIndex("", "sizes") > -1) {
                    err(Messages.getString("Assertions.Error.UseSizesAttrWithoutSrcsetAttr")); //$NON-NLS-1$
                }
            }

            if ("picture".equals(parentName) && "source".equals(localName)) {
                Map<String, String> sourceAtts = new HashMap<>();
                for (int i = 0; i < atts.getLength(); i++) {
                    sourceAtts.put(atts.getLocalName(i), atts.getValue(i));
                }
                siblingSources.put((new LocatorImpl(getDocumentLocator())),
                        sourceAtts);
            }

            if ("figure" == localName) {
                currentFigurePtr = currentPtr + 1;
            }
            if ((ancestorMask & FIGURE_MASK) != 0) {
                if ("img" == localName) {
                    if (stack[currentFigurePtr].hasImg()) {
                        stack[currentFigurePtr].setEmbeddedContentFound();
                    } else {
                        stack[currentFigurePtr].setImgFound();
                    }
                } else if ("audio" == localName || "canvas" == localName
                        || "embed" == localName || "iframe" == localName
                        || "math" == localName || "object" == localName
                        || "svg" == localName || "video" == localName) {
                    stack[currentFigurePtr].setEmbeddedContentFound();
                }
            }
            if ("article" == localName || "aside" == localName
                    || "nav" == localName || "section" == localName) {
                currentSectioningElementPtr = currentPtr + 1;
                currentSectioningDepth++;
            }
            if ("h1" == localName || "h2" == localName || "h3" == localName
                    || "h4" == localName || "h5" == localName
                    || "h6" == localName) {
                currentHeadingPtr = currentPtr + 1;
                if (currentSectioningElementPtr > -1) {
                    stack[currentSectioningElementPtr].setHeadingFound();
                }
            }
            if (((ancestorMask & H1_MASK) != 0 || (ancestorMask & H2_MASK) != 0
                    || (ancestorMask & H3_MASK) != 0
                    || (ancestorMask & H4_MASK) != 0
                    || (ancestorMask & H5_MASK) != 0
                    || (ancestorMask & H6_MASK) != 0) && "img" == localName
                    && atts.getIndex("", "alt") > -1
                    && !"".equals(atts.getValue("", "alt"))) {
                stack[currentHeadingPtr].setImgFound();
            }

            if ("option" == localName && !parent.hasOption()) {
                if (atts.getIndex("", "value") < 0) {
                    parent.setNoValueOptionFound();
                } else if (atts.getIndex("", "value") > -1
                        && "".equals(atts.getValue("", "value"))) {
                    parent.setEmptyValueOptionFound();
                } else {
                    parent.setNonEmptyOption(
                            (new LocatorImpl(getDocumentLocator())));
                }
            }

            // Obsolete elements
            if (OBSOLETE_ELEMENTS.get(localName) != null) {
                err(String.format(
                        Messages.getString("Assertions.Error.UseObsoleteElements"), //$NON-NLS-1$
                        localName, OBSOLETE_ELEMENTS.get(localName)));
            }

            // Exclusions
            Integer maskAsObject;
            int mask = 0;
            String descendantUiString = String.format(
                    Messages.getString("Assertions.Error.Exclusions.Message"), localName); //$NON-NLS-1$
            if ((maskAsObject = ANCESTOR_MASK_BY_DESCENDANT.get(
                    localName)) != null) {
                mask = maskAsObject.intValue();
            } else if ("video" == localName && controls) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.ElementMsg"),
                        "video", "controls"); //$NON-NLS-1$
                checkForInteractiveAncestorRole(descendantUiString);
            } else if ("audio" == localName && controls) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.ElementMsg"),
                        "audio", "controls"); //$NON-NLS-1$
                checkForInteractiveAncestorRole(descendantUiString);
            } else if ("menu" == localName && toolbar) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.ElementMsg"),
                        "menu", "type=toolbar"); //$NON-NLS-1$
                checkForInteractiveAncestorRole(descendantUiString);
            } else if ("img" == localName && usemap) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.ElementMsg"),
                        "img", "usemap"); //$NON-NLS-1$
                checkForInteractiveAncestorRole(descendantUiString);
            } else if ("object" == localName && usemap) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.ElementMsg"),
                        "object", "usemap"); //$NON-NLS-1$
                checkForInteractiveAncestorRole(descendantUiString);
            } else if ("input" == localName && !hidden) {
                mask = A_BUTTON_MASK;
                checkForInteractiveAncestorRole(descendantUiString);
            } else if (tabindex) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.AttributeMsg"), //$NON-NLS-1$
                        "tabindex");
                checkForInteractiveAncestorRole(descendantUiString);
            } else if (role != null && role != ""
                    && Arrays.binarySearch(INTERACTIVE_ROLES, role) >= 0) {
                mask = A_BUTTON_MASK;
                descendantUiString = String.format(
                        Messages.getString("Assertions.Error.Exclusions.AttributeMsg"), //$NON-NLS-1$
                        "role=" + role);
                checkForInteractiveAncestorRole(descendantUiString);
            }
            if (mask != 0) {
                int maskHit = ancestorMask & mask;
                if (maskHit != 0) {
                    for (String ancestor : SPECIAL_ANCESTORS) {
                        if ((maskHit & 1) != 0) {
                            err(String.format(
                                    Messages.getString("Assertions.Error.Exclusions"), //$NON-NLS-1$
                                    descendantUiString, ancestor));
                        }
                        maskHit >>= 1;
                    }
                }
            }
            if (Arrays.binarySearch(INTERACTIVE_ELEMENTS, localName) >= 0) {
                checkForInteractiveAncestorRole(String.format(
                        Messages.getString("Assertions.Error.Exclusions.Message"), //$NON-NLS-1$
                        localName));
            }

            // Ancestor requirements/restrictions
            if ("area" == localName && ((ancestorMask & MAP_MASK) == 0)) {
                err(Messages.getString("Assertions.Error.AncestorRequirments.Area")); //$NON-NLS-1$
            } else if ("img" == localName) {
                String titleVal = atts.getValue("", "title");
                if (ismap && ((ancestorMask & HREF_MASK) == 0)) {
                    err(Messages.getString("Assertions.Error.AncestorRequirments.Img.Ismap")); //$NON-NLS-1$
                }
                if (atts.getIndex("", "alt") < 0) {
                    if ((titleVal == null || "".equals(titleVal))) {
                        if ((ancestorMask & FIGURE_MASK) == 0) {
                            err(Messages.getString("Assertions.Error.AncestorRequirments.Img.Alt")); //$NON-NLS-1$
                        } else {
                            stack[currentFigurePtr].setFigcaptionNeeded();
                            stack[currentFigurePtr].addImageLackingAlt(
                                    new LocatorImpl(getDocumentLocator()));
                        }
                    }
                } else {
                    if ("".equals(atts.getValue("", "alt")) && role != null) {
                        List<String> roles = Arrays.asList(role.trim() //
                                .toLowerCase().split("\\s+"));
                        if (!roles.contains("none")
                                && !roles.contains("presentation")) {
                            err(Messages.getString("Assertions.Error.AncestorRequirments.Img.Alt.Empty")); //$NON-NLS-1$
                        }
                    }
                }
            } else if ("table" == localName) {
                if (atts.getIndex("", "summary") >= 0) {
                    errObsoleteAttribute("summary", "table",
                            Messages.getString("Assertions.Error.AncestorRequirments.Table")); //$NON-NLS-1$
                }
                if (atts.getIndex("", "border") > -1) {
                    errObsoleteAttribute("border", "table",
                            Messages.getString("Assertions.UseCSSInstead")); //$NON-NLS-1$
                }
            } else if ("track" == localName
                    && atts.getIndex("", "default") >= 0) {
                for (Map.Entry<StackNode, TaintableLocatorImpl> entry : openMediaElements.entrySet()) {
                    StackNode node = entry.getKey();
                    TaintableLocatorImpl locator = entry.getValue();
                    if (node.isTrackDescendant()) {
                        err(Messages.getString("Assertions.Error.Track.Default")); //$NON-NLS-1$
                        if (!locator.isTainted()) {
                            warn(Messages.getString("Assertions.Warn.Track.ManyDefault"), locator); //$NON-NLS-1$
                            locator.markTainted();
                        }
                    } else {
                        node.setTrackDescendants();
                    }
                }
            } else if ("hgroup" == localName) {
                incrementUseCounter("hgroup-found");
            } else if ("main" == localName) {
                for (int i = 0; i < currentPtr; i++) {
                    String ancestorName = stack[currentPtr - i].getName();
                    if (ancestorName != null
                            && Arrays.binarySearch(PROHIBITED_MAIN_ANCESTORS,
                                    ancestorName) >= 0) {
                        err(String.format(
                                Messages.getString("Assertions.Error.Main"), //$NON-NLS-1$
                                ancestorName));
                    }
                }
                if (atts.getIndex("", "hidden") < 0) {
                    if (hasVisibleMain) {
                        err(Messages.getString("Assertions.Error.Main.Hidden")); //$NON-NLS-1$
                    }
                    hasVisibleMain = true;
                }
            } else if ("h1" == localName) {
                if (currentSectioningDepth > 1) {
                    warn(h1WarningMessage);
                } else if (currentSectioningDepth == 1) {
                    secondLevelH1s.add(new LocatorImpl(getDocumentLocator()));
                } else {
                    hasTopLevelH1 = true;
                }
            }

            // progress
            else if ("progress" == localName) {
                double value = getDoubleAttribute(atts, "value");
                if (!Double.isNaN(value)) {
                    double max = getDoubleAttribute(atts, "max");
                    if (Double.isNaN(max)) {
                        if (!(value <= 1.0)) {
                            err(String.format(
                                    Messages.getString("Assertions.Error.Meter.MoreThanOne"), "value", "max")); //$NON-NLS-1$
                        }
                    } else {
                        if (!(value <= max)) {
                            err(String.format(
                                    Messages.getString("Assertions.Error.Meter.MoreThan"), "value", "max")); //$NON-NLS-1$
                        }
                    }
                }
            }

            // meter
            else if ("meter" == localName) {
                double value = getDoubleAttribute(atts, "value");
                double min = getDoubleAttribute(atts, "min");
                double max = getDoubleAttribute(atts, "max");
                double optimum = getDoubleAttribute(atts, "optimum");
                double low = getDoubleAttribute(atts, "low");
                double high = getDoubleAttribute(atts, "high");

                if (!Double.isNaN(min) && !Double.isNaN(value)
                        && !(min <= value)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "min", "value")); //$NON-NLS-1$
                }
                if (Double.isNaN(min) && !Double.isNaN(value)
                        && !(0 <= value)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.LessThanZero"), "value", "min")); //$NON-NLS-1$
                }
                if (!Double.isNaN(value) && !Double.isNaN(max)
                        && !(value <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "value", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(value) && Double.isNaN(max)
                        && !(value <= 1)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThanOne"), "value", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(min) && !Double.isNaN(max) && !(min <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "min", "max")); //$NON-NLS-1$
                }
                if (Double.isNaN(min) && !Double.isNaN(max) && !(0 <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.LessThanZero"), "max", "min")); //$NON-NLS-1$
                }
                if (!Double.isNaN(min) && Double.isNaN(max) && !(min <= 1)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThanOne"), "min", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(min) && !Double.isNaN(low) && !(min <= low)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "min", "low")); //$NON-NLS-1$
                }
                if (Double.isNaN(min) && !Double.isNaN(low) && !(0 <= low)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.LessThanZero"), "low", "min")); //$NON-NLS-1$
                }
                if (!Double.isNaN(min) && !Double.isNaN(high)
                        && !(min <= high)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "min", "high")); //$NON-NLS-1$
                }
                if (Double.isNaN(min) && !Double.isNaN(high) && !(0 <= high)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.LessThanZero"), "high", "min")); //$NON-NLS-1$
                }
                if (!Double.isNaN(low) && !Double.isNaN(high)
                        && !(low <= high)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "low", "high")); //$NON-NLS-1$
                }
                if (!Double.isNaN(high) && !Double.isNaN(max)
                        && !(high <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "high", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(high) && Double.isNaN(max) && !(high <= 1)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThanOne"), "high", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(low) && !Double.isNaN(max) && !(low <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "low", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(low) && Double.isNaN(max) && !(low <= 1)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThanOne"), "low", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(min) && !Double.isNaN(optimum)
                        && !(min <= optimum)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "min", "optimum")); //$NON-NLS-1$
                }
                if (Double.isNaN(min) && !Double.isNaN(optimum)
                        && !(0 <= optimum)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.LessThanZero"), "optimum", "min")); //$NON-NLS-1$
                }
                if (!Double.isNaN(optimum) && !Double.isNaN(max)
                        && !(optimum <= max)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThan"), "optimum", "max")); //$NON-NLS-1$
                }
                if (!Double.isNaN(optimum) && Double.isNaN(max)
                        && !(optimum <= 1)) {
                    err(String.format(Messages.getString("Assertions.Error.Meter.MoreThanOne"), "optimum", "max")); //$NON-NLS-1$
                }
            }

            // map required attrs
            else if ("map" == localName && id != null) {
                String nameVal = atts.getValue("", "name");
                if (nameVal != null && !nameVal.equals(id)) {
                    err(Messages.getString("Assertions.Error.MapRequiredAttrs")); //$NON-NLS-1$
                }
            }

            else if ("object" == localName) {
                if (atts.getIndex("", "typemustmatch") >= 0) {
                    if ((atts.getIndex("", "data") < 0)
                            || (atts.getIndex("", "type") < 0)) {
                        err(Messages.getString("Assertions.Error.Element.Object.Typemustmatch")); //$NON-NLS-1$
                    }
                }
            }
            else if ("form" == localName) {
                if (atts.getIndex("", "accept-charset") >= 0) {
                    if (!"utf-8".equals(
                            atts.getValue("", "accept-charset").toLowerCase())) {
                        err(Messages.getString("Assertions.Error.Form.AcceptCharset")); //$NON-NLS-1$
                    }
                }
            }
            // script
            else if ("script" == localName) {
                // script language
                if (languageJavaScript && typeNotTextJavaScript) {
                    err(Messages.getString("Assertions.Error.Script.Type")); //$NON-NLS-1$
                }
                if (atts.getIndex("", "charset") >= 0) {
                    warnObsoleteAttribute("charset", "script", "");
                    if (!"utf-8".equals(
                            atts.getValue("", "charset").toLowerCase())) {
                        err(Messages.getString("Assertions.Error.Script.Charset")); //$NON-NLS-1$
                    }
                }
                // src-less script
                if (atts.getIndex("", "src") < 0) {
                    if (atts.getIndex("", "charset") >= 0) {
                        err(String.format(Messages.getString("Assertions.Error.Script.SrcLess"), "charset")); //$NON-NLS-1$
                    }
                    if (atts.getIndex("", "defer") >= 0) {
                        err(String.format(Messages.getString("Assertions.Error.Script.SrcLess"), "defer")); //$NON-NLS-1$
                    }
                    if (atts.getIndex("", "async") >= 0) {
                        if (!(atts.getIndex("", "type") > -1 && //
                                "module".equals(atts.getValue("", "type") //
                                        .toLowerCase()))) {
                            err(Messages.getString("Assertions.Error.Script.SrcLess.AsyncType")); //$NON-NLS-1$
                        }
                    }
                    if (atts.getIndex("", "integrity") >= 0) {
                        err(String.format(Messages.getString("Assertions.Error.Script.SrcLess"), "integrity")); //$NON-NLS-1$
                    }
                }
                if (atts.getIndex("", "type") > -1) {
                    String scriptType = atts.getValue("", "type").toLowerCase();
                    if (JAVASCRIPT_MIME_TYPES.contains(scriptType)
                            || "".equals(scriptType)) {
                        warn(Messages.getString("Assertions.Warn.Script.UnnecessaryTypeAttr")); //$NON-NLS-1$
                    } else if ("module".equals(scriptType)) {
                        if (atts.getIndex("", "integrity") > -1) {
                            err(String.format(Messages.getString("Assertions.Error.Script.TypeAttrWithModule"), "integrity")); //$NON-NLS-1$
                        }
                        if (atts.getIndex("", "defer") > -1) {
                            err(String.format(Messages.getString("Assertions.Error.Script.TypeAttrWithModule"), "defer")); //$NON-NLS-1$
                        }
                        if (atts.getIndex("", "nomodule") > -1) {
                            err(String.format(Messages.getString("Assertions.Error.Script.TypeAttrWithModule"), "nomodule")); //$NON-NLS-1$
                        }
                    } else if (atts.getIndex("", "src") > -1) {
                        err(Messages.getString("Assertions.Error.Script.Src.TypeAttr")); //$NON-NLS-1$
                    }
                }
            }
            else if ("style" == localName) {
                if (atts.getIndex("", "type") > -1) {
                    String styleType = atts.getValue("", "type").toLowerCase();
                    if ("text/css".equals(styleType)) {
                        warn(Messages.getString("Assertions.Warn.Style.TypeAttr")); //$NON-NLS-1$
                    } else {
                        err(Messages.getString("Assertions.Error.Style.TypeAttr")); //$NON-NLS-1$
                    }
                }
            }

            // bdo required attrs
            else if ("bdo" == localName && atts.getIndex("", "dir") < 0) {
                err(Messages.getString("Assertions.Error.BdoRequiredAttrs")); //$NON-NLS-1$
            }

            // labelable elements
            if (isLabelableElement(localName, atts)) {
                for (Map.Entry<StackNode, Locator> entry : openLabels.entrySet()) {
                    StackNode node = entry.getKey();
                    Locator locator = entry.getValue();
                    if (node.isLabeledDescendants()) {
                        err(Messages.getString("Assertions.Error.Labelable.Elements")); //$NON-NLS-1$
                        warn(Messages.getString("Assertions.Warn.Labelable.Elements"), locator); //$NON-NLS-1$
                    } else {
                        node.setLabeledDescendants();
                    }
                }
                if ((ancestorMask & LABEL_FOR_MASK) != 0) {
                    boolean hasMatchingFor = false;
                    for (int i = 0; (stack[currentPtr - i].getAncestorMask()
                            & LABEL_FOR_MASK) != 0; i++) {
                        String forVal = stack[currentPtr - i].getForAttr();
                        if (forVal != null && forVal.equals(id)) {
                            hasMatchingFor = true;
                            break;
                        }
                    }
                    if (id == null || !hasMatchingFor) {
                        err(String.format(Messages.getString("Assertions.Error.Labelable.AnyDescendant"), localName)); //$NON-NLS-1$
                    }
                }
            }

            // lang and xml:lang for XHTML5
            if (lang != null && xmlLang != null
                    && !equalsIgnoreAsciiCase(lang, xmlLang)) {
                err(Messages.getString("Assertions.Error.XHTML5.Lang")); //$NON-NLS-1$
            }

            if (role != null && owns != null) {
                for (Set<String> value : REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.values()) {
                    if (value.contains(role)) {
                        String[] ownedIds = AttributeUtil.split(owns);
                        for (String ownedId : ownedIds) {
                            Set<String> ownedIdsForThisRole = ariaOwnsIdsByRole.get(
                                    role);
                            if (ownedIdsForThisRole == null) {
                                ownedIdsForThisRole = new HashSet<>();
                            }
                            ownedIdsForThisRole.add(ownedId);
                            ariaOwnsIdsByRole.put(role, ownedIdsForThisRole);
                        }
                        break;
                    }
                }
            }
            if ("datalist" == localName) {
                listIds.addAll(ids);
            }

            // label for
            if ("label" == localName) {
                String forVal = atts.getValue("", "for");
                if (forVal != null) {
                    formControlReferences.add(new IdrefLocator(
                            new LocatorImpl(getDocumentLocator()), forVal));
                }
            }

            if ("form" == localName) {
                formElementIds.addAll(ids);
            }

            if (("button" == localName //
                    || "input" == localName && !hidden) //
                    || "meter" == localName //
                    || "output" == localName //
                    || "progress" == localName //
                    || "select" == localName //
                    || "textarea" == localName) {
                formControlIds.addAll(ids);
            }

            if ("button" == localName || "fieldset" == localName
                    || ("input" == localName && !hidden)
                    || "object" == localName || "output" == localName
                    || "select" == localName || "textarea" == localName) {
                String formVal = atts.getValue("", "form");
                if (formVal != null) {
                    formElementReferences.add(new IdrefLocator(
                            new LocatorImpl(getDocumentLocator()), formVal));
                }
            }

            // input list
            if ("input" == localName && list != null) {
                listReferences.add(new IdrefLocator(
                        new LocatorImpl(getDocumentLocator()), list));
            }

            // input@type=button
            if ("input" == localName
                    && AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                            "button", atts.getValue("", "type"))) {
                if (atts.getValue("", "value") == null
                        || "".equals(atts.getValue("", "value"))) {
                    err(Messages.getString("Assertions.Error.Input.Type.Button.Value")); //$NON-NLS-1$
                }
            }

            // track
            if ("track" == localName) {
                if ("".equals(atts.getValue("", "label"))) {
                    err(Messages.getString("Assertions.Error.Track.Label.EmptyValue")); //$NON-NLS-1$
                }
            }

            // multiple selected options
            if ("option" == localName && selected) {
                for (Map.Entry<StackNode, Locator> entry : openSingleSelects.entrySet()) {
                    StackNode node = entry.getKey();
                    if (node.isSelectedOptions()) {
                        err(Messages.getString("Assertions.Error.Select.ManyOptions")); //$NON-NLS-1$
                    } else {
                        node.setSelectedOptions();
                    }
                }
            }
            if ("meta" == localName) {
                if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                        "content-language", atts.getValue("", "http-equiv"))) {
                    err(Messages.getString("Assertions.Error.Root.Meta.ContentLanguage")); //$NON-NLS-1$
                } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                        "x-ua-compatible", atts.getValue("", "http-equiv"))
                        && !AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                "ie=edge", atts.getValue("", "content"))) {
                    err(Messages.getString("Assertions.Error.Root.Meta.XUACompatible.ContentVal")); //$NON-NLS-1$
                }
                if (atts.getIndex("", "charset") > -1) {
                    if (!"utf-8".equals(
                            atts.getValue("", "charset").toLowerCase())) {
                        err(Messages.getString("Assertions.Error.Root.Meata.Charset.NotUTF8")); //$NON-NLS-1$
                    }
                    if (hasMetaCharset) {
                        err(Messages.getString("Assertions.Error.Root.Meata.Charset.Many")); //$NON-NLS-1$
                    }
                    if (hasContentTypePragma) {
                        err(Messages.getString("Assertions.Error.Root.Meata.BothContentTypeCharset")); //$NON-NLS-1$
                    }
                    hasMetaCharset = true;
                }
                if (atts.getIndex("", "name") > -1) {
                    if ("description".equals(atts.getValue("", "name"))) {
                        if (hasMetaDescription) {
                            err(Messages.getString("Assertions.Error.Root.Meata.Name.Many")); //$NON-NLS-1$
                        }
                        hasMetaDescription = true;
                    }
                    if ("viewport".equals(atts.getValue("", "name"))
                            && atts.getIndex("", "content") > -1) {
                        String contentVal = atts.getValue("",
                                "content").toLowerCase();
                        if (contentVal.contains("user-scalable=no")
                                || contentVal.contains("maximum-scale=1.0")) {
                            warn(Messages.getString("Assertions.Warn.Root.Meata.Name.ViwpointVals")); //$NON-NLS-1$
                        }
                    }
                    if ("theme-color".equals(atts.getValue("", "name"))
                            && atts.getIndex("", "content") > -1) {
                        String contentVal = atts.getValue("",
                                "content").toLowerCase();
                        try {
                            Color.THE_INSTANCE.checkValid(contentVal);
                        } catch (DatatypeException e) {
                            try {
                                if (getErrorHandler() != null) {
                                    String msg = e.getMessage();
                                    if (e instanceof Html5DatatypeException) {
                                        msg = msg.substring(
                                                msg.indexOf(": ") + 2);
                                    }
                                    VnuBadAttrValueException ex = //
                                            new VnuBadAttrValueException(
                                                    localName, uri, "content",
                                                    contentVal, msg,
                                                    getDocumentLocator(),
                                                    Color.class, false);
                                    getErrorHandler().error(ex);
                                }
                            } catch (ClassNotFoundException ce) {
                            }
                        }
                    }
                }
                if (atts.getIndex("", "http-equiv") > -1
                        && AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
                                "content-type",
                                atts.getValue("", "http-equiv"))) {
                    if (hasMetaCharset) {
                        err(Messages.getString("Assertions.Error.Root.Meata.BothContentTypeCharset")); //$NON-NLS-1$
                    }
                    if (hasContentTypePragma) {
                        err(Messages.getString("Assertions.Error.Root.Meata.ContentType")); //$NON-NLS-1$
                    }
                    hasContentTypePragma = true;
                }
            }
            if ("link" == localName) {
                boolean hasRel = false;
                List<String> relList = new ArrayList<>();
                if (atts.getIndex("", "rel") > -1) {
                    hasRel = true;
                    Collections.addAll(relList, //
                            atts.getValue("", "rel") //
                            .toLowerCase().split("\\s+"));
                }
                if (atts.getIndex("", "as") > -1
                        && ((relList != null //
                                && !(relList.contains("preload")
                                        || relList.contains("modulepreload"))
                                || !hasRel))) {
                    err(Messages.getString("Assertions.Error.Root.Link.AS.NoRelAttr")); //$NON-NLS-1$
                }
                if (atts.getIndex("", "integrity") > -1
                        && ((relList != null && !relList.contains("stylesheet")
                                && !relList.contains("preload")
                                && !relList.contains("modulepreload")
                                || !hasRel))) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.ManyVals"), //$NON-NLS-1$
                            "integrity", "stylesheet", "preload", "modulepreload"));
                }
                if (atts.getIndex("", "sizes") > -1
                        && ((relList != null && !relList.contains("icon")
                                && !relList.contains("apple-touch-icon"))
                                && !relList.contains("apple-touch-icon-precomposed")
                                || !hasRel)) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.ManyVals"), //$NON-NLS-1$
                            "sizes", "icon", "apple-touch-icon", "apple-touch-icon-precomposed"));
                }
                if (atts.getIndex("", "color") > -1 //
                        && (!hasRel || (relList != null
                                && !relList.contains("mask-icon")))) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.Single"), //$NON-NLS-1$
                            "color", "mask-icon"));
                }
                if (atts.getIndex("", "scope") > -1 //
                        && ((relList != null
                                && !relList.contains("serviceworker"))
                                || !hasRel)) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.Single"), //$NON-NLS-1$
                            "scope", "serviceworker"));
                }
                if (atts.getIndex("", "updateviacache") > -1 //
                        && ((relList != null
                                && !relList.contains("serviceworker"))
                                || !hasRel)) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.Single"), //$NON-NLS-1$
                            "updateviacache", "serviceworker"));
                }
                if (atts.getIndex("", "workertype") > -1 //
                        && ((relList != null
                                && !relList.contains("serviceworker"))
                                || !hasRel)) {
                    err(String.format(
                            Messages.getString("Assertions.Error.Root.Link.NoRelAttr.Single"), //$NON-NLS-1$
                            "workertype", "serviceworker"));
                }
                if ((ancestorMask & BODY_MASK) != 0
                        && (relList != null
                                && !(relList.contains("dns-prefetch")
                                        || relList.contains("modulepreload")
                                        || relList.contains("pingback")
                                        || relList.contains("preconnect")
                                        || relList.contains("prefetch")
                                        || relList.contains("preload")
                                        || relList.contains("prerender")
                                        || relList.contains("stylesheet")))
                        && atts.getIndex("", "itemprop") < 0
                        && atts.getIndex("", "property") < 0) {
                    err(Messages.getString("Assertions.Error.Root.Link.UseInBodyElement")); //$NON-NLS-1$
                }
            }

            // microdata
            if (itemid && !(itemscope && itemtype)) {
                err(Messages.getString("Assertions.Error.Microdata.Itemid"));
            }
            if (itemref && !itemscope) {
                err(String.format(Messages.getString("Assertions.Error.Microdata.NoItemscope"), "itemref")); //$NON-NLS-1$
            }
            if (itemtype && !itemscope) {
                err(String.format(Messages.getString("Assertions.Error.Microdata.NoItemscope"), "itemtype")); //$NON-NLS-1$
            }

            // Warnings for use of ARIA attributes with markup already
            // having implicit ARIA semantics.
            if (ELEMENTS_WITH_IMPLICIT_ROLE.containsKey(localName)
                    && ELEMENTS_WITH_IMPLICIT_ROLE.get(localName).equals(
                            role)) {
                warn(String.format(
                        Messages.getString("Assertions.Warn.ARIA.UnnecessaryRole"), //$NON-NLS-1$
                        role, localName));
            } else if (ELEMENTS_WITH_IMPLICIT_ROLES.containsKey(localName)
                    && role != null
                    && Arrays.binarySearch(
                            ELEMENTS_WITH_IMPLICIT_ROLES.get(localName),
                            role) >= 0) {
                warn(String.format(
                        Messages.getString("Assertions.Warn.ARIA.UnnecessaryRole"), //$NON-NLS-1$
                        role, localName));
            } else if (ELEMENTS_THAT_NEVER_NEED_ROLE.containsKey(localName)
                    && ELEMENTS_THAT_NEVER_NEED_ROLE.get(localName).equals(
                            role)) {
            } else if ("input" == localName) {
                inputTypeVal = inputTypeVal == null ? "text" : inputTypeVal;
                warn(String.format(
                        Messages.getString("Assertions.Warn.ARIA.NeverNeedRoleAttr"), //$NON-NLS-1$
                        localName));
                if (INPUT_TYPES_WITH_IMPLICIT_ROLE.containsKey(inputTypeVal)
                        && INPUT_TYPES_WITH_IMPLICIT_ROLE.get(
                                inputTypeVal).equals(role)) {
                    warnExplicitRoleUnnecessaryForType("input", role,
                            inputTypeVal);
                } else if ("email".equals(inputTypeVal)
                        || "search".equals(inputTypeVal)
                        || "tel".equals(inputTypeVal)
                        || "text".equals(inputTypeVal)
                        || "url".equals(inputTypeVal)) {
                    if (atts.getIndex("", "list") < 0) {
                        if ("textbox".equals(role)) {
                            warn(String.format(
                                    Messages.getString("Assertions.Warn.ARIA.UnnecessaryTextboxRole"), //$NON-NLS-1$
                                    inputTypeVal));
                        }
                    } else {
                        if ("combobox".equals(role)) {
                            warn(String.format(
                                    Messages.getString("Assertions.Warn.ARIA.UnnecessaryComboboxRole"), //$NON-NLS-1$
                                    inputTypeVal));
                        }

                    }
                }
            } else if (atts.getIndex("", "href") > -1 && "link".equals(role)
                    && ("a".equals(localName) || "area".equals(localName)
                            || "link".equals(localName))) {
                warn(String.format(Messages.getString("Assertions.Warn.ARIA.UnnecessaryLinkRole"), localName)); //$NON-NLS-1$
            } else if (("tbody".equals(localName) || "tfoot".equals(localName)
                    || "thead".equals(localName)) && "rowgroup".equals(role)) {
                warn(String.format(Messages.getString("Assertions.Warn.ARIA.UnnecessaryRole"), "rowgroup", localName)); //$NON-NLS-1$
            } else if ("th" == localName && ("columnheader".equals(role)
                    || "columnheader".equals(role))) {
                warn(String.format(Messages.getString("Assertions.Warn.ARIA.UnnecessaryRole"), role, "th")); //$NON-NLS-1$
            } else if ("li" == localName && "listitem".equals(role)
                    && !"menu".equals(parentName)) {
                warn(Messages.getString("Assertions.Warn.ARIA.UnnecessaryListitemRole")); //$NON-NLS-1$
            } else if ("button" == localName && "button".equals(role)
                    && "menu".equals(atts.getValue("", "type"))) {
                warnExplicitRoleUnnecessaryForType("button", "button", "menu");
            } else if ("menu" == localName && "toolbar".equals(role)
                    && "toolbar".equals(atts.getValue("", "type"))) {
                warnExplicitRoleUnnecessaryForType("menu", "toolbar", "toolbar");
            } else if ("li" == localName && "listitem".equals(role)
                    && !"menu".equals(parentName)) {
                warn(Messages.getString("Assertions.Warn.ARIA.UnnecessaryListitemRole")); //$NON-NLS-1$
            }
        } else {
            int len = atts.getLength();
            for (int i = 0; i < len; i++) {
                if (atts.getType(i) == "ID") {
                    String attVal = atts.getValue(i);
                    if (attVal.length() != 0) {
                        ids.add(attVal);
                    }
                }
                String attLocal = atts.getLocalName(i);
                if (atts.getURI(i).length() == 0) {
                    if ("role" == attLocal) {
                        role = atts.getValue(i);
                    } else if ("aria-activedescendant" == attLocal) {
                        activeDescendant = atts.getValue(i);
                    } else if ("aria-owns" == attLocal) {
                        owns = atts.getValue(i);
                    }
                }
            }

            allIds.addAll(ids);
        }

        // ARIA required owner/ancestors
        Set<String> requiredAncestorRoles = REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.get(
                role);
        if (requiredAncestorRoles != null && !"presentation".equals(parentRole)
                && !"tbody".equals(localName) && !"tfoot".equals(localName)
                && !"thead".equals(localName)) {
            if (!currentElementHasRequiredAncestorRole(requiredAncestorRoles)) {
                if (atts.getIndex("", "id") > -1
                        && !"".equals(atts.getValue("", "id"))) {
                    needsAriaOwner.add(new IdrefLocator(
                            new LocatorImpl(getDocumentLocator()),
                            atts.getValue("", "id"), role));
                } else {
                    errContainedInOrOwnedBy(role, getDocumentLocator());
                }
            }
        }

        // ARIA IDREFS
        for (String att : MUST_NOT_DANGLE_IDREFS) {
            String attVal = atts.getValue("", att);
            if (attVal != null) {
                String[] tokens = AttributeUtil.split(attVal);
                for (String token : tokens) {
                    ariaReferences.add(
                            new IdrefLocator(getDocumentLocator(), token, att));
                }
            }
        }
        allIds.addAll(ids);

        if (isAriaLabelMisuse(ariaLabel, localName, role, atts)) {
            warn(Messages.getString("Assertions.Warn.AriaLabel.Misuse")); //$NON-NLS-1$
            incrementUseCounter("aria-label-misuse-found");
            String systemId = getDocumentLocator().getSystemId();
            if (systemId != null && hasPageEmitterInCallStack()) {
                log4j.info("aria-label misuse " + systemId);
            }
        }

        // aria-activedescendant accompanied by aria-owns
        if (activeDescendant != null && !"".equals(activeDescendant)) {
            // String activeDescendantVal = atts.getValue("",
            // "aria-activedescendant");
            if (owns != null && !"".equals(owns)) {
                activeDescendantWithAriaOwns = true;
                // String[] tokens = AttributeUtil.split(owns);
                // for (int i = 0; i < tokens.length; i++) {
                // String token = tokens[i];
                // if (token.equals(activeDescendantVal)) {
                // activeDescendantWithAriaOwns = true;
                // break;
                // }
                // }
            }
        }
        // activedescendant
        for (Iterator<Map.Entry<StackNode, Locator>> iterator = openActiveDescendants.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<StackNode, Locator> entry = iterator.next();
            if (ids.contains(entry.getKey().getActiveDescendant())) {
                iterator.remove();
            }
        }

        if ("http://www.w3.org/1999/xhtml" == uri) {
            int number = specialAncestorNumber(localName);
            if (number > -1) {
                ancestorMask |= (1 << number);
            }
            if ("a" == localName && href) {
                ancestorMask |= HREF_MASK;
            }
            StackNode child = new StackNode(ancestorMask, localName, role,
                    activeDescendant, forAttr);
            if ("style" == localName) {
                child.setIsCollectingCharacters(true);
            }
            if ("script" == localName) {
                child.setIsCollectingCharacters(true);
            }
            if (activeDescendant != null && !activeDescendantWithAriaOwns) {
                openActiveDescendants.put(child,
                        new LocatorImpl(getDocumentLocator()));
            }
            if ("select" == localName && atts.getIndex("", "multiple") == -1) {
                openSingleSelects.put(child, getDocumentLocator());
            } else if ("label" == localName) {
                openLabels.put(child, new LocatorImpl(getDocumentLocator()));
            } else if ("video" == localName || "audio" == localName) {
                openMediaElements.put(child,
                        new TaintableLocatorImpl(getDocumentLocator()));
            }
            push(child);
            if ("article" == localName || "aside" == localName
                    || "nav" == localName || "section" == localName) {
                if (atts.getIndex("", "aria-label") > -1
                        && !"".equals(atts.getValue("", "aria-label"))) {
                    child.setHeadingFound();
                }
            }
            if ("select" == localName && atts.getIndex("", "required") > -1
                    && atts.getIndex("", "multiple") < 0) {
                if (atts.getIndex("", "size") > -1) {
                    String size = trimSpaces(atts.getValue("", "size"));
                    if (!"".equals(size)) {
                        try {
                            if ((size.length() > 1 && size.charAt(0) == '+'
                                    && Integer.parseInt(size.substring(1)) == 1)
                                    || Integer.parseInt(size) == 1) {
                                child.setOptionNeeded();
                            } else {
                                // do nothing
                            }
                        } catch (NumberFormatException e) {
                        }
                    }
                } else {
                    // default size is 1
                    child.setOptionNeeded();
                }
            }
            if (localName.contains("-")) {
                if (atts.getIndex("", "is") > -1) {
                    err(Messages.getString("Assertions.Error.Element.AutonomousCustom.SpecityISAttr")); //$NON-NLS-1$
                }
                try {
                    CustomElementName.THE_INSTANCE.checkValid(localName);
                } catch (DatatypeException e) {
                    try {
                        if (getErrorHandler() != null) {
                            String msg = e.getMessage();
                            if (e instanceof Html5DatatypeException) {
                                msg = msg.substring(msg.indexOf(": ") + 2);
                            }
                            VnuBadElementNameException ex = new VnuBadElementNameException(
                                    localName, uri, msg, getDocumentLocator(),
                                    CustomElementName.class, false);
                            getErrorHandler().error(ex);
                        }
                    } catch (ClassNotFoundException ce) {
                    }
                }
            }
        } else if ("http://n.validator.nu/custom-elements/" == uri) {
            /*
             * For elements with names containing "-" (custom elements), the
             * customelements/NamespaceChanging* code exposes them to jing as
             * elements in the http://n.validator.nu/custom-elements/ namespace.
             * Therefore our RelaxNG schema allows those elements. However,
             * schematronequiv.Assertions still sees those elements as being in
             * the HTML namespace, so here we need to emit an error for the case
             * where, in source transmitted with an XML content type, somebody
             * (for whatever reason) has elements in their markup which they
             * have explicitly placed in that namespace (otherwise, due to
             * allowing those elements in our RelaxNG schema, Jing on its own
             * won't emit any error for them).
             */
            err(String.format(
                    Messages.getString("Assertions.Error.Element.Custom.NotAllow"), //$NON-NLS-1$
                    localName));
        } else {
            StackNode child = new StackNode(ancestorMask, null, role,
                    activeDescendant, forAttr);
            if (activeDescendant != null) {
                openActiveDescendants.put(child,
                        new LocatorImpl(getDocumentLocator()));
            }
            push(child);
        }
        stack[currentPtr].setLocator(new LocatorImpl(getDocumentLocator()));
    }

    /**
     * @see nu.validator.checker.Checker#characters(char[], int, int)
     */
    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (numberOfTemplatesDeep > 0) {
            return;
        }
        if (stack[currentPtr].getIsCollectingCharacters()) {
            stack[currentPtr].appendToTextContent(ch, start, length);
        }
        StackNode node = peek();
        for (int i = start; i < start + length; i++) {
            char c = ch[i];
            switch (c) {
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    continue;
                default:
                    if ("h1".equals(node.name) || "h2".equals(node.name)
                            || "h3".equals(node.name) || "h4".equals(node.name)
                            || "h5".equals(node.name) || "h6".equals(node.name)
                            || (node.ancestorMask & H1_MASK) != 0
                            || (node.ancestorMask & H2_MASK) != 0
                            || (node.ancestorMask & H3_MASK) != 0
                            || (node.ancestorMask & H4_MASK) != 0
                            || (node.ancestorMask & H5_MASK) != 0
                            || (node.ancestorMask & H6_MASK) != 0) {
                        stack[currentHeadingPtr].setTextNodeFound();
                    } else if ("figcaption".equals(node.name)
                            || (node.ancestorMask & FIGCAPTION_MASK) != 0) {
                        if ((node.ancestorMask & FIGURE_MASK) != 0) {
                            stack[currentFigurePtr].setFigcaptionContentFound();
                        }
                        // for any ancestor figures of the parent figure
                        // of this figcaption, the content of this
                        // figcaption counts as a text node descendant
                        for (int j = 1; j < currentFigurePtr; j++) {
                            if ("figure".equals(
                                    stack[currentFigurePtr - j].getName())) {
                                stack[currentFigurePtr - j].setTextNodeFound();
                            }
                        }
                    } else if ("figure".equals(node.name)
                            || (node.ancestorMask & FIGURE_MASK) != 0) {
                        stack[currentFigurePtr].setTextNodeFound();
                        // for any ancestor figures of this figure, this
                        // also counts as a text node descendant
                        for (int k = 1; k < currentFigurePtr; k++) {
                            if ("figure".equals(
                                    stack[currentFigurePtr - k].getName())) {
                                stack[currentFigurePtr - k].setTextNodeFound();
                            }
                        }
                    } else if ("option".equals(node.name)
                            && !stack[currentPtr - 1].hasOption()
                            && (!stack[currentPtr - 1].hasEmptyValueOption()
                                    || stack[currentPtr - 1].hasNoValueOption())
                            && stack[currentPtr
                                    - 1].nonEmptyOptionLocator() == null) {
                        stack[currentPtr - 1].setNonEmptyOption(
                                (new LocatorImpl(getDocumentLocator())));
                    }
                    return; // This return can be removed if other code is added
                            // here. But it's here for now because we know we
                            // have at least one non-WS character, and for the
                            // purposes of the current code, that's all we need;
                            // it's a waste to keep checking for more.
            }
        }
    }

    private CharSequence renderTypeList(String[] types) {
        StringBuilder sb = new StringBuilder();
        int len = types.length;
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i == len - 1) {
                sb.append("or ");
            }
            sb.append("\u201C");
            sb.append(types[i]);
            sb.append('\u201D');
        }
        return sb;
    }

    private CharSequence renderRoleSet(Set<String> roles) {
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        for (String role : roles) {
            if (first) {
                first = false;
            } else {
                sb.append(" or ");
            }
            sb.append("\u201Crole=");
            sb.append(role);
            sb.append('\u201D');
        }
        return sb;
    }

}
