package org.bluezoo.json;

/**
 * Default implementation for the JSONContentHandler interface.
 * This implementation does nothing. It is simply intented to be a
 * convenient class to subclass if you only want to implement a subset of
 * the JSONContentHandler methods.
 *
 * @author Chris Burdess
 */
public class JSONDefaultHandler implements JSONContentHandler {

    @Override
    public void startObject() throws JSONException {
    }

    @Override
    public void endObject() throws JSONException {
    }

    @Override
    public void startArray() throws JSONException {
    }

    @Override
    public void endArray() throws JSONException {
    }

    @Override
    public void numberValue(Number number) throws JSONException {
    }

    @Override
    public void stringValue(String value) throws JSONException {
    }

    @Override
    public void booleanValue(boolean value) throws JSONException {
    }

    @Override
    public void nullValue() throws JSONException {
    }

    @Override
    public void whitespace(String whitespace) throws JSONException {
    }

    @Override
    public void key(String key) throws JSONException {
    }

    @Override
    public void setLocator(JSONLocator locator) {
    }

}
