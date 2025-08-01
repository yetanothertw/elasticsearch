/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.huggingface;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.InferenceServiceExtension;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.xpack.inference.chunking.ChunkingSettingsBuilder;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSender;
import org.elasticsearch.xpack.inference.external.http.sender.InferenceInputs;
import org.elasticsearch.xpack.inference.services.ConfigurationParseContext;
import org.elasticsearch.xpack.inference.services.SenderService;
import org.elasticsearch.xpack.inference.services.ServiceComponents;
import org.elasticsearch.xpack.inference.services.ServiceUtils;
import org.elasticsearch.xpack.inference.services.huggingface.action.HuggingFaceActionCreator;

import java.util.Map;

import static org.elasticsearch.xpack.inference.services.ServiceUtils.createInvalidModelException;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.parsePersistedConfigErrorMsg;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMap;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrDefaultEmpty;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrThrowIfNull;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.throwIfNotEmptyMap;

public abstract class HuggingFaceBaseService extends SenderService {

    /**
     * The optimal batch size depends on the hardware the model is deployed on.
     * For HuggingFace use a conservatively small max batch size as it is
     * unknown how the model is deployed
     */
    static final int EMBEDDING_MAX_BATCH_SIZE = 20;

    public HuggingFaceBaseService(
        HttpRequestSender.Factory factory,
        ServiceComponents serviceComponents,
        InferenceServiceExtension.InferenceServiceFactoryContext context
    ) {
        this(factory, serviceComponents, context.clusterService());
    }

    public HuggingFaceBaseService(HttpRequestSender.Factory factory, ServiceComponents serviceComponents, ClusterService clusterService) {
        super(factory, serviceComponents, clusterService);
    }

    @Override
    public void parseRequestConfig(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        ActionListener<Model> parsedModelListener
    ) {
        try {
            Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
            Map<String, Object> taskSettingsMap = removeFromMapOrDefaultEmpty(config, ModelConfigurations.TASK_SETTINGS);

            ChunkingSettings chunkingSettings = null;
            if (TaskType.TEXT_EMBEDDING.equals(taskType)) {
                chunkingSettings = ChunkingSettingsBuilder.fromMap(
                    removeFromMapOrDefaultEmpty(config, ModelConfigurations.CHUNKING_SETTINGS)
                );
            }

            var model = createModel(
                new HuggingFaceModelParameters(
                    inferenceEntityId,
                    taskType,
                    serviceSettingsMap,
                    taskSettingsMap,
                    chunkingSettings,
                    serviceSettingsMap,
                    TaskType.unsupportedTaskTypeErrorMsg(taskType, name()),
                    ConfigurationParseContext.REQUEST
                )
            );

            throwIfNotEmptyMap(config, name());
            throwIfNotEmptyMap(serviceSettingsMap, name());
            throwIfNotEmptyMap(taskSettingsMap, name());

            parsedModelListener.onResponse(model);
        } catch (Exception e) {
            parsedModelListener.onFailure(e);
        }
    }

    @Override
    public HuggingFaceModel parsePersistedConfigWithSecrets(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        Map<String, Object> secrets
    ) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrDefaultEmpty(config, ModelConfigurations.TASK_SETTINGS);
        Map<String, Object> secretSettingsMap = removeFromMapOrThrowIfNull(secrets, ModelSecrets.SECRET_SETTINGS);

        ChunkingSettings chunkingSettings = null;
        if (TaskType.TEXT_EMBEDDING.equals(taskType)) {
            chunkingSettings = ChunkingSettingsBuilder.fromMap(removeFromMap(config, ModelConfigurations.CHUNKING_SETTINGS));
        }

        return createModel(
            new HuggingFaceModelParameters(
                inferenceEntityId,
                taskType,
                serviceSettingsMap,
                taskSettingsMap,
                chunkingSettings,
                secretSettingsMap,
                parsePersistedConfigErrorMsg(inferenceEntityId, name()),
                ConfigurationParseContext.PERSISTENT
            )
        );
    }

    @Override
    public HuggingFaceModel parsePersistedConfig(String inferenceEntityId, TaskType taskType, Map<String, Object> config) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrDefaultEmpty(config, ModelConfigurations.TASK_SETTINGS);

        ChunkingSettings chunkingSettings = null;
        if (TaskType.TEXT_EMBEDDING.equals(taskType)) {
            chunkingSettings = ChunkingSettingsBuilder.fromMap(removeFromMap(config, ModelConfigurations.CHUNKING_SETTINGS));
        }

        return createModel(
            new HuggingFaceModelParameters(
                inferenceEntityId,
                taskType,
                serviceSettingsMap,
                taskSettingsMap,
                chunkingSettings,
                null,
                parsePersistedConfigErrorMsg(inferenceEntityId, name()),
                ConfigurationParseContext.PERSISTENT
            )
        );
    }

    protected abstract HuggingFaceModel createModel(HuggingFaceModelParameters input);

    @Override
    public void doInfer(
        Model model,
        InferenceInputs inputs,
        Map<String, Object> taskSettings,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        if (model instanceof HuggingFaceModel == false) {
            listener.onFailure(createInvalidModelException(model));
            return;
        }

        var huggingFaceModel = (HuggingFaceModel) model;
        var actionCreator = new HuggingFaceActionCreator(getSender(), getServiceComponents());

        var action = huggingFaceModel.accept(actionCreator);
        action.execute(inputs, timeout, listener);
    }

    @Override
    protected void validateInputType(InputType inputType, Model model, ValidationException validationException) {
        ServiceUtils.validateInputTypeIsUnspecifiedOrInternal(inputType, validationException);
    }
}
