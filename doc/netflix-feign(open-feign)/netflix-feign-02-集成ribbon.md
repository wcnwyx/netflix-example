##demo
先看一下demo，feign只需要设置Feign.builder().client(RibbonClient.create())即可。  
RibbonClient使用的都是默认的属性。  
```java
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
        //直接手动设置属性了，不用配置文件来加载了，所以ribbon的其它属性都是使用的默认的
        //第一个地址是写了一个完全没有的域名，为了验证feign的Regryer机制
        ConfigurationManager.getConfigInstance().setProperty(
                "myAppProd.ribbon.listOfServers", "https://aaabbbccc.com,https://api.github.com");
        
        GitHub github = Feign.builder()
                .client(RibbonClient.create())//通过这里添加RibbonClient
                .decoder(new GsonDecoder())
                .target(GitHub.class, "https://myAppProd");

        //循环两次执行，可以验证出feign的Retryer机制
        for(int i=0;i<=1;i++){
            List<Contributor> contributors = github.contributors("OpenFeign", "feign");
            contributors.forEach(contributor->{System.out.println(contributor.login + " (" + contributor.contributions + ")");});
        }
    }
}
```

这一块的代码不多，但是几个类的命名很容易迷惑，先列一下：
1. RibbonClient 这是个实现了feign.Client接口的，feign自身默认的实现是Client.Default()，使用的是HttpURLConnection来实现的http请求。（属于Feign的）
2. LBClient 这个是实现了com.netflix.client.IClient接口的。（属于ribbon的）
3. LBClient.RibbonRequest 该request是实现com.netflix.client.ClientRequest接口的。（属于ribbon的）
4. LBClient.RibbonResponse 该response是实现com.netflix.client.IResponse接口的。（属于ribbon）

##RibbonClient
```java
/**
 * RibbonClient can be used in Feign builder to activate smart routing and resiliency capabilities
 * provided by Ribbon. Ex.
 * 
 * <pre>
 * MyService api = Feign.builder.client(RibbonClient.create()).target(MyService.class,
 *     &quot;http://myAppProd&quot;);
 * </pre>
 * 
 * Where {@code myAppProd} is the ribbon client name and {@code myAppProd.ribbon.listOfServers}
 * configuration is set.
 * 
 * RibbonClient可用于Feign Builder，以激活ribbon提供的智能路由和恢复功能。例如：
 * MyService api = Feign.builder.client(RibbonClient.create()).target(MyService.class,"http://myAppProd")
 * myAppProd就是ribbon的client name，可以在配置中通过myAppProd.ribbon.listOfServers配置
 */
public class RibbonClient implements feign.Client { 
  //默认使用的是Client.Default(就是使用HttpURLConnection来实现的http请求)
  private final Client delegate;
  //默认使用的是LBClientFactory,创建出来的就是LBClient
  private final LBClientFactory lbClientFactory;


  public static RibbonClient create() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  RibbonClient(Client delegate, LBClientFactory lbClientFactory) {
    this.delegate = delegate;
    this.lbClientFactory = lbClientFactory;
  }

  @Override
  public Response execute(Request request, Request.Options options) throws IOException {
    try {
      //这里以上面的demo为例
      //asUri=https://myAppProd/repos/OpenFeign/feign/contributors
      URI asUri = URI.create(request.url());
      //clientName=myAppProd
      String clientName = asUri.getHost();
      //uriWithoutHost=https:///repos/OpenFeign/feign/contributors
      URI uriWithoutHost = cleanUrl(request.url(), clientName);
      //ribbonRequest中将delegate保存了，因为ribbon的IClient的execute方法就通过该delegate有调用了回来。
      LBClient.RibbonRequest ribbonRequest =
          new LBClient.RibbonRequest(delegate, request, uriWithoutHost);
      //lbClient()方法获取到LBClient，调用executeWithLoadBalancer()方法来执行逻辑
        //executeWithLoadBalancer方法为LBClient的抽象父类里，最终还是调用的LBClient.execute()方法来执行（这些都是ribbon的业务）
      return lbClient(clientName).executeWithLoadBalancer(ribbonRequest,
          new FeignOptionsClientConfig(options)).toResponse();
    } catch (ClientException e) {
      propagateFirstIOException(e);
      throw new RuntimeException(e);
    }
  }

  static void propagateFirstIOException(Throwable throwable) throws IOException {
    while (throwable != null) {
      if (throwable instanceof IOException) {
        throw (IOException) throwable;
      }
      throwable = throwable.getCause();
    }
  }

  static URI cleanUrl(String originalUrl, String host) {
    return URI.create(originalUrl.replaceFirst(host, ""));
  }

  private LBClient lbClient(String clientName) {
    return lbClientFactory.create(clientName);
  }


  public static final class Builder {

    Builder() {}

    private Client delegate;
    private LBClientFactory lbClientFactory;

    public RibbonClient build() {
      return new RibbonClient(
          delegate != null ? delegate : new Client.Default(null, null),
          lbClientFactory != null ? lbClientFactory : new LBClientFactory.Default());
    }
  }
}
```

##LBClient
```java
public final class LBClient extends
    AbstractLoadBalancerAwareClient<LBClient.RibbonRequest, LBClient.RibbonResponse> {
    private final int connectTimeout;
    private final int readTimeout;
    private final IClientConfig clientConfig;
    private final Set<Integer> retryableStatusCodes;
    private final Boolean followRedirects;

    @Override
    public RibbonResponse execute(RibbonRequest request, IClientConfig configOverride)
            throws IOException, ClientException {
        Request.Options options;
        if (configOverride != null) {
            options =
                    new Request.Options(
                            configOverride.get(CommonClientConfigKey.ConnectTimeout, connectTimeout),
                            (configOverride.get(CommonClientConfigKey.ReadTimeout, readTimeout)),
                            configOverride.get(CommonClientConfigKey.FollowRedirects, followRedirects));
        } else {
            options = new Request.Options(connectTimeout, readTimeout);
        }
        //通过request中保存的feign的Client来调用execute执行，最终执行的逻辑还是调回了feign中
        //这里的Request中的uri已经在父类AbstractLoadBalancerAwareClient中替换为真实的请求地址了
        Response response = request.client().execute(request.toRequest(), options);
        if (retryableStatusCodes.contains(response.status())) {
            response.close();
            throw new ClientException(ClientException.ErrorType.SERVER_THROTTLED);
        }
        return new RibbonResponse(request.getUri(), response);
    }
}
```

##总结：
整体流程逻辑还是比较简单的，步骤如下：
1. 新定义了一个feign.Client的一个实现类RibbonClient，其内部封装了一个LBClientFactory用于生成LBClient。
2. RibbonClient.execute方法调用LBClient.executeWithLoadBalancer方法。
3. LBClient.executeWithLoadBalancer方法中将uri解析为真实地址后，从新通过request.client()从新获取到RibbonClient.delegate。
4. 再调用RibbonClient.delegate().execute()方法就又绕回到feign的逻辑了。