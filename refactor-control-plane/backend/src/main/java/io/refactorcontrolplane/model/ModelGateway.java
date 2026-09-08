package io.refactorcontrolplane.model;

public interface ModelGateway {
    ModelExecution execute(ModelRequest request);
}
