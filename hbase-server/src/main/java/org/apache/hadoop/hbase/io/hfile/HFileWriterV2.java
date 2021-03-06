/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.io.hfile;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.io.hfile.HFile.Writer;
import org.apache.hadoop.hbase.io.hfile.HFileBlock.BlockWritable;
import org.apache.hadoop.hbase.util.BloomFilterWriter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;

/**
 * Writes HFile format version 2.
 */
@InterfaceAudience.Private
public class HFileWriterV2 extends AbstractHFileWriter {
  static final Log LOG = LogFactory.getLog(HFileWriterV2.class);

  /** Max memstore (mvcc) timestamp in FileInfo */
  public static final byte [] MAX_MEMSTORE_TS_KEY =
      Bytes.toBytes("MAX_MEMSTORE_TS_KEY");

  /** KeyValue version in FileInfo */
  public static final byte [] KEY_VALUE_VERSION =
      Bytes.toBytes("KEY_VALUE_VERSION");

  /** Version for KeyValue which includes memstore timestamp */
  public static final int KEY_VALUE_VER_WITH_MEMSTORE = 1;

  /** Inline block writers for multi-level block index and compound Blooms. */
  private List<InlineBlockWriter> inlineBlockWriters =
      new ArrayList<InlineBlockWriter>();

  /** Unified version 2 block writer */
  protected HFileBlock.Writer fsBlockWriter;

  private HFileBlockIndex.BlockIndexWriter dataBlockIndexWriter;
  private HFileBlockIndex.BlockIndexWriter metaBlockIndexWriter;

  /** The offset of the first data block or -1 if the file is empty. */
  private long firstDataBlockOffset = -1;

  /** The offset of the last data block or 0 if the file is empty. */
  protected long lastDataBlockOffset;

  /** The last(stop) Key of the previous data block. */
  private byte[] lastKeyOfPreviousBlock = null;

  /** Additional data items to be written to the "load-on-open" section. */
  private List<BlockWritable> additionalLoadOnOpenData =
    new ArrayList<BlockWritable>();

  protected long maxMemstoreTS = 0;

  static class WriterFactoryV2 extends HFile.WriterFactory {
    WriterFactoryV2(Configuration conf, CacheConfig cacheConf) {
      super(conf, cacheConf);
    }

    @Override
    public Writer createWriter(FileSystem fs, Path path, 
        FSDataOutputStream ostream,
        KVComparator comparator, HFileContext context) throws IOException {
      context.setIncludesTags(false);// HFile V2 does not deal with tags at all!
      return new HFileWriterV2(conf, cacheConf, fs, path, ostream, 
          comparator, context);
      }
    }

  /** Constructor that takes a path, creates and closes the output stream. */
  public HFileWriterV2(Configuration conf, CacheConfig cacheConf,
      FileSystem fs, Path path, FSDataOutputStream ostream, 
      final KVComparator comparator, final HFileContext context) throws IOException {
    super(cacheConf,
        ostream == null ? createOutputStream(conf, fs, path, null) : ostream,
        path, comparator, context);
    finishInit(conf);
  }

  /** Additional initialization steps */
  protected void finishInit(final Configuration conf) {
    if (fsBlockWriter != null)
      throw new IllegalStateException("finishInit called twice");

    fsBlockWriter = new HFileBlock.Writer(blockEncoder, hFileContext);

    // Data block index writer
    boolean cacheIndexesOnWrite = cacheConf.shouldCacheIndexesOnWrite();
    dataBlockIndexWriter = new HFileBlockIndex.BlockIndexWriter(fsBlockWriter,
        cacheIndexesOnWrite ? cacheConf.getBlockCache(): null,
        cacheIndexesOnWrite ? name : null);
    dataBlockIndexWriter.setMaxChunkSize(
        HFileBlockIndex.getMaxChunkSize(conf));
    inlineBlockWriters.add(dataBlockIndexWriter);

    // Meta data block index writer
    metaBlockIndexWriter = new HFileBlockIndex.BlockIndexWriter();
    if (LOG.isTraceEnabled()) LOG.trace("Initialized with " + cacheConf);
  }

  /**
   * At a block boundary, write all the inline blocks and opens new block.
   *
   * @throws IOException
   */
  protected void checkBlockBoundary() throws IOException {
    if (fsBlockWriter.blockSizeWritten() < hFileContext.getBlocksize())
      return;

    finishBlock();
    writeInlineBlocks(false);
    newBlock();
  }

  /** Clean up the current block */
  private void finishBlock() throws IOException {
    if (!fsBlockWriter.isWriting() || fsBlockWriter.blockSizeWritten() == 0)
      return;

    long startTimeNs = System.nanoTime();
    // Update the first data block offset for scanning.
    if (firstDataBlockOffset == -1) {
      firstDataBlockOffset = outputStream.getPos();
    }
    // Update the last data block offset
    lastDataBlockOffset = outputStream.getPos();
    fsBlockWriter.writeHeaderAndData(outputStream);
    int onDiskSize = fsBlockWriter.getOnDiskSizeWithHeader();

    byte[] indexKey = comparator.calcIndexKey(lastKeyOfPreviousBlock, firstKeyInBlock);
    dataBlockIndexWriter.addEntry(indexKey, lastDataBlockOffset, onDiskSize);
    totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();
    HFile.offerWriteLatency(System.nanoTime() - startTimeNs);
    if (cacheConf.shouldCacheDataOnWrite()) {
      doCacheOnWrite(lastDataBlockOffset);
    }
  }

  /** Gives inline block writers an opportunity to contribute blocks. */
  private void writeInlineBlocks(boolean closing) throws IOException {
    for (InlineBlockWriter ibw : inlineBlockWriters) {
      while (ibw.shouldWriteBlock(closing)) {
        long offset = outputStream.getPos();
        boolean cacheThisBlock = ibw.getCacheOnWrite();
        ibw.writeInlineBlock(fsBlockWriter.startWriting(
            ibw.getInlineBlockType()));
        fsBlockWriter.writeHeaderAndData(outputStream);
        ibw.blockWritten(offset, fsBlockWriter.getOnDiskSizeWithHeader(),
            fsBlockWriter.getUncompressedSizeWithoutHeader());
        totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();

        if (cacheThisBlock) {
          doCacheOnWrite(offset);
        }
      }
    }
  }

  /**
   * Caches the last written HFile block.
   * @param offset the offset of the block we want to cache. Used to determine
   *          the cache key.
   */
  private void doCacheOnWrite(long offset) {
    HFileBlock cacheFormatBlock = fsBlockWriter.getBlockForCaching();
    cacheConf.getBlockCache().cacheBlock(
        new BlockCacheKey(name, offset), cacheFormatBlock);
  }

  /**
   * Ready a new block for writing.
   *
   * @throws IOException
   */
  protected void newBlock() throws IOException {
    // This is where the next block begins.
    fsBlockWriter.startWriting(BlockType.DATA);
    firstKeyInBlock = null;
    if (lastKeyLength > 0) {
      lastKeyOfPreviousBlock = new byte[lastKeyLength];
      System.arraycopy(lastKeyBuffer, lastKeyOffset, lastKeyOfPreviousBlock, 0, lastKeyLength);
    }
  }

  /**
   * Add a meta block to the end of the file. Call before close(). Metadata
   * blocks are expensive. Fill one with a bunch of serialized data rather than
   * do a metadata block per metadata instance. If metadata is small, consider
   * adding to file info using {@link #appendFileInfo(byte[], byte[])}
   *
   * @param metaBlockName
   *          name of the block
   * @param content
   *          will call readFields to get data later (DO NOT REUSE)
   */
  @Override
  public void appendMetaBlock(String metaBlockName, Writable content) {
    byte[] key = Bytes.toBytes(metaBlockName);
    int i;
    for (i = 0; i < metaNames.size(); ++i) {
      // stop when the current key is greater than our own
      byte[] cur = metaNames.get(i);
      if (Bytes.BYTES_RAWCOMPARATOR.compare(cur, 0, cur.length, key, 0,
          key.length) > 0) {
        break;
      }
    }
    metaNames.add(i, key);
    metaData.add(i, content);
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   *
   * @param kv
   *          KeyValue to add. Cannot be empty nor null.
   * @throws IOException
   */
  @Override
  public void append(final KeyValue kv) throws IOException {
    append(kv.getMvccVersion(), kv.getBuffer(), kv.getKeyOffset(), kv.getKeyLength(),
        kv.getValueArray(), kv.getValueOffset(), kv.getValueLength());
    this.maxMemstoreTS = Math.max(this.maxMemstoreTS, kv.getMvccVersion());
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   *
   * @param key
   *          Key to add. Cannot be empty nor null.
   * @param value
   *          Value to add. Cannot be empty nor null.
   * @throws IOException
   */
  @Override
  public void append(final byte[] key, final byte[] value) throws IOException {
    append(0, key, 0, key.length, value, 0, value.length);
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   *
   * @param key
   * @param koffset
   * @param klength
   * @param value
   * @param voffset
   * @param vlength
   * @throws IOException
   */
  protected void append(final long memstoreTS, final byte[] key, final int koffset,
      final int klength, final byte[] value, final int voffset, final int vlength)
      throws IOException {
    boolean dupKey = checkKey(key, koffset, klength);
    checkValue(value, voffset, vlength);
    if (!dupKey) {
      checkBlockBoundary();
    }

    if (!fsBlockWriter.isWriting())
      newBlock();

    // Write length of key and value and then actual key and value bytes.
    // Additionally, we may also write down the memstoreTS.
    {
      DataOutputStream out = fsBlockWriter.getUserDataStream();
      out.writeInt(klength);
      totalKeyLength += klength;
      out.writeInt(vlength);
      totalValueLength += vlength;
      out.write(key, koffset, klength);
      out.write(value, voffset, vlength);
      if (this.hFileContext.isIncludesMvcc()) {
        WritableUtils.writeVLong(out, memstoreTS);
      }
    }

    // Are we the first key in this block?
    if (firstKeyInBlock == null) {
      // Copy the key.
      firstKeyInBlock = new byte[klength];
      System.arraycopy(key, koffset, firstKeyInBlock, 0, klength);
    }

    lastKeyBuffer = key;
    lastKeyOffset = koffset;
    lastKeyLength = klength;
    entryCount++;
  }

  @Override
  public void close() throws IOException {
    if (outputStream == null) {
      return;
    }
    // Save data block encoder metadata in the file info.
    blockEncoder.saveMetadata(this);
    // Write out the end of the data blocks, then write meta data blocks.
    // followed by fileinfo, data block index and meta block index.

    finishBlock();
    writeInlineBlocks(true);

    FixedFileTrailer trailer = new FixedFileTrailer(getMajorVersion(), getMinorVersion());

    // Write out the metadata blocks if any.
    if (!metaNames.isEmpty()) {
      for (int i = 0; i < metaNames.size(); ++i) {
        // store the beginning offset
        long offset = outputStream.getPos();
        // write the metadata content
        DataOutputStream dos = fsBlockWriter.startWriting(BlockType.META);
        metaData.get(i).write(dos);

        fsBlockWriter.writeHeaderAndData(outputStream);
        totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();

        // Add the new meta block to the meta index.
        metaBlockIndexWriter.addEntry(metaNames.get(i), offset,
            fsBlockWriter.getOnDiskSizeWithHeader());
      }
    }

    // Load-on-open section.

    // Data block index.
    //
    // In version 2, this section of the file starts with the root level data
    // block index. We call a function that writes intermediate-level blocks
    // first, then root level, and returns the offset of the root level block
    // index.

    long rootIndexOffset = dataBlockIndexWriter.writeIndexBlocks(outputStream);
    trailer.setLoadOnOpenOffset(rootIndexOffset);

    // Meta block index.
    metaBlockIndexWriter.writeSingleLevelIndex(fsBlockWriter.startWriting(
        BlockType.ROOT_INDEX), "meta");
    fsBlockWriter.writeHeaderAndData(outputStream);
    totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();

    if (this.hFileContext.isIncludesMvcc()) {
      appendFileInfo(MAX_MEMSTORE_TS_KEY, Bytes.toBytes(maxMemstoreTS));
      appendFileInfo(KEY_VALUE_VERSION, Bytes.toBytes(KEY_VALUE_VER_WITH_MEMSTORE));
    }

    // File info
    writeFileInfo(trailer, fsBlockWriter.startWriting(BlockType.FILE_INFO));
    fsBlockWriter.writeHeaderAndData(outputStream);
    totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();

    // Load-on-open data supplied by higher levels, e.g. Bloom filters.
    for (BlockWritable w : additionalLoadOnOpenData){
      fsBlockWriter.writeBlock(w, outputStream);
      totalUncompressedBytes += fsBlockWriter.getUncompressedSizeWithHeader();
    }

    // Now finish off the trailer.
    trailer.setNumDataIndexLevels(dataBlockIndexWriter.getNumLevels());
    trailer.setUncompressedDataIndexSize(
        dataBlockIndexWriter.getTotalUncompressedSize());
    trailer.setFirstDataBlockOffset(firstDataBlockOffset);
    trailer.setLastDataBlockOffset(lastDataBlockOffset);
    trailer.setComparatorClass(comparator.getClass());
    trailer.setDataIndexCount(dataBlockIndexWriter.getNumRootEntries());


    finishClose(trailer);

    fsBlockWriter.release();
  }

  @Override
  public void addInlineBlockWriter(InlineBlockWriter ibw) {
    inlineBlockWriters.add(ibw);
  }

  @Override
  public void addGeneralBloomFilter(final BloomFilterWriter bfw) {
    this.addBloomFilter(bfw, BlockType.GENERAL_BLOOM_META);
  }

  @Override
  public void addDeleteFamilyBloomFilter(final BloomFilterWriter bfw) {
    this.addBloomFilter(bfw, BlockType.DELETE_FAMILY_BLOOM_META);
  }

  private void addBloomFilter(final BloomFilterWriter bfw,
      final BlockType blockType) {
    if (bfw.getKeyCount() <= 0)
      return;

    if (blockType != BlockType.GENERAL_BLOOM_META &&
        blockType != BlockType.DELETE_FAMILY_BLOOM_META) {
      throw new RuntimeException("Block Type: " + blockType.toString() +
          "is not supported");
    }
    additionalLoadOnOpenData.add(new BlockWritable() {
      @Override
      public BlockType getBlockType() {
        return blockType;
      }

      @Override
      public void writeToBlock(DataOutput out) throws IOException {
        bfw.getMetaWriter().write(out);
        Writable dataWriter = bfw.getDataWriter();
        if (dataWriter != null)
          dataWriter.write(out);
      }
    });
  }

  @Override
  public void append(byte[] key, byte[] value, byte[] tag) throws IOException {
    throw new UnsupportedOperationException("KV tags are supported only from HFile V3");
  }

  protected int getMajorVersion() {
    return 2;
  }

  protected int getMinorVersion() {
    return HFileReaderV2.MAX_MINOR_VERSION;
  }

  @Override
  public HFileContext getFileContext() {
    return hFileContext;
  }
}
