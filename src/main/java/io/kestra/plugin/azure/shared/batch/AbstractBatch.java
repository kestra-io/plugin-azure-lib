package io.kestra.plugin.azure.shared.batch;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.azure.shared.AbstractConnection;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractBatch extends AbstractConnection {
    @NotNull
    protected Property<String> account;

    @NotNull
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> accessKey;
}
