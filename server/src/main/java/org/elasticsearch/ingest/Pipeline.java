/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.script.ScriptService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * A pipeline is a list of {@link Processor} instances grouped under a unique id.
 */
public final class Pipeline {

    public static final String DESCRIPTION_KEY = "description";
    public static final String PROCESSORS_KEY = "processors";
    public static final String VERSION_KEY = "version";
    public static final String ON_FAILURE_KEY = "on_failure";
    public static final String META_KEY = "_meta";
    public static final String FIELD_ACCESS_PATTERN = "field_access_pattern";
    public static final String DEPRECATED_KEY = "deprecated";
    public static final String CREATED_DATE_MILLIS = "created_date_millis";
    public static final String CREATED_DATE = "created_date";
    public static final String MODIFIED_DATE_MILLIS = "modified_date_millis";
    public static final String MODIFIED_DATE = "modified_date";

    private final String id;
    @Nullable
    private final String description;
    @Nullable
    private final Integer version;
    @Nullable
    private final Map<String, Object> metadata;
    private final CompoundProcessor compoundProcessor;
    private final IngestPipelineMetric metrics;
    private final LongSupplier relativeTimeProvider;
    private final IngestPipelineFieldAccessPattern fieldAccessPattern;
    @Nullable
    private final Boolean deprecated;
    @Nullable
    private final Long createdDateMillis;
    @Nullable
    private final Long modifiedDateMillis;

    public Pipeline(
        String id,
        @Nullable String description,
        @Nullable Integer version,
        @Nullable Map<String, Object> metadata,
        CompoundProcessor compoundProcessor
    ) {
        this(id, description, version, metadata, compoundProcessor, IngestPipelineFieldAccessPattern.CLASSIC, null, null, null);
    }

    public Pipeline(
        String id,
        @Nullable String description,
        @Nullable Integer version,
        @Nullable Map<String, Object> metadata,
        CompoundProcessor compoundProcessor,
        IngestPipelineFieldAccessPattern fieldAccessPattern,
        @Nullable Boolean deprecated,
        @Nullable Long createdDateMillis,
        @Nullable Long modifiedDateMillis
    ) {
        this(
            id,
            description,
            version,
            metadata,
            compoundProcessor,
            System::nanoTime,
            fieldAccessPattern,
            deprecated,
            createdDateMillis,
            modifiedDateMillis
        );
    }

    // package private for testing
    Pipeline(
        String id,
        @Nullable String description,
        @Nullable Integer version,
        @Nullable Map<String, Object> metadata,
        CompoundProcessor compoundProcessor,
        LongSupplier relativeTimeProvider,
        IngestPipelineFieldAccessPattern fieldAccessPattern,
        @Nullable Boolean deprecated,
        @Nullable Long createdDateMillis,
        @Nullable Long modifiedDateMillis
    ) {
        this.id = id;
        this.description = description;
        this.metadata = metadata;
        this.compoundProcessor = compoundProcessor;
        this.version = version;
        this.metrics = new IngestPipelineMetric();
        this.relativeTimeProvider = relativeTimeProvider;
        this.fieldAccessPattern = fieldAccessPattern;
        this.deprecated = deprecated;
        this.createdDateMillis = createdDateMillis;
        this.modifiedDateMillis = modifiedDateMillis;
    }

    /**
     * @deprecated To be removed after Logstash has transitioned fully to the logstash-bridge library. Functionality will be relocated to
     * there. Use {@link Pipeline#create(String, Map, Map, ScriptService, ProjectId, Predicate)} instead.
     */
    @Deprecated
    public static Pipeline create(
        String id,
        Map<String, Object> config,
        Map<String, Processor.Factory> processorFactories,
        ScriptService scriptService,
        ProjectId projectId
    ) throws Exception {
        return create(id, config, processorFactories, scriptService, projectId, IngestService::locallySupportedIngestFeature);
    }

    public static Pipeline create(
        String id,
        Map<String, Object> config,
        Map<String, Processor.Factory> processorFactories,
        ScriptService scriptService,
        ProjectId projectId,
        Predicate<NodeFeature> hasFeature
    ) throws Exception {
        String description = ConfigurationUtils.readOptionalStringProperty(null, null, config, DESCRIPTION_KEY);
        Integer version = ConfigurationUtils.readIntProperty(null, null, config, VERSION_KEY, null);
        Map<String, Object> metadata = ConfigurationUtils.readOptionalMap(null, null, config, META_KEY);
        Boolean deprecated = ConfigurationUtils.readOptionalBooleanProperty(null, null, config, DEPRECATED_KEY);
        String fieldAccessPatternRaw = ConfigurationUtils.readOptionalStringProperty(null, null, config, FIELD_ACCESS_PATTERN);
        if (fieldAccessPatternRaw != null && hasFeature.test(IngestService.FIELD_ACCESS_PATTERN) == false) {
            throw new ElasticsearchParseException(
                "pipeline [" + id + "] doesn't support one or more provided configuration parameters [field_access_pattern]"
            );
        } else if (fieldAccessPatternRaw != null && IngestPipelineFieldAccessPattern.isValidAccessPattern(fieldAccessPatternRaw) == false) {
            throw new ElasticsearchParseException(
                "pipeline [" + id + "] doesn't support value of [" + fieldAccessPatternRaw + "] for parameter [field_access_pattern]"
            );
        }
        IngestPipelineFieldAccessPattern accessPattern = fieldAccessPatternRaw == null
            ? IngestPipelineFieldAccessPattern.CLASSIC
            : IngestPipelineFieldAccessPattern.getAccessPattern(fieldAccessPatternRaw);
        List<Map<String, Object>> processorConfigs = ConfigurationUtils.readList(null, null, config, PROCESSORS_KEY);
        List<Processor> processors = ConfigurationUtils.readProcessorConfigs(
            processorConfigs,
            scriptService,
            processorFactories,
            projectId
        );
        List<Map<String, Object>> onFailureProcessorConfigs = ConfigurationUtils.readOptionalList(null, null, config, ON_FAILURE_KEY);
        List<Processor> onFailureProcessors = ConfigurationUtils.readProcessorConfigs(
            onFailureProcessorConfigs,
            scriptService,
            processorFactories,
            projectId
        );
        String createdDate = ConfigurationUtils.readOptionalStringOrLongProperty(null, null, config, CREATED_DATE_MILLIS);
        String modifiedDate = ConfigurationUtils.readOptionalStringOrLongProperty(null, null, config, MODIFIED_DATE_MILLIS);
        if (config.isEmpty() == false) {
            throw new ElasticsearchParseException(
                "pipeline ["
                    + id
                    + "] doesn't support one or more provided configuration parameters "
                    + Arrays.toString(config.keySet().toArray())
            );
        }
        if (onFailureProcessorConfigs != null && onFailureProcessors.isEmpty()) {
            throw new ElasticsearchParseException("pipeline [" + id + "] cannot have an empty on_failure option defined");
        }
        CompoundProcessor compoundProcessor = new CompoundProcessor(false, processors, onFailureProcessors);
        Long createdDateMillis = createdDate == null ? null : Long.valueOf(createdDate);
        Long modifiedDateMillis = modifiedDate == null ? null : Long.valueOf(modifiedDate);
        return new Pipeline(
            id,
            description,
            version,
            metadata,
            compoundProcessor,
            accessPattern,
            deprecated,
            createdDateMillis,
            modifiedDateMillis
        );
    }

    /**
     * Modifies the data of a document to be indexed based on the processor this pipeline holds
     *
     * If <code>null</code> is returned then this document will be dropped and not indexed, otherwise
     * this document will be kept and indexed.
     */
    public void execute(IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {
        final long startTimeInNanos = relativeTimeProvider.getAsLong();
        metrics.preIngest();
        compoundProcessor.execute(ingestDocument, (result, e) -> {
            long ingestTimeInNanos = relativeTimeProvider.getAsLong() - startTimeInNanos;
            metrics.postIngest(ingestTimeInNanos);
            if (e != null) {
                metrics.ingestFailed();
            }
            // Reset the terminate status now that pipeline execution is complete (if this was executed as part of another pipeline, the
            // outer pipeline should continue):
            ingestDocument.resetTerminate();
            handler.accept(result, e);
        });
    }

    /**
     * The unique id of this pipeline
     */
    public String getId() {
        return id;
    }

    /**
     * An optional description of what this pipeline is doing to the data gets processed by this pipeline.
     */
    @Nullable
    public String getDescription() {
        return description;
    }

    /**
     * An optional version stored with the pipeline so that it can be used to determine if the pipeline should be updated / replaced.
     *
     * @return {@code null} if not supplied.
     */
    @Nullable
    public Integer getVersion() {
        return version;
    }

    @Nullable
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Get the underlying {@link CompoundProcessor} containing the Pipeline's processors
     */
    public CompoundProcessor getCompoundProcessor() {
        return compoundProcessor;
    }

    /**
     * Unmodifiable list containing each processor that operates on the data.
     */
    public List<Processor> getProcessors() {
        return compoundProcessor.getProcessors();
    }

    /**
     * Unmodifiable list containing each on_failure processor that operates on the data in case of
     * exception thrown in pipeline processors
     */
    public List<Processor> getOnFailureProcessors() {
        return compoundProcessor.getOnFailureProcessors();
    }

    /**
     * Flattens the normal and on failure processors into a single list. The original order is lost.
     * This can be useful for pipeline validation purposes.
     */
    public List<Processor> flattenAllProcessors() {
        return compoundProcessor.flattenProcessors();
    }

    /**
     * The metrics associated with this pipeline.
     */
    public IngestPipelineMetric getMetrics() {
        return metrics;
    }

    /**
     * The field access pattern that the pipeline will use to retrieve and set fields on documents.
     */
    public IngestPipelineFieldAccessPattern getFieldAccessPattern() {
        return fieldAccessPattern;
    }

    public Boolean getDeprecated() {
        return deprecated;
    }

    public boolean isDeprecated() {
        return Boolean.TRUE.equals(deprecated);
    }

    public Optional<Long> getCreatedDateMillis() {
        return Optional.ofNullable(createdDateMillis);
    }

    public Optional<Long> getModifiedDateMillis() {
        return Optional.ofNullable(modifiedDateMillis);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Pipeline{");
        sb.append("id='").append(id).append('\'');
        sb.append(", description='").append(description).append('\'');
        sb.append(", version=").append(version);
        sb.append(", metadata=").append(metadata);
        sb.append(", compoundProcessor=").append(compoundProcessor);
        sb.append(", metrics=").append(metrics);
        sb.append(", relativeTimeProvider=").append(relativeTimeProvider);
        sb.append(", fieldAccessPattern=").append(fieldAccessPattern);
        sb.append(", deprecated=").append(deprecated);
        sb.append(", createdDateMillis='").append(createdDateMillis).append('\'');
        sb.append(", modifiedDateMillis='").append(modifiedDateMillis).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
