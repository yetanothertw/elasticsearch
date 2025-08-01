/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec.internal.vectorization;

import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MemorySegmentAccessInput;
import org.elasticsearch.simdvec.ES91Int4VectorsScorer;
import org.elasticsearch.simdvec.ES91OSQVectorsScorer;
import org.elasticsearch.simdvec.ES92Int7VectorsScorer;
import org.elasticsearch.simdvec.internal.MemorySegmentES92Int7VectorsScorer;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

final class PanamaESVectorizationProvider extends ESVectorizationProvider {

    private final ESVectorUtilSupport vectorUtilSupport;

    PanamaESVectorizationProvider() {
        vectorUtilSupport = new PanamaESVectorUtilSupport();
    }

    @Override
    public ESVectorUtilSupport getVectorUtilSupport() {
        return vectorUtilSupport;
    }

    @Override
    public ES91OSQVectorsScorer newES91OSQVectorsScorer(IndexInput input, int dimension) throws IOException {
        if (PanamaESVectorUtilSupport.HAS_FAST_INTEGER_VECTORS && input instanceof MemorySegmentAccessInput msai) {
            MemorySegment ms = msai.segmentSliceOrNull(0, input.length());
            if (ms != null) {
                return new MemorySegmentES91OSQVectorsScorer(input, dimension, ms);
            }
        }
        return new ES91OSQVectorsScorer(input, dimension);
    }

    @Override
    public ES91Int4VectorsScorer newES91Int4VectorsScorer(IndexInput input, int dimension) throws IOException {
        if (PanamaESVectorUtilSupport.HAS_FAST_INTEGER_VECTORS && input instanceof MemorySegmentAccessInput msai) {
            MemorySegment ms = msai.segmentSliceOrNull(0, input.length());
            if (ms != null) {
                return new MemorySegmentES91Int4VectorsScorer(input, dimension, ms);
            }
        }
        return new ES91Int4VectorsScorer(input, dimension);
    }

    @Override
    public ES92Int7VectorsScorer newES92Int7VectorsScorer(IndexInput input, int dimension) throws IOException {
        if (input instanceof MemorySegmentAccessInput msai) {
            MemorySegment ms = msai.segmentSliceOrNull(0, input.length());
            if (ms != null) {
                return new MemorySegmentES92Int7VectorsScorer(input, dimension, ms);
            }
        }
        return new ES92Int7VectorsScorer(input, dimension);

    }
}
