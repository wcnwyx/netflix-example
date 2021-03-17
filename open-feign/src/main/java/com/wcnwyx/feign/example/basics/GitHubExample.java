package com.wcnwyx.feign.example.basics;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.gson.GsonDecoder;

import java.util.List;

public class GitHubExample {
    public static class Contributor {
        String login;
        int contributions;
    }

    interface GitHub {
        @RequestLine("GET /repos/{owner}/{repo}/contributors")
        List<Contributor> contributors(@Param("owner") String owner, @Param("repo") String repo);
    }

    public static void main(String... args) {
        GitHub github = Feign.builder()
                .decoder(new GsonDecoder())
                .target(GitHub.class, "https://api.github.com");

        // Fetch and print a list of the contributors to this library.
        List<Contributor> contributors = github.contributors("OpenFeign", "feign");
        contributors.forEach(contributor->{System.out.println(contributor.login + " (" + contributor.contributions + ")");});
    }
}
