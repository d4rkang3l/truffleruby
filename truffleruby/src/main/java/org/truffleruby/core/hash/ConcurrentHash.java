package org.truffleruby.core.hash;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.truffleruby.core.UnsafeHolder;
import org.truffleruby.language.objects.ObjectGraphNode;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.object.basic.DynamicObjectBasic;

public final class ConcurrentHash implements ObjectGraphNode {

    private final AtomicReferenceArray<ConcurrentEntry> buckets;

    public static void initialize(DynamicObject hash) {
        ConcurrentHash.setFirstInSequence(hash, new ConcurrentEntry(0, null, null));
        ConcurrentHash.setLastInSequence(hash, new ConcurrentEntry(0, null, null));
    }

    public static void linkFirstLast(DynamicObject hash, ConcurrentEntry first, ConcurrentEntry last) {
        ConcurrentEntry sentinelFirst = ConcurrentHash.getFirstInSequence(hash);
        ConcurrentEntry sentinelLast = ConcurrentHash.getLastInSequence(hash);

        if (first != null) {
            sentinelFirst.setNextInSequence(first);
            first.setPreviousInSequence(sentinelFirst);

            sentinelLast.setPreviousInSequence(last);
            last.setNextInSequence(sentinelLast);
        } else {
            sentinelFirst.setNextInSequence(sentinelLast);
            sentinelLast.setPreviousInSequence(sentinelFirst);
        }
    }

    public ConcurrentHash() {
        this(BucketsStrategy.INITIAL_CAPACITY);
    }

    public ConcurrentHash(int capacity) {
        this.buckets = new AtomicReferenceArray<>(capacity);
    }

    public AtomicReferenceArray<ConcurrentEntry> getBuckets() {
        return buckets;
    }

    public void getAdjacentObjects(Set<DynamicObject> reachable) {
        for (int i = 0; i < buckets.length(); i++) {
            ConcurrentEntry entry = buckets.get(i);
            while (entry != null) {
                if (entry.getKey() instanceof DynamicObject) {
                    reachable.add((DynamicObject) entry.getKey());
                }
                if (entry.getValue() instanceof DynamicObject) {
                    reachable.add((DynamicObject) entry.getValue());
                }
                entry = entry.getNextInLookup();
            }
        }
    }

    // {"compareByIdentity":boolean@1,
    // "defaultValue":Object[0],
    // "defaultBlock":Object@3,
    // "lastInSequence":Object@2,
    // "firstInSequence":Object@1,
    // "size":int@0,
    // "store":Object@0}

    private static final long STORE_OFFSET = UnsafeHolder.getFieldOffset(DynamicObjectBasic.class, "object1");
    private static final long SIZE_OFFSET = UnsafeHolder.getFieldOffset(DynamicObjectBasic.class, "primitive1");
    private static final long COMPARE_BY_IDENTITY_OFFSET = UnsafeHolder.getFieldOffset(DynamicObjectBasic.class, "primitive2");
    private static final long FIRST_IN_SEQ_OFFSET = UnsafeHolder.getFieldOffset(DynamicObjectBasic.class, "object2");
    private static final long LAST_IN_SEQ_OFFSET = UnsafeHolder.getFieldOffset(DynamicObjectBasic.class, "object3");

    public static ConcurrentHash getStore(DynamicObject hash) {
        return (ConcurrentHash) UnsafeHolder.UNSAFE.getObject(hash, STORE_OFFSET);
    }

    public static int getSize(DynamicObject hash) {
        return (int) UnsafeHolder.UNSAFE.getLongVolatile(hash, SIZE_OFFSET);
    }

    public static int incrementAndGetSize(DynamicObject hash) {
        return (int) UnsafeHolder.UNSAFE.getAndAddLong(hash, SIZE_OFFSET, 1) + 1;
    }

    public static void decrementSize(DynamicObject hash) {
        UnsafeHolder.UNSAFE.getAndAddLong(hash, SIZE_OFFSET, -1);
    }

    public static boolean compareAndSetCompareByIdentity(DynamicObject hash, boolean old, boolean newSize) {
        return UnsafeHolder.UNSAFE.compareAndSwapLong(hash, COMPARE_BY_IDENTITY_OFFSET, old ? 1L : 0L, newSize ? 1L : 0L);
    }

    public static ConcurrentEntry getFirstInSequence(DynamicObject hash) {
        return (ConcurrentEntry) UnsafeHolder.UNSAFE.getObject(hash, FIRST_IN_SEQ_OFFSET);
    }

    private static void setFirstInSequence(DynamicObject hash, ConcurrentEntry entry) {
        UnsafeHolder.UNSAFE.putObject(hash, FIRST_IN_SEQ_OFFSET, entry);
    }

    public static ConcurrentEntry getLastInSequence(DynamicObject hash) {
        return (ConcurrentEntry) UnsafeHolder.UNSAFE.getObject(hash, LAST_IN_SEQ_OFFSET);
    }

    private static void setLastInSequence(DynamicObject hash, ConcurrentEntry entry) {
        UnsafeHolder.UNSAFE.putObject(hash, LAST_IN_SEQ_OFFSET, entry);
    }

}