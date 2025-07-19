# jsonparser
JSON parser and serializer for Java

This is a tiny and efficient JSON parser and serialization library for Java.
It takes up only 20KB, as opposed to object-mapping-based JSON parsers such as
Apache Jackson (600KB), and is fast and conformant.

Full JavaDoc documentation is included in the package, see the doc subdirectory.

## Parser
The parser follows the same pattern as the SAX API for parsing XML.
You create an implementation of the JSONContentHandler interface.
There is a handy JSONDefaultHandler class that you can subclass if you
only want to implement a subset of the methods. You register this
handler class on an instance of the JSONParser, then call parse().
Your handler will be notified of the events in the JSON stream, in order.

### Example
Here is an example that will simply list all the field names in a JSON file:

    import java.io.*;
    import org.bluezoo.json.*;
    
    public class ListFieldNames extends JSONDefaultHandler {
    
        public void key(String key) throws JSONException {
            System.out.println(key);
        }
    
        public static void main(String[] args) throws Exception {
            InputStream in = null;
            try {
                in = new FileInputStream(args[0]);
                JSONParser parser = new JSONParser();
                parser.setContentHandler(new ListFieldNames());
                parser.parse(in);
            } finally {
                in.close();
            }
        }
    
    }

## Serializer
The parser follows the same pattern as the javax.xml.stream API for writing XML.
You create an instance of JSONStreamWriter that wraps an output stream.
You then call the JSON value construction methods, in order, to construct
the JSON output representation.
Note that no special handling is applied to the stream; you will need to flush
and/or close it yourself if required.
The JSONStreamWriter can be configured with an indent argument to beautify
the output and make it more easily human-readable.

### Example
Here is an example to create a simple JSON file:

    import java.io.*;
    import org.bluezoo.json.*;
    
    public class MakeJohnSmith {
    
        public static void main(String[] args) throws Exception {
            JSONStreamWriter writer = new JSONStreamWriter(System.out, true);
            writer.writeStartObject();
            writer.writeKey("first_name");
            writer.writeString("John");
            writer.writeKey("last_name");
            writer.writeString("Smith");
            writer.writeKey("is_alive");
            writer.writeBoolean(true);
            writer.writeKey("age");
            writer.writeNumber(27);
            writer.writeKey("height");
            writer.writeNumber(6.01);
            writer.writeKey("address");
            writer.writeStartObject();
            writer.writeKey("street_address");
            writer.writeString("21 2nd Street");
            writer.writeKey("city");
            writer.writeString("New York");
            writer.writeEndObject(); // address
            writer.writeKey("phone_numbers");
            writer.writeStartArray();
            writer.writeString("212 555-1234");
            writer.writeString("646 555-4567");
            writer.writeEndArray();
            writer.writeKey("spouse");
            writer.writeNull();
            writer.writeEndObject(); // top level object
            System.out.flush();
        }
    
    }

## Installation
An Apache Ant build file is included.

    git clone https://github.com/cpkb-bluezoo/jsonparser.git
    cd jsonparser
    ant dist

This will create a jar file in the dist subdirectory that you can add to your classpath.

## Conformance
The parser has been tested with [JSONTestSuite](https://github.com/nst/JSONTestSuite)
and is fully conformant with that test suite.

## Maven integration
You can incorporate this project directly into your Maven project.
To do so, add the following elements to your project's pom.xml :

    <repositories>
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>com.github.cpkb-bluezoo</groupId>
            <artifactId>jsonparser</artifactId>
            <version>v1.0.1</version>
        </dependency>
    </dependencies>

