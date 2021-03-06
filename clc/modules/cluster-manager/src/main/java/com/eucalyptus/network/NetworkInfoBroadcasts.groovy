/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.network

import com.eucalyptus.cluster.Cluster
import com.eucalyptus.cluster.NICluster
import com.eucalyptus.cluster.NIClusters
import com.eucalyptus.cluster.NIConfiguration
import com.eucalyptus.cluster.NIDhcpOptionSet
import com.eucalyptus.cluster.NIInstance
import com.eucalyptus.cluster.NIInternetGateway
import com.eucalyptus.cluster.NIManagedSubnet
import com.eucalyptus.cluster.NIManagedSubnets
import com.eucalyptus.cluster.NIMidonet
import com.eucalyptus.cluster.NIMidonetGateway
import com.eucalyptus.cluster.NIMidonetGateways
import com.eucalyptus.cluster.NINetworkAcl
import com.eucalyptus.cluster.NINetworkAclEntry
import com.eucalyptus.cluster.NINetworkInterface
import com.eucalyptus.cluster.NINode
import com.eucalyptus.cluster.NINodes
import com.eucalyptus.cluster.NIProperty
import com.eucalyptus.cluster.NIRoute
import com.eucalyptus.cluster.NIRouteTable
import com.eucalyptus.cluster.NISecurityGroup
import com.eucalyptus.cluster.NISecurityGroupIpPermission
import com.eucalyptus.cluster.NISubnet
import com.eucalyptus.cluster.NISubnets
import com.eucalyptus.cluster.NIVpc
import com.eucalyptus.cluster.NIVpcSubnet
import com.eucalyptus.cluster.NetworkInfo
import com.eucalyptus.compute.common.internal.network.NetworkGroup
import com.eucalyptus.compute.common.internal.network.NetworkPeer
import com.eucalyptus.compute.common.internal.network.NetworkRule
import com.eucalyptus.compute.common.internal.vm.VmInstance
import com.eucalyptus.compute.common.internal.vm.VmNetworkConfig
import com.eucalyptus.compute.common.internal.vpc.DhcpOption
import com.eucalyptus.compute.common.internal.vpc.DhcpOptionSet
import com.eucalyptus.compute.common.internal.vpc.InternetGateway
import com.eucalyptus.compute.common.internal.vpc.NetworkAcl
import com.eucalyptus.compute.common.internal.vpc.NetworkAclEntry
import com.eucalyptus.compute.common.internal.vpc.NetworkAcls
import com.eucalyptus.compute.common.internal.vpc.NetworkInterface
import com.eucalyptus.compute.common.internal.vpc.Route
import com.eucalyptus.compute.common.internal.vpc.RouteTable
import com.eucalyptus.compute.common.internal.vpc.RouteTableAssociation
import com.eucalyptus.compute.common.internal.vpc.Subnet
import com.eucalyptus.compute.common.internal.vpc.Vpc
import com.eucalyptus.network.config.Cluster as ConfigCluster;
import com.eucalyptus.network.config.EdgeSubnet
import com.eucalyptus.network.config.ManagedSubnet
import com.eucalyptus.network.config.MidonetGateway
import com.eucalyptus.network.config.NetworkConfiguration
import com.eucalyptus.network.config.NetworkConfigurations
import com.eucalyptus.util.TypeMapper
import com.eucalyptus.util.TypeMappers
import com.eucalyptus.util.Strings as EucaStrings;
import com.eucalyptus.vm.VmInstances
import com.google.common.base.Function
import com.google.common.base.Objects
import com.google.common.base.Optional
import com.google.common.base.Predicate
import com.google.common.base.Strings
import com.google.common.base.Supplier
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.ListMultimap
import com.google.common.collect.Lists
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import edu.ucsb.eucalyptus.cloud.NodeInfo
import groovy.transform.Immutable
import groovy.transform.PackageScope
import org.apache.log4j.Logger

import javax.annotation.Nullable

import static com.eucalyptus.compute.common.internal.vm.VmInstance.VmStateSet.TORNDOWN
import static com.google.common.collect.Iterables.tryFind

@PackageScope
class NetworkInfoBroadcasts {

  private static final Logger logger = Logger.getLogger( NetworkInfoBroadcasts )

  @PackageScope
  static NetworkInfo buildNetworkConfiguration( final Optional<NetworkConfiguration> configuration,
                                                final NetworkInfoSource networkInfoSource,
                                                final Supplier<List<Cluster>> clusterSupplier,
                                                final Supplier<String> clcHostSupplier,
                                                final Function<List<String>,List<String>> systemNameserverLookup ) {
    Iterable<Cluster> clusters = clusterSupplier.get( )
    Optional<NetworkConfiguration> networkConfiguration = configuration.isPresent( ) ?
        Optional.of( NetworkConfigurations.explode( configuration.get( ), clusters.collect{ Cluster cluster -> cluster.partition } ) ) :
        configuration
    NetworkInfo info = networkConfiguration
        .transform( TypeMappers.lookup( NetworkConfiguration, NetworkInfo ) )
        .or( new NetworkInfo( ) )
    boolean vpcmido = 'VPCMIDO' == networkConfiguration.orNull()?.mode
    boolean managed = ( ( 'MANAGED' == networkConfiguration.orNull()?.mode ) || ( 'MANAGED-NOVLAN' == networkConfiguration.orNull()?.mode ) )

    // populate clusters
    info.configuration.clusters = new NIClusters(
        name: 'clusters',
        clusters: clusters.findResults{ Cluster cluster ->
          ConfigCluster configCluster = networkConfiguration.orNull()?.clusters?.find{ ConfigCluster configCluster -> cluster.partition == configCluster.name }
          configCluster && ( vpcmido || configCluster.subnet ) ?
              new NICluster(
                  name: configCluster.name,
                  subnet: vpcmido ? new NISubnet(
                      name: '172.31.0.0',
                      properties: [
                          new NIProperty( name: 'subnet', values: [ '172.31.0.0' ]),
                          new NIProperty( name: 'netmask', values: [ '255.255.0.0' ]),
                          new NIProperty( name: 'gateway', values: [ '172.31.0.1' ])
                      ]
                  ) : new NISubnet(
                      name: configCluster.subnet.subnet, // broadcast name is always the subnet value
                      properties: [
                          new NIProperty( name: 'subnet', values: [ configCluster.subnet.subnet ]),
                          new NIProperty( name: 'netmask', values: [ configCluster.subnet.netmask ]),
                          new NIProperty( name: 'gateway', values: [ configCluster.subnet.gateway ])
                      ]
                  ),
                  properties: [
                      new NIProperty( name: 'enabledCCIp', values: [ InetAddress.getByName(cluster.hostName).hostAddress ] ),
                      new NIProperty( name: 'macPrefix', values: [ configCluster.macPrefix ] ),
                      vpcmido ? new NIProperty( name: 'privateIps', values: [ '172.31.0.5' ] ) : new NIProperty( name: 'privateIps', values: configCluster.privateIps )
                  ],
                  nodes: new NINodes(
                      name: 'nodes',
                      nodes: cluster.nodeMap.values().collect{ NodeInfo nodeInfo -> new NINode( name: nodeInfo.name ) }
                  )
              ) :
              configCluster && managed ? new NICluster(
                  name: configCluster.name,
                  properties: [
                      new NIProperty( name: 'enabledCCIp', values: [ InetAddress.getByName(cluster.hostName).hostAddress ] ),
                      new NIProperty( name: 'macPrefix', values: [ configCluster.macPrefix ] )
                  ],
                  nodes: new NINodes(
                      name: 'nodes',
                      nodes: cluster.nodeMap.values().collect{ NodeInfo nodeInfo -> new NINode( name: nodeInfo.name ) }
                  )
              ) :
                  null
        } as List<NICluster>
    )

    // populate dynamic properties
    List<String> dnsServers = networkConfiguration.orNull()?.instanceDnsServers?:systemNameserverLookup.apply(['127.0.0.1'])
    info.configuration.properties.addAll( [
        new NIProperty( name: 'enabledCLCIp', values: [clcHostSupplier.get()]),
        new NIProperty( name: 'instanceDNSDomain', values: [networkConfiguration.orNull()?.instanceDnsDomain?:EucaStrings.trimPrefix('.',"${VmInstances.INSTANCE_SUBDOMAIN}.internal")]),
        new NIProperty( name: 'instanceDNSServers', values: dnsServers )
    ]  )

    boolean hasEdgePublicGateway = networkConfiguration.orNull()?.publicGateway != null
    if ( hasEdgePublicGateway ) {
      info.configuration.properties.add(
          new NIProperty( name: 'publicGateway', values: [networkConfiguration.orNull()?.publicGateway] )
      )
    }

    Iterable<VmInstanceNetworkView> instances = Iterables.filter(
        networkInfoSource.instances,
        { VmInstanceNetworkView instance -> !TORNDOWN.contains(instance.state) && !instance.omit } as Predicate<VmInstanceNetworkView> )

    // populate nodes
    ((Multimap<List<String>,String>) instances.inject( HashMultimap.create( ) ){
      Multimap<List<String>,String> map, VmInstanceNetworkView instance ->
        map.put( [ instance.partition, instance.node ], instance.id )
        map
    }).asMap().each{ Map.Entry<List<String>,Collection<String>> entry ->
      info.configuration.clusters.clusters.find{ NICluster cluster -> cluster.name == entry.key[0] }?.with{
        NINode node = nodes.nodes.find{ NINode node -> node.name == entry.key[1] }
        if ( node ) {
          node.instanceIds = entry.value ? entry.value as List<String> : null
        } else {
          null
        }
      }
    }

    // populate vpcs
    Iterable<VpcNetworkView> vpcs = networkInfoSource.vpcs
    Iterable<SubnetNetworkView> subnets = networkInfoSource.subnets
    Iterable<NetworkAclNetworkView> networkAcls = networkInfoSource.networkAcls
    Iterable<RouteTableNetworkView> routeTables = networkInfoSource.routeTables
    Iterable<InternetGatewayNetworkView> internetGateways = networkInfoSource.internetGateways
    Set<String> activeVpcs = (Set<String>) instances.inject( Sets.newHashSetWithExpectedSize( 500 ) ){
      Set<String> vpcIds, VmInstanceNetworkView instance -> instance.vpcId?.with{ String id -> vpcIds.add(id) }; vpcIds
    }
    Map<String,Collection<String>> vpcIdToInternetGatewayIds = (Map<String,Collection<String>> ) ((ArrayListMultimap<String,String>)internetGateways.inject(ArrayListMultimap.<String,String>create()){
      ListMultimap<String,String> map, InternetGatewayNetworkView internetGateway ->
        if ( internetGateway.vpcId ) map.put( internetGateway.vpcId, internetGateway.id )
        map
    }).asMap( )
    info.vpcs.addAll( vpcs.findAll{ VpcNetworkView vpc -> activeVpcs.contains(vpc.id) }.collect{ VpcNetworkView vpc ->
      new NIVpc(
          vpc.id,
          vpc.ownerAccountNumber,
          vpc.cidr,
          vpc.dhcpOptionSetId,
          subnets.findAll{ SubnetNetworkView subnet -> subnet.vpcId == vpc.id }.collect{ SubnetNetworkView subnet ->
            new NIVpcSubnet(
                name: subnet.id,
                ownerId: subnet.ownerAccountNumber,
                cidr: subnet.cidr,
                cluster: subnet.availabilityZone,
                networkAcl: subnet.networkAcl,
                routeTable:
                    tryFind( routeTables, { RouteTableNetworkView routeTable -> routeTable.subnetIds.contains( subnet.id ) } as Predicate<RouteTableNetworkView>).or(
                        Iterables.find( routeTables, { RouteTableNetworkView routeTable -> routeTable.main && routeTable.vpcId == vpc.id } as Predicate<RouteTableNetworkView> ) ).id
            )
          },
          networkAcls.findAll{ NetworkAclNetworkView networkAcl -> networkAcl.vpcId == vpc.id }.collect { NetworkAclNetworkView networkAcl ->
            new NINetworkAcl(
                name: networkAcl.id,
                ownerId: networkAcl.ownerAccountNumber,
                ingressEntries: Lists.transform( networkAcl.ingressRules, TypeMappers.lookup( NetworkAclEntryNetworkView, NINetworkAclEntry ) ) as List<NINetworkAclEntry>,
                egressEntries: Lists.transform( networkAcl.egressRules, TypeMappers.lookup( NetworkAclEntryNetworkView, NINetworkAclEntry ) ) as List<NINetworkAclEntry>
            )
          },
          routeTables.findAll{ RouteTableNetworkView routeTable -> routeTable.vpcId == vpc.id }.collect { RouteTableNetworkView routeTable ->
            new NIRouteTable(
                name: routeTable.id,
                ownerId: routeTable.ownerAccountNumber,
                routes: Lists.transform( routeTable.routes, TypeMappers.lookup( RouteNetworkView, NIRoute ) ) as List<NIRoute>
            )
          },
          vpcIdToInternetGatewayIds.get( vpc.id ) as List<String>?:[] as List<String>
      )
    } )

    // populate instances
    Iterable<NetworkInterfaceNetworkView> networkInterfaces = networkInfoSource.networkInterfaces
    Map<String,Collection<NetworkInterfaceNetworkView>> instanceIdToNetworkInterfaces = (Map<String,Collection<NetworkInterfaceNetworkView>> ) ((ArrayListMultimap<String,NetworkInterfaceNetworkView>) networkInterfaces.inject(ArrayListMultimap.<String,NetworkInterfaceNetworkView>create()){
      ListMultimap<String,NetworkInterfaceNetworkView> map, NetworkInterfaceNetworkView networkInterface ->
        if ( networkInterface.instanceId ) map.put( networkInterface.instanceId, networkInterface )
        map
    }).asMap( )
    info.instances.addAll( instances.collect{ VmInstanceNetworkView instance ->
      new NIInstance(
          name: instance.id,
          ownerId: instance.ownerAccountNumber,
          vpc: instance.vpcId,
          subnet: instance.subnetId,
          macAddress: Strings.emptyToNull( instance.macAddress ),
          publicIp: VmNetworkConfig.DEFAULT_IP==instance.publicAddress||PublicAddresses.isDirty(instance.publicAddress) ? null : instance.publicAddress,
          privateIp: instance.privateAddress,
          securityGroups: instance.securityGroupIds,
          networkInterfaces: instanceIdToNetworkInterfaces.get( instance.id )?.collect{ NetworkInterfaceNetworkView networkInterface ->
            new NINetworkInterface(
                name: networkInterface.id,
                ownerId: networkInterface.ownerAccountNumber,
                deviceIndex: networkInterface.deviceIndex,
                macAddress: networkInterface.macAddress,
                privateIp: networkInterface.privateIp,
                publicIp: networkInterface.publicIp,
                sourceDestCheck: networkInterface.sourceDestCheck,
                securityGroups: networkInterface.securityGroupIds
            )
          } ?: [ ] as List<NINetworkInterface>
      )
    } )

    // populate dhcp option sets
    Iterable<DhcpOptionSetNetworkView> dhcpOptionSets = networkInfoSource.dhcpOptionSets
    info.dhcpOptionSets.addAll( dhcpOptionSets.collect { DhcpOptionSetNetworkView dhcpOptionSet ->
      new NIDhcpOptionSet(
          name: dhcpOptionSet.id,
          ownerId: dhcpOptionSet.ownerAccountNumber,
          properties: dhcpOptionSet.options.collect{ DhcpOptionNetworkView option ->
            if ( 'domain-name-servers' == option.key && 'AmazonProvidedDNS' == option.values?.getAt( 0 ) ) {
              new NIProperty( 'domain-name-servers', dnsServers )
            } else {
              new NIProperty( option.key, option.values )
            }
          }
      )
    } )

    // populate internet gateways
    info.internetGateways.addAll( internetGateways.findAll{ InternetGatewayNetworkView gateway ->
      activeVpcs.contains(gateway.vpcId)
    }.collect { InternetGatewayNetworkView internetGateway ->
      new NIInternetGateway(
          name: internetGateway.id,
          ownerId: internetGateway.ownerAccountNumber,
      )
    } )

    // populate security groups
    Set<String> activeSecurityGroups = (Set<String>) instances.inject( Sets.newHashSetWithExpectedSize( 1000 ) ){
      Set<String> groups, VmInstanceNetworkView instance -> groups.addAll( instance.securityGroupIds ); groups
    }
    Iterable<NetworkGroupNetworkView> groups = networkInfoSource.securityGroups
    info.securityGroups.addAll( groups.findAll{  NetworkGroupNetworkView group -> activeSecurityGroups.contains( group.id ) }.collect{ NetworkGroupNetworkView group ->
      new NISecurityGroup(
          name: group.id,
          ownerId: group.ownerAccountNumber,
          rules: group.rules,
          ingressRules: group.ingressPermissions.collect{ IPPermissionNetworkView ipPermission ->
            new NISecurityGroupIpPermission(
                ipPermission.protocol,
                ipPermission.fromPort,
                ipPermission.toPort,
                ipPermission.icmpType,
                ipPermission.icmpCode,
                ipPermission.groupId,
                ipPermission.groupOwnerAccountNumber,
                ipPermission.cidr
            )
          } as List<NISecurityGroupIpPermission>,
          egressRules: group.egressPermissions.collect{ IPPermissionNetworkView ipPermission ->
            new NISecurityGroupIpPermission(
                ipPermission.protocol,
                ipPermission.fromPort,
                ipPermission.toPort,
                ipPermission.icmpType,
                ipPermission.icmpCode,
                ipPermission.groupId,
                ipPermission.groupOwnerAccountNumber,
                ipPermission.cidr
            )
          } as List<NISecurityGroupIpPermission>
      )
    } )

    if ( logger.isTraceEnabled( ) ) {
      logger.trace( "Constructed network information for ${Iterables.size( instances )} instance(s), ${Iterables.size( groups )} security group(s)" )
    }

    info
  }


  private static Set<String> explodeRules( NetworkRule networkRule ) {
    Set<String> rules = Sets.newLinkedHashSet( )
    // Only EC2-Classic rules supported by this format
    if ( !networkRule.isVpcOnly( ) ) {
      String rule = "";
      if (networkRule.protocol == null) {
        //Special case where ports are not present, but
        // we support that as an exception to EC2-Classic spec
        rule = String.format("-P %d", networkRule.getProtocolNumber());
      } else {
        rule = String.format(
            "-P %d -%s %d%s%d ",
            networkRule.protocol.getNumber(),
            NetworkRule.Protocol.icmp == networkRule.protocol ? "t" : "p",
            networkRule.lowPort,
            NetworkRule.Protocol.icmp == networkRule.protocol ? ":" : "-",
            networkRule.highPort);
      }
      rules.addAll(networkRule.networkPeers.collect { NetworkPeer peer ->
        String.format("%s -o %s -u %s", rule, peer.groupId, peer.userQueryKey)
      })
      rules.addAll(networkRule.ipRanges.collect { String cidr ->
        String.format("%s -s %s", rule, cidr)
      })
    }
    rules
  }

  private static Set<IPPermissionNetworkView> explodePermissions( NetworkRule networkRule ) {
    Set<IPPermissionNetworkView> rules = Sets.newLinkedHashSet( )

    // Rules without a protocol number are pre-VPC support
    if ( networkRule.getProtocolNumber( ) != null ) {
      NetworkRule.Protocol protocol = networkRule.protocol
      Integer protocolNumber = networkRule.protocolNumber
      Integer fromPort = protocol?.extractLowPort( networkRule )
      Integer toPort = protocol?.extractHighPort( networkRule )
      Integer icmpType = protocol?.extractIcmpType( networkRule )
      Integer icmpCode = protocol?.extractIcmpCode( networkRule )

      rules.addAll( networkRule.networkPeers.collect{ NetworkPeer peer ->
        new IPPermissionNetworkView(
            protocolNumber,
            fromPort,
            toPort,
            icmpType,
            icmpCode,
            peer.groupId,
            peer.userQueryKey,
            null
        )
      } )
      rules.addAll( networkRule.ipRanges.collect{ String cidr ->
        new IPPermissionNetworkView(
            protocolNumber,
            fromPort,
            toPort,
            icmpType,
            icmpCode,
            null,
            null,
            cidr
        )
      } )
    }

    rules
  }

  private static boolean validInstanceMetadata( final VmInstance instance) {
    !Strings.isNullOrEmpty( instance.privateAddress ) &&
        !VmNetworkConfig.DEFAULT_IP.equals( instance.privateAddress ) &&
        !instance.networkGroups.isEmpty( ) &&
        !Strings.isNullOrEmpty( VmInstances.toNodeHost( ).apply( instance ) )
  }

  static interface NetworkInfoSource {
    Iterable<VmInstanceNetworkView> getInstances( );
    Iterable<NetworkGroupNetworkView> getSecurityGroups( );
    Iterable<VpcNetworkView> getVpcs( );
    Iterable<SubnetNetworkView> getSubnets( );
    Iterable<DhcpOptionSetNetworkView> getDhcpOptionSets( );
    Iterable<NetworkAclNetworkView> getNetworkAcls( );
    Iterable<RouteTableNetworkView> getRouteTables( );
    Iterable<InternetGatewayNetworkView> getInternetGateways( );
    Iterable<NetworkInterfaceNetworkView> getNetworkInterfaces( );
    Map<String,Iterable<? extends VersionedNetworkView>> getView( );
  }

  @TypeMapper
  enum NetworkConfigurationToNetworkInfo implements Function<NetworkConfiguration, NetworkInfo> {
    INSTANCE;

    @Override
    NetworkInfo apply( final NetworkConfiguration networkConfiguration ) {
      ManagedSubnet managedSubnet = networkConfiguration.managedSubnet
      new NetworkInfo(
          configuration: new NIConfiguration(
              properties: [
                  new NIProperty( name: 'mode', values: [ networkConfiguration.mode ?: 'EDGE' ] ),
                  networkConfiguration.publicIps ?
                      new NIProperty( name: 'publicIps', values: networkConfiguration.publicIps ) :
                      null
              ].findAll( ) as List<NIProperty>,
              midonet: networkConfiguration?.mido ? new NIMidonet(
                  name: "mido",
                  gateways: networkConfiguration?.mido?.gatewayHost ?
                      new NIMidonetGateways(
                          name: 'gateways',
                          gateways : [
                              new NIMidonetGateway(
                                  properties: [
                                      networkConfiguration?.mido?.gatewayHost ?
                                          new NIProperty(
                                              name: 'gatewayHost',
                                              values: [ networkConfiguration.mido.gatewayHost ] ) :
                                          null,
                                      networkConfiguration?.mido?.gatewayIP ?
                                          new NIProperty(
                                              name: 'gatewayIP',
                                              values: [ networkConfiguration.mido.gatewayIP ] ) :
                                          null,
                                      networkConfiguration?.mido?.gatewayInterface ?
                                          new NIProperty(
                                              name: 'gatewayInterface',
                                              values: [ networkConfiguration.mido.gatewayInterface ] ) :
                                          null,
                                  ].findAll( ) as List<NIProperty>,
                              )
                          ]
                      ) :
                      networkConfiguration?.mido?.gateways ?
                          new NIMidonetGateways(
                              name: 'gateways',
                              gateways : networkConfiguration?.mido?.gateways?.collect{ MidonetGateway gateway ->
                                new NIMidonetGateway(
                                    properties: [
                                        new NIProperty( name: 'gatewayHost', values: [ gateway.gatewayHost ] ),
                                        new NIProperty( name: 'gatewayIP', values: [ gateway.gatewayIP ] ),
                                        new NIProperty( name: 'gatewayInterface', values: [ gateway.gatewayInterface ] ),
                                    ].findAll( ) as List<NIProperty>,
                                )
                              } as List<NIMidonetGateway>
                          ) :
                          null,
                  properties: [
                      networkConfiguration?.mido?.eucanetdHost ?
                          new NIProperty( name: 'eucanetdHost', values: [ networkConfiguration.mido.eucanetdHost ] ) :
                          null,
                      networkConfiguration?.mido?.publicNetworkCidr ?
                          new NIProperty( name: 'publicNetworkCidr', values: [ networkConfiguration.mido.publicNetworkCidr ] ) :
                          null,
                      networkConfiguration?.mido?.publicGatewayIP ?
                          new NIProperty( name: 'publicGatewayIP', values: [ networkConfiguration.mido.publicGatewayIP ] ) :
                          null,
                  ].findAll( ) as List<NIProperty>,
              ) : null,
              subnets: networkConfiguration.subnets ? new NISubnets(
                  name: "subnets",
                  subnets: networkConfiguration.subnets.collect{ EdgeSubnet subnet ->
                    new NISubnet(
                        name: subnet.subnet,  // broadcast name is always the subnet value
                        properties: [
                            new NIProperty( name: 'subnet', values: [ subnet.subnet ]),
                            new NIProperty( name: 'netmask', values: [ subnet.netmask ]),
                            new NIProperty( name: 'gateway', values: [ subnet.gateway ])
                        ]
                    )
                  }
              ) : null,
              managedSubnet: managedSubnet ? new NIManagedSubnets(
                  name: "managedSubnet",
                  managedSubnet: new NIManagedSubnet(
                      name: managedSubnet.subnet,  // broadcast name is always the subnet value
                      properties: [
                          new NIProperty( name: 'subnet', values: [ managedSubnet.subnet ] ),
                          new NIProperty( name: 'netmask', values: [ managedSubnet.netmask ] ),
                          new NIProperty( name: 'minVlan', values: [ ( managedSubnet.minVlan ?: ManagedSubnet.MIN_VLAN )  as String ] ),
                          new NIProperty( name: 'maxVlan', values: [ ( managedSubnet.maxVlan ?: ManagedSubnet.MAX_VLAN ) as String ] ),
                          new NIProperty( name: 'segmentSize', values: [ ( managedSubnet.segmentSize ?: ManagedSubnet.DEF_SEGMENT_SIZE ) as String ] )
                      ]
                  )
              ) : null
          )
      )
    }
  }

  interface VersionedNetworkView {
    String getId( )
    int getVersion( )
  }

  @Immutable
  static class VmInstanceNetworkView implements Comparable<VmInstanceNetworkView>, VersionedNetworkView {
    String id
    int version
    VmInstance.VmState state
    Boolean omit
    String ownerAccountNumber
    String vpcId
    String subnetId
    String macAddress
    String privateAddress
    String publicAddress
    String partition
    String node
    List<String> securityGroupIds

    int compareTo( VmInstanceNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum VmInstanceToVmInstanceNetworkView implements Function<VmInstance,VmInstanceNetworkView> {
    INSTANCE;

    @Override
    VmInstanceNetworkView apply( final VmInstance instance ) {
      new VmInstanceNetworkView(
          instance.instanceId,
          instance.version,
          instance.state,
          Objects.firstNonNull( instance.runtimeState.zombie, false ) || !validInstanceMetadata( instance ),
          instance.ownerAccountNumber,
          instance.bootRecord.vpcId,
          instance.bootRecord.subnetId,
          instance.macAddress,
          instance.privateAddress,
          instance.publicAddress,
          instance.partition,
          Strings.nullToEmpty( VmInstances.toNodeHost( ).apply( instance ) ),
          instance.networkGroups.collect{ NetworkGroup group -> group.groupId }
      )
    }
  }

  @Immutable
  static class IPPermissionNetworkView {
    Integer protocol
    Integer fromPort
    Integer toPort
    Integer icmpType
    Integer icmpCode
    String groupId
    String groupOwnerAccountNumber
    String cidr

    boolean equals(final o) {
      if (this.is(o)) return true
      if (getClass() != o.class) return false

      final IPPermissionNetworkView that = (IPPermissionNetworkView) o

      if (cidr != that.cidr) return false
      if (fromPort != that.fromPort) return false
      if (groupId != that.groupId) return false
      if (groupOwnerAccountNumber != that.groupOwnerAccountNumber) return false
      if (icmpCode != that.icmpCode) return false
      if (icmpType != that.icmpType) return false
      if (protocol != that.protocol) return false
      if (toPort != that.toPort) return false

      return true
    }

    int hashCode() {
      int result
      result = (protocol != null ? protocol.hashCode() : 0)
      result = 31 * result + (fromPort != null ? fromPort.hashCode() : 0)
      result = 31 * result + (toPort != null ? toPort.hashCode() : 0)
      result = 31 * result + (icmpType != null ? icmpType.hashCode() : 0)
      result = 31 * result + (icmpCode != null ? icmpCode.hashCode() : 0)
      result = 31 * result + (groupId != null ? groupId.hashCode() : 0)
      result = 31 * result + (groupOwnerAccountNumber != null ? groupOwnerAccountNumber.hashCode() : 0)
      result = 31 * result + (cidr != null ? cidr.hashCode() : 0)
      return result
    }
  }

  @Immutable
  static class NetworkGroupNetworkView implements Comparable<NetworkGroupNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    List<String> rules
    List<IPPermissionNetworkView> ingressPermissions
    List<IPPermissionNetworkView> egressPermissions

    int compareTo( NetworkGroupNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum NetworkGroupToNetworkGroupNetworkView implements Function<NetworkGroup,NetworkGroupNetworkView> {
    INSTANCE;

    @SuppressWarnings("UnnecessaryQualifiedReference")
    @Override
    NetworkGroupNetworkView apply( final NetworkGroup group ) {
      new NetworkGroupNetworkView(
          group.groupId,
          group.version,
          group.ownerAccountNumber,
          group.ingressNetworkRules.collect{ NetworkRule networkRule -> NetworkInfoBroadcasts.explodeRules( networkRule ) }.flatten( ) as List<String>,
          group.ingressNetworkRules.collect{ NetworkRule networkRule -> NetworkInfoBroadcasts.explodePermissions( networkRule ) }.flatten( ) as List<IPPermissionNetworkView>,
          group.egressNetworkRules.collect{ NetworkRule networkRule -> NetworkInfoBroadcasts.explodePermissions( networkRule ) }.flatten( ) as List<IPPermissionNetworkView>
      )
    }
  }

  @Immutable
  static class VpcNetworkView implements Comparable<VpcNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String cidr
    String dhcpOptionSetId

    int compareTo( VpcNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum VpcToVpcNetworkView implements Function<Vpc,VpcNetworkView> {
    INSTANCE;

    @Override
    VpcNetworkView apply( final Vpc vpc ) {
      new VpcNetworkView(
          vpc.displayName,
          vpc.version,
          vpc.ownerAccountNumber,
          vpc.cidr,
          vpc.dhcpOptionSet.displayName
      )
    }
  }

  @Immutable
  static class SubnetNetworkView implements Comparable<SubnetNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String vpcId
    String cidr
    String availabilityZone
    String networkAcl

    int compareTo( SubnetNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum SubnetToSubnetNetworkView implements Function<Subnet,SubnetNetworkView> {
    INSTANCE;

    @Override
    SubnetNetworkView apply( final Subnet subnet ) {
      new SubnetNetworkView(
          subnet.displayName,
          subnet.version,
          subnet.ownerAccountNumber,
          subnet.vpc.displayName,
          subnet.cidr,
          subnet.availabilityZone,
          subnet.networkAcl.displayName
      )
    }
  }

  @Immutable
  static class DhcpOptionSetNetworkView implements Comparable<DhcpOptionSetNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    List<DhcpOptionNetworkView> options

    int compareTo( DhcpOptionSetNetworkView o ) {
      this.id <=> o.id
    }
  }

  @Immutable
  static class DhcpOptionNetworkView {
    String key
    List<String> values
  }

  @TypeMapper
  enum DhcpOptionSetToDhcpOptionSetNetworkView implements Function<DhcpOptionSet,DhcpOptionSetNetworkView> {
    INSTANCE;

    @Override
    DhcpOptionSetNetworkView apply( final DhcpOptionSet dhcpOptionSet ) {
      new DhcpOptionSetNetworkView(
          dhcpOptionSet.displayName,
          dhcpOptionSet.version,
          dhcpOptionSet.ownerAccountNumber,
          ImmutableList.copyOf( dhcpOptionSet.dhcpOptions.collect{ DhcpOption option -> new DhcpOptionNetworkView(
              option.key,
              ImmutableList.copyOf( option.values )
          ) } )
      )
    }
  }

  @Immutable
  static class NetworkAclNetworkView implements Comparable<NetworkAclNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String vpcId
    List<NetworkAclEntryNetworkView> ingressRules
    List<NetworkAclEntryNetworkView> egressRules

    int compareTo( NetworkAclNetworkView o ) {
      this.id <=> o.id
    }
  }

  @Immutable
  static class NetworkAclEntryNetworkView {
    Integer number
    Integer protocol
    String action
    String cidr
    Integer icmpCode
    Integer icmpType
    Integer portRangeFrom
    Integer portRangeTo
  }

  @TypeMapper
  enum NetworkAclToNetworkAclNetworkView implements Function<NetworkAcl,NetworkAclNetworkView> {
    INSTANCE;

    @Override
    NetworkAclNetworkView apply( final NetworkAcl networkAcl ) {
      List<NetworkAclEntry> orderedEntries = NetworkAcls.ENTRY_ORDERING.sortedCopy( networkAcl.entries )
      new NetworkAclNetworkView(
          networkAcl.displayName,
          networkAcl.version,
          networkAcl.ownerAccountNumber,
          networkAcl.vpc.displayName,
          ImmutableList.copyOf( orderedEntries.findAll{ NetworkAclEntry entry -> !entry.egress }.collect{ NetworkAclEntry entry -> TypeMappers.transform( entry, NetworkAclEntryNetworkView  ) } ),
          ImmutableList.copyOf( orderedEntries.findAll{ NetworkAclEntry entry -> entry.egress }.collect{ NetworkAclEntry entry -> TypeMappers.transform( entry, NetworkAclEntryNetworkView  ) } )
      )
    }
  }

  @TypeMapper
  enum NetworkAclEntryToNetworkAclEntryNetworkView implements Function<NetworkAclEntry,NetworkAclEntryNetworkView> {
    INSTANCE;

    @Override
    NetworkAclEntryNetworkView apply( final NetworkAclEntry networkAclEntry ) {
      new NetworkAclEntryNetworkView(
          networkAclEntry.ruleNumber,
          networkAclEntry.protocol,
          String.valueOf( networkAclEntry.ruleAction ),
          networkAclEntry.cidr,
          networkAclEntry.icmpCode,
          networkAclEntry.icmpType,
          networkAclEntry.portRangeFrom,
          networkAclEntry.portRangeTo
      )
    }
  }

  @TypeMapper
  enum NetworkAclEntryNetworkViewToNINetworkAclRule implements Function<NetworkAclEntryNetworkView,NINetworkAclEntry> {
    INSTANCE;

    @Override
    NINetworkAclEntry apply(@Nullable final NetworkAclEntryNetworkView networkAclEntry ) {
      new NINetworkAclEntry(
          networkAclEntry.number,
          networkAclEntry.protocol,
          networkAclEntry.action,
          networkAclEntry.cidr,
          networkAclEntry.icmpCode,
          networkAclEntry.icmpType,
          networkAclEntry.portRangeFrom,
          networkAclEntry.portRangeTo
      )
    }
  }

  @Immutable
  static class RouteTableNetworkView implements Comparable<RouteTableNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String vpcId
    boolean main
    List<String> subnetIds // associated subnets
    List<RouteNetworkView> routes

    int compareTo( RouteTableNetworkView o ) {
      this.id <=> o.id
    }
  }

  @Immutable
  static class RouteNetworkView {
    String destinationCidr
    String gatewayId
  }

  @TypeMapper
  enum RouteTableToRouteTableNetworkView implements Function<RouteTable,RouteTableNetworkView> {
    INSTANCE;

    @Override
    RouteTableNetworkView apply( final RouteTable routeTable ) {
      new RouteTableNetworkView(
          routeTable.displayName,
          routeTable.version,
          routeTable.ownerAccountNumber,
          routeTable.vpc.displayName,
          routeTable.main,
          ImmutableList.copyOf( routeTable.routeTableAssociations.findResults{ RouteTableAssociation association -> association.subnetId } as Collection<String> ),
          ImmutableList.copyOf( routeTable.routes.collect{ Route route -> TypeMappers.transform( route, RouteNetworkView ) } ),
      )
    }
  }

  @TypeMapper
  enum RouteToRouteNetworkView implements Function<Route,RouteNetworkView> {
    INSTANCE;

    @Override
    RouteNetworkView apply(@Nullable final Route route) {
      new RouteNetworkView(
          route.destinationCidr,
          route.getInternetGateway()?.displayName ?: 'local'
      )
    }
  }

  @TypeMapper
  enum RouteNetworkViewToNIRoute implements Function<RouteNetworkView,NIRoute> {
    INSTANCE;

    @Override
    NIRoute apply(@Nullable final RouteNetworkView routeNetworkView) {
      new NIRoute(
          routeNetworkView.destinationCidr,
          routeNetworkView.gatewayId
      )
    }
  }

  @Immutable
  static class InternetGatewayNetworkView implements Comparable<InternetGatewayNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String vpcId

    int compareTo( InternetGatewayNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum InternetGatewayToInternetGatewayNetworkView implements Function<InternetGateway,InternetGatewayNetworkView> {
    INSTANCE;

    @Override
    InternetGatewayNetworkView apply( final InternetGateway internetGateway ) {
      new InternetGatewayNetworkView(
          internetGateway.displayName,
          internetGateway.version,
          internetGateway.ownerAccountNumber,
          internetGateway.vpc?.displayName
      )
    }
  }

  @Immutable
  static class NetworkInterfaceNetworkView implements Comparable<NetworkInterfaceNetworkView>, VersionedNetworkView {
    String id
    int version
    String ownerAccountNumber
    String instanceId
    Integer deviceIndex
    String macAddress
    String privateIp
    String publicIp
    Boolean sourceDestCheck
    List<String> securityGroupIds

    int compareTo( NetworkInterfaceNetworkView o ) {
      this.id <=> o.id
    }
  }

  @TypeMapper
  enum VpcNetworkInterfaceToNetworkInterfaceNetworkView implements Function<NetworkInterface,NetworkInterfaceNetworkView> {
    INSTANCE;

    @Override
    NetworkInterfaceNetworkView apply( final NetworkInterface networkInterface ) {
      new NetworkInterfaceNetworkView(
          networkInterface.displayName,
          networkInterface.version,
          networkInterface.ownerAccountNumber,
          networkInterface.attachment?.instanceId,
          networkInterface.attachment?.deviceIndex,
          networkInterface.macAddress,
          networkInterface.privateIpAddress,
          networkInterface?.association?.publicIp,
          networkInterface.sourceDestCheck,
          networkInterface.networkGroups.collect{ NetworkGroup group -> group.groupId }
      )
    }
  }
}
