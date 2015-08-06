/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.data.filter.closeablebundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.util.AutoField;
import com.addthis.bundle.value.ValueArray;
import com.addthis.bundle.value.ValueObject;

import com.clearspring.analytics.stream.frequency.CountMinSketch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * This closeable bundle filter applies a limit using the count-min sketch data structure.
 * If the input is a scalar value then this filter returns true if it accepts the input. If
 * it rejects the input then that field is removed from the bundle and the filter returns false.
 * If the input is an array then this filter removes values that do not need the limit criteria
 * and always return true.
 */
public class CloseableBundleCMSLimit implements CloseableBundleFilter {

    public enum Bound {
        LOWER, UPPER;
    }

    private static final String KEY_SEPARATOR = "&";

    @Nonnull
    private final AutoField[] keyFields;

    @Nonnull
    private final AutoField valueField;

    @Nullable
    private final AutoField countField;

    private final String dataDir;

    private final int cacheSize;

    private final boolean rejectNull;

    private final int limit;

    /**
     * Optionally specify the depth of the sketch.
     * If 'confidence' is specified then ignore this value.
     */
    private final int depth;

    /**
     * Confidence that the error tolerance is satisfied.
     * If 'confidence' is specified then ignore 'depth' parameter.
     * Expressed as a fraction.
     */
    private final double confidence;

    /**
     * Width of the sketch in bits.
     * Either 'width' or 'percentage' are required.
     */
    private final int width;

    /**
     * Maximum error tolerated as percentage of cardinality.
     * Either 'width' or 'percentage' are required.
     */
    private final double percentage;

    @Nonnull
    private final Bound bound;

    private final CMSLimitHashMap sketches;

    private final int calcWidth;

    private final int calcDepth;

    @JsonCreator
    public CloseableBundleCMSLimit(@JsonProperty(value = "keyFields", required = true) AutoField[] keyFields,
                                   @JsonProperty(value = "valueField", required = true) AutoField valueField,
                                   @JsonProperty("countField") AutoField countField,
                                   @JsonProperty(value = "dataDir", required = true) String dataDir,
                                   @JsonProperty(value = "cacheSize", required = true) int cacheSize,
                                   @JsonProperty("rejectNull") boolean rejectNull,
                                   @JsonProperty("width") int width,
                                   @JsonProperty("depth") int depth,
                                   @JsonProperty(value = "limit", required = true) int limit,
                                   @JsonProperty("confidence") double confidence,
                                   @JsonProperty("percentage") double percentage,
                                   @JsonProperty(value = "bound", required = true) Bound bound) {
        if ((width == 0) && (percentage == 0.0)) {
            throw new IllegalArgumentException("Either 'width' or " +
                                               "'percentage' must be specified.");
        } else if ((width > 0) && (percentage > 0.0)) {
            throw new IllegalArgumentException("Either 'width' or " +
                                               "'percentage' must be specified.");
        } else if (confidence < 0.0 || confidence >= 1.0) {
            throw new IllegalArgumentException("'confidence' must be between 0 and 1");
        }
        this.keyFields = keyFields;
        this.valueField = valueField;
        this.countField = countField;
        this.dataDir = dataDir;
        this.cacheSize = cacheSize;
        this.rejectNull = rejectNull;
        this.width = width;
        this.depth = depth;
        this.limit = limit;
        this.confidence = confidence;
        this.percentage = percentage;
        this.bound = bound;
        this.sketches = new CMSLimitHashMap();
        int cWidth = width;
        int cDepth = depth;
        if (cWidth == 0) {
            cWidth =  (int) Math.ceil(Math.E / percentage);
        }
        if (confidence > 0.0) {
            cDepth = (int) Math.ceil(-Math.log(1.0 - confidence));
        }
        calcWidth = cWidth;
        calcDepth = cDepth;
    }

    @Override public synchronized void close() {
        try {
            for (Map.Entry<String, CountMinSketch> entry : sketches.entrySet()) {
                writeSketch(entry.getKey(), entry.getValue());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override public boolean filter(Bundle row) {
        StringBuilder sb = new StringBuilder();
        for (AutoField keyField : keyFields) {
            Optional<String> optional = keyField.getString(row);
            if (optional.isPresent()) {
                if (sb.length() > 0) {
                    sb.append(KEY_SEPARATOR);
                }
                sb.append(optional.get());
            } else if (rejectNull) {
                return false;
            }
        }
        return updateSketch(row, sb.toString(), valueField.getValue(row));
    }

    private synchronized boolean updateSketch(Bundle row, String key, ValueObject valueObject) {
        CountMinSketch sketch = sketches.get(key);
        if (valueObject == null) {
            return false;
        }
        if (valueObject.getObjectType() == ValueObject.TYPE.ARRAY) {
            ValueArray array = valueObject.asArray();
            Iterator<ValueObject> iterator = array.iterator();
            while (iterator.hasNext()) {
                ValueObject next = iterator.next();
                updateString(next.asString().asNative(), sketch, iterator, row);
            }
            return true;
        } else {
            return updateString(valueObject.asString().asNative(), sketch, null, row);
        }
    }

    private boolean updateString(String input, CountMinSketch sketch, Iterator<ValueObject> iterator, Bundle bundle) {
        long current = sketch.estimateCount(input);
        switch (bound) {
            case UPPER:
                if (current >= limit) {
                    removeElement(iterator, bundle);
                    return false;
                } else {
                    updateCount(input, sketch, bundle);
                }
                break;
            case LOWER:
                if (current < limit) {
                    removeElement(iterator, bundle);
                    updateCount(input, sketch, bundle);
                    return false;
                }
                break;
        }
        return true;
    }

    private void removeElement(Iterator<ValueObject> iterator, Bundle bundle) {
        if (iterator != null) {
            iterator.remove();
        } else {
            valueField.removeValue(bundle);
        }
    }

    private void updateCount(String input, CountMinSketch sketch, Bundle bundle) {
        long myCount = 1;
        if (countField != null) {
            myCount = countField.getLong(bundle).orElse(0);
        }
        if (myCount > 0) {
            sketch.add(input, myCount);
        }
    }

    private void writeSketch(String key, CountMinSketch sketch) throws IOException {
        byte[] data = CountMinSketch.serialize(sketch);
        Path parent = Paths.get(dataDir);
        Path path = Paths.get(dataDir, key);
        Files.createDirectories(parent);
        Files.write(path, data);
    }

    private class CMSLimitHashMap extends LinkedHashMap<String, CountMinSketch> {

        CMSLimitHashMap() {
            super(cacheSize, 0.75f, true);
        }

        @Override
        public CountMinSketch get(Object key) {
            CountMinSketch sketch = super.get(key);
            if (sketch == null) {
                sketch = new CountMinSketch(calcDepth, calcWidth, 0);
                put(key.toString(), sketch);
            }
            return sketch;
        }

        protected boolean removeEldestEntry(Map.Entry<String, CountMinSketch> eldest) {
            try {
                if (size() >= cacheSize) {
                    String key = eldest.getKey();
                    CountMinSketch value = eldest.getValue();
                    writeSketch(key, value);
                    return true;
                } else {
                    return false;
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

}
