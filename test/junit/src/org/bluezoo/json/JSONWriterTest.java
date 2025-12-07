package org.bluezoo.json;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class JSONWriterTest {

    @Test
    public void testSimpleObject() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("name");
        writer.writeString("Alice");
        writer.writeKey("age");
        writer.writeNumber(30);
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"name\":\"Alice\",\"age\":30}", json);
    }

    @Test
    public void testSimpleArray() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartArray();
        writer.writeNumber(1);
        writer.writeNumber(2);
        writer.writeNumber(3);
        writer.writeEndArray();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("[1,2,3]", json);
    }

    @Test
    public void testNestedStructures() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("users");
        writer.writeStartArray();
        
        writer.writeStartObject();
        writer.writeKey("id");
        writer.writeNumber(1);
        writer.writeKey("name");
        writer.writeString("Alice");
        writer.writeEndObject();
        
        writer.writeStartObject();
        writer.writeKey("id");
        writer.writeNumber(2);
        writer.writeKey("name");
        writer.writeString("Bob");
        writer.writeEndObject();
        
        writer.writeEndArray();
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"users\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}", json);
    }

    @Test
    public void testStringEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("text");
        writer.writeString("Hello\n\"World\"\t\r\b\f\\");
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"text\":\"Hello\\n\\\"World\\\"\\t\\r\\b\\f\\\\\"}", json);
    }

    @Test
    public void testControlCharacterEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("ctrl");
        writer.writeString("test\u0001\u001fend");
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"ctrl\":\"test\\u0001\\u001fend\"}", json);
    }

    @Test
    public void testUtf8Characters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("emoji");
        writer.writeString("Hello 👋 World 🌍");
        writer.writeKey("chinese");
        writer.writeString("你好世界");
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("Hello 👋 World 🌍"));
        assertTrue(json.contains("你好世界"));
    }

    @Test
    public void testPrimitiveValues() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("bool_true");
        writer.writeBoolean(true);
        writer.writeKey("bool_false");
        writer.writeBoolean(false);
        writer.writeKey("null");
        writer.writeNull();
        writer.writeKey("int");
        writer.writeNumber(42);
        writer.writeKey("float");
        writer.writeNumber(3.14);
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"bool_true\":true,\"bool_false\":false,\"null\":null,\"int\":42,\"float\":3.14}", json);
    }

    @Test
    public void testLargeOutput() throws Exception {
        // Test that automatic flushing works with large output
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartArray();
        for (int i = 0; i < 1000; i++) {
            writer.writeStartObject();
            writer.writeKey("id");
            writer.writeNumber(i);
            writer.writeKey("value");
            writer.writeString("Item " + i);
            writer.writeEndObject();
        }
        writer.writeEndArray();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("[{\"id\":0,\"value\":\"Item 0\"}"));
        assertTrue(json.endsWith("{\"id\":999,\"value\":\"Item 999\"}]"));
    }

    @Test
    public void testEmptyStructures() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);

        writer.writeStartObject();
        writer.writeKey("empty_array");
        writer.writeStartArray();
        writer.writeEndArray();
        writer.writeKey("empty_object");
        writer.writeStartObject();
        writer.writeEndObject();
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("{\"empty_array\":[],\"empty_object\":{}}", json);
    }

    @Test
    public void testIndentedOutput() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out, IndentConfig.tabs());

        writer.writeStartObject();
        writer.writeKey("name");
        writer.writeString("Alice");
        writer.writeKey("age");
        writer.writeNumber(30);
        writer.writeKey("hobbies");
        writer.writeStartArray();
        writer.writeString("reading");
        writer.writeString("coding");
        writer.writeEndArray();
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String expected = "{\n" +
                "\t\"name\": \"Alice\",\n" +
                "\t\"age\": 30,\n" +
                "\t\"hobbies\": [\n" +
                "\t\t\"reading\",\n" +
                "\t\t\"coding\"\n" +
                "\t]\n" +
                "}";
        assertEquals(expected, json);
    }

    @Test
    public void testIndentedOutputWithSpaces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out, IndentConfig.spaces2());

        writer.writeStartObject();
        writer.writeKey("x");
        writer.writeNumber(1);
        writer.writeEndObject();
        writer.close();

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String expected = "{\n  \"x\": 1\n}";
        assertEquals(expected, json);
    }
}

