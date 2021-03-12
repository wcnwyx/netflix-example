##接口ILoadBalancer
```java
/**
 * Interface that defines the operations for a software loadbalancer. A typical
 * loadbalancer minimally need a set of servers to loadbalance for, a method to
 * mark a particular server to be out of rotation and a call that will choose a
 * server from the existing list of server.
 * 
 * 定义软件负载均衡器操作的接口。
 * 典型的负载均衡器至少需要一组服务去实现负载均衡，一个标记指定服务不可用的方法
 * 和一个从现有服务列表中选择一个服务的方法。
 */
public interface ILoadBalancer {

	/**
	 * Initial list of servers.
	 * This API also serves to add additional ones at a later time
	 * The same logical server (host:port) could essentially be added multiple times
	 * (helpful in cases where you want to give more "weightage" perhaps ..)
	 * 
	 * 初始化服务列表。
     * 这个APi也提供后期添加额外的数据。
     * 同一逻辑服务器（host:port一样）本质上可以添加多次
     * （在你想赋予更多的权重的时候很有帮助）
	 */
	public void addServers(List<Server> newServers);
	
	/**
	 * Choose a server from load balancer.
	 * 
     * 从负载均衡器里选择一个服务。
     * 负载均衡器用该key确认返回哪个服务。可以为null   
	 */
	public Server chooseServer(Object key);
	
	/**
	 * To be called by the clients of the load balancer to notify that a Server is down
	 * else, the LB will think its still Alive until the next Ping cycle - potentially
	 * (assuming that the LB Impl does a ping)
	 * 
     * 以负载均衡器的客户端调用通知一个服务已下线，
     * 知道下一个Ping周期负载均衡器认为其仍然有效
     * （假设该负载均衡器实现了Ping）
     * 
	 */
	public void markServerDown(Server server);
	
	/**
	 * @return Only the servers that are up and reachable.
     * 只返回up状态并且可达的服务
     */
    public List<Server> getReachableServers();

    /**
     * @return All known servers, both reachable and unreachable.
     * 返回所有一直的服务，包括可达的和不可达的。
     */
	public List<Server> getAllServers();
}
```

##抽象实现类AbstractLoadBalancer
```java
/**
 * AbstractLoadBalancer contains features required for most loadbalancing
 * implementations.
 * 
 * AbstractLoadBalancer包含大多数负载均衡所需要实现的功能。
 * 
 * An anatomy of a typical LoadBalancer consists of 1. A List of Servers (nodes)
 * that are potentially bucketed based on a specific criteria. 2. A Class that
 * defines and implements a LoadBalacing Strategy via <code>IRule</code> 3. A
 * Class that defines and implements a mechanism to determine the
 * suitability/availability of the nodes/servers in the List.
 * 
 * 典型的负载均衡器解剖结构包括：
 * 1. 根据特定条件可能进行存储的服务器（节点）列表。
 * 2. 一个通过IRule定义和实现负载均衡策略的类。
 * 3. 一个类其定义并实现一种机制来确定一组节点/服务器的实用性/可用性
 */
public abstract class AbstractLoadBalancer implements ILoadBalancer {
    
    //根据状态定义了一个服务组的概念
    public enum ServerGroup{
        ALL,
        STATUS_UP,
        STATUS_NOT_UP        
    }
        
    /**
     * delegate to {@link #chooseServer(Object)} with parameter null.
     * 以null参数委托接口的chooseServer（Object）方法
     */
    public Server chooseServer() {
    	return chooseServer(null);
    }

    
    /**
     * List of servers that this Loadbalancer knows about
     * 根据ServerGroup获取一组服务
     */
    public abstract List<Server> getServerList(ServerGroup serverGroup);
    
    /**
     * Obtain LoadBalancer related Statistics
     * 获得与负载均衡器相关的统计数据
     */
    public abstract LoadBalancerStats getLoadBalancerStats();    
}
```

##基础实现类BaseLoadBalancer-1：接口ILoadBalancer和父类AbstractLoadBalancer的实现逻辑
```java
/**
 * A basic implementation of the load balancer where an arbitrary list of
 * servers can be set as the server pool. A ping can be set to determine the
 * liveness of a server. Internally, this class maintains an "all" server list
 * and an "up" server list and use them depending on what the caller asks for.
 * 
 * 一个负载均衡器的基础实现，可以将任意服务器列表设置为服务器池。
 * 可以设置ping来确认服务器的存活状态。
 * 在内部，此类维护了一个“all”服务器列表和一个“up”服务器列表，并根据调用者的要求来使用他们。
 */
public class BaseLoadBalancer extends AbstractLoadBalancer implements
        PrimeConnections.PrimeConnectionListener, IClientConfigAware {

    //给负载均衡器起了个名字
    private static final String DEFAULT_NAME = "default";
    protected String name = DEFAULT_NAME;
    
    //路由规则，默认使用的是轮询算法的规则
    private final static IRule DEFAULT_RULE = new RoundRobinRule();
    protected IRule rule = DEFAULT_RULE;
    
    //存储着所有的服务的列表，@Monitor为netflix-servo的监控
    @Monitor(name = PREFIX + "AllServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> allServerList = Collections
            .synchronizedList(new ArrayList<Server>());
    
    //存储UP状态的所有服务列表，并且被监控
    @Monitor(name = PREFIX + "UpServerList", type = DataSourceType.INFORMATIONAL)
    protected volatile List<Server> upServerList = Collections
            .synchronizedList(new ArrayList<Server>());

    //读写锁来控制all和up集合的操作
    protected ReadWriteLock allServerLock = new ReentrantReadWriteLock();
    protected ReadWriteLock upServerLock = new ReentrantReadWriteLock();

    //netflix-servo的一个监控计数器，用于统计ChooseServer的次数
    private volatile Counter counter = Monitors.newCounter("LoadBalancer_ChooseServer");
    
    //记录着当前负载均衡器的一些状态统计
    protected LoadBalancerStats lbStats;
    
    
    @Override
    public void addServers(List<Server> newServers) {
        if (newServers != null && newServers.size() > 0) {
            try {
                ArrayList<Server> newList = new ArrayList<Server>();
                newList.addAll(allServerList);
                newList.addAll(newServers);
                setServersList(newList);
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Exception while adding Servers", name, e);
            }
        }
    }

    @Override
    public Server chooseServer(Object key) {
        //计算器统计数据
        if (counter == null) {
            counter = createCounter();
        }
        counter.increment();
        if (rule == null) {
            return null;
        } else {
            try {
                //通过调用路由规则来选择一个服务（默认是轮询规则）
                return rule.choose(key);
            } catch (Exception e) {
                logger.warn("LoadBalancer [{}]:  Error choosing server for key {}", name, key, e);
                return null;
            }
        }
    }

    @Override
    public void markServerDown(Server server) {
        if (server == null || !server.isAlive()) {
            return;
        }
        logger.error("LoadBalancer [{}]:  markServerDown called on [{}]", name, server.getId());
        //通过alive属性来表示up或者down
        server.setAlive(false);
        // forceQuickPing();
        
        //通知状态变更的监听器
        notifyServerStatusChangeListener(singleton(server));
    }

    @Override
    public List<Server> getReachableServers() {
        return Collections.unmodifiableList(upServerList);
    }

    @Override
    public List<Server> getAllServers() {
        return Collections.unmodifiableList(allServerList);
    }

    @Override
    public List<Server> getServerList(ServerGroup serverGroup) {
        switch (serverGroup) {
            case ALL:
                return allServerList;
            case STATUS_UP:
                return upServerList;
            case STATUS_NOT_UP:
                //ALL和UP的差集
                ArrayList<Server> notAvailableServers = new ArrayList<Server>(
                        allServerList);
                ArrayList<Server> upServers = new ArrayList<Server>(upServerList);
                notAvailableServers.removeAll(upServers);
                return notAvailableServers;
        }
        return new ArrayList<Server>();
    }

    @Override
    public LoadBalancerStats getLoadBalancerStats() {
        return lbStats;
    }
}
```

##基础实现类BaseLoadBalancer-2： PING和监听器逻辑
```java
public class BaseLoadBalancer extends AbstractLoadBalancer implements
        PrimeConnections.PrimeConnectionListener, IClientConfigAware {
    
    //定义了ping实现策略，默认是SerialPingStrategy，连续的执行ping
    private final static SerialPingStrategy DEFAULT_PING_STRATEGY = new SerialPingStrategy();
    protected IPingStrategy pingStrategy = DEFAULT_PING_STRATEGY;

    //ping动作的具体实现，比如说执行一次http请求，根据响应码来判断是否alive
    protected IPing ping = null;

    //执行ping的定时器
    protected Timer lbTimer = null;

    //控制ping不被同时多次执行
    protected AtomicBoolean pingInProgress = new AtomicBoolean(false);

    //allServer集合有变化时的监听器结合
    private List<ServerListChangeListener> changeListeners = new CopyOnWriteArrayList<ServerListChangeListener>();

    //服务状态变更的监听器集合
    private List<ServerStatusChangeListener> serverStatusListeners = new CopyOnWriteArrayList<ServerStatusChangeListener>();

    public BaseLoadBalancer(String name, IRule rule, LoadBalancerStats stats,
                            IPing ping, IPingStrategy pingStrategy) {
        this.name = name;
        this.ping = ping;
        this.pingStrategy = pingStrategy;
        setRule(rule);
        setupPingTask();
        lbStats = stats;
        init();
    }

    /**
     * Register with monitors and start priming connections if it is set.
     * 注册netflix-servo监控数据
     */
    protected void init() {
        Monitors.registerObject("LoadBalancer_" + name, this);
        // register the rule as it contains metric for available servers count
        Monitors.registerObject("Rule_" + name, this.getRule());
        if (enablePrimingConnections && primeConnections != null) {
            primeConnections.primeConnections(getReachableServers());
        }
    }

    //启动ping任务
    void setupPingTask() {
        if (canSkipPing()) {
            return;
        }
        if (lbTimer != null) {
            lbTimer.cancel();
        }
        lbTimer = new ShutdownEnabledTimer("NFLoadBalancer-PingTimer-" + name,
                true);
        lbTimer.schedule(new PingTask(), 0, pingIntervalSeconds * 1000);
        //强制执行一次ping
        forceQuickPing();
    }

    //检查是否可以跳过ping
    private boolean canSkipPing() {
        if (ping == null
                || ping.getClass().getName().equals(DummyPing.class.getName())) {
            //DummyPing是直接写死的返回true的
            return true;
        } else {
            return false;
        }
    }

    //强制执行ping
    public void forceQuickPing() {
        if (canSkipPing()) {
            return;
        }
        logger.debug("LoadBalancer [{}]:  forceQuickPing invoking", name);

        try {
            new Pinger(pingStrategy).runPinger();
        } catch (Exception e) {
            logger.error("LoadBalancer [{}]: Error running forceQuickPing()", name, e);
        }
    }

    class PingTask extends TimerTask {
        public void run() {
            try {
                new Pinger(pingStrategy).runPinger();
            } catch (Exception e) {
                logger.error("LoadBalancer [{}]: Error pinging", name, e);
            }
        }
    }

    //服务状态变更，通知给所有的监听器
    private void notifyServerStatusChangeListener(final Collection<Server> changedServers) {
        if (changedServers != null && !changedServers.isEmpty() && !serverStatusListeners.isEmpty()) {
            for (ServerStatusChangeListener listener : serverStatusListeners) {
                try {
                    listener.serverStatusChanged(changedServers);
                } catch (Exception e) {
                    logger.error("LoadBalancer [{}]: Error invoking server status change listener", name, e);
                }
            }
        }
    }

    /**
     * Class that contains the mechanism to "ping" all the instances
     * 封装了ping所有实例的机制。
     */
    class Pinger {

        private final IPingStrategy pingerStrategy;

        public Pinger(IPingStrategy pingerStrategy) {
            this.pingerStrategy = pingerStrategy;
        }

        //调用IPingStrategy执行ping操作，根据ping结果来更新allServer和upServer的数据
        public void runPinger() throws Exception {
            //判断ping是否在执行中
            if (!pingInProgress.compareAndSet(false, true)) {
                return; // Ping in progress - nothing to do
            }

            // we are "in" - we get to Ping

            Server[] allServers = null;
            boolean[] results = null;

            Lock allLock = null;
            Lock upLock = null;

            try {
                /*
                 * The readLock should be free unless an addServer operation is
                 * going on...
                 */
                allLock = allServerLock.readLock();
                allLock.lock();
                allServers = allServerList.toArray(new Server[allServerList.size()]);
                allLock.unlock();

                int numCandidates = allServers.length;
                results = pingerStrategy.pingServers(ping, allServers);

                final List<Server> newUpList = new ArrayList<Server>();
                //记录所有本次ping之后isAlive有变化的服务
                final List<Server> changedServers = new ArrayList<Server>();

                for (int i = 0; i < numCandidates; i++) {
                    boolean isAlive = results[i];
                    Server svr = allServers[i];
                    boolean oldIsAlive = svr.isAlive();

                    svr.setAlive(isAlive);

                    if (oldIsAlive != isAlive) {
                        changedServers.add(svr);
                        logger.debug("LoadBalancer [{}]:  Server [{}] status changed to {}",
                                name, svr.getId(), (isAlive ? "ALIVE" : "DEAD"));
                    }

                    if (isAlive) {
                        newUpList.add(svr);
                    }
                }
                upLock = upServerLock.writeLock();
                upLock.lock();
                upServerList = newUpList;
                upLock.unlock();

                notifyServerStatusChangeListener(changedServers);
            } finally {
                pingInProgress.set(false);
            }
        }
    }
    
    /**
     * Default implementation for <c>IPingStrategy</c>, performs ping
     * serially, which may not be desirable, if your <c>IPing</c>
     * implementation is slow, or you have large number of servers.
     * ping策略的默认实现，连续的执行ping，如果ping过慢或者有大量的服务器，这个实现策略将不太理想。
     */
    private static class SerialPingStrategy implements IPingStrategy {

        @Override
        public boolean[] pingServers(IPing ping, Server[] servers) {
            int numCandidates = servers.length;
            boolean[] results = new boolean[numCandidates];

            logger.debug("LoadBalancer:  PingTask executing [{}] servers configured", numCandidates);

            for (int i = 0; i < numCandidates; i++) {
                //默认是不成功的响应
                results[i] = false;
                try {
                    if (ping != null) {
                        //IPing.isAlive是具体执行的ping操作了，不再细看
                        results[i] = ping.isAlive(servers[i]);
                    }
                } catch (Exception e) {
                    logger.error("Exception while pinging Server: '{}'", servers[i], e);
                }
            }
            return results;
        }
    }
}
```