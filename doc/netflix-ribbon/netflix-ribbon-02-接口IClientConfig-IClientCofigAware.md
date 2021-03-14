##接口：IClientConfig
该接口其实就是将客户端配置封装成了类。没有什么难点，只是代码中大面积出现，还是看一下比较好。
```java
/**
 * Defines the client configuration used by various APIs to initialize clients or load balancers
 * and for method execution. The default implementation is {@link DefaultClientConfigImpl}.
 * 
 * 定义了各种AIP所使用的客户端配置，为了初始化客户端或者负载均衡器和方法执行。
 * 默认实现是DefaultClientConfigImpl
 */
public interface IClientConfig {
    
}
```

##实现类DefaultClientConfigImpl
内容就不列了，就是一堆get属性的方法，看下注释。  
Archaius为netflix的一个配置管理的项目。  
```java
/**
 * Default client configuration that loads properties from Archaius's ConfigurationManager.
 * 默认客户端配置是通过Archaius的ConfigurationManager类来加载属性的。
 * 
 * The easiest way to configure client and load balancer is through loading properties into Archaius that conform to the specific format:
 * <clientName>.<nameSpace>.<propertyName>=<value>
 * You can define properties in a file on classpath or as system properties. If former, ConfigurationManager.loadPropertiesFromResources() API should be called to load the file.
 * By default, "ribbon" should be the nameSpace.
 * 
 * 最简单的方式去配置客户端和负载均衡器是通过加载属性到Archaius，符合特定规则：
 * <clientName>.<nameSpace>.<propertyName>=<value>
 * 你可以在classpath上的文件中定义属性，也可以将属性定义为系统属性。如果是前者，ConfigurationManager.loadPropertiesFromResources() 方法将会调用来加载文件。
 * 默认的nameSpace是"ribbon"

 * If there is no property specified for a named client, {@code com.netflix.client.ClientFactory} will still create the client and
 * load balancer with default values for all necessary properties. The default
 * values are specified in this class as constants.
 * 
 * 如果没有为命名客户端指定属性，com.netflix.client.ClientFactory将使用所有必须属性的默认值来创建client和load balancer。
 * 默认是在此类中指定为常量。
 * 
 * If a property is missing the clientName, it is interpreted as a property that applies to all clients. For example
 * ribbon.ReadTimeout=1000
 * This will establish the default ReadTimeout property for all clients.
 * 
 * 如果一个属性没有clientName， 它被解释为适用于所有客户端的属性。比如：
 * ribbon.ReadTimeout=1000
 * 这将为所有客户端建立默认的ReadTimeout属性。
 * 
 * You can also programmatically set properties by constructing instance of DefaultClientConfigImpl. Follow these steps:
 * 
 * - Get an instance by calling {@link #getClientConfigWithDefaultValues(String)} to load default values,
 *   and any properties that are already defined with Configuration in Archaius
 * - Set all desired properties by calling {@link #setProperty(IClientConfigKey, Object)} API.
 * - Pass this instance together with client name to {@code com.netflix.client.ClientFactory} API.
 * 
 * 你还可以通过构造DefaultClientConfigImpl实例以编程方式设置属性。遵循以下步骤：
 * - 通过调用getClientConfigWithDefaultValues(String)方法加载默认值来获取实例，
 *   以及任何在Archaius中已经用配置定义的属性。
 * - 通过调用setProperty（IClientConfigKey，Object）API设置所有需要的属性
 * - 将此实例与客户端名称一起传递给 com.netflix.client.ClientFactory API
 *
 * 
 * If it is desired to have properties defined in a different name space, for example, "foo"
 * myclient.foo.ReadTimeout=1000
 * You should use {@link #getClientConfigWithDefaultValues(String, String)} - in the first step above.
 *
 * 如果需要在不同的名称空间中定义特性，例如，"foo"
 * myclient.foo.ReadTimeout=1000
 * 在上面的第一步中，应该使用 getClientConfigWithDefaultValues（String，String） 
 */
public class DefaultClientConfigImpl implements IClientConfig {
    
}
```


##接口IClientConfigAware
ribbon中很多类都实现了该接口，比如说（BaseLoadBalancer、AbstractLoadBalancerRule、LoadBalancerStats、LoadBalancerContext）  
因为ribbon本身就是一个客户端类型的负载均衡，很多类都需要依赖于配置文件来初始化。  
```java
/**
 * There are multiple classes (and components) that need access to the configuration.
 * Its easier to do this by using {@link IClientConfig} as the object that carries these configurations
 * and to define a common interface that components that need this can implement and hence be aware of.
 *
 * 有多个类（和组件）需要访问配置。
 * 通过使用IClientConfig作为携带这些配置的对象，并定义一个公共接口，实现它的接口可以实现并因此意识到，这样做更容易。
 * 就是说通过实现这个接口，通过接口方法中传入的IClientConfig对象来进行初始化。
 */
public interface IClientConfigAware {

    public abstract void initWithNiwsConfig(IClientConfig clientConfig);
    
}
```