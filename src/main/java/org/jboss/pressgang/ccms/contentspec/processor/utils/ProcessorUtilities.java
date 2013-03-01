package org.jboss.pressgang.ccms.contentspec.processor.utils;

import java.util.List;

import org.jboss.pressgang.ccms.contentspec.SpecTopic;
import org.jboss.pressgang.ccms.contentspec.constants.CSConstants;
import org.jboss.pressgang.ccms.contentspec.processor.exceptions.InvalidKeyValueException;
import org.jboss.pressgang.ccms.contentspec.processor.structures.VariableSet;
import org.jboss.pressgang.ccms.contentspec.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.contentspec.provider.PropertyTagProvider;
import org.jboss.pressgang.ccms.contentspec.provider.TagProvider;
import org.jboss.pressgang.ccms.contentspec.provider.TopicProvider;
import org.jboss.pressgang.ccms.contentspec.provider.TopicSourceURLProvider;
import org.jboss.pressgang.ccms.contentspec.wrapper.PropertyTagInTopicWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.PropertyTagWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.TagWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.TopicSourceURLWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.contentspec.wrapper.collection.UpdateableCollectionWrapper;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.StringUtilities;
import org.jboss.pressgang.ccms.utils.structures.Pair;

public class ProcessorUtilities {
    /**
     * Finds a set of variables that are grouped by delimiters. It also skips nested
     * groups and returns them as part of the set so they can be processed separately.
     * eg. [var1, var2, [var3, var4], var5]
     * <p/>
     * This method will also account for missing brackets. Example with no end bracket: [var1, var2 [var3, var4]
     *
     * @param input      The string to find the set for.
     * @param startDelim The starting delimiter for the set.
     * @param endDelim   The ending delimiter for the set.
     * @param startPos   The position to start searching from in the string.
     * @return A VariableSet object that contains the contents of the set, the start position
     *         in the string and the end position.
     */
    public static VariableSet findVariableSet(final String input, final char startDelim, final char endDelim, final int startPos) {
        final int startIndex = StringUtilities.indexOf(input, startDelim, startPos);
        int endIndex = StringUtilities.indexOf(input, endDelim, startPos);
        int nextStartIndex = startIndex == -1 ? -1 : StringUtilities.indexOf(input, startDelim, startIndex + 1);

        /*
         * Find the ending delimiter that matches the start delimiter. This is done
         * by checking to see if the next start delimiter is before the current end
         * delimiter. If that is the case then there is a nested set so look for the
         * next end delimiter.
         */
        while (nextStartIndex < endIndex && nextStartIndex != -1 && endIndex != -1) {
            final int prevEndIndex = endIndex;
            endIndex = StringUtilities.indexOf(input, endDelim, endIndex + 1);
            nextStartIndex = StringUtilities.indexOf(input, startDelim, prevEndIndex + 1);
        }

        // Build the resulting set object
        final VariableSet set = new VariableSet();

        if (endIndex == -1 && startIndex != -1) {
            set.setContents(input.substring(startIndex));
            set.setEndPos(null);
            set.setStartPos(startIndex);
        } else if (startIndex != -1) {
            set.setContents(input.substring(startIndex, endIndex + 1));
            set.setEndPos(endIndex);
            set.setStartPos(startIndex);
        } else if (endIndex != -1) {
            set.setContents(input.substring(startPos, endIndex + 1));
            set.setEndPos(endIndex);
            set.setStartPos(null);
        } else {
            String remainingData = input.substring(startPos);
            if (!remainingData.trim().isEmpty()) {
                set.setContents(remainingData);
                set.setEndPos(input.length() - 1);
            } else {
                set.setContents(null);
                set.setEndPos(null);
            }
            set.setStartPos(null);
        }
        return set;
    }

    /**
     * Validates a KeyValue pair for a content specification and then returns the processed key and value..
     *
     * @param keyValueString The string to be broken down and validated.
     * @return A Pair where the first value is the key and the second is the value.
     * @throws InvalidKeyValueException
     */
    public static Pair<String, String> getAndValidateKeyValuePair(final String keyValueString) throws InvalidKeyValueException {
        String tempInput[] = StringUtilities.split(keyValueString, '=', 2);
        // Remove the whitespace from each value in the split array
        tempInput = CollectionUtilities.trimStringArray(tempInput);
        if (tempInput.length >= 2) {
            return new Pair<String, String>(tempInput[0], StringUtilities.replaceEscapeChars(tempInput[1]));
        } else {
            throw new InvalidKeyValueException();
        }
    }

    /**
     * Clones a Topic, resets the Added By and CSP ID properties and ignores assigned writers when cloning.
     *
     * @param providerFactory
     * @param specTopic       The SpecTopic object that represents a topic.
     * @return The cloned topic wrapper entity.
     */
    public static TopicWrapper cloneTopic(final DataProviderFactory providerFactory, final SpecTopic specTopic) {
        final TopicProvider topicProvider = providerFactory.getProvider(TopicProvider.class);
        final TopicSourceURLProvider topicSourceUrlProvider = providerFactory.getProvider(TopicSourceURLProvider.class);
        final TagProvider tagProvider = providerFactory.getProvider(TagProvider.class);
        final PropertyTagProvider propertyTagProvider = providerFactory.getProvider(PropertyTagProvider.class);

        // Get the existing topic from the database
        int clonedId = Integer.parseInt(specTopic.getId().substring(1));
        final TopicWrapper originalTopic = topicProvider.getTopic(clonedId, null);
        final TopicWrapper cloneTopic = topicProvider.newTopic();

        // Set the ID to null so a new ID will be created
        cloneTopic.setId(null);
        // Set-up the basic parameters
        cloneTopic.setTitle(originalTopic.getTitle());
        cloneTopic.setDescription(originalTopic.getDescription());
        cloneTopic.setHtml(originalTopic.getHtml());
        cloneTopic.setXml(originalTopic.getXml());
        cloneTopic.setXmlDoctype(originalTopic.getXmlDoctype());
        cloneTopic.setLocale(originalTopic.getLocale());

        // Go through each collection and add the original topics data
        if (originalTopic.getIncomingRelationships() != null && !originalTopic.getIncomingRelationships().isEmpty()) {
            final CollectionWrapper<TopicWrapper> cloneIncomingTopics = topicProvider.newTopicCollection();
            for (final TopicWrapper incomingRelationship : originalTopic.getIncomingRelationships().getItems()) {
                cloneIncomingTopics.addNewItem(incomingRelationship);
            }
            cloneTopic.setIncomingRelationships(cloneIncomingTopics);
        }

        if (originalTopic.getOutgoingRelationships() != null && !originalTopic.getOutgoingRelationships().isEmpty()) {
            final CollectionWrapper<TopicWrapper> cloneOutgoingTopics = topicProvider.newTopicCollection();
            for (final TopicWrapper outgoingRelationship : originalTopic.getOutgoingRelationships().getItems()) {
                cloneOutgoingTopics.addNewItem(outgoingRelationship);
            }
            cloneTopic.setOutgoingRelationships(cloneOutgoingTopics);
        }

        // SOURCE URLS
        if (originalTopic.getSourceURLs() != null && !originalTopic.getSourceURLs().isEmpty()) {
            final CollectionWrapper<TopicSourceURLWrapper> cloneSourceUrls = topicSourceUrlProvider.newTopicSourceURLCollection(cloneTopic);
            for (final TopicSourceURLWrapper sourceUrl : originalTopic.getSourceURLs().getItems()) {
                final TopicSourceURLWrapper cloneSourceUrl = cloneTopicSourceUrl(topicSourceUrlProvider, sourceUrl, cloneTopic);
                cloneSourceUrls.addNewItem(cloneSourceUrl);
            }
            cloneTopic.setSourceURLs(cloneSourceUrls);
        }

        // TAGS
        if (originalTopic.getTags() != null && !originalTopic.getTags().isEmpty()) {
            final CollectionWrapper<TagWrapper> newTags = tagProvider.newTagCollection();
            final List<TagWrapper> tags = originalTopic.getTags().getItems();
            for (final TagWrapper tag : tags) {
                // Remove the old writer tag as it will get replaced
                if (!tag.containedInCategory(CSConstants.WRITER_CATEGORY_ID)) {
                    newTags.addNewItem(tag);
                }
            }

            // Set the tags if any tags exist
            if (!newTags.isEmpty()) {
                cloneTopic.setTags(newTags);
            }
        }

        final UpdateableCollectionWrapper<PropertyTagInTopicWrapper> newProperties = propertyTagProvider.newPropertyTagInTopicCollection();
        final List<PropertyTagInTopicWrapper> propertyItems = originalTopic.getProperties().getItems();
        boolean cspPropertyFound = false;
        for (final PropertyTagInTopicWrapper property : propertyItems) {
            final PropertyTagInTopicWrapper clonedProperty = cloneTopicProperty(propertyTagProvider, property);
            // Ignore the CSP Property ID as we will add a new one
            if (property.getId().equals(CSConstants.CSP_PROPERTY_ID)) {
                cspPropertyFound = true;

                clonedProperty.setValue(specTopic.getUniqueId());
                newProperties.addNewItem(clonedProperty);
            } else if (property.getId().equals(CSConstants.ADDED_BY_PROPERTY_TAG_ID)) {
                // Ignore the added by property as we will add it later
            } else {
                newProperties.addNewItem(clonedProperty);
            }
        }

        if (!cspPropertyFound) {
            final PropertyTagWrapper propertyTag = propertyTagProvider.getPropertyTag(CSConstants.CSP_PROPERTY_ID);
            final PropertyTagInTopicWrapper cspProperty = propertyTagProvider.newPropertyTagInTopic(propertyTag);
            cspProperty.setValue(specTopic.getUniqueId());
            newProperties.addNewItem(cspProperty);
        }

        // Add the added by property tag
        final String assignedWriter = specTopic.getAssignedWriter(true);
        if (assignedWriter != null) {
            final PropertyTagWrapper addedByPropertyTag = propertyTagProvider.getPropertyTag(CSConstants.ADDED_BY_PROPERTY_TAG_ID);
            final PropertyTagInTopicWrapper addedByProperty = propertyTagProvider.newPropertyTagInTopic(addedByPropertyTag);
            addedByProperty.setValue(assignedWriter);
            newProperties.addNewItem(addedByProperty);
        }

        if (!newProperties.isEmpty()) {
            cloneTopic.setProperties(newProperties);
        }

        return cloneTopic;
    }

    /**
     * Clones a Topic Property Tag.
     *
     * @param propertyTagProvider The property tag provider to lookup additional details.
     * @param originalProperty    The PropertyTag to be cloned.
     * @return The cloned property tag.
     */
    public static PropertyTagInTopicWrapper cloneTopicProperty(final PropertyTagProvider propertyTagProvider,
            final PropertyTagInTopicWrapper originalProperty) {
        final PropertyTagWrapper propertyTag = propertyTagProvider.getPropertyTag(originalProperty.getId());
        final PropertyTagInTopicWrapper newPropertyTag = propertyTagProvider.newPropertyTagInTopic(propertyTag);

        newPropertyTag.setName(originalProperty.getName());
        newPropertyTag.setValue(originalProperty.getValue());

        return newPropertyTag;
    }

    /**
     * Clones a Topic Source URL
     *
     * @param topicSourceUrlProvider The Topic Source URL provider to lookup additional details.
     * @param originalSourceUrl      The Source URL to be cloned.
     * @param parent                 The parent to the new topic source url.
     * @return The cloned topic source url.
     */
    public static TopicSourceURLWrapper cloneTopicSourceUrl(final TopicSourceURLProvider topicSourceUrlProvider,
            final TopicSourceURLWrapper originalSourceUrl, final TopicWrapper parent) {
        final TopicSourceURLWrapper sourceUrl = topicSourceUrlProvider.newTopicSourceURL(parent);

        sourceUrl.setTitle(originalSourceUrl.getTitle());
        sourceUrl.setDescription(originalSourceUrl.getDescription());
        sourceUrl.setUrl(originalSourceUrl.getUrl());

        return sourceUrl;
    }
}
