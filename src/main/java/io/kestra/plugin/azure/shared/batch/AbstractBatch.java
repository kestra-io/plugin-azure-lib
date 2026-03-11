package io.kestra.plugin.azure.shared.batch;

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
    protected Property<String> accessKey;
}
