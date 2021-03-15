DynamicServerListLoadBalancer为ILoadBalancer的一个实现类，采用策略模式实现了动态获取服务列表的能力，也是ribbon-eureka的切入点。  
先看下类注释。  
##1: DynamicServerListLoadBalancer预览   
```java
/**
 * A LoadBalancer that has the capabilities to obtain the candidate list of
 * servers using a dynamic source. i.e. The list of servers can potentially be
 * changed at Runtime. It also contains facilities wherein the list of servers
 * can be passed through a Filter criteria to filter out servers that do not
 * meet the desired criteria.
 * 
 * 一种负载均衡器，具有从动态源获取后续服务器列表的能力。
 * 服务器列表可能会在运行时更改。
 * 它还包含一些功能，其中服务器列表可以通过过滤条件来过滤出不合符条件的服务器。
 * 
 */
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    volatile ServerList<T> serverListImpl;
    
    volatile ServerListFilter<T> filter;

    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };

    protected volatile ServerListUpdater serverListUpdater;
}
```

策略就是ServerList这个接口，动态性就体现在ServerListUpdater里，下面先看下这两个接口。

##2: ServerList接口
```java
/**
 * Interface that defines the methods sed to obtain the List of Servers
 * 定义获取服务器列表方法的接口。
 */
public interface ServerList<T extends Server> {

    //获取服务器的初始列表
    public List<T> getInitialListOfServers();
    
    /**
     * Return updated list of servers. This is called say every 30 secs
     * (configurable) by the Loadbalancer's Ping cycle
     * 返回更新的服务器列表。后面的意思不太好理解，其实就是会被ServerListUpdater定时调用来更新。
     */
    public List<T> getUpdatedListOfServers();   

}
```

###2.1: ServerList接口实现类-ConfigurationBasedServerList
```java
/**
 * Utility class that can load the List of Servers from a Configuration (i.e
 * properties available via Archaius). The property name be defined in this format:
 * 
 * <pre>{@code
<clientName>.<nameSpace>.listOfServers=<comma delimited hostname:port strings>
}</pre>
 * 
 * 实用的类，可以从配置加载服务列表(可通过Archaius获得属性)。属性名称定义为以下格式：
 * <clientName>.<nameSpace>.listOfServers=<comma delimited hostname:port strings>
 * 该类获取服务器列表的方式其实就是从配置列表中解析配置项。
 */
public class ConfigurationBasedServerList extends AbstractServerList<Server>  {

	private IClientConfig clientConfig;
		
	@Override
	public List<Server> getInitialListOfServers() {
	    return getUpdatedListOfServers();
	}

	@Override
	public List<Server> getUpdatedListOfServers() {
        String listOfServers = clientConfig.get(CommonClientConfigKey.ListOfServers);
        return derive(listOfServers);
	}

	@Override
	public void initWithNiwsConfig(IClientConfig clientConfig) {
	    this.clientConfig = clientConfig;
	}
	
	protected List<Server> derive(String value) {
	    List<Server> list = Lists.newArrayList();
		if (!Strings.isNullOrEmpty(value)) {
			for (String s: value.split(",")) {
				list.add(new Server(s.trim()));
			}
		}
        return list;
	}
}
```

###2.2: ServerList接口实现类-DiscoveryEnabledNIWSServerList
该类是ribbon-eureka提供的，用于ribbon中动态从eureka中获取服务器列表的策略。  
```java
/**
 * The server list class that fetches the server information from Eureka client. ServerList is used by
 * {@link DynamicServerListLoadBalancer} to get server list dynamically.
 *
 * 该ServerList类从eureka cliet中获取服务信息。
 * ServerList 是用于DynamicServerListLoadBalancer动态的获取服务器列表。
 *
 */
public class DiscoveryEnabledNIWSServerList extends AbstractServerList<DiscoveryEnabledServer>{

    boolean prioritizeVipAddressBasedServers = true;
    private final Provider<EurekaClient> eurekaClientProvider;

    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig, Provider<EurekaClient> eurekaClientProvider) {
        this.eurekaClientProvider = eurekaClientProvider;
        initWithNiwsConfig(clientConfig);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        //基于IClientConfig的初始化，不列详细代码了
    }

    @Override
    public List<DiscoveryEnabledServer> getInitialListOfServers(){
        return obtainServersViaDiscovery();
    }

    @Override
    public List<DiscoveryEnabledServer> getUpdatedListOfServers(){
        return obtainServersViaDiscovery();
    }

    private List<DiscoveryEnabledServer> obtainServersViaDiscovery() {
        List<DiscoveryEnabledServer> serverList = new ArrayList<DiscoveryEnabledServer>();

        if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
            logger.warn("EurekaClient has not been initialized yet, returning an empty list");
            return new ArrayList<DiscoveryEnabledServer>();
        }

        EurekaClient eurekaClient = eurekaClientProvider.get();
        if (vipAddresses!=null){
            //可以支持多个vipAddress（在springCloud中，是以applicationName来当做vipAddress注册到eureka中的）
            for (String vipAddress : vipAddresses.split(",")) {
                // if targetRegion is null, it will be interpreted as the same region of client
                // eurekaClient.getInstancesByVipAddress来获取实例信息
                // 支持配置remoteRegion
                List<InstanceInfo> listOfInstanceInfo = eurekaClient.getInstancesByVipAddress(vipAddress, isSecure, targetRegion);
                for (InstanceInfo ii : listOfInstanceInfo) {
                    if (ii.getStatus().equals(InstanceStatus.UP)) {

                        if(shouldUseOverridePort){

                            // copy is necessary since the InstanceInfo builder just uses the original reference,
                            // and we don't want to corrupt the global eureka copy of the object which may be
                            // used by other clients in our system
                            //拷贝是必须的，因为InstanceInfo生成器只使用原始引用，
                            //并且我们不希望损坏对象的全局eureka副本，该副本可能被系统中的其它客户端使用。
                            InstanceInfo copy = new InstanceInfo(ii);

                            if(isSecure){
                                ii = new InstanceInfo.Builder(copy).setSecurePort(overridePort).build();
                            }else{
                                ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                            }
                        }

                        DiscoveryEnabledServer des = createServer(ii, isSecure, shouldUseIpAddr);
                        serverList.add(des);
                    }
                }
                if (serverList.size()>0 && prioritizeVipAddressBasedServers){
                    //如果当前vipAddress已经找到服务了，并且prioritizeVipAddressBasedServers，就直接返回了。
                    //prioritizeVipAddressBasedServers 根据字面意思应该是多个vipAddress有优先级，如果第一个有可用服务，就不在使用后续的了。
                    break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
                }
            }
        }
        return serverList;
    }

    //将InstanceInfo转换为DiscoveryEnabledServer用于保存在LoadBalancer中
    protected DiscoveryEnabledServer createServer(final InstanceInfo instanceInfo, boolean useSecurePort, boolean useIpAddr) {
        DiscoveryEnabledServer server = new DiscoveryEnabledServer(instanceInfo, useSecurePort, useIpAddr);

        // Get availabilty zone for this instance.
        EurekaClientConfig clientConfig = eurekaClientProvider.get().getEurekaClientConfig();
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
        String instanceZone = InstanceInfo.getZone(availZones, instanceInfo);
        server.setZone(instanceZone);

        return server;
    }

}
```

##3: ServerListUpdater接口
根据接口名字其实也可以知道它是用来做ServerList的更新操作的。  
```java
/**
 * strategy for {@link com.netflix.loadbalancer.DynamicServerListLoadBalancer} to use for different ways
 * of doing dynamic server list updates.
 *
 * DynamicServerListLoadBalancer用于不同方式来动态更新ServerList的策略。
 */
public interface ServerListUpdater {

    /**
     * an interface for the updateAction that actually executes a server list update
     * 实际用于更新服务器列表动作的接口。
     */
    public interface UpdateAction {
        void doUpdate();
    }


    /**
     * start the serverList updater with the given update action
     * This call should be idempotent.
     *
     * 用给定的UpdateAction（具体的更新动作类）来启动该ServerListUpdater
     * 该调用应该是幂等的。
     */
    void start(UpdateAction updateAction);

    /**
     * stop the serverList updater. This call should be idempotent
     * 停止该ServerListUpdater。该调用应该是幂等的。
     */
    void stop();

    /**
     * @return the last update timestamp as a {@link java.util.Date} string
     * 返回上次更新时间戳。
     */
    String getLastUpdate();

    /**
     * @return the number of ms that has elapsed since last update
     * 返回自上次更新以来经历的毫秒数。
     */
    long getDurationSinceLastUpdateMs();

    /**
     * @return the number of update cycles missed, if valid
     * 返回更新丢失的周期数，如果可用
     */
    int getNumberMissedCycles();

    /**
     * @return the number of threads used, if vaid
     * 返回使用的线程数，如果可用。
     */
    int getCoreThreads();
}
```


###3.1: ServerListUpdater接口实现类-PollingServerListUpdater
EurekaNotificationServerListUpdater是ribbon自带的一个默认实现类。  
```java
/**
 * A default strategy for the dynamic server list updater to update.
 *
 * 一个默认的策略来更新ServerList
 */
public class PollingServerListUpdater implements ServerListUpdater {

    private static final Logger logger = LoggerFactory.getLogger(PollingServerListUpdater.class);

    private static long LISTOFSERVERS_CACHE_UPDATE_DELAY = 1000; // msecs;
    private static int LISTOFSERVERS_CACHE_REPEAT_INTERVAL = 30 * 1000; // msecs;

    //该类就是为了持有一个线程池，用于定时地更新ServerList操作
    private static class LazyHolder {
        private final static String CORE_THREAD = "DynamicServerListLoadBalancer.ThreadPoolSize";
        private final static DynamicIntProperty poolSizeProp = new DynamicIntProperty(CORE_THREAD, 2);
        private static Thread _shutdownThread;

        static ScheduledThreadPoolExecutor _serverListRefreshExecutor = null;

        static {
            int coreSize = poolSizeProp.get();
            ThreadFactory factory = (new ThreadFactoryBuilder())
                    .setNameFormat("PollingServerListUpdater-%d")
                    .setDaemon(true)
                    .build();
            _serverListRefreshExecutor = new ScheduledThreadPoolExecutor(coreSize, factory);
            poolSizeProp.addCallback(new Runnable() {
                @Override
                public void run() {
                    _serverListRefreshExecutor.setCorePoolSize(poolSizeProp.get());
                }

            });
            _shutdownThread = new Thread(new Runnable() {
                public void run() {
                    logger.info("Shutting down the Executor Pool for PollingServerListUpdater");
                    shutdownExecutorPool();
                }
            });
            Runtime.getRuntime().addShutdownHook(_shutdownThread);
        }

        private static void shutdownExecutorPool() {
            if (_serverListRefreshExecutor != null) {
                _serverListRefreshExecutor.shutdown();

                if (_shutdownThread != null) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(_shutdownThread);
                    } catch (IllegalStateException ise) { // NOPMD
                        // this can happen if we're in the middle of a real
                        // shutdown,
                        // and that's 'ok'
                    }
                }

            }
        }
    }

    private static ScheduledThreadPoolExecutor getRefreshExecutor() {
        return LazyHolder._serverListRefreshExecutor;
    }


    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private volatile long lastUpdated = System.currentTimeMillis();
    private final long initialDelayMs;
    private final long refreshIntervalMs;

    private volatile ScheduledFuture<?> scheduledFuture;

    public PollingServerListUpdater(final long initialDelayMs, final long refreshIntervalMs) {
        this.initialDelayMs = initialDelayMs;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    @Override
    public synchronized void start(final UpdateAction updateAction) {
        if (isActive.compareAndSet(false, true)) {
            final Runnable wrapperRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isActive.get()) {
                        if (scheduledFuture != null) {
                            scheduledFuture.cancel(true);
                        }
                        return;
                    }
                    try {
                        updateAction.doUpdate();
                        lastUpdated = System.currentTimeMillis();
                    } catch (Exception e) {
                        logger.warn("Failed one update cycle", e);
                    }
                }
            };
            
            //定时执行updateAction.doUpdate()
            scheduledFuture = getRefreshExecutor().scheduleWithFixedDelay(
                    wrapperRunnable,
                    initialDelayMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            logger.info("Already active, no-op");
        }
    }

    @Override
    public synchronized void stop() {
        if (isActive.compareAndSet(true, false)) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }
        } else {
            logger.info("Not active, no-op");
        }
    }

}
```

###3.2: ServerListUpdater接口实现类-EurekaNotificationServerListUpdater
该类也是使用在ribbon从eureka动态获取服务器信息的情况下。ServerListUpdater也有默认的一个实现，就不看了  
```java
/**
 * A server list updater for the {@link com.netflix.loadbalancer.DynamicServerListLoadBalancer} that
 * utilizes eureka's event listener to trigger LB cache updates.
 * 
 * 一个DynamicServerListLoadBalancer使用的ServerListUpdater，使用了eureka的事件监听器来触发负载均衡器的缓存更新。
 * 
 * Note that when a cache refreshed notification is received, the actual update on the serverList is
 * done on a separate scheduler as the notification is delivered on an eurekaClient thread.
 *
 * 注意：当收到缓存刷新的通知是，实际的更新serverList操作将在单独的scheduler上执行，因为通知将在eurekaClient的线程上传递。
 * 就是说eurekaClient在将事件通知给事件监听器的时候，是循环通知所有的监听器，单独使用一个scheduler来异步执行，不要影响eurekaClient。
 */
public class EurekaNotificationServerListUpdater implements ServerListUpdater {

    private static final Logger logger = LoggerFactory.getLogger(EurekaNotificationServerListUpdater.class);

    //该类就是为了持有一个线程池，用于异步地更新ServerList操作
    private static class LazyHolder {
        private final static String CORE_THREAD = "EurekaNotificationServerListUpdater.ThreadPoolSize";
        private final static String QUEUE_SIZE = "EurekaNotificationServerListUpdater.queueSize";
        private final static LazyHolder SINGLETON = new LazyHolder();

        private final DynamicIntProperty poolSizeProp = new DynamicIntProperty(CORE_THREAD, 2);
        private final DynamicIntProperty queueSizeProp = new DynamicIntProperty(QUEUE_SIZE, 1000);
        private final ThreadPoolExecutor defaultServerListUpdateExecutor;
        private final Thread shutdownThread;

        private LazyHolder() {
            int corePoolSize = getCorePoolSize();
            defaultServerListUpdateExecutor = new ThreadPoolExecutor(
                    corePoolSize,
                    corePoolSize * 5,
                    0,
                    TimeUnit.NANOSECONDS,
                    new ArrayBlockingQueue<Runnable>(queueSizeProp.get()),
                    new ThreadFactoryBuilder()
                            .setNameFormat("EurekaNotificationServerListUpdater-%d")
                            .setDaemon(true)
                            .build()
            );

            poolSizeProp.addCallback(new Runnable() {
                @Override
                public void run() {
                    int corePoolSize = getCorePoolSize();
                    defaultServerListUpdateExecutor.setCorePoolSize(corePoolSize);
                    defaultServerListUpdateExecutor.setMaximumPoolSize(corePoolSize * 5);
                }
            });

            shutdownThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    logger.info("Shutting down the Executor for EurekaNotificationServerListUpdater");
                    try {
                        defaultServerListUpdateExecutor.shutdown();
                        Runtime.getRuntime().removeShutdownHook(shutdownThread);
                    } catch (Exception e) {
                        // this can happen in the middle of a real shutdown, and that's ok.
                    }
                }
            });

            Runtime.getRuntime().addShutdownHook(shutdownThread);
        }

        private int getCorePoolSize() {
            int propSize = poolSizeProp.get();
            if (propSize > 0) {
                return propSize;
            }
            return 2; // default
        }        
    }

    public static ExecutorService getDefaultRefreshExecutor() {
        return LazyHolder.SINGLETON.defaultServerListUpdateExecutor;
    }

    //防止同时更新
    final AtomicBoolean updateQueued = new AtomicBoolean(false);
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicLong lastUpdated = new AtomicLong(System.currentTimeMillis());
    private final Provider<EurekaClient> eurekaClientProvider;
    private final ExecutorService refreshExecutor;

    //Eureka的事件监听器
    private volatile EurekaEventListener updateListener;
    private volatile EurekaClient eurekaClient;

    public EurekaNotificationServerListUpdater() {
        this(new LegacyEurekaClientProvider());
    }

    public EurekaNotificationServerListUpdater(final Provider<EurekaClient> eurekaClientProvider) {
        this(eurekaClientProvider, getDefaultRefreshExecutor());
    }

    public EurekaNotificationServerListUpdater(final Provider<EurekaClient> eurekaClientProvider, ExecutorService refreshExecutor) {
        this.eurekaClientProvider = eurekaClientProvider;
        this.refreshExecutor = refreshExecutor;
    }

    @Override
    public synchronized void start(final UpdateAction updateAction) {
        if (isActive.compareAndSet(false, true)) {
            //初始化Eureka的事件监听器
            this.updateListener = new EurekaEventListener() {
                @Override
                public void onEvent(EurekaEvent event) {
                    if (event instanceof CacheRefreshedEvent) {
                        if (!updateQueued.compareAndSet(false, true)) {  // if an update is already queued
                            logger.info("an update action is already queued, returning as no-op");
                            return;
                        }

                        if (!refreshExecutor.isShutdown()) {
                            try {
                                refreshExecutor.submit(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            //updateAction为真是的更新ServerList操作
                                            updateAction.doUpdate();
                                            lastUpdated.set(System.currentTimeMillis());
                                        } catch (Exception e) {
                                            logger.warn("Failed to update serverList", e);
                                        } finally {
                                            updateQueued.set(false);
                                        }
                                    }
                                });  // fire and forget
                            } catch (Exception e) {
                                logger.warn("Error submitting update task to executor, skipping one round of updates", e);
                                updateQueued.set(false);  // if submit fails, need to reset updateQueued to false
                            }
                        }
                        else {
                            logger.debug("stopping EurekaNotificationServerListUpdater, as refreshExecutor has been shut down");
                            stop();
                        }
                    }
                }
            };
            if (eurekaClient == null) {
                eurekaClient = eurekaClientProvider.get();
            }
            if (eurekaClient != null) {
                //将事件监听器注册到eurekaClient中
                eurekaClient.registerEventListener(updateListener);
            } else {
                logger.error("Failed to register an updateListener to eureka client, eureka client is null");
                throw new IllegalStateException("Failed to start the updater, unable to register the update listener due to eureka client being null.");
            }
        } else {
            logger.info("Update listener already registered, no-op");
        }
    }

    @Override
    public synchronized void stop() {
        if (isActive.compareAndSet(true, false)) {
            if (eurekaClient != null) {
                eurekaClient.unregisterEventListener(updateListener);
            }
        } else {
            logger.info("Not currently active, no-op");
        }
    }

}
```

##4: 再看DynamicServerListLoadBalancer
```java
/**
 * A LoadBalancer that has the capabilities to obtain the candidate list of
 * servers using a dynamic source. i.e. The list of servers can potentially be
 * changed at Runtime. It also contains facilities wherein the list of servers
 * can be passed through a Filter criteria to filter out servers that do not
 * meet the desired criteria.
 * 
 * 一种负载均衡器，具有从动态源获取后续服务器列表的能力。
 * 服务器列表可能会在运行时更改。
 * 它还包含一些功能，其中服务器列表可以通过过滤条件来过滤出不合符条件的服务器。
 * 
 */
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    
    //用于获取服务列表（上面已经看过怎么用了）
    volatile ServerList<T> serverListImpl;
    
    //一个过滤器，可以按照规则过滤服务器列表
    volatile ServerListFilter<T> filter;

    //ServerListUpdater的具体更新动作，可以看出来是根据本类的updateListOfServers方法来实现的。
    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };
    
    //一个服务列表更新器（上面已经看过怎么用了）
    protected volatile ServerListUpdater serverListUpdater;


    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                         ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping);
        this.serverListImpl = serverList;
        this.filter = filter;
        this.serverListUpdater = serverListUpdater;
        if (filter instanceof AbstractServerListFilter) {
            ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
        }
        restOfInit(clientConfig);
    }


    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        //基于IClientConfig的初始化方法，不列详细的代码了
    }

    //UpdateAction调用方法
    public void updateListOfServers() {
        List<T> servers = new ArrayList<T>();
        if (serverListImpl != null) {
            //如果是eureka的ServerList实现，就是从eurekaClient中获取服务信息
            servers = serverListImpl.getUpdatedListOfServers();
            LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);

            if (filter != null) {
                //如果配置有过滤器，通过过滤器进行一次筛选
                servers = filter.getFilteredListOfServers(servers);
                LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                        getIdentifier(), servers);
            }
        }
        //将获取到地可用的Servers更新到LoadBalancer中（super.setServersList(lsrv);）
        updateAllServerList(servers);
    }
}
```
##5: 总结：
1. DynamicServerListLoadBalancer是一个ILoadBalancer的实现类，使用策略模式实现了从动态源获取服务器列表的功能（比如eureka中），并可以附带一个过滤器。
2. ServerList的eureka实现是从eurekaClient中通过getInstancesByVipAddress方法获取服务列表信息。
3. ServerListUpdater的eureka实现是通过EurekaEventListener来实现动态更新的。