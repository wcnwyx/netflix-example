package com.wcnwyx.zuul.example;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.util.HTTPRequestUtils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import java.net.URISyntaxException;

import static com.netflix.zuul.constants.ZuulHeaders.CONTENT_ENCODING;

public class RouteFilter extends ZuulFilter {

    @Override
    public String filterType() {
        return "route";
    }

    @Override
    public int filterOrder() {
        return 100;
    }

    @Override
    public boolean shouldFilter() {
        return RequestContext.getCurrentContext().getRouteHost() != null && RequestContext.getCurrentContext().sendZuulResponse();
    }

    @Override
    public Object run() throws ZuulException {
        HttpGet httpRequest = null;
        try {
            httpRequest = new HttpGet(RequestContext.getCurrentContext().getRouteHost().toURI());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        try {
            HttpClient httpClient = newClient();
            HttpResponse response = httpClient.execute(httpRequest);

            RequestContext.getCurrentContext().set("hostZuulResponse", response);
            RequestContext.getCurrentContext().setResponseStatusCode(response.getStatusLine().getStatusCode());
            RequestContext.getCurrentContext().setResponseDataStream(response.getEntity().getContent());

            boolean isOriginResponseGzipped = false;

            for (Header h : response.getHeaders(CONTENT_ENCODING)) {
                if (HTTPRequestUtils.getInstance().isGzipped(h.getValue())) {
                    isOriginResponseGzipped = true;
                    break;
                }
            }
            RequestContext.getCurrentContext().setResponseGZipped(isOriginResponseGzipped);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static final CloseableHttpClient newClient() {
        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setRetryHandler(new DefaultHttpRequestRetryHandler(0, false));
        builder.setRedirectStrategy(new RedirectStrategy() {
            @Override
            public boolean isRedirected(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws ProtocolException {
                return false;
            }

            @Override
            public HttpUriRequest getRedirect(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws ProtocolException {
                return null;
            }
        });

        return builder.build();
    }
}
