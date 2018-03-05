/**
 * =============================================================================
 *
 * ORCID (R) Open Source
 * http://orcid.org
 *
 * Copyright (c) 2012-2014 ORCID, Inc.
 * Licensed under an MIT-Style License (MIT)
 * http://orcid.org/open-source-license
 *
 * This copyright and license information (including a link to the full license)
 * shall be included in its entirety in all copies or substantial portion of
 * the software.
 *
 * =============================================================================
 */
package org.orcid.listener.s3;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Resource;
import javax.xml.bind.JAXBException;

import org.orcid.jaxb.model.error_v2.OrcidError;
import org.orcid.jaxb.model.record.summary_v2.ActivitiesSummary;
import org.orcid.jaxb.model.record.summary_v2.EducationSummary;
import org.orcid.jaxb.model.record.summary_v2.EmploymentSummary;
import org.orcid.jaxb.model.record.summary_v2.FundingGroup;
import org.orcid.jaxb.model.record.summary_v2.FundingSummary;
import org.orcid.jaxb.model.record.summary_v2.PeerReviewGroup;
import org.orcid.jaxb.model.record.summary_v2.PeerReviewSummary;
import org.orcid.jaxb.model.record.summary_v2.WorkGroup;
import org.orcid.jaxb.model.record.summary_v2.WorkSummary;
import org.orcid.jaxb.model.record_v2.Activity;
import org.orcid.jaxb.model.record_v2.Affiliation;
import org.orcid.jaxb.model.record_v2.AffiliationType;
import org.orcid.jaxb.model.record_v2.Record;
import org.orcid.listener.exception.DeprecatedRecordException;
import org.orcid.listener.exception.LockedRecordException;
import org.orcid.listener.orcid.Orcid12APIClient;
import org.orcid.listener.orcid.Orcid20APIClient;
import org.orcid.listener.persistence.managers.RecordStatusManager;
import org.orcid.listener.persistence.util.ActivityType;
import org.orcid.listener.persistence.util.AvailableBroker;
import org.orcid.utils.DateUtils;
import org.orcid.utils.listener.BaseMessage;
import org.orcid.utils.listener.LastModifiedMessage;
import org.orcid.utils.listener.RetryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Core logic for listeners
 * 
 * @author tom
 *
 */
@Component
public class S3MessageProcessor implements Consumer<LastModifiedMessage> {

    public static final String VND_ORCID_XML = "application/vnd.orcid+xml";
    public static final String VND_ORCID_JSON = "application/vnd.orcid+json";
    
    Logger LOG = LoggerFactory.getLogger(S3MessageProcessor.class);

    @Value("${org.orcid.message-listener.api12Enabled:true}")
    private boolean is12IndexingEnabled;

    @Value("${org.orcid.message-listener.api20Enabled:true}")
    private boolean is20IndexingEnabled;

    @Value("${org.orcid.message-listener.api20ActivitiesEnabled:true}")
    private boolean is20ActivitiesIndexingEnabled;

    @Value("${org.orcid.message-listener.index.summaries:false}")
    private boolean summaryIndexerEnabled;
    
    @Value("${org.orcid.message-listener.index.activities:false}")
    private boolean activitiesIndexerEnabled;
    
    @Resource
    private Orcid12APIClient orcid12ApiClient;
    @Resource
    private Orcid20APIClient orcid20ApiClient;
    @Resource
    private S3Manager s3Manager;
    @Resource
    private ExceptionHandler exceptionHandler;
    @Resource
    private RecordStatusManager recordStatusManager;    

    /**
     * Populates the Amazon S3 buckets
     */
    public void accept(LastModifiedMessage m) {
        String orcid = m.getOrcid();
        update_1_2_API(orcid);
        update_2_0_API(m);        
    }

    public void accept(RetryMessage m) {
        String orcid = m.getOrcid();
        AvailableBroker destinationBroker = AvailableBroker.fromValue(m.getMap().get(RetryMessage.BROKER_NAME));
        if (AvailableBroker.DUMP_STATUS_1_2_API.equals(destinationBroker)) {
            update_1_2_API(orcid);
        } else if (AvailableBroker.DUMP_STATUS_2_0_API.equals(destinationBroker)) {
            update_2_0_API(m);
        } 
    }

    private void update_1_2_API(String orcid) {
        if (is12IndexingEnabled) {
            try {
                LOG.info("Processing XML for record " + orcid);
                boolean xmlUpdated = update_1_2_API_XML(orcid);
                LOG.info("XML for record " + orcid + " has been processed");
                
                LOG.info("Processing JSON for record " + orcid);
                boolean jsonUpdated = update_1_2_API_JSON(orcid);
                LOG.info("JSON for record " + orcid + " has been processed");
                if(xmlUpdated && jsonUpdated) {
                    recordStatusManager.markAsSent(orcid, AvailableBroker.DUMP_STATUS_1_2_API);                
                } else {
                    recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
                }            
            } catch (LockedRecordException | DeprecatedRecordException e) {
                try {
                    if (e instanceof LockedRecordException) {
                        LOG.error("Record " + orcid + " is locked");
                        exceptionHandler.handle12LockedRecordException(orcid, ((LockedRecordException) e).getOrcidMessage());
                    } else {
                        LOG.error("Record " + orcid + " is deprecated");
                        exceptionHandler.handle12DeprecatedRecordException(orcid, ((DeprecatedRecordException) e).getOrcidDeprecated());
                    }
                    recordStatusManager.markAsSent(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
                } catch (Exception e1) {
                    LOG.error("Unable to handle LockedRecordException for record " + orcid, e1);
                    recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
                }
            } catch (AmazonClientException e) {
                LOG.error("Unable to fetch record " + orcid + " for 1.2 API: " + e.getMessage(), e);
                recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
            } catch (Exception e) {
                // something else went wrong fetching record from ORCID and
                // threw a
                // runtime exception
                LOG.error("Unable to fetch record " + orcid + " for 1.2 API: " + e.getMessage(), e);
                recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
            }
        }
    }
    
    private boolean update_1_2_API_XML(String orcid) throws LockedRecordException, DeprecatedRecordException, IOException {
        byte [] data = orcid12ApiClient.fetchPublicProfile(orcid, VND_ORCID_XML);
        if(data != null) {
            s3Manager.updateS3(orcid, data, VND_ORCID_XML);
            return true;
        }
        return false;
    }

    private boolean update_1_2_API_JSON(String orcid) throws LockedRecordException, DeprecatedRecordException, IOException {
        byte [] data = orcid12ApiClient.fetchPublicProfile(orcid, VND_ORCID_JSON);
        if(data != null) {
            s3Manager.updateS3(orcid, data, VND_ORCID_JSON);    
            return true;
        }
        return false;
    }
    
    private void update_2_0_API(BaseMessage message) {
        String orcid = message.getOrcid();
        if (is20IndexingEnabled) {
            // Update API 2.0
            try {
                Record record = orcid20ApiClient.fetchPublicRecord(message);
                if (record != null) {
                    s3Manager.updateS3(orcid, record);
                    recordStatusManager.markAsSent(orcid, AvailableBroker.DUMP_STATUS_2_0_API);
                }
            } catch (LockedRecordException | DeprecatedRecordException e) {
                try {
                    OrcidError error = null;
                    if (e instanceof LockedRecordException) {
                        LOG.error("Record " + orcid + " is locked");
                        error = ((LockedRecordException) e).getOrcidError();
                    } else {
                        LOG.error("Record " + orcid + " is deprecated");
                        error = ((DeprecatedRecordException) e).getOrcidError();
                    }
                    exceptionHandler.handle20Exception(orcid, error);
                    recordStatusManager.markAsSent(orcid, AvailableBroker.DUMP_STATUS_2_0_API);
                } catch (Exception e1) {
                    LOG.error("Unable to handle LockedRecordException for record " + orcid, e1);
                    recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_2_0_API);
                }
            } catch (AmazonClientException e) {
                LOG.error("Unable to fetch record " + orcid + " for 2.0 API: " + e.getMessage(), e);
                recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_1_2_API);
            } catch (Exception e) {
                // something else went wrong fetching record from ORCID and
                // threw a
                // runtime exception
                LOG.error("Unable to fetch record " + orcid + " for 2.0 API: " + e.getMessage(), e);
                recordStatusManager.markAsFailed(orcid, AvailableBroker.DUMP_STATUS_2_0_API);
            }
        }
    }
    
	private void update_2_0_activities(BaseMessage message) throws JsonProcessingException, JAXBException {
		String orcid = message.getOrcid();
		if (activitiesIndexerEnabled) {
			ActivitiesSummary as = null;

			try {
				as = orcid20ApiClient.fetchPublicActivitiesSummary(message);
			} catch (LockedRecordException | DeprecatedRecordException e) {				
				// Remove all activities from this record
				s3Manager.clearActivities(orcid);
				
				// Mark brokers as ok
				//TODO
			}

			if (as != null) {
				Map<ActivityType, Map<String, S3ObjectSummary>> existingActivities = s3Manager.searchActivities(orcid);

				// TODO: HANDLE EXCEPTIONS!!!!!
				if (as.getEducations() != null && !as.getEducations().getSummaries().isEmpty()) {
					Map<String, S3ObjectSummary> existingEducations = existingActivities.get(ActivityType.EDUCATIONS);
					processActivities(orcid, as.getEducations().getSummaries(), existingEducations,
							ActivityType.EDUCATIONS);
				} else {
					// TODO: Clear all activities from that record
				}

				if (as.getEmployments() != null && !as.getEmployments().getSummaries().isEmpty()) {
					for (EmploymentSummary x : as.getEmployments().getSummaries()) {

					}
				}

				if (as.getFundings() != null && !as.getFundings().getFundingGroup().isEmpty()) {
					for (FundingGroup g : as.getFundings().getFundingGroup()) {
						for (FundingSummary x : g.getFundingSummary()) {

						}
					}
				}

				if (as.getWorks() != null && !as.getWorks().getWorkGroup().isEmpty()) {
					for (WorkGroup g : as.getWorks().getWorkGroup()) {
						for (WorkSummary x : g.getWorkSummary()) {

						}
					}
				}

				if (as.getPeerReviews() != null && !as.getPeerReviews().getPeerReviewGroup().isEmpty()) {
					for (PeerReviewGroup g : as.getPeerReviews().getPeerReviewGroup()) {
						for (PeerReviewSummary x : g.getPeerReviewSummary()) {

						}
					}
				}
			}
		}
	}
    
    private void update_2_0_Summary(BaseMessage message) {
        String orcid = message.getOrcid();
        if(summaryIndexerEnabled) {
        	
        }        
    }
    
    private void processActivities(String orcid, List<? extends Activity> activities, Map<String, S3ObjectSummary> existingElements, ActivityType type) throws JsonProcessingException, JAXBException {
    	for (Activity x : activities) {
			String putCodeString = String.valueOf(x.getPutCode());
			Activity activity = null;
			if(existingElements.containsKey(putCodeString)) {
				S3ObjectSummary existingObject = existingElements.get(putCodeString);
				Date elementLastModified = DateUtils.convertToDate(x.getLastModifiedDate().getValue());
				Date s3LastModified = existingObject.getLastModified();
				if(elementLastModified.after(s3LastModified)) {
					activity = fetchActivity(orcid, x.getPutCode(), type);
				}				
			} else {
				activity = fetchActivity(orcid, x.getPutCode(), type);
			}
			
			if(activity != null) {
				// Upload it to S3
				s3Manager.uploadActivity(orcid, putCodeString, activity);
				// Remove it from the existingElements list means that the elements was already processed 
				existingElements.remove(putCodeString);				
			}
		}
		//Remove from S3 all element that still exists on the existingEducations map
		for(String putCode : existingElements.keySet()) {
			s3Manager.removeActivity(orcid, putCode, type);
		}
    }
    
    private Activity fetchActivity(String orcid, Long putCode, ActivityType type) {
    	switch (type) {
    	case EDUCATIONS:
    		return orcid20ApiClient.fetchAffiliation(orcid, putCode, AffiliationType.EDUCATION);    		
    	case EMPLOYMENTS:
    		return orcid20ApiClient.fetchAffiliation(orcid, putCode, AffiliationType.EMPLOYMENT);
    	case FUNDINGS:
    		return orcid20ApiClient.fetchFunding(orcid, putCode);
    	case PEER_REVIEWS:
    		return orcid20ApiClient.fetchPeerReview(orcid, putCode);
    	case WORKS:
    		return orcid20ApiClient.fetchWork(orcid, putCode);    		
    	default: 
    		throw new IllegalArgumentException("Invalid type! Imposible: " + type);
    	}     	
    }
}
