package com.localcanvas.app;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full structural validator for GGUF (v1-v3) and SafeTensors model files,
 * plus strict HTTP Content-Range / resume validation.
 */
public final class DownloadValidator {
    private static final long MAX_SAFE_TENSOR_HEADER = 100L * 1024L * 1024L;
    private static final long MAX_GGUF_TENSORS = 1_000_000L;
    private static final long MAX_GGUF_METADATA = 1_000_000L;
    private static final long MAX_GGUF_STRING = 16L * 1024L * 1024L;
    private static final int MAX_GGUF_ARRAY_DEPTH = 4;

    private DownloadValidator() {}

    public static final class Range {
        public final long start, end, total;
        public Range(long s, long e, long t) { start = s; end = e; total = t; }
    }

    public static Range parseContentRange(String value) throws IOException {
        if (value == null || !value.startsWith("bytes ")) {
            throw new IOException("Content-Range 없음");
        }
        int dash = value.indexOf('-', 6);
        int slash = value.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) {
            throw new IOException("Content-Range 형식 오류");
        }
        try {
            long s = Long.parseLong(value.substring(6, dash).trim());
            long e = Long.parseLong(value.substring(dash + 1, slash).trim());
            long t = Long.parseLong(value.substring(slash + 1).trim());
            if (s < 0 || e < s || t <= e) {
                throw new IOException("Content-Range 범위 오류");
            }
            return new Range(s, e, t);
        } catch (NumberFormatException e) {
            throw new IOException("Content-Range 숫자 오류", e);
        }
    }

    public static boolean acceptsResume(String range, long offset, long localLength) throws IOException {
        Range r = parseContentRange(range);
        return r.start == offset && offset == localLength && r.end == r.total - 1;
    }

    public static boolean isSupportedModel(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 16) return false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (new String(magic, StandardCharsets.US_ASCII).equals("GGUF")) {
                return validGguf(in, file.length());
            }
            return validSafeTensors(in, file.length());
        } catch (EOFException e) {
            return false;
        }
    }

    private static boolean validGguf(RandomAccessFile in, long fileLength) throws IOException {
        if (fileLength < 24) return false;
        long version = readUnsignedLe32(in);
        long tensorCount = readLe64(in);
        long metadataCount = readLe64(in);
        if (version < 1 || version > 3 || tensorCount <= 0 || metadataCount <= 0
                || tensorCount > MAX_GGUF_TENSORS || metadataCount > MAX_GGUF_METADATA) {
            return false;
        }

        long alignment = 32;
        for (long i = 0; i < metadataCount; i++) {
            String key = readGgufString(in, fileLength);
            if (key == null) return false;
            int valueType = (int) readUnsignedLe32(in);
            if ("general.alignment".equals(key)) {
                if (valueType == 4) {
                    long a = readUnsignedLe32(in);
                    if (a <= 0 || (a & (a - 1)) != 0) return false;
                    alignment = a;
                    continue;
                } else if (valueType == 10) {
                    long a = readLe64(in);
                    if (a <= 0 || (a & (a - 1)) != 0) return false;
                    alignment = a;
                    continue;
                }
            }
            if (!skipGgufValue(in, valueType, fileLength, 0)) return false;
        }

        long maxTensorEnd = 0;
        for (long i = 0; i < tensorCount; i++) {
            String name = readGgufString(in, fileLength);
            if (name == null || name.isEmpty()) return false;
            long nDims = readUnsignedLe32(in);
            if (nDims < 1 || nDims > 8) return false;
            long elements = 1;
            for (long d = 0; d < nDims; d++) {
                long dim = readLe64(in);
                if (dim <= 0) return false;
                if (elements > Long.MAX_VALUE / dim) return false;
                elements *= dim;
            }
            int ggmlType = (int) readUnsignedLe32(in);
            if (ggmlType < 0 || ggmlType > 64) return false;
            long offset = readLe64(in);
            if (offset < 0) return false;
            long minBytes = Math.max(1L, elements / 256L);
            if (offset > Long.MAX_VALUE - minBytes) return false;
            long end = offset + minBytes;
            if (end > maxTensorEnd) maxTensorEnd = end;
        }

        long pos = in.getFilePointer();
        long rem = pos % alignment;
        long dataStart = rem == 0 ? pos : pos + (alignment - rem);
        if (dataStart < pos || dataStart > fileLength) return false;
        return dataStart + maxTensorEnd <= fileLength;
    }

    private static String readGgufString(RandomAccessFile in, long fileLength) throws IOException {
        long len = readLe64(in);
        if (len < 0 || len > MAX_GGUF_STRING || in.getFilePointer() + len > fileLength) return null;
        byte[] buf = new byte[(int) len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static boolean skipGgufValue(RandomAccessFile in, int type, long fileLength, int depth) throws IOException {
        if (depth > MAX_GGUF_ARRAY_DEPTH) return false;
        switch (type) {
            case 0: case 1: case 7: return skipBytesChecked(in, 1, fileLength);
            case 2: case 3: return skipBytesChecked(in, 2, fileLength);
            case 4: case 5: case 6: return skipBytesChecked(in, 4, fileLength);
            case 10: case 11: case 12: return skipBytesChecked(in, 8, fileLength);
            case 8: return readGgufString(in, fileLength) != null;
            case 9: {
                int elemType = (int) readUnsignedLe32(in);
                long count = readLe64(in);
                if (count < 0 || count > MAX_GGUF_TENSORS) return false;
                for (long i = 0; i < count; i++) {
                    if (!skipGgufValue(in, elemType, fileLength, depth + 1)) return false;
                }
                return true;
            }
            default:
                return false;
        }
    }

    private static boolean skipBytesChecked(RandomAccessFile in, long n, long fileLength) throws IOException {
        long pos = in.getFilePointer();
        if (n < 0 || pos + n > fileLength) return false;
        in.seek(pos + n);
        return true;
    }

    private static boolean validSafeTensors(RandomAccessFile in, long fileLength) throws IOException {
        // isSupportedModel reads the four-byte GGUF discriminator first. SafeTensors
        // has no discriminator, so rewind before reading its eight-byte header length.
        in.seek(0L);
        long headerLength = Long.reverseBytes(in.readLong());
        if (headerLength <= 0 || headerLength > fileLength - 8 || headerLength > MAX_SAFE_TENSOR_HEADER) {
            return false;
        }
        byte[] header = new byte[(int) headerLength];
        in.readFully(header);
        String json = new String(header, StandardCharsets.UTF_8).trim();
        Object parsed;
        try {
            parsed = new JsonParser(json).parse();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!(parsed instanceof Map<?, ?>)) return false;
        Map<?, ?> root = (Map<?, ?>) parsed;
        long dataStart = 8L + headerLength;
        int tensorCount = 0;
        List<long[]> intervals = new ArrayList<>();

        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("__metadata__".equals(key)) {
                if (!(entry.getValue() instanceof Map<?, ?>)) return false;
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?>)) return false;
            Map<?, ?> meta = (Map<?, ?>) entry.getValue();
            Object dtypeObj = meta.get("dtype");
            Object shapeObj = meta.get("shape");
            Object offsetsObj = meta.get("data_offsets");
            if (!(dtypeObj instanceof String) || !(shapeObj instanceof List<?>) || !(offsetsObj instanceof List<?>)) {
                return false;
            }
            int elemBytes = dtypeSize((String) dtypeObj);
            if (elemBytes <= 0) return false;

            List<?> shape = (List<?>) shapeObj;
            long elements = 1;
            for (Object dimObj : shape) {
                if (!(dimObj instanceof Number)) return false;
                long d = ((Number) dimObj).longValue();
                if (d < 0) return false;
                if (d > 0 && elements > Long.MAX_VALUE / d) return false;
                elements *= d;
            }

            List<?> offsets = (List<?>) offsetsObj;
            if (offsets.size() != 2 || !(offsets.get(0) instanceof Number) || !(offsets.get(1) instanceof Number)) {
                return false;
            }
            long start = ((Number) offsets.get(0)).longValue();
            long end = ((Number) offsets.get(1)).longValue();
            if (start < 0 || end < start || dataStart > fileLength || end > fileLength - dataStart) {
                return false;
            }
            if (elements > Long.MAX_VALUE / elemBytes) return false;
            long expectedBytes = elements * elemBytes;
            if ((end - start) != expectedBytes) {
                return false;
            }
            if (end > start) {
                intervals.add(new long[]{start, end});
            }
            tensorCount++;
        }
        if (tensorCount == 0) return false;

        // Verify no overlapping tensor byte ranges
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        for (int i = 1; i < intervals.size(); i++) {
            if (intervals.get(i)[0] < intervals.get(i - 1)[1]) {
                return false;
            }
        }
        return true;
    }

    private static int dtypeSize(String dtype) {
        switch (dtype) {
            case "F64": case "I64": return 8;
            case "F32": case "I32": return 4;
            case "F16": case "BF16": case "I16": case "U16": return 2;
            case "I8": case "U8": case "BOOL": case "F8_E4M3": case "F8_E5M2": return 1;
            default: return -1;
        }
    }

    private static long readUnsignedLe32(RandomAccessFile in) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
    }

    private static long readLe64(RandomAccessFile in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }

    private static final class JsonParser {
        private final String s;
        private int pos = 0;

        JsonParser(String s) { this.s = s; }

        Object parse() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos != s.length()) throw new IllegalArgumentException("Trailing JSON content");
            return v;
        }

        private Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected EOF");
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { expect("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw new IllegalArgumentException("Unexpected char: " + c);
        }

        private Map<String, Object> parseObject() {
            pos++;
            Map<String, Object> map = new HashMap<>();
            skipWs();
            if (peek('}')) { pos++; return map; }
            while (true) {
                skipWs();
                if (!peek('"')) throw new IllegalArgumentException("Expected key string");
                String key = parseString();
                skipWs();
                if (!peek(':')) throw new IllegalArgumentException("Expected ':'");
                pos++;
                if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; return map; }
                throw new IllegalArgumentException("Expected ',' or '}'");
            }
        }

        private List<Object> parseArray() {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; return list; }
                throw new IllegalArgumentException("Expected ',' or ']'");
            }
        }

        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("Unterminated escape");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': case '\\': case '/': sb.append(esc); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Bad boolean");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isFloat = false;
            if (peek('.')) {
                isFloat = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (peek('e') || peek('E')) {
                isFloat = true;
                pos++;
                if (peek('+') || peek('-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            try {
                return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number: " + num);
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("Expected " + lit);
            pos += lit.length();
        }

        private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }
        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
