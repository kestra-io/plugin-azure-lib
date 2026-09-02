package io.kestra.plugin.azure.shared.storage.blob.abstracts;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.annotations.PluginProperty;

public interface AbstractBlobStorageObjectInterface {
    @Schema(
        title = "The blob container."
    )
    @NotNull
    @PluginProperty(group = "main")
    Property<String> getContainer();

    @Schema(
        title = "The full path of the blob within the container."
    )
    @NotNull
    @PluginProperty(group = "main")
    Property<String> getName();
}
