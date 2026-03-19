package io.kestra.plugin.azure.shared.storage.blob.services;

import com.azure.core.credential.AzureNamedKeyCredential;
import com.azure.core.http.rest.PagedIterable;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.azure.shared.storage.blob.abstracts.ListInterface;
import io.kestra.plugin.azure.shared.storage.blob.models.Blob;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class BlobService {

    public static List<Blob> list(RunContext runContext, BlobContainerClient client, ListInterface list) throws IllegalVariableEvaluationException {
        ListBlobsOptions listBlobsOptions = new ListBlobsOptions();

        if (list.getPrefix() != null) {
            listBlobsOptions.setPrefix(runContext.render(list.getPrefix()).as(String.class).orElseThrow());
        }

        String regExp = runContext.render(list.getRegexp()).as(String.class).orElse(null);


        PagedIterable<BlobItem> blobItems;
        if (list.getDelimiter() != null) {
            blobItems = client.listBlobsByHierarchy(
                    runContext.render(list.getDelimiter()).as(String.class).orElseThrow(),
                    listBlobsOptions,
                    Duration.ofSeconds(30)
            );
        } else {
            blobItems = client.listBlobs(listBlobsOptions, Duration.ofSeconds(30));
        }

        var filter = runContext.render(list.getFilter()).as(ListInterface.Filter.class).orElse(null);
        return blobItems
                .stream()
                .filter(blob -> BlobService.filter(blob, regExp, filter))
                .map(blob -> Blob.of(client.getBlobContainerName(), blob))
                .collect(Collectors.toList());
    }

    private static boolean filter(BlobItem object, String regExp, ListInterface.Filter filter) {
        return (regExp == null || object.getName().matches(regExp)) &&
                (
                        (filter == ListInterface.Filter.BOTH) ||
                                (filter == ListInterface.Filter.DIRECTORY && object.getProperties().getContentType() == null) ||
                                (filter == ListInterface.Filter.FILES && object.getProperties().getContentType() != null)
                );
    }

    public static BlobServiceClient client(
            Property<String> endpoint,
            Property<String> connectionString,
            Property<String> sharedKeyAccountName,
            Property<String> sharedKeyAccountAccessKey,
            Property<String> sasToken,
            RunContext runContext
    ) throws IllegalVariableEvaluationException {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();

        if (endpoint != null) {
            builder.endpoint(runContext.render(endpoint).as(String.class).orElseThrow());
        }

        if (connectionString != null) {
            builder.connectionString(runContext.render(connectionString).as(String.class).orElseThrow());
        } else if (sharedKeyAccountName != null && sharedKeyAccountAccessKey != null) {
            builder.credential(new AzureNamedKeyCredential(
                    runContext.render(sharedKeyAccountName).as(String.class).orElseThrow(),
                    runContext.render(sharedKeyAccountAccessKey).as(String.class).orElseThrow()
            ));
        } else if (sasToken != null ) {
            builder.sasToken(runContext.render(sasToken).as(String.class).orElseThrow());
        } else {
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }


        return builder.buildClient();
    }
}
