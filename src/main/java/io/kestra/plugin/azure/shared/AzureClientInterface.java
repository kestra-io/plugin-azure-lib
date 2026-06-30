package io.kestra.plugin.azure.shared;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import io.kestra.core.models.annotations.PluginProperty;

/**
 * Top-level interface that can be used by plugins to retrieve
 * required configuration properties in order to establish connection to Azure services.
 */
public interface AzureClientInterface {
    @Schema(
        title = "Connection string of the Storage Account."
    )
    @PluginProperty(secret = true, group = "connection")
    Property<String> getConnectionString();

    @Schema(
        title = "Shared Key account name for authenticating requests."
    )
    @PluginProperty(group = "advanced")
    Property<String> getSharedKeyAccountName();

    @Schema(
        title = "Shared Key access key for authenticating requests."
    )
    @PluginProperty(secret = true, group = "connection")
    Property<String> getSharedKeyAccountAccessKey();
}
