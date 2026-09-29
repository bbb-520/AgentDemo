package com.bbb.exercise.agentdemo.model;

import java.util.List;
import java.util.Objects;

public final class ModelRouter {
    private final List<ModelDescriptor> models;

    public ModelRouter(List<ModelDescriptor> models) {
        this.models = List.copyOf(models);
    }

    public ModelDescriptor route(ModelInvocation invocation) {
        return models.stream()
                .filter(model -> Objects.equals(model.capability(), invocation.capability()))
                .filter(model -> invocation.preferredProvider() == null || invocation.preferredProvider().isBlank()
                        || Objects.equals(model.provider(), invocation.preferredProvider()))
                .filter(model -> invocation.preferredModel() == null || invocation.preferredModel().isBlank()
                        || Objects.equals(model.model(), invocation.preferredModel()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的模型: " + invocation.capability()));
    }
}
