package io.kestra.plugin.azure.shared;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import io.kestra.core.models.annotations.PluginProperty;

public interface AbstractConnectionInterface {
    @Schema(
        title = "The blob service endpoint."
    )
    @PluginProperty(group = "connection")
    Property<String> getEndpoint();
}
