/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices.template.put;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.reservedstate.ReservedComposableIndexTemplateAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.ComponentTemplate;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.FixForMultiProject;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Optional;
import java.util.Set;

public class TransportPutComponentTemplateAction extends AcknowledgedTransportMasterNodeAction<PutComponentTemplateAction.Request> {

    private final MetadataIndexTemplateService indexTemplateService;
    private final IndexScopedSettings indexScopedSettings;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportPutComponentTemplateAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        MetadataIndexTemplateService indexTemplateService,
        ActionFilters actionFilters,
        IndexScopedSettings indexScopedSettings,
        ProjectResolver projectResolver
    ) {
        super(
            PutComponentTemplateAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            PutComponentTemplateAction.Request::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.indexTemplateService = indexTemplateService;
        this.indexScopedSettings = indexScopedSettings;
        this.projectResolver = projectResolver;
    }

    @Override
    protected ClusterBlockException checkBlock(PutComponentTemplateAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.METADATA_WRITE);
    }

    public static ComponentTemplate normalizeComponentTemplate(
        ComponentTemplate componentTemplate,
        IndexScopedSettings indexScopedSettings
    ) {
        Template template = componentTemplate.template();
        // Normalize the index settings if necessary
        if (template.settings() != null) {
            Settings.Builder builder = Settings.builder().put(template.settings()).normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX);
            Settings settings = builder.build();
            indexScopedSettings.validate(settings, true);
            template = Template.builder(template).settings(settings).build();
            componentTemplate = new ComponentTemplate(
                template,
                componentTemplate.version(),
                componentTemplate.metadata(),
                componentTemplate.deprecated(),
                componentTemplate.createdDateMillis().orElse(null),
                componentTemplate.modifiedDateMillis().orElse(null)
            );
        }

        return componentTemplate;
    }

    @Override
    protected void masterOperation(
        Task task,
        final PutComponentTemplateAction.Request request,
        final ClusterState state,
        final ActionListener<AcknowledgedResponse> listener
    ) {
        ComponentTemplate componentTemplate = normalizeComponentTemplate(request.componentTemplate(), indexScopedSettings);
        var projectId = projectResolver.getProjectId();
        indexTemplateService.putComponentTemplate(
            request.cause(),
            request.create(),
            request.name(),
            request.masterNodeTimeout(),
            componentTemplate,
            projectId,
            listener
        );
    }

    @Override
    public Optional<String> reservedStateHandlerName() {
        return Optional.of(ReservedComposableIndexTemplateAction.NAME);
    }

    @Override
    public Set<String> modifiedKeys(PutComponentTemplateAction.Request request) {
        return Set.of(ReservedComposableIndexTemplateAction.reservedComponentName(request.name()));
    }

    @Override
    @FixForMultiProject // does this need to be a more general concept?
    protected void validateForReservedState(PutComponentTemplateAction.Request request, ClusterState state) {
        super.validateForReservedState(request, state);

        validateForReservedState(
            projectResolver.getProjectMetadata(state).reservedStateMetadata().values(),
            reservedStateHandlerName().get(),
            modifiedKeys(request),
            request.toString()
        );
    }
}
