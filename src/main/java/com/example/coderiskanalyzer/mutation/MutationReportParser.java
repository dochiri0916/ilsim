package com.example.coderiskanalyzer.mutation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.stereotype.Component;

/** Reads PIT's XML report without treating unknown future statuses as successful evidence. */
@Component
public class MutationReportParser {
    public List<MutationEvidence> parse(Path report) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList nodes = factory.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("mutation");
            List<MutationEvidence> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element mutation = (Element) nodes.item(i);
                result.add(new MutationEvidence(child(mutation, "mutatedClass"), child(mutation, "mutatedMethod"),
                        integer(child(mutation, "lineNumber")), child(mutation, "mutator"),
                        status(mutation.hasAttribute("status") ? mutation.getAttribute("status") : child(mutation, "status")),
                        child(mutation, "description")));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IOException("Could not parse PIT XML report", exception);
        }
    }

    private String child(Element element, String name) {
        NodeList children = element.getElementsByTagName(name);
        if (children.getLength() == 0) return "";
        Node item = children.item(0);
        return item.getTextContent() == null ? "" : item.getTextContent().trim();
    }

    private int integer(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private MutationStatus status(String value) {
        return switch (value) {
            case "KILLED", "SURVIVED", "NO_COVERAGE", "NON_VIABLE", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR" -> MutationStatus.valueOf(value);
            default -> MutationStatus.UNKNOWN;
        };
    }
}
