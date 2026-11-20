package com.sf.release.infra.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CODING endpoint templates, loaded from {@code coding-endpoints.yml} under the
 * {@code coding} prefix (imported by {@code application.yml}; the resolver and adapter
 * consume them).
 *
 * <p>Paths, payload mappings and status vocabularies are configuration, not code, so an
 * inaccurate external API document only requires a YAML change. The class defaults to
 * empty maps; without the file the engine has no endpoint and the resolver reports
 * the missing action.
 */
@Component
@ConfigurationProperties(prefix = "coding")
public class CodingProperties {

    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private Map<String, List<String>> statusMapping = new LinkedHashMap<>();

    public Map<String, Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }

    public Map<String, List<String>> getStatusMapping() {
        return statusMapping;
    }

    public void setStatusMapping(Map<String, List<String>> statusMapping) {
        this.statusMapping = statusMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(statusMapping);
    }

    /** One endpoint template: HTTP method, URL pattern and optional JSONPath mappings. */
    public static class Endpoint {

        private String method = "GET";
        private String url;
        private String body;
        private String bodyWithoutCommit;
        private Map<String, String> headers = new LinkedHashMap<>();
        private String responseId;
        private String responseStatus;
        private String responseDigest;
        private String responseUrl;
        private String responseCommit;

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        /** Optional request body template; {@code {var}} placeholders are JSON-escaped. */
        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        /**
         * Optional request body used instead of {@link #body} when the action has no known
         * commit (a build trigger whose pipeline has not learned a commit yet). It must omit
         * the commit field; the engine then builds the branch head. When {@code null}, the
         * regular body is used.
         */
        public String getBodyWithoutCommit() {
            return bodyWithoutCommit;
        }

        public void setBodyWithoutCommit(String bodyWithoutCommit) {
            this.bodyWithoutCommit = bodyWithoutCommit;
        }

        /** Extra request headers, also rendered from {@code {var}} placeholders. */
        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        }

        public String getResponseId() {
            return responseId;
        }

        public void setResponseId(String responseId) {
            this.responseId = responseId;
        }

        public String getResponseStatus() {
            return responseStatus;
        }

        public void setResponseStatus(String responseStatus) {
            this.responseStatus = responseStatus;
        }

        public String getResponseDigest() {
            return responseDigest;
        }

        public void setResponseDigest(String responseDigest) {
            this.responseDigest = responseDigest;
        }

        /** Optional JSONPath of a browser-visible job URL returned by a trigger call. */
        public String getResponseUrl() {
            return responseUrl;
        }

        public void setResponseUrl(String responseUrl) {
            this.responseUrl = responseUrl;
        }

        /**
         * Optional JSONPath of the commit the triggered build actually uses. A trigger response
         * without it yields a {@code null} {@code TriggerResult.commitSha}; the platform then
         * keeps {@code pipeline_instance.commit_sha} null instead of inventing a value.
         */
        public String getResponseCommit() {
            return responseCommit;
        }

        public void setResponseCommit(String responseCommit) {
            this.responseCommit = responseCommit;
        }
    }
}
