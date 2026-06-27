package com.example.pushdown.aggregate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Standard {@link MergeFunction} implementations for the common SQL
 * aggregate functions: {@code SUM}, {@code COUNT}, {@code AVG}, {@code MIN},
 * {@code MAX}.
 *
 * <p>State is serialized via Java serialization (the intermediate states are
 * simple boxed numbers or the {@link AvgState} record, all of which are
 * {@link Serializable}). This keeps the on-wire format self-describing
 * without coupling the framework to a specific columnar encoding.
 */
public final class MergeFunctions {

    private MergeFunctions() {}

    /** Intermediate state for {@code AVG}: sum of values plus row count. */
    public record AvgState(BigDecimal sum, long count) implements Serializable {}

    public static MergeFunction sum() {
        return new SumMergeFunction();
    }

    public static MergeFunction count() {
        return new CountMergeFunction();
    }

    public static MergeFunction avg() {
        return new AvgMergeFunction();
    }

    public static MergeFunction min() {
        return new MinMergeFunction();
    }

    public static MergeFunction max() {
        return new MaxMergeFunction();
    }

    // ----- helpers -----

    private static byte[] toBytes(Object obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T fromBytes(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    // ----- implementations -----

    private static class SumMergeFunction implements MergeFunction {
        @Override
        public Object merge(Object p1, Object p2) {
            if (p1 instanceof Integer && p2 instanceof Integer) {
                return (Integer) p1 + (Integer) p2;
            }
            if (p1 instanceof Long && p2 instanceof Long) {
                return (Long) p1 + (Long) p2;
            }
            if (p1 instanceof BigDecimal && p2 instanceof BigDecimal) {
                return ((BigDecimal) p1).add((BigDecimal) p2);
            }
            if (p1 instanceof Double && p2 instanceof Double) {
                return (Double) p1 + (Double) p2;
            }
            throw new IllegalArgumentException("Unsupported type for SUM merge: " + p1.getClass());
        }

        @Override
        public byte[] serialize(Object state) { return toBytes(state); }

        @Override
        public Object deserialize(byte[] data) { return fromBytes(data); }

        @Override
        public Object finalize(Object state) { return state; } // SUM final = intermediate
    }

    private static class CountMergeFunction implements MergeFunction {
        @Override
        public Object merge(Object p1, Object p2) {
            return (Long) p1 + (Long) p2;
        }

        @Override
        public byte[] serialize(Object state) { return toBytes(state); }

        @Override
        public Object deserialize(byte[] data) { return fromBytes(data); }

        @Override
        public Object finalize(Object state) { return state; }
    }

    private static class AvgMergeFunction implements MergeFunction {
        @Override
        public Object merge(Object p1, Object p2) {
            AvgState s1 = (AvgState) p1;
            AvgState s2 = (AvgState) p2;
            return new AvgState(s1.sum().add(s2.sum()), s1.count() + s2.count());
        }

        @Override
        public byte[] serialize(Object state) { return toBytes(state); }

        @Override
        public Object deserialize(byte[] data) { return fromBytes(data); }

        @Override
        public Object finalize(Object state) {
            AvgState s = (AvgState) state;
            if (s.count() == 0) {
                return null;
            }
            return s.sum().doubleValue() / s.count();
        }
    }

    private static class MinMergeFunction implements MergeFunction {
        @SuppressWarnings("unchecked")
        @Override
        public Object merge(Object p1, Object p2) {
            return ((Comparable<Object>) p1).compareTo(p2) <= 0 ? p1 : p2;
        }

        @Override
        public byte[] serialize(Object state) { return toBytes(state); }

        @Override
        public Object deserialize(byte[] data) { return fromBytes(data); }

        @Override
        public Object finalize(Object state) { return state; }
    }

    private static class MaxMergeFunction implements MergeFunction {
        @SuppressWarnings("unchecked")
        @Override
        public Object merge(Object p1, Object p2) {
            return ((Comparable<Object>) p1).compareTo(p2) >= 0 ? p1 : p2;
        }

        @Override
        public byte[] serialize(Object state) { return toBytes(state); }

        @Override
        public Object deserialize(byte[] data) { return fromBytes(data); }

        @Override
        public Object finalize(Object state) { return state; }
    }
}
