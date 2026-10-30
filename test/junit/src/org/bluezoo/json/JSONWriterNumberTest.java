package org.bluezoo.json;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * JSON has no representation for NaN or the infinities, so the writer
 * refuses them rather than emit text that no JSON parser (including this
 * library's own) will accept. Every other number is written as before.
 */
public class JSONWriterNumberTest {

    private static final Number[] NOT_REPRESENTABLE = {
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
    };

    private static String write(Number value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        writer.writeStartArray();
        writer.writeNumber(value);
        writer.writeEndArray();
        writer.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void nanAndInfinitiesAreRejected() throws Exception {
        for (Number n : NOT_REPRESENTABLE) {
            try {
                write(n);
                fail(n.getClass().getSimpleName() + " " + n + " cannot be written as JSON");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(n.toString()));
            }
        }
    }

    @Test
    public void aRejectedNumberLeavesTheWriterUsable() throws Exception {
        // The check comes before any separator or indentation is written, so
        // a rejected value leaves the output exactly as it was
        for (IndentConfig indent : new IndentConfig[] {null, IndentConfig.spaces2()}) {
            for (Number bad : NOT_REPRESENTABLE) {
                ByteArrayOutputStream withFailure = new ByteArrayOutputStream();
                JSONWriter writer = new JSONWriter(withFailure, indent);
                writer.writeStartArray();
                writer.writeNumber(1);
                try {
                    writer.writeNumber(bad);
                    fail("rejected");
                } catch (IllegalArgumentException expected) {
                    // continue as if the call had not been made
                }
                writer.writeNumber(2);
                writer.writeEndArray();
                writer.close();

                ByteArrayOutputStream clean = new ByteArrayOutputStream();
                JSONWriter reference = new JSONWriter(clean, indent);
                reference.writeStartArray();
                reference.writeNumber(1);
                reference.writeNumber(2);
                reference.writeEndArray();
                reference.close();

                assertArrayEquals(bad + (indent == null ? "" : " indented"), clean.toByteArray(),
                        withFailure.toByteArray());
            }
        }
    }

    @Test
    public void everyOtherNumberIsStillWrittenAndReadsBack() throws Exception {
        Number[] ok = {
            0, -1, Integer.MAX_VALUE, Long.MIN_VALUE, new BigInteger("123456789012345678901234567890"),
            0.0, -0.0, 1.5, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE,
            Float.MAX_VALUE, Float.MIN_VALUE, 1.0e-7f, new BigDecimal("1E+3"), new BigDecimal("-0.000123"),
        };
        for (Number n : ok) {
            String json = write(n);
            final int[] numbers = new int[1];
            JSONParser parser = new JSONParser();
            parser.setContentHandler(new JSONDefaultHandler() {
                @Override
                public void numberValue(Number number) {
                    numbers[0]++;
                }
            });
            parser.receive(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            parser.close();
            assertEquals(n + " -> " + json, 1, numbers[0]);
        }
    }
}
