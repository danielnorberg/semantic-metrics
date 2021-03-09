/*
 * Copyright (C) 2016 - 2020 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.metrics.core;


import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import com.tdunning.math.stats.TDigest;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.math.IntMath.isPowerOfTwo;

/**
 * Semantic Metric implementation of {@link Distribution}.
 * This implementation ensures threadsafety for recording  data
 * and retrieving distribution point value.
 * <p>
 * {@link SemanticMetricDistribution} is backed by Ted Dunning T-digest implementation.
 *
 * <p>{@link TDigest} "sketch" are generated by clustering real-valued samples and
 * retaining the mean and number of samples for each cluster.
 * The generated data structure is mergeable and produces fairly
 * accurate percentile even for long-tail distribution.
 *
 * <p> We are using T-digest compression level of 100.
 * With that level of compression, our own benchmark using Pareto distribution
 * dataset, shows P99 error rate  is less than 2% .
 * From P99.9 to P99.999 the error rate is slightly higher than 2%.
 */
public class SemanticMetricDistribution implements Distribution {

    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    private static final int COMPRESSION_DEFAULT_LEVEL = 100;

    private static final int INITIAL_STRIPES = 1;

    private final ReentrantLock swapLock = new ReentrantLock();

    private volatile StripedTDigest striped;

    SemanticMetricDistribution() {
        this.striped = new StripedTDigest(INITIAL_STRIPES);
    }

    @Override
    public void record(double val) {
        while (true) {
            final StripedTDigest current = striped;
            final int index = ThreadLocalRandom.current().nextInt() & current.mask;
            final ReentrantLock lock = current.locks[index];
            // TODO: use non-trying lock method after one (or a few?) retries instead of spinning
            if (!lock.tryLock()) {
                contended(current);
                continue;
            }
            try {
                final TDigest digest = current.digests[index];
                digest.add(val);
                return;
            } finally {
                lock.unlock();
            }
        }
    }


    /**
     * Called when updates fail to lock their stripe tdigest, i.e. the stripe is contended.
     * Try to double the number of stripes in this case.
     */
    private void contended(StripedTDigest current) {
        if (current.size >= NCPU) {
            return;
        }
        if (!swapLock.tryLock()) {
            return;
        }
        try {
            if (this.striped != current) {
                return;
            }
            this.striped = new StripedTDigest(current, current.size << 1);
        } finally {
            swapLock.unlock();
        }
    }

    @Override
    public ByteString getValueAndFlush() {
        swapLock.lock();
        StripedTDigest prev;
        try {
            prev = striped;
            striped = new StripedTDigest(prev.size);
        } finally {
            swapLock.unlock();
        }
        final TDigest merged = prev.merged();
        final ByteBuffer byteBuffer = ByteBuffer.allocate(merged.smallByteSize());
        merged.asSmallBytes(byteBuffer);
        return ByteString.copyFrom(byteBuffer.array());
    }


    @Override
    public long getCount() {
        final StripedTDigest striped = this.striped;
        long count = 0;
        for (int i = 0; i < striped.size; i++) {
            final ReentrantLock lock = striped.locks[i];
            final TDigest digest = striped.digests[i];
            lock.lock();
            try {
                count += digest.size();
            } finally {
                lock.unlock();
            }
        }
        return count;
    }

    @VisibleForTesting
    TDigest tDigest() {
        return striped.merged();
    }

    private static class StripedTDigest {

        final int size;
        final ReentrantLock[] locks;
        final TDigest[] digests;
        final int mask;

        private StripedTDigest(int size) {
            assert isPowerOfTwo(size);
            this.size = size;
            this.locks = new ReentrantLock[size];
            this.digests = new TDigest[size];
            this.mask = mask(size);
            for (int i = 0; i < size; i++) {
                locks[i] = new ReentrantLock();
                digests[i] = createDigest();
            }
        }

        private StripedTDigest(StripedTDigest old, int size) {
            assert isPowerOfTwo(size);
            this.size = size;
            this.locks = new ReentrantLock[size];
            this.digests = new TDigest[size];
            this.mask = mask(size);
            for (int i = 0; i < size - 1; i += 2) {
                locks[i] = old.locks[i];
                digests[i] = old.digests[i];
            }
            for (int i = 1; i < size; i += 2) {
                locks[i] = new ReentrantLock();
                digests[i] = createDigest();
            }
        }

        private int mask(int size) {
            assert isPowerOfTwo(size);
            return size - 1;
        }

        public TDigest merged() {
            final TDigest merged = createDigest();
            for (int i = 0; i < size; i++) {
                final ReentrantLock lock = locks[i];
                lock.lock();
                try {
                    final TDigest digest = digests[i];
                    merged.add(digest);
                } finally {
                    lock.unlock();
                }
            }
            return merged;
        }
    }

    private static TDigest createDigest() {
        return TDigest.createDigest(COMPRESSION_DEFAULT_LEVEL);
    }
}
