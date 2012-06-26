package com.redhat.contentspec.processor;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import com.redhat.contentspec.constants.CSConstants;
import com.redhat.contentspec.interfaces.ShutdownAbleApp;
import com.redhat.contentspec.ContentSpec;
import com.redhat.contentspec.SpecTopic;
import com.redhat.contentspec.processor.constants.ProcessorConstants;
import com.redhat.contentspec.processor.structures.ProcessingOptions;
import com.redhat.contentspec.processor.utils.ProcessorUtilities;
import com.redhat.contentspec.rest.RESTManager;
import com.redhat.contentspec.rest.RESTReader;
import com.redhat.contentspec.rest.RESTWriter;
import com.redhat.contentspec.rest.utils.TopicPool;
import com.redhat.contentspec.utils.logging.ErrorLogger;
import com.redhat.contentspec.utils.logging.ErrorLoggerManager;
import com.redhat.ecs.commonstructures.Pair;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.UserV1;
import com.redhat.topicindex.rest.entities.CategoryV1;
import com.redhat.topicindex.rest.entities.PropertyTagV1;
import com.redhat.topicindex.rest.entities.TagV1;
import com.redhat.topicindex.rest.entities.TopicSourceUrlV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;

/**
 * A class to fully process a Content Specification. It first parses the data using a ContentSpecParser,
 * then validates the Content Specification using a ContentSpecValidator and lastly saves the data to the database.
 * It can also be configured to only validate the data and not save it.
 * 
 * @author lnewson
 */
@SuppressWarnings("rawtypes")
public class ContentSpecProcessor implements ShutdownAbleApp
{
	private final Logger LOG = Logger.getLogger("com.redhat.contentspec.processor.CustomContentSpecProcessor");
	
	private final ErrorLogger log;
	private final ErrorLoggerManager elm;
	
	private final RESTManager dbManager;
	private final RESTReader reader;
	private final RESTWriter writer;
	
	private final ProcessingOptions processingOptions;
	private final ContentSpecParser csp;
	private ContentSpecValidator validator;
	private final TopicPool topics;
	private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
	private final AtomicBoolean shutdown = new AtomicBoolean(false);
	
	/**
	 * Constructor
	 * 
	 * @param restManager A DBManager object that manages the REST connection and the functions to read/write to the REST Interface.
	 * @param elm An Error Logger Manager that will be used to store all the log messages in case they need to be accessed at a later stage.
	 * @param processingOptions The set of options to use when processing.
	 */
	public ContentSpecProcessor(final RESTManager restManager, final ErrorLoggerManager elm, final ProcessingOptions processingOptions)
	{
		reader = restManager.getReader();
		writer = restManager.getWriter();
		log = elm.getLogger(ContentSpecProcessor.class);
		this.elm = elm;
		this.dbManager = restManager;
		this.csp = new ContentSpecParser(elm, restManager);
		this.topics = new TopicPool(restManager.getRESTClient());
		this.processingOptions = processingOptions;
	}
	
	/**
	 * Gets the Content Specification Object for the content specification.
	 * 
	 * @return The ContentSpec object that's used to store the processed data.
	 */
	public ContentSpec getContentSpec()
	{
		return csp.getContentSpec();
	}
	
	/**
	 * Gets the Content Specification Topics inside of a content specification
	 * 
	 * @return The mapping of topics to their unique content specification ID's.
	 */
	public HashMap<String, SpecTopic> getSpecTopics()
	{
		return csp.getSpecTopics();
	}
	
	/**
	 * Process a content specification so that it is parsed, validated and saved.
	 * 
	 * @param contentSpec The Content Specification that is to be processed.
	 * @param user The user who requested the process operation.
	 * @param mode The mode to parse the content specification in.
	 * @return True if everything was processed successfully otherwise false.
	 * @throws Exception Any unexpected exception that occurred when processing.
	 */
	public boolean processContentSpec(final String contentSpec, final UserV1 user, final ContentSpecParser.ParsingMode mode) throws Exception
	{
		return processContentSpec(contentSpec, user, mode, false);
	}
	
	/**
	 * Process a content specification so that it is parsed, validated and saved.
	 * 
	 * @param contentSpec The Content Specification that is to be processed.
	 * @param user The user who requested the process operation.
	 * @param mode The mode to parse the content specification in.
	 * @param addRevisions If revision numbers should be added to each SpecTopic in the output.
	 * @return True if everything was processed successfully otherwise false.
	 * @throws Exception Any unexpected exception that occurred when processing.
	 */
	@SuppressWarnings({ "unchecked" })
	public boolean processContentSpec(final String contentSpec, final UserV1 user, final ContentSpecParser.ParsingMode mode, final boolean addRevisions) throws Exception
	{
		boolean editing = false;
		
		// Check if the app should be shutdown
		if (isShuttingDown.get())
		{
			shutdown.set(true);
			return false;
		}
		
		LOG.info("Starting to parse...");
		if (mode == ContentSpecParser.ParsingMode.EDITED) editing = true;
		boolean error = !csp.parse(contentSpec, user, mode);
		
		// Check if the app should be shutdown
		if (isShuttingDown.get())
		{
			shutdown.set(true);
			return false;
		}
		
		if (!csp.getReferencedLatestTopicIds().isEmpty())
		{
			// Download the list of topics in one go to reduce I/O overhead
			LOG.info("Attempting to download all the latest topics...");
			reader.getTopicsByIds(csp.getReferencedLatestTopicIds(), csp.getContentSpec().getLocale() != null && !csp.getContentSpec().getLocale().equals(CommonConstants.DEFAULT_LOCALE));
		}
		
		final List<Pair<Integer, Integer>> referencedRevisionTopicIds = csp.getReferencedRevisionTopicIds();
		if (!referencedRevisionTopicIds.isEmpty())
		{
			LOG.info("Attempting to download all the revision topics...");
			
			final int showPercent = 5;
			final float total = referencedRevisionTopicIds.size();
			float current = 0;
			int lastPercent = 0;
			
			for (final Pair<Integer, Integer> topicToRevision : referencedRevisionTopicIds)
			{
				reader.getTopicById(topicToRevision.getFirst(), topicToRevision.getSecond());
				
				++current;
				final int percent = Math.round(current / total * 100);
				if (percent - lastPercent >= showPercent)
				{
					lastPercent = percent;
					log.info("\tDownloading revision topics " + percent + "% Done");
				}
			}
		}
		
		// Check if the app should be shutdown
		if (isShuttingDown.get())
		{
			shutdown.set(true);
			return false;
		}
		
		// Validate the content specification
		LOG.info("Starting to validate...");
		if (csp.getContentSpec().getLocale() == null || csp.getContentSpec().getLocale().equals(CommonConstants.DEFAULT_LOCALE))
		{
			validator = new ContentSpecValidator<TopicV1>(TopicV1.class, elm, dbManager, processingOptions);
		}
		else
		{
			validator = new ContentSpecValidator<TranslatedTopicV1>(TranslatedTopicV1.class, elm, dbManager, processingOptions);
		}
		
		if (error || !validator.validateContentSpec(csp.getContentSpec(), csp.getSpecTopics()) || !validator.validateRelationships(csp.getProcessedRelationships(), csp.getSpecTopics(), csp.getTargetLevels(), csp.getTargetTopics()))
		{
			log.error(ProcessorConstants.ERROR_INVALID_CS_MSG);
			return false;
		}
		else
		{
			log.info(ProcessorConstants.INFO_VALID_CS_MSG);
			
			// If we aren't validating then save the content specification
			if (!processingOptions.isValidating())
			{
				// Check if the app should be shutdown
				if (isShuttingDown.get())
				{
					shutdown.set(true);
					return false;
				}
				
				LOG.info("Saving the Content Specification...");
				if (saveContentSpec(csp.getContentSpec(), csp.getSpecTopics(), editing))
				{
					log.info(ProcessorConstants.INFO_SUCCESSFUL_SAVE_MSG);
				}
				else
				{
					return false;
				}
			}
		}
		return true;
	}
	
	/**
	 * Creates an entity to be sent through the REST interface to create or update a DB entry.
	 * 
	 * @param specTopic The Content Specification Topic to create the topic entity from.
	 * @return True if the topic saved successfully otherwise false.
	 */
	protected TopicV1 createTopicEntity(SpecTopic specTopic)
	{
		if (isShuttingDown.get())
		{
			return null;
		}
		
		try
		{		
			// Create the unique ID for the property
			final BaseRestCollectionV1<PropertyTagV1> properties = new BaseRestCollectionV1<PropertyTagV1>();
			final PropertyTagV1 cspProperty = new PropertyTagV1();
			cspProperty.setValueExplicit(Integer.toString(specTopic.getLineNumber()));
			cspProperty.setAddItem(true);
			cspProperty.setId(CSConstants.CSP_PROPERTY_ID);
			properties.addItem(cspProperty);
			
			TopicV1 topic = null;
			
			// Create a Tag collection that will hold the tags for this topic entity
			BaseRestCollectionV1<TagV1> topicTags = new BaseRestCollectionV1<TagV1>();
			
			if (specTopic.isTopicANewTopic())
			{					
				// Create the topic entity.
				topic = new TopicV1();
				
				// Set the basics
				topic.setTitleExplicit(specTopic.getTitle());
				topic.setDescriptionExplicit(specTopic.getDescription(true));
				topic.setXmlExplicit("");
				
				// Write the type
				final TagV1 type = reader.getTypeByName(specTopic.getType());
				if (type == null)
				{
					log.error(String.format(ProcessorConstants.ERROR_TYPE_NONEXIST_MSG, specTopic.getPreProcessedLineNumber(), specTopic.getText()));
					return null;
				}
				type.setAddItem(true);
				topicTags.addItem(type);
				
				// Add the type to the topic
				type.setAddItem(true);
				topicTags.addItem(type);
			}
			else if (specTopic.isTopicAClonedTopic())
			{
				// Get the existing topic from the database
				int clonedId = Integer.parseInt(specTopic.getId().substring(1));
				final TopicV1 originalTopic = reader.getTopicById(clonedId, null);
				topic = originalTopic.clone(true);
				
				// Set the ID to null so a new ID will be created
				topic.setId(null);
				// Set other items to null that should be recreated
				topic.setCreated(null);
				topic.setLastModified(null);
				// Set-up the configured parameters so that everything gets saved
				topic.setConfiguredParameters(CollectionUtilities.toArrayList(TopicV1.TAGS_NAME, TopicV1.SOURCE_URLS_NAME, TopicV1.INCOMING_NAME,
						TopicV1.OUTGOING_NAME, TopicV1.PROPERTIES_NAME, TopicV1.TITLE_NAME, TopicV1.XML_NAME, TopicV1.DESCRIPTION_NAME, TopicV1.HTML_NAME));
			
				// Go through each collection and set the "addItem" attribute to true
				for (final TopicV1 incomingRelationship: topic.getIncomingRelationships().getItems())
				{
					incomingRelationship.setAddItem(true);
				}
				
				for (final TopicV1 outgoingRelationship: topic.getOutgoingRelationships().getItems())
				{
					outgoingRelationship.setAddItem(true);
				}
				
				for (final TopicSourceUrlV1 sourceUrl: topic.getSourceUrls_OTM().getItems())
				{
					sourceUrl.setAddItem(true);
					sourceUrl.setConfiguredParameters(CollectionUtilities.toArrayList(TopicSourceUrlV1.TITLE_NAME, TopicSourceUrlV1.URL_NAME, TopicSourceUrlV1.DESCRIPTION_NAME));
				}
				
				for (final PropertyTagV1 property: topic.getProperties().getItems())
				{
					// Ignore the CSP Property ID as we will add a new one
					if (!property.getId().equals(CSConstants.CSP_PROPERTY_ID))
					{
						property.setAddItem(true);
						properties.addItem(property);
					}
				}
				
				for (final TagV1 tag: topic.getTags().getItems())
				{
					tag.setAddItem(true);
					topicTags.addItem(tag);
				}
			}
			else if (specTopic.isTopicAnExistingTopic())
			{
				final TopicV1 originalTopic = reader.getTopicById(specTopic.getDBId(), null);
				topic = originalTopic.clone(true);
				
				// Remove any existing property tags
				for (final PropertyTagV1 property: topic.getProperties().getItems())
				{
					// Remove the CSP Property ID as we will add a new one
					if (property.getId().equals(CSConstants.CSP_PROPERTY_ID))
					{
						property.setRemoveItem(true);
						properties.addItem(property);
					}
				}	
			}
			topic.setPropertiesExplicit(properties);
			
			// Check if the app should be shutdown
			if (isShuttingDown.get())
			{
				shutdown.set(true);
				return null;
			}
			
			if (!specTopic.isTopicAnExistingTopic())
			{
				// Set the assigned writer (Tag Table)
				final List<TagV1> assignedWriterTags = reader.getTagsByName(specTopic.getAssignedWriter(true));
				if (assignedWriterTags.size() != 1)
				{
					log.error(String.format(ProcessorConstants.ERROR_WRITER_NONEXIST_MSG, specTopic.getPreProcessedLineNumber(), specTopic.getText()));
					return null;
				}
				final TagV1 writerTag = assignedWriterTags.iterator().next();
				// Save a new assigned writer
				writerTag.setAddItem(true);
				topicTags.addItem(writerTag);
			}
			
			// Check if the app should be shutdown
			if (isShuttingDown.get()) {
				shutdown.set(true);
				return null;
			}
			
			// Get the tags for the topic
			List<String> tagNames = specTopic.getTags(true);
			final List<TagV1> tags = new ArrayList<TagV1>();
			for (String tagName: tagNames) {
				final List<TagV1> tagList = reader.getTagsByName(tagName);
				if (tagList.size() == 1) {
					tags.add(tagList.get(0));
				}
			}
			final Map<CategoryV1, List<TagV1>> mapping = ProcessorUtilities.getCategoryMappingFromTagList(tags);
			
			// Check if the app should be shutdown
			if (isShuttingDown.get())
			{
				shutdown.set(true);
				return null;
			}
			
			// If the topic is a cloned topic then only save new tags/urls
			if (specTopic.isTopicAClonedTopic())
			{
				// Save the new tags
				// Find tags that aren't already in the database and adds them
				final List<TagV1> tttList = topic.getTags().getItems();
				for (final CategoryV1 cat: mapping.keySet())
				{					
					for (final TagV1 tag: mapping.get(cat)) 
					{
						boolean found = false;
						for (final TagV1 ttt: tttList) 
						{
							if (ttt.getId().equals(tag.getId()))
							{
								found = true;
								break;
							}
						}
						
						if (!found)
						{
							tag.setAddItem(true);
							topicTags.addItem(tag);
						}
					}
				}
				
				// Check if the app should be shutdown
				if (isShuttingDown.get()) {
					shutdown.set(true);
					return null;
				}
				
				// Remove the database tags for - tags
				tagNames = specTopic.getRemoveTags(true);
				final List<TagV1> removeTags = new ArrayList<TagV1>();
				for (final String tagName: tagNames)
				{
					final List<TagV1> tagList = reader.getTagsByName(tagName);
					if (tagList.size() == 1) {
						tags.add(tagList.get(0));
					}
				}
				
				for (final TagV1 ttt: tttList)
				{
					boolean found = false;
					for (final TagV1 tag: removeTags)
					{
						if (ttt.getId().equals(tag.getId())) {
							found = true;
						}
					}
					
					if (found)
					{
						// Set the tag to be removed from the database
						ttt.setAddItem(false);
						ttt.setRemoveItem(true);
						topicTags.addItem(ttt);
					}
					
					// Remove the old writer tag as it will get replaced
					if (ttt.isInCategory(CSConstants.WRITER_CATEGORY_ID))
					{
						ttt.setAddItem(false);
						ttt.setRemoveItem(true);
						topicTags.addItem(ttt);
					}
				}
			}
			else if (specTopic.isTopicAnExistingTopic() && specTopic.getRevision() == null)
			{
				// Finds tags that aren't already in the database and adds them
				final List<TagV1> tttList = topic.getTags().getItems();
				for (final CategoryV1 cat: mapping.keySet())
				{					
					for (final TagV1 tag: mapping.get(cat))
					{
						boolean found = false;
						for (final TagV1 ttt: tttList)
						{
							if (ttt.getId().equals(tag.getId()))
							{
								found = true;
								break;
							}
						}
						if (!found)
						{
							tag.setAddItem(true);
							topicTags.addItem(tag);
						}
					}
				}
			}
			else
			{
				// Save the tags
				for (final CategoryV1 cat: mapping.keySet())
				{					
					for (final TagV1 tag: mapping.get(cat))
					{
						tag.setAddItem(true);
						topicTags.addItem(tag);
					}
				}
			}
			
			// Check if the app should be shutdown
			if (isShuttingDown.get())
			{
				shutdown.set(true);
				return null;
			}
			
			if (!specTopic.isTopicAnExistingTopic())
			{
				// Save the new Source Urls
				final List<String> urls = specTopic.getSourceUrls();
				
				final BaseRestCollectionV1<TopicSourceUrlV1> sourceUrls = topic.getSourceUrls_OTM() == null ? 
						new BaseRestCollectionV1<TopicSourceUrlV1>() : topic.getSourceUrls_OTM();

				for (final String url: urls)
				{
					final TopicSourceUrlV1 sourceUrl = new TopicSourceUrlV1();
					sourceUrl.setAddItem(true);
					sourceUrl.setUrlExplicit(url);
					sourceUrls.addItem(sourceUrl);
				}
				
				if (sourceUrls.getItems() != null && !sourceUrls.getItems().isEmpty())
				{
					topic.setSourceUrlsExplicit_OTM(sourceUrls);
				}
			}
			
			// Check if the app should be shutdown
			if (isShuttingDown.get())
			{
				shutdown.set(true);
				return null;
			}
			
			if (topicTags.getItems() != null && !topicTags.getItems().isEmpty())
			{
				topic.setTagsExplicit(topicTags);
			}
			
			return topic;
		}
		catch (Exception e)
		{
			log.debug(e.getMessage());
			log.debug(ExceptionUtilities.getStackTrace(e), 2);
			return null;
		}
	}
	
	/**
	 * Syncs all duplicated topics with their real topic counterpart in the content specification.
	 * 
	 * @param specTopics A HashMap of the all the topics in the Content Specification. The key is the Topics ID.
	 * @return True if the duplicated topics saved successfully otherwise false.
	 */
	protected void syncDuplicatedTopics(final HashMap<String, SpecTopic> specTopics)
	{
		for (final String topicId: specTopics.keySet())
		{
			final SpecTopic topic = specTopics.get(topicId);
			// Sync the normal duplicates first
			if (topic.isTopicADuplicateTopic())
			{
				String id = topic.getId();
				String temp = "N" + id.substring(1);
				SpecTopic cloneTopic = specTopics.get(temp);
				topic.setDBId(cloneTopic.getDBId());
			}
			// Sync the duplicate cloned topics
			else if (topic.isTopicAClonedDuplicateTopic())
			{
				final String id = topic.getId();
				final String idType = id.substring(1);
				SpecTopic cloneTopic = null;
				for (final String key: specTopics.keySet())
				{
					if (key.endsWith(idType) && !key.endsWith(id))
					{
						cloneTopic = specTopics.get(key);
					}
				}
				topic.setDBId(cloneTopic.getDBId());
				
			}
		}
	}
	
	/**
	 * Saves the Content Specification and all of the topics in the content specification
	 * 
	 * @param contentSpec The Content Specification to be saved.
	 * @param specTopics A HashMap of the all the Content Specification Topics that exist in the Content Specification. The key is the Topics ID.
	 * @param edit Whether the content specification is being edited or created.
	 * @return True if the topic saved successfully otherwise false.
	 */
	public boolean saveContentSpec(final ContentSpec contentSpec, final HashMap<String, SpecTopic> specTopics, final boolean edit)
	{
		try
		{
			// Get the full text representation of the processed content spec
			String fullText = "";
			for (final String line: contentSpec.getPreProcessedText())
			{
				fullText += line + "\n";
			}
			
			// A new content specification
			if (contentSpec.getId() == 0)
			{
				contentSpec.setId(writer.createContentSpec(contentSpec.getTitle(), fullText, contentSpec.getDtd(), contentSpec.getCreatedBy()));
				if (contentSpec.getId() == 0)
				{
					log.error(ProcessorConstants.ERROR_DATABASE_ERROR_MSG);
					throw new Exception("Failed to create the pre content specification.");
				}
			}
			// An existing content specification
			else
			{
				if (!writer.updateContentSpec(contentSpec.getId(), contentSpec.getTitle(), fullText, contentSpec.getDtd()))
				{
					log.error(ProcessorConstants.ERROR_DATABASE_ERROR_MSG);
					throw new Exception("Failed to create the pre content specification.");
				}
			}
			
			// Create the new topic entities
			for (final String specTopicId: specTopics.keySet())
			{
				// Check if the app should be shutdown
				if (isShuttingDown.get())
				{
					shutdown.set(true);
					throw new Exception("Shutdown Requested");
				}
				
				// Add topics to the TopicPool that need to be added or updated
				final SpecTopic specTopic = specTopics.get(specTopicId);
				if (specTopic.getId().matches("(" + CSConstants.NEW_TOPIC_ID_REGEX + "|" + CSConstants.CLONED_TOPIC_ID_REGEX + ")"))
				{
					final TopicV1 topic = createTopicEntity(specTopic);
					if (topic == null)
					{
						log.error(ProcessorConstants.ERROR_PROCESSING_ERROR_MSG);
						throw new Exception("Failed to create topic: " + specTopicId);
					}
					topics.addNewTopic(topic);
				}
				else if (specTopic.isTopicAnExistingTopic() && !specTopic.getTags(true).isEmpty() && specTopic.getRevision() == null)
				{
					final TopicV1 topic = createTopicEntity(specTopics.get(specTopicId));
					if (topic == null)
					{
						log.error(ProcessorConstants.ERROR_PROCESSING_ERROR_MSG);
						throw new Exception("Failed to create topic: " + specTopicId);
					}
					topics.addUpdatedTopic(topic);
				}
			}
			
			// Check if the app should be shutdown
			if (isShuttingDown.get())
			{
				shutdown.set(true);
				throw new Exception("Shutdown Requested");
			}
			
			// From here on the main saving happens so this shouldn't be interrupted
			
			// Save the new topic entities
			if (!topics.savePool())
			{
				log.error(ProcessorConstants.ERROR_DATABASE_ERROR_MSG);
				throw new Exception("Failed to save the pool of topics.");
			}
			
			// Initialise the new and cloned topics using the populated topic pool
			for (final String key: specTopics.keySet())
			{
				topics.initialiseFromPool(specTopics.get(key));
			}
			
			// Sync the Duplicated Topics (ID = X<Number>)
			syncDuplicatedTopics(specTopics);
			
			// Create the post processed content spec
			final String postCS = ProcessorUtilities.generatePostContentSpec(contentSpec, specTopics);
			if (postCS == null)
			{
				log.error(ProcessorConstants.ERROR_PROCESSING_ERROR_MSG);
				throw new Exception("Failed to create the Post Content Specification.");
			}
			if (!writer.updatePostContentSpec(contentSpec.getId(), postCS))
			{
				log.error(ProcessorConstants.ERROR_DATABASE_ERROR_MSG);
				throw new Exception("Failed to save the post content Specification");
			}
		}
		catch (Exception e)
		{
			// Clean up the data that was created
			if (contentSpec.getId() != 0 && !edit) writer.deleteContentSpec(contentSpec.getId());
			if (topics.isInitialised()) topics.rollbackPool();
			log.debug(e.getMessage());
			log.debug(ExceptionUtilities.getStackTrace(e), 2);
			return false;
		}
		return true;
	}

	@Override
	public void shutdown()
	{
		isShuttingDown.set(true);
		if (validator != null)
		{
			validator.shutdown();
		}
	}

	@Override
	public boolean isShutdown()
	{
		return shutdown.get();
	}
}
