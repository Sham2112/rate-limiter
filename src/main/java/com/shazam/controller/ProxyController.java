package com.shazam.controller;

import com.shazam.model.Route;
import com.shazam.model.RouteConfigs;
import com.shazam.utils.NetworkUtils;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class ProxyController {
    
    @Autowired
    private RouteConfigs routeConfigs;

    private final java.util.Map<String, RestClient> clientsByUpstream = new java.util.concurrent.ConcurrentHashMap<>();

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> relayRequest(
            HttpMethod method,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] body,
            @RequestParam MultiValueMap<String, String> queryParams,
            jakarta.servlet.http.HttpServletRequest request) {

        Route route = NetworkUtils.selectRoute(request, routeConfigs);
        if (route == null){
            return ResponseEntity.notFound().build();
        }
        RestClient restClient = clientFor(route.getUpstream());
        String path = request.getRequestURI();

        return restClient.method(method)
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParams(queryParams)
                        .build())
                .headers(httpHeaders -> {
                    httpHeaders.addAll(headers);
                    httpHeaders.remove(HttpHeaders.HOST);
                    httpHeaders.remove(HttpHeaders.CONTENT_LENGTH);
                    httpHeaders.remove(HttpHeaders.CONNECTION);
                    httpHeaders.remove("Keep-Alive");
                    httpHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
                    httpHeaders.remove(HttpHeaders.TE);
                    httpHeaders.remove(HttpHeaders.UPGRADE);
                    httpHeaders.add("X-Forwarded-For", request.getRemoteAddr());
                })
                .body(body != null ? body : new byte[0])
                .exchange((req, res) -> {
                    byte[] responseBody = res.bodyTo(byte[].class);
                    HttpHeaders responseHeaders = new HttpHeaders();
                    responseHeaders.addAll(res.getHeaders());
                    responseHeaders.remove("Transfer-Encoding");
                    responseHeaders.remove("Connection");
                    return ResponseEntity.status(res.getStatusCode())
                            .headers(responseHeaders)
                            .body(responseBody);
                });
    }

    private RestClient clientFor(String upstream){
        return clientsByUpstream.computeIfAbsent(upstream,
                url -> RestClient.builder().baseUrl(url).build());
    }
}
