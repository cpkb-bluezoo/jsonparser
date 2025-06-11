# jsonparser
JSON parser and serializer for Java

## Parser
The parser follows the same pattern as the SAX API for parsing XML.
You create an implementation of the JSONContentHandler interface.
There is a handy JSONDefaultHandler class that you can subclass if you
only want to implement a subset of the methods. You register this
handler class on an instance of the JSONParser, then call parse().
Your handler will be notified of the events in the JSON stream, in order.

## Serializer
TODO
