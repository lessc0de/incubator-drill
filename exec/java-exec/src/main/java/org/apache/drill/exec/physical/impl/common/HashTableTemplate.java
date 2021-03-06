/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl.common;

import java.util.ArrayList;

import javax.inject.Named;

import org.apache.drill.common.expression.ExpressionPosition;
import org.apache.drill.common.expression.FieldReference;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.logical.data.NamedExpression;
import org.apache.drill.common.types.Types;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.compile.sig.RuntimeOverridden;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.expr.holders.IntHolder;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.vector.IntVector;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.allocator.VectorAllocator;
import org.apache.drill.exec.vector.BigIntVector;
import org.apache.drill.exec.expr.holders.BigIntHolder;
import org.apache.drill.exec.vector.allocator.FixedVectorAllocator;

public abstract class HashTableTemplate implements HashTable {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HashTable.class);
  private static final boolean EXTRA_DEBUG = false;

  private static final int EMPTY_SLOT = -1;

  // A hash 'bucket' consists of the start index to indicate start of a hash chain

  // Array of start indexes. start index is a global index across all batch holders
  private IntVector startIndices;

  // Array of batch holders..each batch holder can hold up to BATCH_SIZE entries
  private ArrayList<BatchHolder> batchHolders;

  // Size of the hash table in terms of number of buckets
  private int tableSize = 0;

  // Threshold after which we rehash; It must be the tableSize * loadFactor
  private int threshold;

  // Actual number of entries in the hash table
  private int numEntries = 0;

  // current available (free) slot globally across all batch holders
  private int freeIndex = 0;

  // Placeholder for the current index while probing the hash table
  private IntHolder currentIdxHolder;

  private FragmentContext context;

  // The incoming build side record batch
  private RecordBatch incomingBuild;

  // The incoming probe side record batch (may be null)
  private RecordBatch incomingProbe;

  // The outgoing record batch
  private RecordBatch outgoing;

  // Hash table configuration parameters
  private HashTableConfig htConfig;

  // The original container from which others may be cloned
  private VectorContainer htContainerOrig;

  private MaterializedField dummyIntField;

  private int outputCount = 0;

  // This class encapsulates the links, keys and values for up to BATCH_SIZE
  // *unique* records. Thus, suppose there are N incoming record batches, each
  // of size BATCH_SIZE..but they have M unique keys altogether, the number of
  // BatchHolders will be (M/BATCH_SIZE) + 1
  public class BatchHolder {

    // Container of vectors to hold type-specific keys
    private VectorContainer htContainer;

    // Array of 'link' values
    private IntVector links;

    // Array of hash values - this is useful when resizing the hash table
    private IntVector hashValues;

    int maxOccupiedIdx = 0;

    private BatchHolder(int idx) {

      if (idx == 0) {  // first batch holder can use the original htContainer
        htContainer = htContainerOrig;
      } else { // otherwise create a new one using the original's fields
        htContainer = new VectorContainer();
        for (VectorWrapper<?> w : htContainerOrig) {
          ValueVector vv = TypeHelper.getNewVector(w.getField(), context.getAllocator());
          VectorAllocator.getAllocator(vv, 50 /* avg width */).alloc(HashTable.BATCH_SIZE);
          htContainer.add(vv);
        }
      }

      links = allocMetadataVector(HashTable.BATCH_SIZE, EMPTY_SLOT);
      hashValues = allocMetadataVector(HashTable.BATCH_SIZE, 0);
    }

    private void init(IntVector links, IntVector hashValues, int size) {
      for (int i=0; i < size; i++) {
        links.getMutator().set(i, EMPTY_SLOT);
      }
      for (int i=0; i < size; i++) {
        hashValues.getMutator().set(i, 0);
      }
      links.getMutator().setValueCount(size);
      hashValues.getMutator().setValueCount(size);
    }

    private void setup() {
      setupInterior(incomingBuild, incomingProbe, outgoing, htContainer);
    }

    // Check if the key at the currentIdx position in hash table matches the key
    // at the incomingRowIdx. if the key does not match, update the
    // currentIdxHolder with the index of the next link.
    private boolean isKeyMatch(int incomingRowIdx,
                               IntHolder currentIdxHolder,
                               boolean isProbe) {

      int currentIdxWithinBatch = currentIdxHolder.value & BATCH_MASK;
      boolean match = false;

      if (isProbe)
        match = isKeyMatchInternalProbe(incomingRowIdx, currentIdxWithinBatch);
      else
        match = isKeyMatchInternalBuild(incomingRowIdx, currentIdxWithinBatch);

      if (! match) {
        currentIdxHolder.value = links.getAccessor().get(currentIdxWithinBatch);
      }
      return match;
    }

    // Insert a new <key1, key2...keyN> entry coming from the incoming batch into the hash table
    // container at the specified index
    private boolean insertEntry(int incomingRowIdx, int currentIdx, int hashValue, BatchHolder lastEntryBatch, int lastEntryIdxWithinBatch) {
      int currentIdxWithinBatch = currentIdx & BATCH_MASK;

      if (! setValue(incomingRowIdx, currentIdxWithinBatch)) {
        return false;
      }

      // the previous entry in this hash chain should now point to the entry in this currentIdx
      if (lastEntryBatch != null) {
        lastEntryBatch.updateLinks(lastEntryIdxWithinBatch, currentIdx);
      }

      // since this is the last entry in the hash chain, the links array at position currentIdx
      // will point to a null (empty) slot
      links.getMutator().set(currentIdxWithinBatch, EMPTY_SLOT);
      hashValues.getMutator().set(currentIdxWithinBatch, hashValue);

      maxOccupiedIdx = Math.max(maxOccupiedIdx, currentIdxWithinBatch);

      if (EXTRA_DEBUG) logger.debug("BatchHolder: inserted key at incomingRowIdx = {}, currentIdx = {}, hash value = {}.", incomingRowIdx, currentIdx, hashValue);

      return true;
    }

    private void updateLinks(int lastEntryIdxWithinBatch, int currentIdx) {
      links.getMutator().set(lastEntryIdxWithinBatch, currentIdx);
    }

    private void rehash(int numbuckets, IntVector newStartIndices, int batchStartIdx) {

      if (EXTRA_DEBUG) logger.debug("Resizing and rehashing table...");

      int size = links.getAccessor().getValueCount();
      IntVector newLinks = allocMetadataVector(size, EMPTY_SLOT);
      IntVector newHashValues = allocMetadataVector(size, 0);

      for (int i = 0; i <= maxOccupiedIdx; i++) {
        int entryIdxWithinBatch = i;
        int entryIdx = entryIdxWithinBatch + batchStartIdx;
        int hash = hashValues.getAccessor().get(entryIdxWithinBatch); // get the already saved hash value
        int bucketIdx = getBucketIndex(hash, numbuckets);
        int newStartIdx = newStartIndices.getAccessor().get(bucketIdx);

        if (newStartIdx == EMPTY_SLOT) { // new bucket was empty
          newStartIndices.getMutator().set(bucketIdx, entryIdx); // update the start index to point to entry
          newLinks.getMutator().set(entryIdxWithinBatch, EMPTY_SLOT);
          newHashValues.getMutator().set(entryIdxWithinBatch, hash);

          if (EXTRA_DEBUG) logger.debug("New bucket was empty. bucketIdx = {}, newStartIndices[ {} ] = {}, newLinks[ {} ] = {}, hash value = {}.", bucketIdx, bucketIdx, newStartIndices.getAccessor().get(bucketIdx), entryIdxWithinBatch, newLinks.getAccessor().get(entryIdxWithinBatch), newHashValues.getAccessor().get(entryIdxWithinBatch));

        } else {
          // follow the new table's hash chain until we encounter empty slot
          int idx = newStartIdx;
          int idxWithinBatch = 0;
          while (true) {
            idxWithinBatch = idx & BATCH_MASK;
            if (newLinks.getAccessor().get(idxWithinBatch) == EMPTY_SLOT) {
              newLinks.getMutator().set(idxWithinBatch, entryIdx);
              newLinks.getMutator().set(entryIdxWithinBatch, EMPTY_SLOT);
              newHashValues.getMutator().set(entryIdxWithinBatch, hash);

              if (EXTRA_DEBUG) logger.debug("Followed hash chain in new bucket. bucketIdx = {}, newLinks[ {} ] = {}, newLinks[ {} ] = {}, hash value = {}.", bucketIdx, idxWithinBatch, newLinks.getAccessor().get(idxWithinBatch), entryIdxWithinBatch, newLinks.getAccessor().get(entryIdxWithinBatch), newHashValues.getAccessor().get(entryIdxWithinBatch));

              break;
            }
            idx = newLinks.getAccessor().get(idxWithinBatch);
          }

        }

      }

      links.clear();
      hashValues.clear();

      links = newLinks;
      hashValues = newHashValues;
    }

    private boolean outputKeys() {

      /** for debugging
      Object tmp = (htContainer).getValueAccessorById(0, BigIntVector.class).getValueVector();
      BigIntVector vv0 = null;
      BigIntHolder holder = null;

      if (tmp != null) {
        vv0 = ((BigIntVector) tmp);
        holder = new BigIntHolder();
      }
      */

      for (int i = 0; i <= maxOccupiedIdx; i++) {
        if (outputRecordKeys(i, outputCount) ) {
          if (EXTRA_DEBUG) logger.debug("Outputting keys to {}", outputCount) ;

          // debugging
          // holder.value = vv0.getAccessor().get(i);
          // if (holder.value == 100018 || holder.value == 100021) {
          //  logger.debug("Outputting key = {} at index - {} to outgoing index = {}.", holder.value, i, outputCount);
          // }

          outputCount++;
        } else {
          return false;
        }
      }
      return true;
    }

    private void dump(int idx) {
      while (true) {
        int idxWithinBatch = idx & BATCH_MASK;
        if (idxWithinBatch == EMPTY_SLOT) {
          break;
        } else {
          logger.debug("links[ {} ] = {}, hashValues[ {} ] = {}.", idxWithinBatch, links.getAccessor().get(idxWithinBatch), idxWithinBatch, hashValues.getAccessor().get(idxWithinBatch));
          idx = links.getAccessor().get(idxWithinBatch);
        }
      }
    }

    private void clear() {
      htContainer.clear();;
      links.clear();
      hashValues.clear();
    }

    // These methods will be code-generated

    @RuntimeOverridden
    protected void setupInterior(@Named("incomingBuild") RecordBatch incomingBuild,
                                 @Named("incomingProbe") RecordBatch incomingProbe,
                                 @Named("outgoing") RecordBatch outgoing,
                                 @Named("htContainer") VectorContainer htContainer) {}

    @RuntimeOverridden
    protected boolean isKeyMatchInternalBuild(@Named("incomingRowIdx") int incomingRowIdx, @Named("htRowIdx") int htRowIdx) {return false;}

    @RuntimeOverridden
    protected boolean isKeyMatchInternalProbe(@Named("incomingRowIdx") int incomingRowIdx, @Named("htRowIdx") int htRowIdx) {return false;}

    @RuntimeOverridden
    protected boolean setValue(@Named("incomingRowIdx") int incomingRowIdx, @Named("htRowIdx") int htRowIdx) {return false;}

    @RuntimeOverridden
    protected boolean outputRecordKeys(@Named("htRowIdx") int htRowIdx, @Named("outRowIdx") int outRowIdx) {return false;}

  } // class BatchHolder


  @Override
  public void setup(HashTableConfig htConfig, FragmentContext context,
                    RecordBatch incomingBuild, RecordBatch incomingProbe,
                    RecordBatch outgoing, VectorContainer htContainerOrig) {
    float loadf = htConfig.getLoadFactor();
    int initialCap = htConfig.getInitialCapacity();

    if (loadf <= 0 || Float.isNaN(loadf)) throw new IllegalArgumentException("Load factor must be a valid number greater than 0");
    if (initialCap <= 0) throw new IllegalArgumentException("The initial capacity must be greater than 0");
    if (initialCap > MAXIMUM_CAPACITY) throw new IllegalArgumentException("The initial capacity must be less than maximum capacity allowed");

    if (htConfig.getKeyExprsBuild() == null || htConfig.getKeyExprsBuild().length == 0) throw new IllegalArgumentException("Hash table must have at least 1 key expression");

    this.htConfig = htConfig;
    this.context = context;
    this.incomingBuild = incomingBuild;
    this.incomingProbe = incomingProbe;
    this.outgoing = outgoing;
    this.htContainerOrig = htContainerOrig;

    // round up the initial capacity to nearest highest power of 2
    tableSize = roundUpToPowerOf2(initialCap);
    if (tableSize > MAXIMUM_CAPACITY)
      tableSize = MAXIMUM_CAPACITY;

    threshold = (int) Math.ceil(tableSize * loadf);

    dummyIntField = MaterializedField.create(SchemaPath.getSimplePath("dummy"), Types.required(MinorType.INT));

    startIndices = allocMetadataVector(tableSize, EMPTY_SLOT);

    // Create the first batch holder
    batchHolders = new ArrayList<BatchHolder>();
    addBatchHolder();

    doSetup(incomingBuild, incomingProbe);

    currentIdxHolder = new IntHolder();
  }

  public int numBuckets() {
    return startIndices.getAccessor().getValueCount();
  }

  public int size() {
    return numEntries;
  }

  public boolean isEmpty() {
    return numEntries == 0;
  }

  public void clear() {
    if (batchHolders != null) {
      for (BatchHolder bh : batchHolders) {
        bh.clear();
      }
      batchHolders.clear();
      batchHolders = null;
    }
    startIndices.clear();
    currentIdxHolder = null;
    numEntries = 0;
  }

  private int getBucketIndex(int hash, int numBuckets) {
    return hash & (numBuckets - 1);
  }

  private static int roundUpToPowerOf2(int number) {
    int rounded = number >= MAXIMUM_CAPACITY
           ? MAXIMUM_CAPACITY
           : (rounded = Integer.highestOneBit(number)) != 0
               ? (Integer.bitCount(number) > 1) ? rounded << 1 : rounded
               : 1;

        return rounded;
  }

  public PutStatus put(int incomingRowIdx, IntHolder htIdxHolder) {

    int hash = getHashBuild(incomingRowIdx);
    int i = getBucketIndex(hash, numBuckets());
    int startIdx = startIndices.getAccessor().get(i);
    int currentIdx;
    int currentIdxWithinBatch;
    BatchHolder bh;
    BatchHolder lastEntryBatch = null;
    int lastEntryIdxWithinBatch = EMPTY_SLOT;


    if (startIdx == EMPTY_SLOT) {
      // this is the first entry in this bucket; find the first available slot in the
      // container of keys and values
      currentIdx = freeIndex++;
      addBatchIfNeeded(currentIdx);

      if (EXTRA_DEBUG) logger.debug("Empty bucket index = {}. incomingRowIdx = {}; inserting new entry at currentIdx = {}.", i, incomingRowIdx, currentIdx);

      if (insertEntry(incomingRowIdx, currentIdx, hash, lastEntryBatch, lastEntryIdxWithinBatch)) {
        // update the start index array
        startIndices.getMutator().set(getBucketIndex(hash, numBuckets()), currentIdx);
        htIdxHolder.value = currentIdx;
        return PutStatus.KEY_ADDED;
      }
      return PutStatus.PUT_FAILED;
    }

    currentIdx = startIdx;
    boolean found = false;

    bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);
    currentIdxHolder.value = currentIdx;

    // if startIdx is non-empty, follow the hash chain links until we find a matching
    // key or reach the end of the chain
    while (true) {
      currentIdxWithinBatch = currentIdxHolder.value & BATCH_MASK;

      if (bh.isKeyMatch(incomingRowIdx, currentIdxHolder, false)) {
        htIdxHolder.value = currentIdxHolder.value;
        found = true;
        break;
      }
      else if (currentIdxHolder.value == EMPTY_SLOT) {
        lastEntryBatch = bh;
        lastEntryIdxWithinBatch = currentIdxWithinBatch;
        break;
      } else {
        bh = batchHolders.get( (currentIdxHolder.value >>> 16) & HashTable.BATCH_MASK);
        lastEntryBatch = bh;
      }
    }

    if (!found) {
      // no match was found, so insert a new entry
      currentIdx = freeIndex++;
      addBatchIfNeeded(currentIdx);

      if (EXTRA_DEBUG) logger.debug("No match was found for incomingRowIdx = {}; inserting new entry at currentIdx = {}.", incomingRowIdx, currentIdx);

      if (insertEntry(incomingRowIdx, currentIdx, hash, lastEntryBatch, lastEntryIdxWithinBatch)) {
        htIdxHolder.value = currentIdx;
        return PutStatus.KEY_ADDED;
      }
      else
        return PutStatus.PUT_FAILED;
    }

    return found ? PutStatus.KEY_PRESENT : PutStatus.KEY_ADDED ;
  }

  private boolean insertEntry(int incomingRowIdx, int currentIdx, int hashValue, BatchHolder lastEntryBatch, int lastEntryIdx) {

    // resize hash table if needed and transfer the metadata
    resizeAndRehashIfNeeded(currentIdx);

    addBatchIfNeeded(currentIdx);

    BatchHolder bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);

    if (bh.insertEntry(incomingRowIdx, currentIdx, hashValue, lastEntryBatch, lastEntryIdx)) {
      numEntries++ ;
      return true;
    }

    return false;
  }

  // Return -1 if key is not found in the hash table. Otherwise, return the global index of the key
  @Override
  public int containsKey(int incomingRowIdx, boolean isProbe) {
    int hash = isProbe ? getHashProbe(incomingRowIdx) : getHashBuild(incomingRowIdx);
    int i = getBucketIndex(hash, numBuckets());

    int currentIdx = startIndices.getAccessor().get(i);

    if (currentIdx == EMPTY_SLOT)
      return -1;

    BatchHolder bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);
    currentIdxHolder.value = currentIdx;

    boolean found = false;

    while (true) {
      if (bh.isKeyMatch(incomingRowIdx, currentIdxHolder, isProbe)) {
        found = true;
        break;
      } else if (currentIdxHolder.value == EMPTY_SLOT) {
        break;
      } else {
        bh = batchHolders.get( (currentIdxHolder.value >>> 16) & BATCH_MASK);
      }
    }

    return found ? currentIdxHolder.value : -1;
  }

  // Add a new BatchHolder to the list of batch holders if needed. This is based on the supplied
  // currentIdx; since each BatchHolder can hold up to BATCH_SIZE entries, if the currentIdx exceeds
  // the capacity, we will add a new BatchHolder.
  private BatchHolder addBatchIfNeeded(int currentIdx) {
    int totalBatchSize = batchHolders.size() * BATCH_SIZE;

    if (currentIdx >= totalBatchSize) {
      BatchHolder bh = addBatchHolder();
      if (EXTRA_DEBUG) logger.debug("HashTable: Added new batch. Num batches = {}.", batchHolders.size());
      return bh;
    }
    else {
      return batchHolders.get(batchHolders.size() - 1);
    }
  }

  private BatchHolder addBatchHolder() {
    BatchHolder bh = new BatchHolder(batchHolders.size());
    batchHolders.add(bh);
    bh.setup();
    return bh;
  }

  // Resize the hash table if needed by creating a new one with double the number of buckets.
  // For each entry in the old hash table, re-hash it to the new table and update the metadata
  // in the new table.. the metadata consists of the startIndices, links and hashValues.
  // Note that the keys stored in the BatchHolders are not moved around.
  private void resizeAndRehashIfNeeded(int currentIdx) {
    if (numEntries < threshold)
      return;

    if (EXTRA_DEBUG) logger.debug("Hash table numEntries = {}, threshold = {}; resizing the table...", numEntries, threshold);

    // If the table size is already MAXIMUM_CAPACITY, don't resize
    // the table, but set the threshold to Integer.MAX_VALUE such that
    // future attempts to resize will return immediately.
    if (tableSize == MAXIMUM_CAPACITY) {
      threshold = Integer.MAX_VALUE;
      return;
    }

    int newSize = 2 * tableSize;

    tableSize = roundUpToPowerOf2(newSize);
    if (tableSize > MAXIMUM_CAPACITY)
      tableSize = MAXIMUM_CAPACITY;

    // set the new threshold based on the new table size and load factor
    threshold = (int) Math.ceil(tableSize * htConfig.getLoadFactor());

    IntVector newStartIndices = allocMetadataVector(tableSize, EMPTY_SLOT);

    for (int i = 0; i < batchHolders.size(); i++) {
      BatchHolder bh = batchHolders.get(i) ;
      int batchStartIdx = i * BATCH_SIZE;
      bh.rehash(tableSize, newStartIndices, batchStartIdx);
    }

    startIndices.clear();
    startIndices = newStartIndices;

    if (EXTRA_DEBUG) {
      logger.debug("After resizing and rehashing, dumping the hash table...");
      logger.debug("Number of buckets = {}.", startIndices.getAccessor().getValueCount());
      for (int i = 0; i < startIndices.getAccessor().getValueCount(); i++) {
        logger.debug("Bucket: {}, startIdx[ {} ] = {}.", i, i, startIndices.getAccessor().get(i));
        int idx = startIndices.getAccessor().get(i);
        BatchHolder bh = batchHolders.get( (idx >>> 16) & BATCH_MASK);
        bh.dump(idx);
      }
    }
  }

  public boolean outputKeys() {
    for (BatchHolder bh : batchHolders) {
      if ( ! bh.outputKeys()) {
        return false;
      }
    }
    return true;
  }

  private IntVector allocMetadataVector(int size, int initialValue) {
    IntVector vector = (IntVector) TypeHelper.getNewVector(dummyIntField, context.getAllocator());
    vector.allocateNew(size);
    for (int i=0; i < size; i++) {
      vector.getMutator().set(i, initialValue);
    }
    vector.getMutator().setValueCount(size);
    return vector;
  }

  // These methods will be code-generated in the context of the outer class
  protected abstract void doSetup(@Named("incomingBuild") RecordBatch incomingBuild, @Named("incomingProbe") RecordBatch incomingProbe);
  protected abstract int getHashBuild(@Named("incomingRowIdx") int incomingRowIdx) ;
  protected abstract int getHashProbe(@Named("incomingRowIdx") int incomingRowIdx) ;

}


