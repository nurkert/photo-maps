package eu.nurkert.photomaps.web;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * A minimal DER writer, enough for a self-signed certificate and a certificate signing request.
 */
final class Der {

    private Der() {}

    static byte[] time(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            int shift = 63 - Long.numberOfLeadingZeros(value | 1);
            for (int group = shift / 7; group > 0; group--) {
                out.write((int) (0x80 | (value >> (7 * group)) & 0x7F));
            }
            out.write((int) (value & 0x7F));
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    /** A UTF8String. */
    static byte[] utf8(String text) {
        return tlv(0x0C, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A BIT STRING without unused bits. */
    static byte[] bits(byte[] content) {
        byte[] bits = new byte[content.length + 1];
        System.arraycopy(content, 0, bits, 1, content.length);
        return tlv(0x03, bits);
    }

    /** A Name with only a common name. */
    static byte[] commonName(String name) {
        return seq(set(seq(oid("2.5.4.3"), utf8(name))));
    }

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        int length = content.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = (32 - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >> (8 * i));
            }
        }
        out.writeBytes(content.toByteArray());
        return out.toByteArray();
    }
}
