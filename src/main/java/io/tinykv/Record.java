package io.tinykv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Core data models: Key, LogRecord, RecordType, IndexEntry, and Hint.
 */
public final class Record {

    private Record() {}

    public enum Type {
        PUT((byte) 1),
        DELETE((byte) 2);

        private final byte code;
        Type(byte code) { this.code = code; }
        public byte getCode() { return code; }
        public static Type fromCode(byte b) {
            return b == 1 ? PUT : b == 2 ? DELETE : null;
        }
    }

    public static final class Key implements Comparable<Key> {
        private final byte[] bytes;
        private final int hash;

        private Key(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "Key bytes cannot be null");
            if (bytes.length == 0) throw new IllegalArgumentException("Key cannot be empty");
            this.hash = Arrays.hashCode(bytes);
        }

        public static Key of(byte[] bytes) { return new Key(bytes.clone()); }
        public static Key of(String utf8) { return of(utf8.getBytes(StandardCharsets.UTF_8)); }

        public byte[] getBytes() { return bytes.clone(); }
        public int length() { return bytes.length; }
        public String asUtf8() { return new String(bytes, StandardCharsets.UTF_8); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other) || this.hash != other.hash) return false;
            return Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() { return hash; }

        @Override
        public int compareTo(Key o) { return Arrays.compareUnsigned(this.bytes, o.bytes); }

        @Override
        public String toString() { return asUtf8(); }
    }

    public record LogRecord(long timestamp, Type type, Key key, byte[] value, long crc) {
        public LogRecord {
            Objects.requireNonNull(type);
            Objects.requireNonNull(key);
            value = (value != null) ? value.clone() : new byte[0];
        }

        public static LogRecord put(Key key, byte[] val, long ts) {
            return new LogRecord(ts, Type.PUT, key, val, 0L);
        }

        public static LogRecord delete(Key key, long ts) {
            return new LogRecord(ts, Type.DELETE, key, new byte[0], 0L);
        }

        public boolean isTombstone() { return type == Type.DELETE; }
        @Override public byte[] value() { return value.clone(); }
    }

    public record IndexEntry(long segmentId, long offset, int length, long timestamp) {}

    public record Hint(long timestamp, Key key, long segmentId, long offset, int length) {}
}
