package com.wcnwyx.zuul.example;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;

import java.net.MalformedURLException;
import java.net.URL;

public class PreRouteFilter extends ZuulFilter {
    @Override
    public int filterOrder() {
        return 50000;
    }

    @Override
    public String filterType() {
        return "pre";
    }

    @Override
    public boolean shouldFilter() {
        return true;
    }

    @Override
    public Object run() {
        System.out.println("running javaPreFilter");
        try {
            // sets origin
            RequestContext.getCurrentContext().setRouteHost(new URL("http://apache.org/"));
            // sets custom header to send to the origin
            RequestContext.getCurrentContext().addZuulRequestHeader("cache-control", "max-age=3600");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }
}
