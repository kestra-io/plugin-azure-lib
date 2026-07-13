package io.kestra.plugin.azure.shared;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientCertificateCredential;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import io.kestra.core.models.annotations.PluginProperty;

/**
 * Base class for Azure tasks that authenticate via Azure AD service principal or default credential chain.
 * For more information please refer to the <a href="https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme?view=azure-java-stable">Azure Identity documentation</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAzureIdentityConnection extends Task implements AzureIdentityConnectionInterface {
    @NotNull
    @Schema(title = "Azure AD tenant ID (GUID)")
    @PluginProperty(group = "main")
    protected Property<String> tenantId;

    @Schema(
        title = "Client ID of the Azure AD application",
        description = "Application (client) ID used for service principal authentication."
    )
    @PluginProperty(group = "connection")
    protected Property<String> clientId;

    @Schema(
        title = "Client secret for the Azure AD application",
        description = "Secret value associated with the client ID; store in a Kestra secret."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "PEM-encoded certificate content for client authentication",
        description = "PEM text for certificate-based auth; alternative to clientSecret."
    )
    @PluginProperty(secret = true, group = "advanced")
    @ToString.Exclude
    protected Property<String> pemCertificate;

    public TokenCredential credentials(RunContext runContext) throws IllegalVariableEvaluationException {
        final var tenantId = runContext.render(this.tenantId).as(String.class).orElse(null);
        final var clientId = runContext.render(this.clientId).as(String.class).orElse(null);

        // Create client/secret credentials
        final var clientSecret = runContext.render(this.clientSecret).as(String.class).orElse(null);
        if (StringUtils.isNotBlank(clientSecret)) {
            runContext.logger().info("Authentication is using Client Secret Credentials");
            return getClientSecretCredential(tenantId, clientId, clientSecret);
        }

        // Create client/certificate credentials
        final var pemCertificate = runContext.render(this.pemCertificate).as(String.class).orElse(null);
        if (StringUtils.isNotBlank(pemCertificate)) {
            runContext.logger().info("Authentication is using Client Certificate Credentials");
            return getClientCertificateCredential(tenantId, clientId, pemCertificate);
        }

        // Fall back to default Azure credential chain
        runContext.logger().info("Authentication is using Default Azure Credentials");
        return new DefaultAzureCredentialBuilder().tenantId(tenantId).build();
    }

    private ClientCertificateCredential getClientCertificateCredential(String tenantId, String clientId, String pemCertificate) {
        return new ClientCertificateCredentialBuilder()
            .clientId(clientId)
            .tenantId(tenantId)
            .pemCertificate(new ByteArrayInputStream(StandardCharsets.UTF_8.encode(pemCertificate).array()))
            .build();
    }

    private ClientSecretCredential getClientSecretCredential(String tenantId, String clientId, String clientSecret) {
        return new ClientSecretCredentialBuilder()
            .clientId(clientId)
            .tenantId(tenantId)
            .clientSecret(clientSecret)
            .build();
    }
}
