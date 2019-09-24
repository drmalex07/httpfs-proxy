package gr.helix.httpfsproxy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;

import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import gr.helix.httpfsproxy.model.RestResponse;
import gr.helix.httpfsproxy.model.SimpleUserDetails;
import gr.helix.httpfsproxy.model.controller.ContentSummaryResult;
import gr.helix.httpfsproxy.model.controller.FileChecksumResult;
import gr.helix.httpfsproxy.model.controller.FileStatusResult;
import gr.helix.httpfsproxy.model.controller.FilePathResult;
import gr.helix.httpfsproxy.model.controller.ListStatusResult;
import gr.helix.httpfsproxy.model.ops.BooleanResponse;
import gr.helix.httpfsproxy.model.ops.ContentSummary;
import gr.helix.httpfsproxy.model.ops.ContentSummaryResponse;
import gr.helix.httpfsproxy.model.ops.EnumOperation;
import gr.helix.httpfsproxy.model.ops.FileChecksum;
import gr.helix.httpfsproxy.model.ops.FileStatus;
import gr.helix.httpfsproxy.model.ops.GetFileChecksumResponse;
import gr.helix.httpfsproxy.model.ops.GetFileStatusResponse;
import gr.helix.httpfsproxy.model.ops.GetHomeDirectoryResponse;
import gr.helix.httpfsproxy.model.ops.ListStatusResponse;
import gr.helix.httpfsproxy.model.ops.MakeDirectoryRequestParameters;
import gr.helix.httpfsproxy.model.ops.ReadFileRequestParameters;
import gr.helix.httpfsproxy.model.ops.VoidRequestParameters;
import gr.helix.httpfsproxy.service.OperationTemplate;
import lombok.NonNull;


@Controller
@Validated
public class FilesController
{
    private final static Logger logger = LoggerFactory.getLogger(FilesController.class);
    
    @Autowired
    @Qualifier("httpClient")
    CloseableHttpClient httpClient;
    
    @Autowired
    @Qualifier("getHomeDirectoryTemplate")
    private OperationTemplate<VoidRequestParameters, GetHomeDirectoryResponse> getHomeDirectoryTemplate;
    
    @Autowired
    @Qualifier("listStatusTemplate")
    private OperationTemplate<VoidRequestParameters, ListStatusResponse > listStatusTemplate;
    
    @Autowired
    @Qualifier("getFileStatusTemplate")
    private OperationTemplate<VoidRequestParameters, GetFileStatusResponse> getFileStatusTemplate;
    
    @Autowired
    @Qualifier("getContentSummaryTemplate")
    private OperationTemplate<VoidRequestParameters, ContentSummaryResponse> getContentSummaryTemplate;
    
    @Autowired
    @Qualifier("getFileChecksumTemplate")
    private OperationTemplate<VoidRequestParameters, GetFileChecksumResponse> getFileChecksumTemplate;
    
    @Autowired
    @Qualifier("readFileTemplate")
    private OperationTemplate<ReadFileRequestParameters, ?> readFileTemplate;
    
    @Autowired
    @Qualifier("makeDirectoryTemplate")
    private OperationTemplate<MakeDirectoryRequestParameters, BooleanResponse> makeDirectoryTemplate;
    
    @ModelAttribute("userDetails")
    SimpleUserDetails userDetails(Authentication authentication)
    {
        return Optional.ofNullable(authentication)
            .map(Authentication::getPrincipal)
            .filter(SimpleUserDetails.class::isInstance)
            .map(SimpleUserDetails.class::cast)
            .orElse(null);
    }
    
    IllegalStateException wrapFailureAsException(
        EnumOperation operation, HttpUriRequest request, org.apache.http.StatusLine statusLine)
    {
        String errMessage = String.format(
            "The backend server failed on an operation of [%s]: %s", operation.name(), statusLine);
        return new IllegalStateException(errMessage);
    }
    
    @ExceptionHandler({ValidationException.class})
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public RestResponse<?> handleException(ValidationException ex)
    {
        return RestResponse.error("constraint validation has failed: " + ex.getMessage());
    }
    
    @ExceptionHandler({IllegalArgumentException.class})
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public RestResponse<?> handleException(IllegalArgumentException ex)
    {
        return RestResponse.error("got an invalid argument: " + ex.getMessage());
    }
    
    @ExceptionHandler({FileNotFoundException.class})
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public RestResponse<?> handleException(FileNotFoundException ex)
    {
        return RestResponse.error("file does not exist: " + ex.getMessage());
    }
    
    @ExceptionHandler({IllegalStateException.class})
    @ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public RestResponse<?> handleException(IllegalStateException ex)
    {
        return RestResponse.error("server encountered an unexpected state: " + ex.getMessage());
    }
    
    @ExceptionHandler({IOException.class})
    @ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public RestResponse<?> handleException(IOException ex)
    {
        return RestResponse.error("server encountered an i/o error: " + ex.getMessage());
    }

    @GetMapping(path = "/files/home-directory", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RestResponse<?> getHomeDirectory(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails)
            throws Exception
    {
        final HttpUriRequest request1 = getHomeDirectoryTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), "/");
        logger.debug("getHomeDirectory: {}", request1);
        
        FilePathResult result = null;
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
            if (!status.equals(HttpStatus.OK)) {
                throw wrapFailureAsException(getHomeDirectoryTemplate.operation(), request1, statusLine);
            }
            Assert.state(response1.getEntity() != null, "expected a response HTTP entity!");
            GetHomeDirectoryResponse r1 = getHomeDirectoryTemplate.responseFromHttpEntity(response1.getEntity());
            result = FilePathResult.of(r1.getPath());
        }
        
        return RestResponse.result(result);
    }
    
    @GetMapping(path = "/files/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RestResponse<?> getStatus(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath)
            throws Exception  
    {
        final HttpUriRequest request1 = getFileStatusTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath);
        logger.debug("getStatus: {}", request1);
        
        FileStatusResult result = null;
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
            if (status.equals(HttpStatus.NOT_FOUND)) {
                throw new FileNotFoundException(filePath);
            } else if (!status.equals(HttpStatus.OK)) {
                throw wrapFailureAsException(getFileStatusTemplate.operation(), request1, statusLine);
            }
            Assert.state(response1.getEntity() != null, "expected a response HTTP entity!");
            GetFileStatusResponse r1 = getFileStatusTemplate.responseFromHttpEntity(response1.getEntity());
            result = FileStatusResult.of(r1.getFileStatus());
        }
        
        return RestResponse.result(result);
    }
    
    @GetMapping(path = "/files/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RestResponse<?> getSummary(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath)
            throws IOException  
    {
        final HttpUriRequest request1 = getContentSummaryTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath);
        logger.debug("getSummary: {}", request1);
        
        ContentSummaryResult result = null;
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
            if (status.equals(HttpStatus.NOT_FOUND)) {
                throw new FileNotFoundException(filePath);
            } else if (!status.equals(HttpStatus.OK)) {
                throw wrapFailureAsException(getContentSummaryTemplate.operation(), request1, statusLine);
            }
            Assert.state(response1.getEntity() != null, "expected a response HTTP entity!");
            ContentSummaryResponse r1 = getContentSummaryTemplate.responseFromHttpEntity(response1.getEntity());
            result = ContentSummaryResult.of(r1.getSummary());
        }
        
        return RestResponse.result(result);
    }
    
    @GetMapping(path = "/files/file-checksum", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RestResponse<?> getFileChecksum(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath)
            throws Exception  
    {
        final HttpUriRequest request1 = getFileChecksumTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath);
        logger.debug("getFileChecksum: {}", request1);
        
        FileChecksumResult result = null;
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
            if (status.equals(HttpStatus.NOT_FOUND)) {
                throw new FileNotFoundException(filePath);
            } else if (!status.equals(HttpStatus.OK)) {
                throw wrapFailureAsException(getFileChecksumTemplate.operation(), request1, statusLine);
            }
            Assert.state(response1.getEntity() != null, "expected a response HTTP entity!");
            GetFileChecksumResponse r1 = getFileChecksumTemplate.responseFromHttpEntity(response1.getEntity());
            result = FileChecksumResult.of(r1.getChecksum()); 
        }
        
        return RestResponse.result(result);
    }
    
    @GetMapping(path = "/files/list-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RestResponse<?> listStatus(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath)
            throws Exception
    {
        final HttpUriRequest request1 = listStatusTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath);
        logger.debug("listStatus: {}", request1);
        
        ListStatusResult result = null; 
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
            if (status.equals(HttpStatus.NOT_FOUND)) {
                throw new FileNotFoundException(filePath);
            } else if (!status.equals(HttpStatus.OK)) {
                throw wrapFailureAsException(listStatusTemplate.operation(), request1, statusLine);
            }
            Assert.state(response1.getEntity() != null, "expected a response HTTP entity!");
            ListStatusResponse r1 = listStatusTemplate.responseFromHttpEntity(response1.getEntity());
            result = ListStatusResult.of(r1.getStatusList());
        }
        
        return RestResponse.result(result);
    }
    
    private static final int READ_REQUEST_BUFFER_SIZE = 2 * 4096;
    
    @GetMapping(path = "/files/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> download(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath,
        @RequestParam(name = "length", required = false) @Min(0) Long length,
        @RequestParam(name = "offset", required = false) @Min(0) Long offset) 
            throws Exception
    {
        final ReadFileRequestParameters parameters = 
            new ReadFileRequestParameters(length, offset, READ_REQUEST_BUFFER_SIZE);
        final HttpUriRequest request1 = readFileTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath, parameters);
        logger.debug("download: {}", request1);
        
        final CloseableHttpResponse response1 = httpClient.execute(request1);
        final org.apache.http.StatusLine statusLine = response1.getStatusLine();
        final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
        
        if (!status.equals(HttpStatus.OK)) {
            // The request has failed: examine status and throw a proper exception
            Exception ex = null;
            if (status.equals(HttpStatus.NOT_FOUND)) {
                ex = new FileNotFoundException(filePath);
            } else {
                ex = wrapFailureAsException(readFileTemplate.operation(), request1, statusLine);
            }
            response1.close();
            throw ex;
        }
        
        // The request was successful: Stream data from backend
        
        final StreamingResponseBody body = new StreamingResponseBody()
        {
            @Override
            public void writeTo(OutputStream outputStream) throws IOException
            {                
                logger.debug("Copying data from {}", filePath);
                long nbytes = -1L;
                try {
                    final org.apache.http.HttpEntity e = response1.getEntity();
                    Assert.state(e != null, "expected a response HTTP entity!");
                    nbytes = IOUtils.copyLarge(e.getContent(), outputStream);
                } finally {
                    response1.close();
                }
                logger.debug("Copied {} bytes from {}", nbytes, filePath);
            }
        };
        
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(body);
    }
    
    @PostMapping(path = "/files/directory", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> makeDirectory(
        @NonNull Authentication authn, 
        @ModelAttribute("userDetails") @NotNull SimpleUserDetails userDetails,
        @RequestParam("path") String filePath, 
        @RequestParam(name  = "permission", defaultValue = "775") String permission)
            throws Exception
    {
        final MakeDirectoryRequestParameters parameters = MakeDirectoryRequestParameters.of(permission);
        
        final HttpUriRequest request1 = makeDirectoryTemplate
            .requestForPath(userDetails.getUsernameForHdfs(), filePath, parameters);
        
        try (CloseableHttpResponse response1 = httpClient.execute(request1)) {
            final org.apache.http.StatusLine statusLine = response1.getStatusLine();
            final HttpStatus status = HttpStatus.valueOf(statusLine.getStatusCode());
        }
        
        // Todo makeDirectory
        return null;
    }
}
