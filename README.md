# docker-akka

A very simple [Docker] container orchestrator based in [Akka] for learning purposes,
written in [Scala].

The orchestrator uses [Akka] as its core, which makes it resilient (fault tolerant),
distributed and scalable (elastic). It also includes Consul for Service Discovery
of the running services.

## Testing

```
sbt stage
mkdir ansible/roles/dockerakka/files/dockerakka
cp -R target/universal/stage/* ansible/roles/dockerakka/files/dockerakka/
cd ansible
vagrant up
ansible-playbook -i hosts playbook.yml
```

When Ansible is finished, you can check the cluster status at http://192.168.33.11:8500

## Overview

![alt tag](docs/Overview.png)

The orchestrator uses [Akka Cluster] to make a cluster of nodes and distribute the
work among them, making the system scalable by adding more nodes to the cluster.
Using [Akka Cluster] also makes it easy to create remote actors in cluster members
and send messages to them.

Each node in the cluster has one **role**, which could be either *master* or *worker*.

For being stateful and fault-tolerant the master nodes have an event journal persisted in
[Cassandra] using [Akka Persistence]. Saving the event journal allow *ServiceMaster* actor
to be moved to another node or recovered from a crash without losing its state, which makes
the system resilient.

The previous diagram shows the architecture of the orchestrator including its actor
system. This architecture will be explained in more detail in the following sections.

## Cluster bootstrapping

The official Akka Cluster [guide](http://doc.akka.io/docs/akka/current/scala/cluster-usage.html) shows how to use
predefined seed nodes for bootstrapping. This nodes conform the cluster and the rest of
the members join them. The problem with this approach is that the seed nodes are
fixed in configuration.
```
cluster {
  seed-nodes = [
    "akka.tcp://ClusterSystem@127.0.0.1:2551",
    "akka.tcp://ClusterSystem@127.0.0.1:2552"]
}
```
For being more dynamic, is possible to leave the seed-nodes property empty and perform
a manual joining by using the method:
```
Cluster(system).joinSeedNodes(seedNodes)
```
Given that, is possible to use a service discovery tool like [Consul] to perform
the cluster bootstrapping by adding some code, as stated in [this reference](http://sap1ens.com/blog/2016/11/12/bootstrapping-akka-cluster-with-consul/).

The [**ConsulBootstrapping**] (src/main/scala/com/github/roberveral/dockerakka/cluster/ConsulBootstrapping.scala) trait has the code for registering, obtaining the seed nodes
and deregistering a docker-akka orchestrator service. For obtaining the seed nodes to use
for joining the cluster from Consul, only healthy masters are taken in care.
```
def getSeedAddresses(implicit config: Config, system: ActorSystem): List[Address] = {
    // Gets a consul agent to interact with the service
    val consul: Consul = getConsulAgent
    // Set query options for Consul
    val queryOpts = ImmutableQueryOptions
      .builder()
      .consistencyMode(ConsistencyMode.CONSISTENT)
      .build()
    // Get registered master nodes (only master nodes are used for bootstrapping)
    val serviceNodes = consul.healthClient().getHealthyServiceInstances("docker-akka-master", queryOpts)

    // Create a list of remote addresses with the healthy master nodes
    serviceNodes.getResponse.toList map { node =>
      Address("akka.tcp", system.name, node.getNode.getAddress, node.getService.getPort)
    }
  }
```
For interacting with Consul, the [orbitz Consul Client library](https://github.com/OrbitzWorldwide/consul-client) is used, which makes it very easy.

In the configuration, this is the input required to accomplish this bootstrapping
strategy:
```
consul {
  host       = "127.0.0.1"
  port       = 8500
}
```

## REST API

In order to use the orchestrator service, each *master* node has a REST API implemented
with [Akka HTTP] to create, scale and destroy services that are run in the cluster.

Type | Endpoint | Description | Parameters
--- | --- | --- | ---
GET | /members | Gets current cluster members | -
GET | /services/:service | Gets detailed info about the running instances of the given service | -
POST | /service/:service | Creates the given service | `{ "image": "docker-image", "ports": ["list of ports to expose"], "instances": "number of instances to start" }`
PUT | /service/:service | Scales the given service | `{ "instances": "new number of instances of the service" }`

## Workflow detail

![alt tag](docs/Workflow.png)

The orchestrator is multi-master. This means that the APIs of all the master nodes
can be used and they give the same results. There is no leader, all the masters
have some workload.

When a request is made against the API, it results in a message sent to the **ServiceScheduler** actor. This actor forwards messages to the corresponding **ShardRegion**.
This is because the ServiceMaster actors (which control the workload associated with
a service) are sharded between the master nodes using [Akka Sharding](http://doc.akka.io/docs/akka/2.4.16/scala/cluster-sharding.html). The ShardRegion,
with the service name embedded in the message will know in which Region (master node) the
Shard that contains this master is placed, and get a reference to forward the request to it.
This is done in a transparent way, in code is only needed to send a message to the ShardRegion actor.
The Sharding procedure will create an actor for the service if it doesn't exist, so it's
important to persist the master actor state to allow the Sharding mechanism moving the Shard to
another node or recovering from a crash.

The **ServiceMaster** actor controls how many instances of the service are running and
in which worker nodes. There is one actor per service running. It controls how the work is sent to the workers and it
resend new work when a worker fall down. A master creates an **AdaptiveLoadBalancingPool**
router which spawns a **ServiceWorker** actor for this service in each worker node.
This means there will be at most one instance running of a service in a worker node.
When the *SerivceMaster* has work to do (instances of the service to run) it send as much
messages as instances remaining to the router. This router uses [Akka Cluster Metrics](http://doc.akka.io/docs/akka/current/scala/cluster-metrics.html)
extension, which means that it will forward the request to the worker node with less
workload using metrics like CPU or memory, resulting in a more fair resource utilization.
The *ServiceMaster* actor then waits for the worker to request that work, or keeps sending
messages to the router until all the instances are running.

The work model is the following:
- The *ServiceMaster* offers the service instance to the router.
- A *ServiceWorker* for the service receives the message and requests the work to the master.
- The *ServiceMaster* accepts the worker if it has instances to run and watch it.
- That *ServiceWorker* starts running the service.

Once the *ServiceMaster* has registered a worker, all the messages like the status are
forwarded to the remote *ServiceWorker* actor without using the router.

The **ServiceWorker** actor could be in two main states: idle or running. A *ServiceWorker* actor
is associated with a concrete service, and there is one actor running in each worker node
for each service (spawned by the router created by the *ServiceMaster* associated with
  each service). This has not performance impact beacuse the Akka actors are very
  cheap in their resource usage. In the *idle* state, the *ServiceWorker* is waiting
  for an offer to run an instance of the service. In the *running* state, it is actually
  running a container of the service. For interacting with [Docker], the [Spotify Docker Client library](https://github.com/spotify/docker-client)
  is used. The worker also registers the new running service in [Consul], for Service Discovery.

A known issue is that when a *ServiceWorker* in running state crashes, the Docker container
and the Consul entry remains active. As a workaround, when this crashed node rejoins the
cluster and a *ServiceWorker* in idle state is created it search first if there is a running
container associated with the worker for the service. In that case, if it changes to running
it simply attached to the previous container. After a defined timeout, if it remains in idle
state it removes the container and the Consul entry (kind of self garbage collection).

[Akka]: http://akka.io/
[Akka Cluster]: http://doc.akka.io/docs/akka/current/common/cluster.html
[Akka Persistence]: http://doc.akka.io/docs/akka/2.4/scala/persistence.html
[Akka HTTP]: http://doc.akka.io/docs/akka-http/current/scala.html
[Scala]: https://www.scala-lang.org/
[Cassandra]: http://cassandra.apache.org/
[Consul]: https://www.consul.io/
[Docker]: https://www.docker.com/
