package org.bluezoo.json;

/**
 * Test helper: a handler that reassembles streamed string content and reports
 * each complete string to {@link #stringValue(String)}, for tests that care
 * about string values rather than how they were delivered.
 */
abstract class StringAssemblingHandler extends JSONDefaultHandler {

    private StringBuilder current;

    @Override
    public final void startStringValue() {
        current = new StringBuilder();
    }

    @Override
    public final void stringValueContent(CharSequence content) {
        current.append(content);
    }

    @Override
    public final void endStringValue() throws JSONException {
        String value = current.toString();
        current = null;
        stringValue(value);
    }

    /** Called with the complete value of each string. */
    public void stringValue(String value) throws JSONException {
    }
}
