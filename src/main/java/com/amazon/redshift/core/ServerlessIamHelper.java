package com.amazon.redshift.core;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.amazon.redshift.core.IamHelper.CredentialProviderType;
import com.amazon.redshift.jdbc.ResourceLock;
import com.amazon.redshift.logger.RedshiftLogger;
import com.amazon.redshift.plugin.utils.RequestUtils;
import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.redshiftserverless.AWSRedshiftServerlessClient;
import com.amazonaws.services.redshiftserverless.AWSRedshiftServerlessClientBuilder;
import com.amazonaws.services.redshiftserverless.model.GetWorkgroupRequest;
import com.amazonaws.services.redshiftserverless.model.GetWorkgroupResult;
import com.amazonaws.services.redshiftserverless.model.Endpoint;
import com.amazonaws.services.redshiftserverless.model.GetCredentialsRequest;
import com.amazonaws.services.redshiftserverless.model.GetCredentialsResult;

// In Serverless there is no V2 API.
// If user specify group_federation with serverless,
// it will call Provision V2 API.
public final class ServerlessIamHelper {
	private ResourceLock lock = new ResourceLock();
	private RedshiftLogger log;
	private AWSRedshiftServerlessClient client;
	
	private static Map<String, GetCredentialsResult> credentialsCache = new HashMap<String, GetCredentialsResult>();
	
	ServerlessIamHelper(RedshiftJDBCSettings settings,
											RedshiftLogger log,
											AWSCredentialsProvider credProvider) {
		
		this.log = log;
		AWSRedshiftServerlessClientBuilder builder = AWSRedshiftServerlessClientBuilder.standard();
		
		builder = (AWSRedshiftServerlessClientBuilder) IamHelper.setBuilderConfiguration(settings, log, builder);		
		
		client = (AWSRedshiftServerlessClient) builder.withCredentials(credProvider).build();
	}
	
	void describeConfiguration(RedshiftJDBCSettings settings) {
	  com.amazonaws.services.redshiftserverless.model.GetWorkgroupRequest req = new GetWorkgroupRequest();
	  try (ResourceLock ignore = lock.obtain()) {  
      if(settings.m_workGroup != null && settings.m_workGroup.length() > 0) {
        // Set workgroup in the request
        req.setWorkgroupName(settings.m_workGroup);
      }
      else
      {
        throw new AmazonClientException("Serverless workgroup is not set.");
      }
	  
	  com.amazonaws.services.redshiftserverless.model.GetWorkgroupResult   resp = client.getWorkgroup(req);
		
	  Endpoint endpoint = resp.getWorkgroup().getEndpoint();	
		
      if (null == endpoint)
      {
          throw new AmazonClientException("Serverless endpoint is not available yet.");
      }
		
      settings.m_host = endpoint.getAddress();
      settings.m_port = endpoint.getPort();
	  }
	}
	
	void getCredentialsResult(RedshiftJDBCSettings settings,
									CredentialProviderType providerType,
									boolean idpCredentialsRefresh
			) throws AmazonClientException {
	try (ResourceLock ignore = lock.obtain()) {
    String key = null;
    GetCredentialsResult credentials = null;
    		
    if(!settings.m_iamDisableCache) {
      key = IamHelper.getCredentialsCacheKey(settings, providerType, true);
      credentials = credentialsCache.get(key);
    }

    if (credentials == null
    			|| (providerType == CredentialProviderType.PLUGIN
    						&& idpCredentialsRefresh)
    			|| RequestUtils.isCredentialExpired(credentials.getExpiration()))
    {
        if (RedshiftLogger.isEnable())
          log.logInfo("GetCredentials NOT from cache");
    	
        if(!settings.m_iamDisableCache)	          
        	credentialsCache.remove(key);
        
        GetCredentialsRequest request = new GetCredentialsRequest();
        if (settings.m_iamDuration > 0)
        {
            request.setDurationSeconds(settings.m_iamDuration);
        }

        request.setDbName(settings.m_Schema);
        if(settings.m_workGroup != null && settings.m_workGroup.length() > 0) {
          // Set workgroup in the request
          request.setWorkgroupName(settings.m_workGroup);
        }
        else
        {
            if(settings.m_isCname)
            {
                request.setCustomDomainName(settings.m_host);
            }
        }

        if (RedshiftLogger.isEnable()) {
            log.logInfo(request.toString());
        }

        for (int i = 0; i < IamHelper.MAX_AMAZONCLIENT_RETRY; ++i)
        {
            try
            {
                credentials = client.getCredentials(request);
                break;
            } 
            catch (AmazonClientException ace)
            {
    					IamHelper.checkForApiCallRateExceedError(ace, i, "getCredentialsResult", log);
            }
        }

        if(!settings.m_iamDisableCache)
        	credentialsCache.put(key, credentials);
    }
    else {
      if (RedshiftLogger.isEnable())
        log.logInfo("GetCredentials from cache");
    }
    
    settings.m_username = credentials.getDbUser();
    settings.m_password = credentials.getDbPassword();
    
    if(RedshiftLogger.isEnable()) {
        Date now = new Date();
        log.logInfo(now + ": Using GetCredentialsResult with expiration " + credentials.getExpiration());
        log.logInfo(now + ": Using GetCredentialsResultV2 with TimeToRefresh " + credentials.getNextRefreshTime());
    }
	}
  }
}
