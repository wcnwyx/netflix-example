package com.wcnwyx.ribbon.simple;

import com.netflix.client.ClientFactory;
import com.netflix.client.http.HttpRequest;
import com.netflix.client.http.HttpResponse;
import com.netflix.config.ConfigurationManager;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import com.netflix.niws.client.http.RestClient;

import java.net.URI;

public class RibbonSimpleTest {
    public static void main(String[] args) throws Exception {
        ConfigurationManager.loadPropertiesFromResources("sample-client.properties");  // 1
        System.out.println(ConfigurationManager.getConfigInstance().getProperty("sample-client.ribbon.listOfServers"));
        RestClient client = (RestClient) ClientFactory.getNamedClient("sample-client");  // 2
        HttpRequest request = HttpRequest.newBuilder().uri(new URI("/")).build(); // 3
        for (int i = 0; i < 2; i++)  {
            HttpResponse response = client.executeWithLoadBalancer(request); // 4
            System.out.println("Status code for " + response.getRequestedURI() + "  :" + response.getStatus());
        }
        @SuppressWarnings("rawtypes")
        ZoneAwareLoadBalancer lb = (ZoneAwareLoadBalancer) client.getLoadBalancer();
        System.out.println(lb.getLoadBalancerStats());
        ConfigurationManager.getConfigInstance().setProperty(
                "sample-client.ribbon.listOfServers", "www.aaa.com:80,www.baidu.com:80"); // 5
        System.out.println("changing servers ...");
        Thread.sleep(3000); // 6
        for (int i = 0; i < 2; i++)  {
            HttpResponse response = null;
            try {
                response = client.executeWithLoadBalancer(request);
                System.out.println("Status code for " + response.getRequestedURI() + "  : " + response.getStatus());
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        System.out.println(lb.getLoadBalancerStats()); // 7
    }
}
