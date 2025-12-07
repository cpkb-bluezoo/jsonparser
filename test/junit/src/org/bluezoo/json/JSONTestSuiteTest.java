package org.bluezoo.json;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Parameterized test for JSON parser using the JSONTestSuite.
 * 
 * Test file naming convention:
 * - y_*.json - Files that SHOULD be parsed successfully
 * - n_*.json - Files that SHOULD fail to parse
 * - i_*.json - Implementation-defined (may pass or fail)
 * 
 * This test runs the parser against all test files in JSONTestSuite/test_parsing
 * and validates the behavior matches expectations based on the file name prefix.
 */
@RunWith(Parameterized.class)
public class JSONTestSuiteTest {
    
    private static final String TEST_DIR = "JSONTestSuite/test_parsing";
    
    private final File testFile;
    private final ExpectedResult expectedResult;
    
    /**
     * Expected result based on test file naming convention
     */
    private enum ExpectedResult {
        SHOULD_PASS,      // y_*.json
        SHOULD_FAIL,      // n_*.json
        IMPLEMENTATION    // i_*.json
    }
    
    /**
     * Constructor called by JUnit for each test case
     */
    public JSONTestSuiteTest(File testFile, ExpectedResult expectedResult) {
        this.testFile = testFile;
        this.expectedResult = expectedResult;
    }
    
    /**
     * Generates the collection of test parameters (all JSON files in test_parsing directory)
     */
    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File testDir = new File(TEST_DIR);
        if (!testDir.exists() || !testDir.isDirectory()) {
            fail("Test directory not found: " + TEST_DIR);
        }
        
        File[] files = testDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            fail("No test files found in: " + TEST_DIR);
        }
        
        // Sort files for consistent test ordering
        List<File> fileList = new ArrayList<>();
        Collections.addAll(fileList, files);
        Collections.sort(fileList);
        
        List<Object[]> params = new ArrayList<>();
        for (File file : fileList) {
            String name = file.getName();
            ExpectedResult expected;
            
            if (name.startsWith("y_")) {
                expected = ExpectedResult.SHOULD_PASS;
            } else if (name.startsWith("n_")) {
                expected = ExpectedResult.SHOULD_FAIL;
            } else if (name.startsWith("i_")) {
                expected = ExpectedResult.IMPLEMENTATION;
            } else {
                // Skip files that don't match the naming convention
                continue;
            }
            
            params.add(new Object[]{file, expected});
        }
        
        return params;
    }
    
    /**
     * Test method that runs for each test file
     */
    @Test
    public void testJSONFile() throws Exception {
        boolean parseSucceeded = false;
        JSONException caughtException = null;
        
        try {
            // Attempt to parse the test file
            parseFile(testFile);
            parseSucceeded = true;
        } catch (JSONException e) {
            // Parser rejected the file
            caughtException = e;
            parseSucceeded = false;
        }
        
        // Validate result based on expected behavior
        String fileName = testFile.getName();
        
        switch (expectedResult) {
            case SHOULD_PASS:
                if (!parseSucceeded) {
                    fail(String.format(
                        "File '%s' should have been parsed successfully but failed with: %s",
                        fileName,
                        caughtException != null ? caughtException.getMessage() : "unknown error"
                    ));
                }
                break;
                
            case SHOULD_FAIL:
                if (parseSucceeded) {
                    fail(String.format(
                        "File '%s' should have failed to parse but was accepted",
                        fileName
                    ));
                }
                break;
                
            case IMPLEMENTATION:
                // Implementation-defined: both pass and fail are acceptable
                // Just log the result for informational purposes
                // No assertion needed
                break;
        }
    }
    
    /**
     * Parse a JSON file using the JSONParser
     */
    private void parseFile(File file) throws Exception {
        JSONParser parser = new JSONParser();
        
        // Use a simple content handler that just receives events
        // We're only testing whether parsing succeeds or fails
        parser.setContentHandler(new JSONDefaultHandler());
        
        try (InputStream in = new FileInputStream(file)) {
            parser.parse(in);
        }
    }
}

