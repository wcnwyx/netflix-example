package com.wcnwyx.feign.example.basics;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.Retryer;
import feign.gson.GsonDecoder;
import feign.ribbon.RibbonClient;
import com.netflix.config.ConfigurationManager;

import java.io.IOException;
import java.util.List;

public class GitHubRibbonExample {
    public static class Contributor {
        String login;
        int contributions;
    }

    interface GitHub {
        @RequestLine("GET /repos/{owner}/{repo}/contributors")
        List<Contributor> contributors(@Param("owner") String owner, @Param("repo") String repo);
    }

    public static void main(String... args) throws IOException {
        ConfigurationManager.loadPropertiesFromResources("sample-client.properties");

        GitHub github = Feign.builder()
                .client(RibbonClient.create())
//                .retryer(Retryer.NEVER_RETRY)
                .decoder(new GsonDecoder())
                .target(GitHub.class, "https://sample-client");

        for(int i=0;i<=2;i++){
            List<Contributor> contributors = github.contributors("OpenFeign", "feign");
            contributors.forEach(contributor->{System.out.println(contributor.login + " (" + contributor.contributions + ")");});
        }
    }
}
