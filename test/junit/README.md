# JUnit Test Suite for JSON Parser

This directory contains JUnit 4 unit tests for the JSON parser.

## JSONTestSuite Test

The `JSONTestSuiteTest` is a parameterized JUnit test that validates the parser against the comprehensive [JSONTestSuite](https://github.com/nst/JSONTestSuite) test collection.

### Test File Naming Convention

The test files in `JSONTestSuite/test_parsing/` follow a specific naming convention:

- **`y_*.json`** - Files that SHOULD be parsed successfully (valid JSON)
- **`n_*.json`** - Files that SHOULD fail to parse (invalid JSON)
- **`i_*.json`** - Implementation-defined behavior (may pass or fail)

### Running the Tests

To build and run all JUnit tests:

```bash
ant junit-test
```

To clean and rebuild:

```bash
ant clean-junit junit-build junit-test
```

### Test Results

The test suite currently tests **318 test files** from the JSONTestSuite.

Results are written to `test/junit/results/` in plain text format.

### Understanding Test Failures

Test failures indicate differences between the parser's behavior and the JSONTestSuite expectations:

1. **Parser too permissive**: Accepts malformed JSON that should fail (files starting with `n_`)
2. **Parser too strict**: Rejects valid JSON that should succeed (files starting with `y_`)
3. **Implementation-defined**: Files starting with `i_` can pass or fail without causing test failures

These results help document the parser's conformance level and identify areas for improvement.

## Adding More Tests

To add additional unit tests:

1. Create a new test class in `test/junit/src/org/bluezoo/json/`
2. Name the file with the suffix `*Test.java`
3. The ant build will automatically discover and run it

## Dependencies

- JUnit 4.13.1 (`test/junit/lib/junit-4.13.1.jar`)
- Hamcrest 3.0 (`test/junit/lib/hamcrest-3.0.jar`)

