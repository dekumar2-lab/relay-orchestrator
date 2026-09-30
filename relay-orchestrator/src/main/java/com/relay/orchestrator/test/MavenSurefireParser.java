package com.relay.orchestrator.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses JUnit XML reports produced by:
 * - Maven surefire: target/surefire-reports/TEST-*.xml
 * - Maven failsafe: target/failsafe-reports/TEST-*.xml
 * - Gradle: build/test-results/test/TEST-*.xml
 *
 * Same schema across all three.
 */
@Service
public class MavenSurefireParser {

    private static final Logger log = LoggerFactory.getLogger(MavenSurefireParser.class);

    private static final List<String> REPORT_DIRS = List.of(
            "target/surefire-reports",
            "target/failsafe-reports",
            "build/test-results/test");

    public List<TestFailure> parseFailures(Path repoRoot) {
        List<TestFailure> failures = new ArrayList<>();
        for (Path dir : reportDirs(repoRoot)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("TEST-"))
                        .filter(p -> p.getFileName().toString().endsWith(".xml"))
                        .forEach(p -> parseOne(p, failures));
            } catch (IOException e) {
                log.debug("Could not list {}: {}", dir, e.getMessage());
            }
        }
        return failures;
    }

    public int countTests(Path repoRoot) {
        int total = 0;
        for (Path dir : reportDirs(repoRoot)) {
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                        .filter(p -> p.getFileName().toString().endsWith(".xml"))
                        .toList();
                for (Path p : files) {
                    total += countTestsInFile(p);
                }
            } catch (IOException e) {
                log.debug("Could not list {}: {}", dir, e.getMessage());
            }
        }
        return total;
    }

    // ------------------------------------------------------------------

    private List<Path> reportDirs(Path repoRoot) {
        List<Path> out = new ArrayList<>();
        for (String rel : REPORT_DIRS) {
            Path p = repoRoot.resolve(rel);
            if (Files.isDirectory(p))
                out.add(p);
        }
        return out;
    }

    private void parseOne(Path xmlPath, List<TestFailure> out) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlPath.toFile());

            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Node n = testcases.item(i);
                if (!(n instanceof Element tc))
                    continue;
                String className = tc.getAttribute("classname");
                String testName = tc.getAttribute("name");

                Element failure = firstChild(tc, "failure");
                Element error = firstChild(tc, "error");
                Element skipped = firstChild(tc, "skipped");

                if (failure != null) {
                    out.add(new TestFailure(className, testName,
                            emptyIfNull(failure.getAttribute("message")),
                            truncate(failure.getTextContent(), 2000),
                            TestFailure.Kind.FAILURE));
                } else if (error != null) {
                    out.add(new TestFailure(className, testName,
                            emptyIfNull(error.getAttribute("message")),
                            truncate(error.getTextContent(), 2000),
                            TestFailure.Kind.ERROR));
                } else if (skipped != null) {
                    out.add(new TestFailure(className, testName,
                            "skipped",
                            "",
                            TestFailure.Kind.SKIPPED));
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.debug("Failed to parse {}: {}", xmlPath, e.getMessage());
        }
    }

    private int countTestsInFile(Path xmlPath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlPath.toFile());

            NodeList suites = doc.getElementsByTagName("testsuite");
            if (suites.getLength() > 0) {
                String tests = ((Element) suites.item(0)).getAttribute("tests");
                if (!tests.isEmpty())
                    return Integer.parseInt(tests);
            }
            return doc.getElementsByTagName("testcase").getLength();
        } catch (Exception e) {
            return 0;
        }
    }

    private Element firstChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0)
            return null;
        Node n = list.item(0);
        return n instanceof Element ? (Element) n : null;
    }

    private String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }
}