##1: demo：
先看一个github上官方的demo，大体上有个概念：  
```java
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
                .decoder(new GsonDecoder())//添加一个decoder用来解析响应报文，api.github.com返回的报文是json格式的
                .target(GitHub.class, "https://api.github.com");

        /* The owner and repository parameters will be used to expand the owner and repo expressions
         * defined in the RequestLine.
         * 参数owner和repository将被用来扩展RequestLine中的｛owner｝和｛repo｝表达式
         * 
         * the resulting uri will be https://api.github.com/repos/OpenFeign/feign/contributors
         * 最终的uri是https://api.github.com/repos/OpenFeign/feign/contributors
         */
        List<Contributor> contributors = github.contributors("OpenFeign", "feign");
        contributors.forEach(contributor->{System.out.println(contributor.login + " (" + contributor.contributions + ")");});
    }
}
```

从demo中大概可以看出来一些逻辑：
1. 接口的方法中通过相应的注解来描述出http的信息（@RequestLine、@Param等）。
2. 通过构建器模式来构建Feign，可以设置一些属性（Encoder、Decoder、Client等）。
3. Feign.builder().target(Class<T> apiType, String url) 该方法将生成一个接口的代理类。
4. 代理类里每个接口方法的执行都是使用解析好的http信息来做真实的http请求。

##2：接口注解详情：
Feign annotations define the `Contract` between the interface and how the underlying client
should work.  Feign's default contract defines the following annotations:

| Annotation     | Interface Target | Usage |
|----------------|------------------|-------|
| `@RequestLine` | Method           | Defines the `HttpMethod` and `UriTemplate` for request.  `Expressions`, values wrapped in curly-braces `{expression}` are resolved using their corresponding `@Param` annotated parameters. |
| `@Param`       | Parameter        | Defines a template variable, whose value will be used to resolve the corresponding template `Expression`, by name provided as annotation value. If value is missing it will try to get the name from bytecode method parameter name (if the code was compiled with `-parameters` flag). |
| `@Headers`     | Method, Type     | Defines a `HeaderTemplate`; a variation on a `UriTemplate`.  that uses `@Param` annotated values to resolve the corresponding `Expressions`.  When used on a `Type`, the template will be applied to every request.  When used on a `Method`, the template will apply only to the annotated method. |
| `@QueryMap`    | Parameter        | Defines a `Map` of name-value pairs, or POJO, to expand into a query string. |
| `@HeaderMap`   | Parameter        | Defines a `Map` of name-value pairs, to expand into `Http Headers` |
| `@Body`        | Method           | Defines a `Template`, similar to a `UriTemplate` and `HeaderTemplate`, that uses `@Param` annotated values to resolve the corresponding `Expressions`.|

1. `@RequestLine` : 定义请求的`HttpMethod`和`UriTemplate`。 表达式中，用大括号`{expression}`包装的值使用相应的`@param`注解参数进行解析。
2. `@Param` : 定义一个模板变量，其值用于解析相应的模板表达式，按照名称来匹配。如果缺失值，将尝试从字节码方法参数名称获取名称（如果代码是用`-parameters`标志编译的）。
3. `@Headers` : 定义了一个`HearderTemplate`;`UriTemplate`的一个变体。使用`@param`注解的值来解析表达式。当使用在一个`Type`上时，模板将被应用到每个请求。当使用在一个`Method`上时，模板将只应用到被注解的方法上。
4. `@QueryMap` : 定义了一个键值对的`Map`或者一个POJO，用来扩展到一个查询字符串。
5. `@HeaderMap` : 定义了一个键值对的`Map`，用来扩展到`Http Headers`。
6. `@body` : 定义了一个模板，类似于`UriTemplate`和`HeaderTemplate`，其使用`@Param`注解的值来解析相应的表达式。

##3： Feign类及其实现类和构建器
```java
/**
 * Feign's purpose is to ease development against http apis that feign restfulness. <br>
 * In implementation, Feign is a {@link Feign#newInstance factory} for generating {@link Target
 * targeted} http apis.
 * Feign的目的是简化针对假装Restfully的http apis的开发。
 * 在实现中，Feign 是一个用来产生Http apis的工厂。
 */
public abstract class Feign {
    /**
     * Returns a new instance of an HTTP API, defined by annotations in the {@link Feign Contract},
     * for the specified {@code target}. You should cache this result.
     * 返回一个HTTP API的实例，由入参target的注解来定义。应该缓存起来该结果。
     * 返回的就是一个JDK代理对象。
     */
    public abstract <T> T newInstance(Target<T> target);

    //构建器
    public static class Builder {

        //请求拦截器
        private final List<RequestInterceptor> requestInterceptors =
                new ArrayList<RequestInterceptor>();
        private Logger.Level logLevel = Logger.Level.NONE;
        //用于解析Feign自身提供的注解
        private Contract contract = new Contract.Default();
        //用于真实的执行request请求的
        private Client client = new Client.Default(null, null);
        //重试机制
        private Retryer retryer = new Retryer.Default();
        private Logger logger = new NoOpLogger();
        //报文的一组Encoder和Decoder
        private Encoder encoder = new Encoder.Default();
        private Decoder decoder = new Decoder.Default();
        //处理查询参数的
        private QueryMapEncoder queryMapEncoder = new QueryMapEncoder.Default();
        //处理响应异常
        private ErrorDecoder errorDecoder = new ErrorDecoder.Default();
        //可以来设置（connectTimeout、readTimeout）
        private Options options = new Options();
        
        //用于生成java反射的InvocationHandler的工厂
        private InvocationHandlerFactory invocationHandlerFactory =
                new InvocationHandlerFactory.Default();
        //是否处理404响应码
        private boolean decode404;
        //Response是否需要再decode后进行关闭
        private boolean closeAfterDecode = true;
        //表示http请求异常是返回原始异常还是返回包装为RetryableException的异常
        private ExceptionPropagationPolicy propagationPolicy = NONE;

        public <T> T target(Class<T> apiType, String url) {
            //封装了一层HardCodedTarget，就是封装了class和url
            return target(new HardCodedTarget<T>(apiType, url));
        }

        public <T> T target(Target<T> target) {
            return build().newInstance(target);
        }

        //最终构建出的是Feign子类ReflectiveFeign
        public Feign build() {
            SynchronousMethodHandler.Factory synchronousMethodHandlerFactory =
                    new SynchronousMethodHandler.Factory(client, retryer, requestInterceptors, logger,
                            logLevel, decode404, closeAfterDecode, propagationPolicy);
            ParseHandlersByName handlersByName =
                    new ParseHandlersByName(contract, options, encoder, decoder, queryMapEncoder,
                            errorDecoder, synchronousMethodHandlerFactory);
            return new ReflectiveFeign(handlersByName, invocationHandlerFactory, queryMapEncoder);
        }
    }
}
```

Feign的实现类ReflectiveFeign
```java
public class ReflectiveFeign extends Feign {
    private final ParseHandlersByName targetToHandlersByName;
    private final InvocationHandlerFactory factory;
    private final QueryMapEncoder queryMapEncoder;

    ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
                    QueryMapEncoder queryMapEncoder) {
        this.targetToHandlersByName = targetToHandlersByName;
        this.factory = factory;
        this.queryMapEncoder = queryMapEncoder;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T newInstance(Target<T> target) {
        //MethodHandler为一个方法执行逻辑的封装处理器
        //targetToHandlersByName是类解析接口方法和注解，生成MethodHandler
        Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
        //接口每个方法的定义与MethodHandler的映射
        Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
        //default类型方法的处理器
        List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();
        
        //target为HardCodeTarget，里面封装了接口的class和url
        //循环接口类的所有方法
        for (Method method : target.type().getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                //如果是Object的方法，忽略掉
                continue;
            } else if (Util.isDefault(method)) {
                //如果是default的
                DefaultMethodHandler handler = new DefaultMethodHandler(method);
                defaultMethodHandlers.add(handler);
                methodToHandler.put(method, handler);
            } else {
                //从nameToHandler中找到该方法对应的处理器
                methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
            }
        }
        
        //生成jdk代理所需要的InvocationHandler，并且传入了methodToHandler
        //factory默认使用的是InvocationHandlerFactory.Default()，create创建出来的是FeignInvocationHandler
        InvocationHandler handler = factory.create(target, methodToHandler);
        //生成接口的代理对象
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
                new Class<?>[] {target.type()}, handler);

        for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
            defaultMethodHandler.bindTo(proxy);
        }
        return proxy;
    }

    //InvocationHandler的实现类
    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, MethodHandler> dispatch;

        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler =
                            args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            }

            //从map中找到该method对应的MethodHandler，再调用处理
            return dispatch.get(method).invoke(args);
        }

    }
}
```
源码看下来有以下结果：
1. Feign是用来根据接口以及注解来生成一个HttpApis实例的。
2. ReflectiveFeign是一个Feign的具体实现类，根据类名也能看出来是利用了反射来生成一个HttpApis实例（代理类）。
3. Feign.Builder支持一些额外的配置，默认也都提供了默认实现。比如：
    - RequestInterceptor：请求拦截器
    - Client：具体执行http请求，默认使用的是HttpURLConnection来实现的。
    - Encoder、Decoder： 用于编码或解码请求（有默认实现）
    - Retryer： 重试机制（有默认实现）
    - Options： 定义了一些网络请求参数
4. 具体的执行逻辑在InvocationHandler的实现类FeignInvocationHandler里，其内部封装了一个Map<Method, MethodHandler>，MethodHandler就是每个代理方法具体的执行逻辑。


##4： MethodHandler代理方法具体的执行逻辑与其初始化过程
先看下MethodHandler接口的代码：  
```java
  interface MethodHandler {

    Object invoke(Object[] argv) throws Throwable;
  }
```

MethodHandler接口只有一个方法定义，就是根据参数来执行。  
看一下其默认实现类SynchronousMethodHandler：  
```java
final class SynchronousMethodHandler implements MethodHandler {
    private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

    private final MethodMetadata metadata;
    private final Target<?> target;
    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final RequestTemplate.Factory buildTemplateFromArgs;
    private final Options options;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;

    private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
                                     List<RequestInterceptor> requestInterceptors, Logger logger,
                                     Logger.Level logLevel, MethodMetadata metadata,
                                     RequestTemplate.Factory buildTemplateFromArgs, Options options,
                                     Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
                                     boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy) {
        //省略了一堆的赋值操作代码
    }

    @Override
    public Object invoke(Object[] argv) throws Throwable {
        //根据入参及方法上标注的注解将http请求解析出来
        RequestTemplate template = buildTemplateFromArgs.create(argv);
        //查找参数中有没有Options如果有用参数里带的，没有的话用构造函数里传入的
        Options options = findOptions(argv);
        Retryer retryer = this.retryer.clone();
        //这里采用了循环处理，如果有异常并且不允许重试，就抛出异常结束了
        while (true) {
            try {
                return executeAndDecode(template, options);
            } catch (RetryableException e) {
                try {
                    //如果该异常经过判断符合重试，retryer将不抛出异常，while循环将继续下次执行。
                    retryer.continueOrPropagate(e);
                } catch (RetryableException th) {
                    Throwable cause = th.getCause();
                    if (propagationPolicy == UNWRAP && cause != null) {
                        //如果不包装，就将RetryableException里封装的原始异常给抛出去。
                        throw cause;
                    } else {
                        throw th;
                    }
                }
                if (logLevel != Logger.Level.NONE) {
                    logger.logRetry(metadata.configKey(), logLevel);
                }
                continue;
            }
        }
    }

    Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
        //最终通过template.request()创建出来Request（HttpMethod,url,header,body）
        //会优先逐个处理拦截器RequestInterceptor
        Request request = targetRequest(template);

        
        Response response;
        long start = System.nanoTime();
        try {
            //client实际处理HTTP请求，默认使用的是jdk的HttpURLConnection
            response = client.execute(request, options);
        } catch (IOException e) {
            if (logLevel != Logger.Level.NONE) {
                logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
            }
            //errorExecuting是将Exception封装到了RetryableException中，以供外层Retryer来判断是否重试
            throw errorExecuting(request, e);
        }
        long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        boolean shouldClose = true;
        try {
            //处理并返回Response
            if (Response.class == metadata.returnType()) {
                if (response.body() == null) {
                    return response;
                }
                if (response.body().length() == null ||
                        response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
                    shouldClose = false;
                    return response;
                }
                // Ensure the response body is disconnected
                byte[] bodyData = Util.toByteArray(response.body().asInputStream());
                return response.toBuilder().body(bodyData).build();
            }
            if (response.status() >= 200 && response.status() < 300) {
                if (void.class == metadata.returnType()) {
                    return null;
                } else {
                    Object result = decode(response);
                    shouldClose = closeAfterDecode;
                    return result;
                }
            } else if (decode404 && response.status() == 404 && void.class != metadata.returnType()) {
                Object result = decode(response);
                shouldClose = closeAfterDecode;
                return result;
            } else {
                throw errorDecoder.decode(metadata.configKey(), response);
            }
        } catch (IOException e) {
            throw errorReading(request, response, e);
        } finally {
            if (shouldClose) {
                ensureClosed(response.body());
            }
        }
    }

    long elapsedTime(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    Request targetRequest(RequestTemplate template) {
        for (RequestInterceptor interceptor : requestInterceptors) {
            interceptor.apply(template);
        }
        return target.apply(template);
    }

    Object decode(Response response) throws Throwable {
        try {
            return decoder.decode(response, metadata.returnType());
        } catch (FeignException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
        }
    }

    Options findOptions(Object[] argv) {
        if (argv == null || argv.length == 0) {
            return this.options;
        }
        return (Options) Stream.of(argv)
                .filter(o -> o instanceof Options)
                .findFirst()
                .orElse(this.options);
    }
}
```
MethodHandler的执行逻辑大体为：
1. 解析处理Request，处理Request拦截器。
2. 解析处理Options
3. 循环调用client.execute(Request, Options)
    - 如果有报错将异常封装为RetryableException并执行retryer.continueOrPropagate(RetryableException)来判断是否继续重试
    - 没有报错正常返回跳出循环
4. 解析、转码、关闭、返回Response


MethodHandler的解析过程：
```java
public class ReflectiveFeign extends Feign {
    
    static final class ParseHandlersByName {

        private final Contract contract;
        private final Options options;
        private final Encoder encoder;
        private final Decoder decoder;
        private final ErrorDecoder errorDecoder;
        private final QueryMapEncoder queryMapEncoder;
        private final SynchronousMethodHandler.Factory factory;

        ParseHandlersByName(
                Contract contract,
                Options options,
                Encoder encoder,
                Decoder decoder,
                QueryMapEncoder queryMapEncoder,
                ErrorDecoder errorDecoder,
                SynchronousMethodHandler.Factory factory) {
            this.contract = contract;
            this.options = options;
            this.factory = factory;
            this.errorDecoder = errorDecoder;
            this.queryMapEncoder = queryMapEncoder;
            this.encoder = checkNotNull(encoder, "encoder");
            this.decoder = checkNotNull(decoder, "decoder");
        }

        public Map<String, MethodHandler> apply(Target key) {
            //解析接口类的所有方法，封装为MethodMetadata
            List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
            Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
            for (MethodMetadata md : metadata) {
                //创建不同的RequestTemplate.Factory，用于创建RequestTemplate
                BuildTemplateByResolvingArgs buildTemplate;
                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
                    buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
                } else if (md.bodyIndex() != null) {
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
                } else {
                    buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder);
                }
                //默认的factory创建出来的就是SynchronousMethodHandler
                result.put(md.configKey(),
                        factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
            }
            return result;
        }
    }
}
```