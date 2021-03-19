package com.wcnwyx.feign.example.basics;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.gson.GsonDecoder;
import feign.ribbon.RibbonClient;
import com.netflix.config.ConfigurationManager;

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

    public static void main(String... args){
        ConfigurationManager.getConfigInstance().setProperty(
                "myAppProd.ribbon.listOfServers", "https://aaabbbccc.com,https://api.github.com");
        GitHub github = Feign.builder()
                .client(RibbonClient.create())
                .decoder(new GsonDecoder())
                .target(GitHub.class, "https://myAppProd");

        for(int i=0;i<=1;i++){
            List<Contributor> contributors = github.contributors("OpenFeign", "feign");
            contributors.forEach(contributor->{System.out.println(contributor.login + " (" + contributor.contributions + ")");});
        }
    }
}
