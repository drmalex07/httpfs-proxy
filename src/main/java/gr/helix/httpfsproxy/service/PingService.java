package gr.helix.httpfsproxy.service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import gr.helix.httpfsproxy.config.HttpFsServiceConfiguration;
import gr.helix.httpfsproxy.model.backend.ServiceStatus;
import gr.helix.httpfsproxy.model.backend.ServiceStatusInfo;
import gr.helix.httpfsproxy.model.backend.VoidRequestParameters;
import gr.helix.httpfsproxy.model.backend.ops.GetHomeDirectoryResponse;

@Service
@Profile({"production", "development"})
public class PingService
{
    private final static Logger logger = LoggerFactory.getLogger(PingService.class);
    
    @Autowired
    HttpFsServiceConfiguration backend;
    
    @Autowired
    @Qualifier("httpClient")
    private CloseableHttpClient httpClient;
  
    @Autowired
    @Qualifier("getHomeDirectoryTemplate")
    private OperationTemplate<VoidRequestParameters, GetHomeDirectoryResponse> getHomeDirectoryTemplate;
    
    private Map<URI, ServiceStatusInfo> statusReport = new ConcurrentHashMap<>();
        
    void pingBackendService(URI baseUri) 
        throws HttpResponseException, IOException
    {        
        final String userName = backend.getDefaultUser();
        final HttpUriRequest request = getHomeDirectoryTemplate.requestForPath(userName, "/");
        
        final String expectedHomeDirectory = String.format("/user/%s", userName);
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            final StatusLine responseStatus = response.getStatusLine();
            if (responseStatus.getStatusCode() != HttpStatus.SC_OK)
                throw new HttpResponseException(responseStatus.getStatusCode(), responseStatus.getReasonPhrase());
            final GetHomeDirectoryResponse r = 
                getHomeDirectoryTemplate.responseFromHttpEntity(response.getEntity());
            if (!expectedHomeDirectory.equals(r.getPath()))
                throw new IllegalStateException("Expected key [Path] to hold the user\'s home directory!");
        }   
        return;
    }
    
    /**
     * Ping backend HttpFs services and report their status
     */
    @Scheduled(fixedRate = 5000L)
    public void pingBackend()
    {
        for (URI baseUri: backend.getBaseUris()) {
            final Instant t0 = Instant.now();
            ServiceStatusInfo statusInfo = null;
            try {
                pingBackendService(baseUri);
            } catch (HttpResponseException ex) {
                statusInfo = ServiceStatusInfo.of(baseUri, ServiceStatus.FAILED, t0, 
                    String.format("The request has failed: %s", ex.getMessage()));
            } catch (IllegalStateException ex) {
                statusInfo = ServiceStatusInfo.of(baseUri, ServiceStatus.FAILED, t0, ex.getMessage());
            } catch (IOException ex) {
                statusInfo = ServiceStatusInfo.of(baseUri, ServiceStatus.DOWN, t0, 
                    String.format("Encountered an I/O exception: %s", ex.getMessage()));
            }
            final Instant t1 = Instant.now();
            if (statusInfo == null) {
                statusInfo = ServiceStatusInfo.of(baseUri, ServiceStatus.OK, t0);
            }
            if (!statusInfo.getStatus().isSuccessful()) {
                logger.error("Reporting status for {}: {} - {}", 
                    baseUri, statusInfo.getStatus(), statusInfo.getErrorMessage());
            } else {
                logger.debug("Reporting status for {}: OK ({}ms)", baseUri, 
                    t1.toEpochMilli() - t0.toEpochMilli());
            }
            // Store status to our internal report
            this.statusReport.put(baseUri, statusInfo);
        }
    }
    
    public Map<URI, ServiceStatusInfo> getReport()
    {
        return Collections.unmodifiableMap(this.statusReport);
    }
}
