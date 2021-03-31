package com.wcnwyx.zuul.example;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.filters.FilterRegistry;
import com.netflix.zuul.http.ZuulServlet;
import com.netflix.zuul.monitoring.MonitoringHelper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.net.MalformedURLException;
import java.net.URL;

public class ZuulMain {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        contextHandler.addServlet(new ServletHolder(new ZuulServlet()), "/zuul");
        // mocks monitoring infrastructure as we don't need it for this simple app
        MonitoringHelper.initMocks();
        initJavaFilters();

        server.start();
        server.join();
    }

    private static void initJavaFilters() {
        final FilterRegistry r = FilterRegistry.instance();

        r.put("javaPreFilter", new ZuulFilter() {
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
                RequestContext.getCurrentContext().set("javaPreFilter-ran", true);
                try {
                    RequestContext.getCurrentContext().setRouteHost(new URL("http://apache.org/"));
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
                return null;
            }
        });

        r.put("javaPostFilter", new ZuulFilter() {
            @Override
            public int filterOrder() {
                return 50000;
            }

            @Override
            public String filterType() {
                return "post";
            }

            @Override
            public boolean shouldFilter() {
                return true;
            }

            @Override
            public Object run() {
                System.out.println("running javaPostFilter");
                RequestContext.getCurrentContext().set("javaPostFilter-ran", true);
                return null;
            }
        });
    }
}
