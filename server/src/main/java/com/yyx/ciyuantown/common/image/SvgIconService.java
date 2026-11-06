package com.yyx.ciyuantown.common.image;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import org.xml.sax.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SvgIconService {
    public static final int MAX_BYTES = 256 * 1024;
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final Set<String> TAGS = Set.of("svg", "g", "path", "rect", "circle", "ellipse", "line",
            "polyline", "polygon", "defs", "linearGradient", "radialGradient", "stop", "clipPath", "mask",
            "use", "symbol", "title", "desc", "text", "tspan");
    private static final Set<String> ATTRS = Set.of("id", "class", "focusable", "viewBox", "width", "height", "x", "y", "x1", "x2", "y1", "y2",
            "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "fill", "fill-rule", "fill-opacity", "stroke",
            "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset",
            "stroke-opacity", "opacity", "clip-path", "clip-rule", "mask", "maskUnits", "maskContentUnits", "clipPathUnits",
            "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "spreadMethod", "fx", "fy", "fr",
            "href", "preserveAspectRatio", "vector-effect", "color", "style", "version", "font-size", "font-family",
            "font-weight", "text-anchor", "dominant-baseline", "dx", "dy", "role", "aria-hidden", "aria-label", "aria-labelledby",
            "pathLength", "shape-rendering", "color-interpolation");
    private static final Set<String> STYLE_PROPS = Set.of("fill", "fill-rule", "fill-opacity", "stroke", "stroke-width",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity",
            "opacity", "clip-path", "clip-rule", "mask", "stop-color", "stop-opacity", "color", "vector-effect",
            "font-size", "font-family", "font-weight", "text-anchor", "dominant-baseline");
    private static final Pattern LOCAL_URL = Pattern.compile("url\\(\\s*['\"]?#[A-Za-z_][\\w:.-]*['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private final StorageService storage;

    public String save(byte[] bytes) {
        byte[] clean = sanitize(bytes);
        Path temporary = null;
        try {
            temporary = Files.createTempFile("mine-icon-", ".svg");
            Files.write(temporary, clean);
            return storage.put(storage.active(), "config/mine-icons/" + UUID.randomUUID() + ".svg", temporary, "image/svg+xml").url();
        } catch (IOException e) { throw new BizException("SVG 图标保存失败"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    public byte[] sanitize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new BizException("SVG 内容不能为空且不能超过256KB");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) throws SAXException { throw e; }
                public void error(SAXParseException e) throws SAXException { throw e; }
                public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            Element root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || !SVG_NS.equals(root.getNamespaceURI())) throw invalid();
            int[] count = {0}; validate(root, 0, count);
            // Drop processing instructions and comments when serializing the validated SVG root.
            var transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            var output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(root), new StreamResult(output));
            return output.toByteArray();
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private void validate(Element element, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 5000 || !SVG_NS.equals(element.getNamespaceURI()) || !TAGS.contains(element.getLocalName())) throw invalid();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getLocalName(), value = attribute.getNodeValue().strip();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                if (!Set.of(SVG_NS, "http://www.w3.org/1999/xlink").contains(value)) throw invalid();
                continue;
            }
            if (attribute.getNamespaceURI() != null && !("href".equals(name) && "http://www.w3.org/1999/xlink".equals(attribute.getNamespaceURI()))) throw invalid();
            if (!ATTRS.contains(name)) throw invalid();
            if ("href".equals(name) && !value.matches("#[A-Za-z_][\\w:.-]*")) throw invalid();
            if ("style".equals(name)) {
                for (String declaration : value.split(";")) {
                    if (declaration.isBlank()) continue;
                    String[] parts = declaration.split(":", 2);
                    if (parts.length != 2 || !STYLE_PROPS.contains(parts[0].strip().toLowerCase(Locale.ROOT))) throw invalid();
                    validateValue(parts[1]);
                }
            } else validateValue(value);
        }
        for (Node child = element.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child instanceof Element e) validate(e, depth + 1, count);
            else if (child.getNodeType() == Node.COMMENT_NODE) element.removeChild(child);
            else if (child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE) throw invalid();
            child = next;
        }
    }

    private void validateValue(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        String withoutLocalUrls = LOCAL_URL.matcher(lower).replaceAll("");
        if (withoutLocalUrls.contains("url(") || lower.contains("javascript:") || lower.contains("data:")
                || lower.contains("expression(") || lower.contains("\\") || lower.contains("@")
                || lower.contains("{") || lower.contains("}") || lower.contains("/*") || lower.contains("<")) throw invalid();
    }
    private BizException invalid() { return new BizException("SVG 无效或包含不支持的元素、脚本、事件或外部资源，请使用纯矢量图标"); }
}
