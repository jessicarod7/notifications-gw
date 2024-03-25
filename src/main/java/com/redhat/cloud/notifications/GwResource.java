package com.redhat.cloud.notifications;

import com.redhat.cloud.notifications.auth.RhIdPrincipal;
import com.redhat.cloud.notifications.ingress.Action;
import com.redhat.cloud.notifications.ingress.Context;
import com.redhat.cloud.notifications.ingress.Event;
import com.redhat.cloud.notifications.ingress.Metadata;
import com.redhat.cloud.notifications.ingress.Parser;
import com.redhat.cloud.notifications.ingress.Payload;
import com.redhat.cloud.notifications.ingress.Recipient;
import com.redhat.cloud.notifications.model.SourceEnvironment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
@Path("/notifications")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class GwResource {

    public static final String EGRESS_CHANNEL = "egress";
    public static final String FAILURES_COUNTER = "notifications.gw.failed.requests";
    public static final String MESSAGE_ID_HEADER = "rh-message-id";
    public static final String NOTIFICATIONS_EMAILS_INTERNAL_ONLY_ENABLED = "notifications.emails.internal-only.enabled";

    private static final String X509_IDENTITY_TYPE = "X509";

    private static final String SERVICE_ACCOUNT_IDENTITY_TYPE = "ServiceAccount";
    private static final String SOURCE_ENVIRONMENT_HEADER = "rh-source-environment";
    private static final String REDHAT_DOMAIN = "@redhat.com";

    @ConfigProperty(name = "notifications.kafka-callback-timeout-seconds", defaultValue = "60")
    long callbackTimeout;

    @ConfigProperty(name = NOTIFICATIONS_EMAILS_INTERNAL_ONLY_ENABLED, defaultValue = "true")
    boolean internalEmailsOnly;

    @Inject
    @Channel(EGRESS_CHANNEL)
    Emitter<String> emitter;

    @Inject
    MeterRegistry meterRegistry;

    @RestClient
    RestValidationClient restValidationClient;

    private final Counter receivedActions;
    private final Counter forwardedActions;

    public GwResource(MeterRegistry registry) {
        receivedActions = registry.counter("notifications.gw.received");
        forwardedActions = registry.counter("notifications.gw.forwarded");
    }

    @POST
    @Operation(summary = "Forward one message to the notification system")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Message forwarded"),
        @APIResponse(responseCode = "403", description = "No permission"),
        @APIResponse(responseCode = "401"),
        @APIResponse(responseCode = "400", description = "Incoming message was not valid"),
        @APIResponse(responseCode = "503", description = "Message delivery to Kafka failed")
    })
    public Response forward(@jakarta.ws.rs.core.Context SecurityContext sec, @NotNull @Valid RestAction ra) {
        receivedActions.increment();

        String sourceEnvironment = null;
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        if (X509_IDENTITY_TYPE.equals(principal.getType()) || SERVICE_ACCOUNT_IDENTITY_TYPE.equals(principal.getType())) {
            Optional<SourceEnvironment> sourceEnv = getSourceEnvironment(ra.bundle, ra.application, principal.getName());
            if (sourceEnv.isPresent()) {
                sourceEnvironment = sourceEnv.get().name;
            }
        }

        if (internalEmailsOnly) {
            List<String> forbiddenEmails = new ArrayList<>();
            if (ra.getRecipients() != null) {
                for (RestRecipient restRecipient : ra.getRecipients()) {
                    if (restRecipient.getEmails() != null) {
                        for (String email : restRecipient.getEmails()) {
                            if (!email.trim().toLowerCase().endsWith(REDHAT_DOMAIN)) {
                                forbiddenEmails.add(email);
                            }
                        }
                    }
                }
            }
            if (!forbiddenEmails.isEmpty()) {
                return Response
                        .status(BAD_REQUEST)
                        .header(CONTENT_TYPE, APPLICATION_JSON)
                        .entity(buildResponseEntity(false, "External email addresses are forbidden in the recipients.emails field: " + String.join(", ", forbiddenEmails)))
                        .build();
            }
        }

        try (Response response = this.restValidationClient.validate(ra.getBundle(), ra.getApplication(), ra.getEventType())) {
            // This try block is intentionally empty.
        } catch (final WebApplicationException e) {
            // Build a nice error message for the caller.
            final Response response = e.getResponse();
            String incomingErrorMessage;
            // TODO The following try/catch block is a workaround for a Quarkus bug. Remove it ASAP!
            try {
                incomingErrorMessage = response.readEntity(String.class);
            } catch (ConcurrentModificationException ex) {
                incomingErrorMessage = "";
                Log.error("Could not retrieve entity from notifications-backend response", ex);
            }
            // Determine which status code we will return to the gateway caller
            // and log the error appropriately.
            final String logMessage = "Unable to validate the provided rest action due to notifications-backend responding with an error. Received status code: %s, received error message: %s, received REST action in the gateway: %s";
            final Status returningStatusCodeFromGW;
            final String returningErrorMessageFromGW;
            if (response.getStatus() == BAD_REQUEST.getStatusCode()) {
                returningStatusCodeFromGW = BAD_REQUEST;
                returningErrorMessageFromGW = incomingErrorMessage;
                Log.debugf(logMessage, response.getStatus(), incomingErrorMessage, ra);
            } else {
                returningStatusCodeFromGW = SERVICE_UNAVAILABLE;
                returningErrorMessageFromGW = "Unable to validate the message, please try again later";
                Log.errorf(logMessage, response.getStatus(), incomingErrorMessage, ra);
            }

            this.incrementFailuresCounter(returningStatusCodeFromGW);

            // Notify the caller about the error.
            return Response
                .status(returningStatusCodeFromGW)
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .entity(buildResponseEntity(false, returningErrorMessageFromGW))
                .build();
        } catch (final ProcessingException e) {
            Log.errorf(e, "Unable to reach notifications-backend to validate the following payload: %s", ra);

            this.incrementFailuresCounter(SERVICE_UNAVAILABLE);

            // Raised when the notifications-backend is unreachable.
            return Response
                .status(SERVICE_UNAVAILABLE)
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .entity(buildResponseEntity(false, "Unable to validate the message, please try again later"))
                .build();
        }

        Action.ActionBuilder builder = new Action.ActionBuilder();
        LocalDateTime parsedTime = LocalDateTime.parse(ra.timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        builder.withTimestamp(parsedTime);
        List<RestEvent> events = ra.getEvents();
        List<Event> eventList = new ArrayList<>(events.size());
        for (RestEvent restEvent : events) {
            Payload.PayloadBuilder payloadBuilder = new Payload.PayloadBuilder();
            restEvent.getPayload().forEach(payloadBuilder::withAdditionalProperty);

            Event event = new Event.EventBuilder()
                    .withMetadata(new Metadata.MetadataBuilder().build())
                    .withPayload(payloadBuilder.build())
                    .build();
            eventList.add(event);
        }

        List<Recipient> recipientList = new ArrayList<>();
        List<RestRecipient> recipients = ra.getRecipients();
        if (recipients != null) {
            for (RestRecipient restRecipient : recipients) {
                Recipient recipient = new Recipient.RecipientBuilder()
                        .withIgnoreUserPreferences(restRecipient.isIgnoreUserPreferences())
                        .withOnlyAdmins(restRecipient.isOnlyAdmins())
                        .withUsers(restRecipient.getUsers() != null ? restRecipient.getUsers() : List.of())
                        .withEmails(restRecipient.getEmails() != null ? restRecipient.getEmails() : List.of())
                        .build();
                recipientList.add(recipient);
            }
        }

        Context.ContextBuilder contextBuilder = new Context.ContextBuilder();
        ra.getContext().forEach(contextBuilder::withAdditionalProperty);

        builder.withEvents(eventList);
        builder.withRecipients(recipientList);
        builder.withEventType(ra.eventType);
        builder.withApplication(ra.application);
        builder.withBundle(ra.bundle);
        builder.withAccountId(ra.accountId);
        builder.withContext(contextBuilder.build());
        builder.withOrgId(ra.orgId);
        builder.withId(ra.id);

        Action action = builder.build();
        String serializedAction = Parser.encode(action);

        CompletableFuture<Void> callback = new CompletableFuture<>();
        Message<String> message = buildMessageWithId(serializedAction, sourceEnvironment, callback);

        try {
            emitter.send(message);
            /*
             * The following line waits until the callback is complete or the timeout delay is expired.
             * If the Kafka server responded with an ack, no exception will be thrown and the execution of the try block will continue.
             * If the Kafka server responded with a nack, an ExecutionException will be thrown, resulting in an HTTP 503 status returned to the caller.
             * If the timeout delay is expired, a TimeoutException will be thrown, resulting in an HTTP 503 status returned to the caller.
             */
            callback.get(callbackTimeout, TimeUnit.SECONDS);
            forwardedActions.increment();
            String responseEntity = buildResponseEntity(true, null);
            return Response.ok(responseEntity).build();
        } catch (Throwable t) {
            this.incrementFailuresCounter(SERVICE_UNAVAILABLE);
            Log.error("Message delivery to Kafka failed", t);
            String responseEntity = buildResponseEntity(false, "Message delivery to Kafka failed, please try again later");
            return Response.status(SERVICE_UNAVAILABLE).entity(responseEntity).build();
        }
    }

    private static Message<String> buildMessageWithId(String payload, String sourceEnvironment, CompletableFuture<Void> callback) {
        byte[] messageId = UUID.randomUUID().toString().getBytes(UTF_8);
        Headers headers = new RecordHeaders()
                .add(MESSAGE_ID_HEADER, messageId);
        if (sourceEnvironment != null) {
            headers.add(SOURCE_ENVIRONMENT_HEADER, sourceEnvironment.getBytes(UTF_8));
        }
        OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withHeaders(headers)
                .build();
        return Message.of(payload)
                .addMetadata(metadata)
                .withAck(() -> {
                    callback.complete(null);
                    return CompletableFuture.completedFuture(null);
                }).withNack(reason -> {
                    callback.completeExceptionally(reason);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private static String buildResponseEntity(boolean success, String details) {
        JsonObject response = new JsonObject();
        response.put("result", success ? "success" : "error");
        if (details != null) {
            response.put("details", details);
        }
        return response.encode();
    }

    private Optional<SourceEnvironment> getSourceEnvironment(String bundle, String app, String authenticationData) {
        try {
            SourceEnvironment sourceEnvironment = restValidationClient.validateCertificate(bundle, app, authenticationData);
            Log.infof("Authentication validated, coming from source environment %s", sourceEnvironment.name);
            return Optional.of(sourceEnvironment);
        } catch (Exception e) {
            Log.infof("Unable to validate authentication '%s' for bundle '%s' and application '%s'",
                authenticationData,
                bundle,
                app);
            return Optional.empty();
        }
    }

    /**
     * Increments the gateway's failures counter.
     * @param status the status to set as a label.
     */
    private void incrementFailuresCounter(final Status status) {
        this.meterRegistry.counter(FAILURES_COUNTER, Tags.of("status_code", String.valueOf(status.getStatusCode()))).increment();
    }
}
