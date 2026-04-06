package io.kestra.plugin.azure.shared.storage.abstracts;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import io.kestra.core.models.annotations.PluginProperty;

public interface AbstractBlobStorageContainerInterface {
    @Schema(
        title = "The blob container."
    )
    @NotNull
    @PluginProperty(group = "main")
    Property<String> getContainer();
}
