/**
 * Made by Kay Lerch (https://twitter.com/KayLerch)
 * <p>
 * Attached license applies.
 * This library is licensed under GNU GENERAL PUBLIC LICENSE Version 3 as of 29 June 2007
 */
package io.klerch.alexa.state.handler;

import com.amazon.speech.speechlet.Session;
import com.amazonaws.services.iot.AWSIot;
import com.amazonaws.services.iot.AWSIotClient;
import com.amazonaws.services.iot.model.*;
import com.amazonaws.services.iotdata.AWSIotData;
import com.amazonaws.services.iotdata.AWSIotDataClient;
import com.amazonaws.services.iotdata.model.GetThingShadowRequest;
import com.amazonaws.services.iotdata.model.GetThingShadowResult;
import com.amazonaws.services.iotdata.model.UpdateThingShadowRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klerch.alexa.state.model.AlexaScope;
import io.klerch.alexa.state.model.AlexaStateModel;
import io.klerch.alexa.state.utils.AlexaStateException;
import io.klerch.alexa.state.utils.EncryptUtils;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.String.format;

/**
 * As this handler works in the user and application scope it persists all models to a thing shadow in AWS IoT.
 * A saved state goes into the "desired" JSON portion of a shadow state whereas state is read only from
 * the "reported" portion of that shadow. That said this handler differs a bit from the other ones as you
 * cannot expect to read state like you wrote it to the store. It needs the thing to fulfill the desired
 * state and reports it back to the shadow. That's how you cannot only trigger physical action by saving state
 * over this handler but also get informed about current thing state.
 */
public class AWSIotStateHandler extends AlexaSessionStateHandler {
    private final Logger log = Logger.getLogger(AWSIotStateHandler.class);

    private final AWSIot awsClient;
    private final AWSIotData awsDataClient;
    private final String thingAttributeName = "name";
    private final String thingAttributeUser = "amzn-user-id";
    private final String thingAttributeApp = "amzn-app-id";
    private List<String> thingsExisting = new ArrayList<>();

    public AWSIotStateHandler(final Session session) {
        this(session, new AWSIotClient(), new AWSIotDataClient());
    }

    public AWSIotStateHandler(final Session session, final AWSIot awsClient, final AWSIotData awsDataClient) {
        super(session);
        this.awsClient = awsClient;
        this.awsDataClient = awsDataClient;
    }

    /**
     * Returns the AWS connection client used by this handler to manage resources
     * in AWS IoT.
     * @return AWS connection client for AWS IoT
     */
    public AWSIot getAwsClient() {
        return this.awsClient;
    }

    /**
     * Returns the AWS connection client used by this handler to store model states in
     * thing shadows of AWS IoT.
     * @return AWS data connection client for AWS IoT
     */
    public AWSIotData getAwsDataClient() {
        return this.awsDataClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeModel(final AlexaStateModel model) throws AlexaStateException {
        // write to session
        super.writeModel(model);

        if (model.hasUserScopedField()) {
            publishState(model, AlexaScope.USER);
        }
        if (model.hasApplicationScopedField()) {
            publishState(model, AlexaScope.APPLICATION);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <TModel extends AlexaStateModel> Optional<TModel> readModel(final Class<TModel> modelClass) throws AlexaStateException {
        return this.readModel(modelClass, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeModel(final AlexaStateModel model) throws AlexaStateException {
        super.removeModel(model);

        if (model.hasSessionScopedField() || model.hasUserScopedField()) {
            removeModelFromShadow(model, AlexaScope.USER);
        }
        if (model.hasApplicationScopedField()) {
            removeModelFromShadow(model, AlexaScope.APPLICATION);
        }
        log.debug(format("Removed state from AWS IoT shadow for '%1$s'.", model));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <TModel extends AlexaStateModel> Optional<TModel> readModel(final Class<TModel> modelClass, final String id) throws AlexaStateException {
        // if there is nothing for this model in the session ...
        final Optional<TModel> modelSession = super.readModel(modelClass, id);
        // create new model with given id. for now we assume a model exists for this id. we find out by
        // reading file from the bucket in the following lines. only if this is true model will be written back to session
        final TModel model = modelSession.orElse(createModel(modelClass, id));
        // we need to remember if there will be something from thing shadow to be written to the model
        // in order to write those values back to the session at the end of this method
        Boolean modelChanged = false;
        // and if there are user-scoped fields ...
        if (model.hasUserScopedField() && fromThingShadowToModel(model, AlexaScope.USER)) {
            modelChanged = true;
        }
        // and if there are app-scoped fields ...
        if (model.hasApplicationScopedField() && fromThingShadowToModel(model, AlexaScope.APPLICATION)) {
            modelChanged = true;
        }
        // so if model changed from within something out of the shadow we want this to be in the speechlet as well
        // this gives you access to user- and app-scoped attributes throughout a session without reading from S3 over and over again
        if (modelChanged) {
            super.writeModel(model);
            return Optional.of(model);
        }
        else {
            // if there was nothing received from IOT and there is nothing to return from session
            // then its not worth return the model. better indicate this model does not exist
            return modelSession.isPresent() ? Optional.of(model) : Optional.empty();
        }
    }

    /**
     * Returns name of the thing whose shadow is updated by this handler. It depends on
     * the scope of the fields persisted in AWS IoT as APPLICATION-scoped fields go to a different
     * thing shadow than USER-scoped fields.
     * @param scope The scope this thing is dedicated to
     * @return Name of the thing for this scope
     * @throws AlexaStateException Any error regarding thing name generation
     */
    public String getThingName(final AlexaScope scope) throws AlexaStateException {
        return AlexaScope.APPLICATION.includes(scope) ? getAppScopedThingName() : getUserScopedThingName();
    }

    /**
     * The thing will be created in AWS IoT if not existing for this application (when scope
     * APPLICATION is given) or for this user in this application (when scope USER is given)
     * @param scope The scope this thing is dedicated to
     * @throws AlexaStateException Any error regarding thing creation or existence check
     */
    public void createThingIfNotExisting(final AlexaScope scope) throws AlexaStateException {
        final String thingName = getThingName(scope);
        if (!doesThingExist(thingName)) {
            createThing(thingName, scope);
        }
    }

    /**
     * Returns if the thing dedicated to the scope given is existing in AWS IoT.
     * @param scope The scope this thing is dedicated to
     * @return True, if the thing dedicated to the scope given is existing in AWS IoT.
     * @throws AlexaStateException Any error regarding thing creation or existence check
     */
    public boolean doesThingExist(final AlexaScope scope) throws AlexaStateException {
        final String thingName = getThingName(scope);
        return doesThingExist(thingName);
    }

    private void removeModelFromShadow(final AlexaStateModel model, final AlexaScope scope) throws AlexaStateException {
        final String nodeName = model.getAttributeKey();
        final String thingName = getThingName(scope);
        final String thingState = getState(scope);
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode root = mapper.readTree(thingState);
            if (!root.isMissingNode()) {
                final JsonNode desired = root.path("state").path("desired");
                if (!desired.isMissingNode() && desired instanceof ObjectNode) {
                    ((ObjectNode) desired).remove(nodeName);
                }
            }
            final String json = mapper.writeValueAsString(root);
            publishState(thingName, json);
        } catch (IOException e) {
            final String error = format("Could not extract model state of '%1$s' from thing shadow '%2$s'", model, thingName);
            log.error(error, e);
            throw AlexaStateException.create(error).withCause(e).withModel(model).build();
        }
    }

    private boolean fromThingShadowToModel(final AlexaStateModel model, final AlexaScope scope) throws AlexaStateException {
        // read from item with scoped model
        final String thingName = getThingName(scope);
        final String thingState = getState(scope);
        final String nodeName = model.getAttributeKey();
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode node = mapper.readTree(thingState).path("state").path("reported").path(nodeName);
            if (!node.isMissingNode()) {
                final String json = mapper.writeValueAsString(node);
                return model.fromJSON(json, scope);
            }
        } catch (IOException e) {
            final String error = format("Could not extract model state of '%1$s' from thing shadow '%2$s'", model, thingName);
            log.error(error, e);
            throw AlexaStateException.create(error).withCause(e).withModel(model).build();
        }
        return false;
    }

    /**
     * Returns the name of the thing which is used to store model state scoped
     * as USER
     * @return Thing name for user-wide model state
     * @throws AlexaStateException some exceptions may occure when encrypting the user-id
     */
    public String getUserScopedThingName() throws AlexaStateException {
        // user-ids in Alexa are too long for thing names in AWS IOT.
        // use the SHA1-hash of the user-id
        final String userHash;
        try {
            userHash = EncryptUtils.encryptSha1(session.getUser().getUserId());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            final String error = "Could not encrypt user-id for generating the IOT thing-name";
            log.error(error, e);
            throw AlexaStateException.create(error).withHandler(this).withCause(e).build();
        }
        return getAppScopedThingName() + "-" + userHash;
    }

    /**
     * Returns the name of the thing which is used to store model state scoped
     * as APPLICATION
     * @return Thing name for application-wide model state
     */
    public String getAppScopedThingName() {
        // thing names do not allow dots in it
        return session.getApplication().getApplicationId().replace(".", "-");
    }

    private String getState(final AlexaScope scope) throws AlexaStateException {
        final String thingName = getThingName(scope);

        createThingIfNotExisting(scope);

        final GetThingShadowRequest awsRequest = new GetThingShadowRequest().withThingName(thingName);
        try {
            final GetThingShadowResult response = awsDataClient.getThingShadow(awsRequest);
            final ByteBuffer buffer = response.getPayload();

            try {
                return (buffer != null && buffer.hasArray()) ? new String(buffer.array(), "UTF-8") : "{}";
            } catch (UnsupportedEncodingException e) {
                final String error = format("Could not handle received contents of thing-shadow '%1$s'", thingName);
                log.error(error, e);
                throw AlexaStateException.create(error).withCause(e).withHandler(this).build();
            }
        }
        // if a thing does not have a shadow this is a usual exception
        catch (com.amazonaws.services.iotdata.model.ResourceNotFoundException e) {
            log.info(e);
            // we are fine with a thing having no shadow what just means there's nothing to read out for the model
            // return an empty JSON to indicate nothing is in the thing shadow
            return "{}";
        }
    }

    private void publishState(final AlexaStateModel model, final AlexaScope scope) throws AlexaStateException {
        final String thingName = getThingName(scope);
        createThingIfNotExisting(scope);
        final String payload = "{\"state\":{\"desired\":{\"" + model.getAttributeKey() + "\":" + model.toJSON(scope) + "}}}";
        publishState(thingName, payload);
        log.debug(format("State '%1$s' is published to shadow of '%2$s' in AWS IoT.", payload, thingName));
    }

    private void publishState(final String thingName, final String json) throws AlexaStateException {
        final ByteBuffer buffer;
        try {
            buffer = ByteBuffer.wrap(json.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            final String error = format("Could not prepare JSON for model state publication to thing shadow '%1$s'", thingName);
            log.error(error, e);
            throw AlexaStateException.create(error).withCause(e).withHandler(this).build();
        }
        final UpdateThingShadowRequest iotRequest = new UpdateThingShadowRequest().withThingName(thingName).withPayload(buffer);
        awsDataClient.updateThingShadow(iotRequest);
    }

    private void createThing(final String thingName, final AlexaScope scope) {
        // only create thing if not already existing
        final AttributePayload attrPayload = new AttributePayload();
        // add thing name as attribute as well. this is how the handler queries for the thing from now on
        attrPayload.addAttributesEntry(thingAttributeName, thingName);
        // if scope is user an attribute saves the plain user id as it is encrypted in the thing name
        if (AlexaScope.USER.includes(scope)) {
            attrPayload.addAttributesEntry(thingAttributeUser, session.getUser().getUserId());
        }
        // another thing attributes holds the Alexa application-id
        attrPayload.addAttributesEntry(thingAttributeApp, session.getApplication().getApplicationId());
        // now create the thing
        final CreateThingRequest request = new CreateThingRequest().withThingName(thingName).withAttributePayload(attrPayload);
        awsClient.createThing(request);
        log.info(format("Thing '%1$s' is created in AWS IoT.", thingName));
    }

    private boolean doesThingExist(final String thingName) {
        // if already checked existence than return immediately
        if (thingsExisting.contains(thingName)) return true;
        // query by an attribute having the name of the thing
        // unfortunately you can only query for things with their attributes, not directly with their names
        final ListThingsRequest request = new ListThingsRequest().withAttributeName(thingAttributeName).withAttributeValue(thingName).withMaxResults(1);
        final ListThingsResult result = awsClient.listThings(request);
        if(result != null && result.getThings() != null && result.getThings().isEmpty()) {
            thingsExisting.add(thingName);
            return true;
        }
        return false;
    }
}
