## Filter Overview

At the center of Zuul is a series of Filters that are capable of performing a range of actions during the routing of HTTP requests and responses.  
Zuul的中心是一系列过滤器，它们能够在HTTP请求和响应的路由过程中执行一系列操作。  

The following are the key characteristics of a Zuul Filter:

* __Type__: most often defines the stage during the routing flow when the Filter will be applied (although it can be any custom string)
* __Execution Order__: applied within the Type, defines the order of execution across multiple Filters
* __Criteria__: the conditions required in order for the Filter to be executed
* __Action__: the action to be executed if the Criteria is met

以下是Zuul过滤器的关键特性:  
* __Type__: most often defines the stage during the routing flow when the Filter will be applied (although it can be any custom string)
* __Execution Order__: 应用于类型中，定义跨多个过滤器的执行顺序
* __Criteria__: 执行过滤器所需的条件
* __Action__: 满足条件时要执行的操作

Zuul provides a framework to dynamically read, compile, and run these Filters. Filters do not communicate with each other directly - instead they share state through a RequestContext which is unique to each request.  
Zuul提供了一个框架来动态地读取、编译和运行这些过滤器。过滤器不直接相互通信，而是通过每个请求所特有的RequestContext共享状态。  

Filters are currently written in Groovy, although Zuul supports any JVM-based language. The source code for each Filter is written to a specified set of directories on the Zuul server that are periodically polled for changes. Updated filters are read from disk, dynamically compiled into the running server, and are invoked by Zuul for each subsequent request.


## Filter Types

There are several standard Filter types that correspond to the typical lifecycle of a request:

* __PRE__ Filters execute before routing to the origin. Examples include request authentication, choosing origin servers, and logging debug info.
* __ROUTING__ Filters handle routing the request to an origin. This is where the origin HTTP request is built and sent using Apache HttpClient or Netflix Ribbon.
* __POST__ Filters execute after the request has been routed to the origin.  Examples include adding standard HTTP headers to the response, gathering statistics and metrics, and streaming the response from the origin to the client.
* __ERROR__ Filters execute when an error occurs during one of the other phases.

Alongside the default Filter flow, Zuul allows us to create custom filter types and execute them explicitly.  For example, we have a custom STATIC type that generates a response within Zuul instead of forwarding the request to an origin.  We have a few use cases for this, one of which is internal endpoints that contain debug data about a particular Zuul instance.


## Zuul Request Lifecycle

![zuul-request-lifecycle.png](http://netflix.github.io/zuul/images/zuul-request-lifecycle.png)