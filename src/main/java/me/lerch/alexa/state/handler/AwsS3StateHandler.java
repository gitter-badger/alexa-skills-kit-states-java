/**
 * Made by Kay Lerch (https://twitter.com/KayLerch)
 *
 * Attached license applies.
 * This library is licensed under GNU GENERAL PUBLIC LICENSE Version 3 as of 29 June 2007
 */
package me.lerch.alexa.state.handler;

import com.amazon.speech.speechlet.Session;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import me.lerch.alexa.state.model.AlexaScope;
import me.lerch.alexa.state.model.AlexaStateModel;
import me.lerch.alexa.state.utils.AlexaStateErrorException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This handler reads and writes state for AlexaStateModels and considers all its fields annotated with AlexaSaveState-tags.
 * As this handler works in the user and application scope it persists all models to an S3 bucket.
 * This handler derives from the AlexaSessionStateHandler thus it reads and writes state out of S3 files also to your Alexa
 * session. For each individual scope (which is described by the Alexa User Id there will be a directory in your bucket which
 * then contains files - one for each instance of a saved model.
 */
public class AwsS3StateHandler extends AlexaSessionStateHandler {

    private final AmazonS3Client awsClient;
    private final String bucketName;
    private final String folderNameApp = "__application";
    private final String fileExtension = "json";

    /**
     * Takes the Alexa session and an AWS client set up for the AWS region the given bucket is in. The
     * credentials of this client need permission for getting and putting objects to this bucket.
     * @param session The Alexa session of your current skill invocation.
     * @param awsClient An AWS client capable of getting and putting objects to the given bucket.
     * @param bucketName The bucket where all saved states will go into.
     */
    public AwsS3StateHandler(final Session session, final AmazonS3Client awsClient, final String bucketName) {
        super(session);
        this.awsClient = awsClient;
        this.bucketName = bucketName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeModel(final AlexaStateModel model) throws AlexaStateErrorException {
        // write to session
        super.writeModel(model);
        boolean hasAppScopedFields = model.getSaveStateFields(AlexaScope.APPLICATION).stream().findAny().isPresent();
        boolean hasUserScopedFields = model.getSaveStateFields(AlexaScope.USER).stream().findAny().isPresent();

        if (hasUserScopedFields) {
            final String filePath = getUserScopedFilePath(model.getClass(), model.getId());
            // add json as new content of file
            final String fileContents = model.toJSON(AlexaScope.USER);
            // write all user-scoped attributes to file
            awsClient.putObject(bucketName, filePath, fileContents);
        }
        if (hasAppScopedFields) {
            // add primary keys as attributes
            final String filePath = getAppScopedFilePath(model.getClass(), model.getId());
            // add json as new content of file
            final String fileContents = model.toJSON(AlexaScope.APPLICATION);
            // write all app-scoped attributes to file
            awsClient.putObject(bucketName, filePath, fileContents);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeModel(AlexaStateModel model) {
        super.removeModel(model);
        // removeState user-scoped file
        awsClient.deleteObject(bucketName, getUserScopedFilePath(model.getClass(), model.getId()));
        // removeState app-scoped file
        awsClient.deleteObject(bucketName, getAppScopedFilePath(model.getClass(), model.getId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <TModel extends AlexaStateModel> Optional<TModel> readModel(final Class<TModel> modelClass) throws AlexaStateErrorException {
        return this.readModel(modelClass, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <TModel extends AlexaStateModel> Optional<TModel> readModel(final Class<TModel> modelClass, final String id) throws AlexaStateErrorException {
        // if there is nothing for this model in the session ...
        // create new model with given id. for now we assume a model exists for this id. we find out by
        // reading file from the bucket in the following lines. only if this is true model will be written back to session
        final TModel model = super.readModel(modelClass, id).orElse(createModel(modelClass, id));
        // get all fields which are user-scoped
        final boolean hasUserScopedFields = !model.getSaveStateFields(AlexaScope.USER).isEmpty();
        // get all fields which are app-scoped
        final boolean hasAppScopedFields = !model.getSaveStateFields(AlexaScope.APPLICATION).isEmpty();
        // we need to remember if there will be something from dynamodb to be written to the model
        // in order to write those values back to the session at the end of this method
        Boolean modelChanged = false;
        // and if there are user-scoped fields ...
        if (hasUserScopedFields && fromS3FileContentsToModel(model, id, AlexaScope.USER)) {
            modelChanged = true;
        }
        // and if there are app-scoped fields ...
        if (hasAppScopedFields && fromS3FileContentsToModel(model, id, AlexaScope.APPLICATION)) {
            modelChanged = true;
        }
        // so if model changed from within something out of S3 we want this to be in the speechlet as well
        // this gives you access to user- and app-scoped attributes throughout a session without reading from S3 over and over again
        if (modelChanged) {
            super.writeModel(model);
        }
        return Optional.of(model);
    }

    private boolean fromS3FileContentsToModel(final AlexaStateModel alexaStateModel, final String id, final AlexaScope scope) throws AlexaStateErrorException {
        // do only read from file if model has fields tagged with given scope
        if (!alexaStateModel.getSaveStateFields(scope).isEmpty()) {
            // read from item with scoped model
            final String filePath = AlexaScope.APPLICATION.includes(scope) ? getAppScopedFilePath(alexaStateModel.getClass(), id) : getUserScopedFilePath(alexaStateModel.getClass(), id);
            if (awsClient.doesObjectExist(bucketName, filePath)) {
                // extract values from json and assign it to model
                return alexaStateModel.fromJSON(getS3FileContentsAsString(filePath), scope);
            }
        }
        return false;
    }

    private String getS3FileContentsAsString(final String filePath) throws AlexaStateErrorException {
        final S3Object file = awsClient.getObject(bucketName, filePath);
        final BufferedReader reader = new BufferedReader(new InputStreamReader(file.getObjectContent()));
        final StringBuilder sb = new StringBuilder();
        String line;
        try {
            while((line = reader.readLine()) != null){
                sb.append(line);
            }
        } catch (IOException e) {
            throw AlexaStateErrorException.create("Could not read from S3-file " + filePath).withCause(e).withHandler(this).build();
        }
        final String fileContents = sb.toString();
        return fileContents.isEmpty() ? "{}" : fileContents;
    }

    private <TModel extends AlexaStateModel> String getUserScopedFilePath(Class<TModel> modelClass) {
        return getUserScopedFilePath(modelClass, null);
    }

    private <TModel extends AlexaStateModel> String getUserScopedFilePath(Class<TModel> modelClass, String id) {
        return  session.getUser().getUserId() + "/" + getAttributeKey(modelClass, id) + "." + fileExtension;
    }

    private <TModel extends AlexaStateModel> String getAppScopedFilePath(Class<TModel> modelClass) {
        return getAppScopedFilePath(modelClass, null);
    }

    private <TModel extends AlexaStateModel> String getAppScopedFilePath(Class<TModel> modelClass, String id) {
        return folderNameApp + "/" + getAttributeKey(modelClass, id) + "." + fileExtension;
    }
}