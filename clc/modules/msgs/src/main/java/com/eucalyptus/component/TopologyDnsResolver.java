/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses/.
 * 
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 * 
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 * 
 * Software License Agreement (BSD License)
 * 
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use of this software in source and binary forms,
 * with or without modification, are permitted provided that the
 * following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 * THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 * COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 * AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 * IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 * SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 * WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 * REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 * IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 * NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.component;

import static com.eucalyptus.util.dns.DnsResolvers.DnsRequest;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;

import com.eucalyptus.bootstrap.Host;
import com.eucalyptus.bootstrap.Hosts;
import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.configurable.ConfigurableField;
import com.eucalyptus.util.Cidr;
import com.eucalyptus.util.CollectionUtils;
import com.eucalyptus.util.dns.DnsResolvers.DnsResolver;
import com.eucalyptus.util.dns.DnsResolvers.DnsResponse;
import com.eucalyptus.util.dns.DnsResolvers.RequestType;
import com.eucalyptus.util.dns.DomainNameRecords;
import com.eucalyptus.util.dns.DomainNames;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * DNS Resolver which bases replies on the current system topology.
 * 
 * Here system-subdomain is to be read as either the internal-subdomain
 * or the public-subdomain
 * 
 * Each logical service name is a CNAME record of the form:
 * 1. component.system-subdomain if component is not partitioned.
 * 2. component.partition.system-subdomain if component is partitioned.
 * 
 * Then, each instance of a particular service has an A record of the form:
 * 1. service-name.component.system-subdomain if component is not partitioned.
 * 2. service-name.component.partition.system-subdomain if component is partitioned.
 * 
 * For example, if we had two Walruses in HA called WS_1 and WS_2,
 * with WS_1 being ENABLED, then we would have:
 * 1. WS_1.walrus.public-subdomain. 60 IN A <registered address for WS_1>
 * 2. WS_1.walrus.internal-subdomain. 60 IN A <registered address for WS_1>
 * 3. WS_2.walrus.public-subdomain. 60 IN A <registered address for WS_2>
 * 4. WS_2.walrus.internal-subdomain. 60 IN A <registered address for WS_2>
 * 5. walrus.public-subdomain. 60 IN CNAME WS_1.walrus.public-subdomain.
 * 6. walrus.internal-subdomain. 60 IN CNAME WS_1.walrus.internal-subdomain.
 * 
 * @note the current implementation does not support service names which have '.'s in them.
 */
@ConfigurableClass( root = "dns.services",
                    description = "Options controlling DNS name resolution for Eucalyptus services." )
public class TopologyDnsResolver extends DnsResolver {
  private static Logger LOG = Logger.getLogger( TopologyDnsResolver.class );

  @ConfigurableField( description = "Enable the service topology resolver.  Note: dns.enable must also be 'true'" )
  public static Boolean enabled = Boolean.TRUE;
  enum ResolverSupport implements Predicate<Name> {
    COMPONENT {
      
      @Override
      public boolean apply( Name input ) {
        boolean exists = false;
        String label =  input.getLabelString( 0 );
        try {
          ComponentId compId = ComponentIds.lookup( label );
          exists |= compId.isPublicService( );
          exists |= compId.isAdminService( );
          exists |= compId.isRegisterable( );
          exists |= compId.isInternal( );//GRZE:HACK:TEMPORARY!
          if ( exists && compId.isPartitioned( ) ) {
            exists &= Partitions.exists( input.getLabelString( 1 ) );
          }
          exists &= compId.isDnsSupported( );
        } catch ( NoSuchElementException ex ) {
          exists = false;
        }
        if ( !exists ) {
          try {
            ComponentIds.lookupByDnsName( label );
            exists = true;
          } catch ( NoSuchElementException ex2 ) {
            exists = false;
          }
        }
        return exists;
      }
      
    },
    SERVICE {
      @Override
      public boolean apply( Name input ) {
        boolean exists = input.labels( ) >= 2;
        if ( exists ) {
          try {
            ComponentId compId = ComponentIds.lookup( input.getLabelString( 1 ) );
            exists |= compId.isPublicService( );
            exists |= compId.isAdminService( );
            exists |= compId.isRegisterable( );
            if ( exists && compId.isPartitioned( ) && input.labels( ) >= 3 ) {
              exists &= Partitions.exists( input.getLabelString( 2 ) );
            }
            if ( exists ) {
              Components.lookup( compId ).lookup( input.getLabelString( 0 ) );
              exists = true;
            }
          } catch ( NoSuchElementException ex ) {
            exists = false;
          }
        }
        return exists;
      }
      
    };
    public static final//
    Function<Name, ServiceConfiguration>        //
                                                 SERVICE_FUNCTION   = new Function<Name, ServiceConfiguration>( ) {
                                                                      @Override
                                                                      public ServiceConfiguration apply( final Name name ) {
                                                                        if ( !DomainNames.isSystemSubdomain( name ) ) {
                                                                          throw new IllegalArgumentException( "Cannot resolve service for "
                                                                                                              + name
                                                                                                              + "because it is outside the system domains" );
                                                                        } else {
                                                                          try {
                                                                            // Strip off the first
// label and use that as the service configuration name
                                                                            String serviceName = name.getLabelString( 0 );
                                                                            // Strip off the second
// label and use that as the component type
                                                                            String componentName = name.getLabelString( 1 );
                                                                            return Components.lookup( componentName ).lookup( serviceName );
                                                                          } catch ( Exception ex ) {
                                                                            try {
                                                                              return ServiceConfigurations.lookupByName( name.getLabelString( 0 ) );
                                                                            } catch ( Exception ex1 ) {
                                                                              throw new IllegalArgumentException( "Cannot resolve service for "
                                                                                                                  + name
                                                                                                                  + " because of: "
                                                                                                                  + ex.getMessage( ) );
                                                                            }
                                                                          }
                                                                        }
                                                                      }
                                                                    };
    public static final//
    Function<Name, Class<? extends ComponentId>> //
                                                 COMPONENT_FUNCTION = new Function<Name, Class<? extends ComponentId>>( ) {
                                                                      @Override
                                                                      public Class<? extends ComponentId> apply( final Name name ) {
                                                                        if ( !DomainNames.isSystemSubdomain( name ) ) {
                                                                          throw new IllegalArgumentException( "Cannot resolve a component type for "
                                                                                                              + name
                                                                                                              + "which is outside the system domains" );
                                                                        } else {
                                                                          try {
                                                                            // Strip off the first
// label and use that as the component type
                                                                            String componentName = name.getLabelString( 0 );
                                                                            try {
                                                                              final Class<? extends ComponentId> componentIdClass =
                                                                                  ComponentIds.lookup( componentName ).getClass( );
                                                                              if ( ComponentIds.lookup( componentIdClass ).isDnsSupported( ) ) {
                                                                                return componentIdClass;
                                                                              }
                                                                            } catch ( NoSuchElementException e ) {
                                                                            }
                                                                            return ComponentIds.lookupByDnsName( componentName ).getClass( );
                                                                          } catch ( Exception ex ) {
                                                                            throw new IllegalArgumentException( "Cannot resolve a component type for "
                                                                                                                + name
                                                                                                                + " because of: "
                                                                                                                + ex.getMessage( ) );
                                                                          }
                                                                        }
                                                                      }
                                                                    };
    
    @Override
    public abstract boolean apply( Name input );
    
  }
  
  private static final LoadingCache<Name, ServiceConfiguration> //
                                                                serviceNameMap = CacheBuilder.newBuilder( )
                                                                                             .refreshAfterWrite( 30, TimeUnit.SECONDS )
                                                                                             .build( CacheLoader.from( ResolverSupport.SERVICE_FUNCTION ) );
  
  @Override
  public boolean checkAccepts( DnsRequest request ) {
    final Record query = request.getQuery( );
    if ( enabled ) {
      if ( DomainNames.isSystemSubdomain( query.getName( ) ) ) {
        return ( ResolverSupport.COMPONENT.apply( query.getName( ) )
             || ResolverSupport.SERVICE.apply( query.getName( ) ) );
      } else if ( query.getName( ).labels( ) <= 2 && ResolverSupport.COMPONENT.apply( query.getName( ) ) ) {
        return true;
      } else if ( query.getName( ).labels( ) <= 3 && ResolverSupport.SERVICE.apply( query.getName( ) ) ) {
        return true;
      }
    } 
    return false;
  }
  
  private Predicate<ServiceConfiguration> RESOLVABLE_STATE = new Predicate<ServiceConfiguration>(){
    @Override
    public boolean apply(ServiceConfiguration arg0) {
      if ( Component.State.ENABLED.equals(arg0.lookupState()) || Component.State.DISABLED.equals(arg0.lookupState()))
          return true;
      return false;
    }
  };
  
  @Override
  public DnsResponse lookupRecords( DnsRequest request ) {
    final Record query = request.getQuery( );
    final Name name = query.getName( );
    if ( ResolverSupport.COMPONENT.apply( name ) ) {
      Class<? extends ComponentId> compIdType = ResolverSupport.COMPONENT_FUNCTION.apply( name );
      Component comp = Components.lookup( compIdType );
      List<ServiceConfiguration> configs = Lists.newArrayList( );
      final ComponentId componentId = comp.getComponentId( );
      if ( componentId.isPartitioned( ) ) {
        String partitionName = name.getLabelString( 1 );
        Partition partition = Partitions.lookupByName( partitionName );
        if ( componentId.isManyToOnePartition( ) ) {
          for ( ServiceConfiguration conf : Iterables.filter( Components.lookup( compIdType ).services( ),
                                                              ServiceConfigurations.filterByPartition( partition ) ) ) {
            configs.add( conf );
          }
          Collections.shuffle( configs );
        } else {
          configs.add( Topology.lookup( compIdType, partition ) );
        }
      } else {
        if ( componentId.isManyToOnePartition( ) ) {
          for ( ServiceConfiguration conf : Components.lookup( compIdType ).services( ) ) {
            configs.add( conf );
          }
          Collections.shuffle( configs );
        } else {
          configs.add( Topology.lookup( compIdType ) );
        }
      }
      List<Record> answers = Lists.newArrayList( );
      for ( ServiceConfiguration config : configs ) {
        if(RESOLVABLE_STATE.apply(config)){
          Record aRecord = DomainNameRecords.addressRecord(
              name,
              maphost( request.getLocalAddress( ), config.getInetAddress( ) ) );
          answers.add( aRecord );
        }
      }
      return DnsResponse.forName( query.getName( ) )
                        .answer( RequestType.A.apply( query ) ? answers : null);
    } else if ( ResolverSupport.SERVICE.apply( name ) ) {
      ServiceConfiguration config = ResolverSupport.SERVICE_FUNCTION.apply( name );
      return DnsResponse.forName( query.getName( ) )
                        .answer( RequestType.A.apply( query ) ? 
                            DomainNameRecords.addressRecord(
                                name,
                                maphost( request.getLocalAddress( ), config.getInetAddress( ) ) ) 
                                : null );
    } else {
      throw new NoSuchElementException( "Failed to lookup name: " + name );
    }
  }
  
  @Override
  public String toString( ) {
    return this.getClass( ).getSimpleName( );
  }

  @SuppressWarnings( "ConstantConditions" )
  public static InetAddress maphost( final InetAddress listenerAddress,
                                     final InetAddress hostAddress ) {
    InetAddress result = hostAddress;
    try{
      final NetworkInterface networkInterface = NetworkInterface.getByInetAddress(listenerAddress);
      short prefix = networkInterface.getInterfaceAddresses().get(0).getNetworkPrefixLength();
      for(final InterfaceAddress ifaddr : networkInterface.getInterfaceAddresses()){
        if (listenerAddress.equals(ifaddr.getAddress())) {
          prefix= ifaddr.getNetworkPrefixLength();
          break;
        }
      }
      final Cidr listenerCidr = Cidr.fromAddress(listenerAddress, prefix);
      final Host host = Hosts.lookup(hostAddress);
      if( host != null ) {
        result = Iterables.tryFind(host.getHostAddresses(), listenerCidr).or(result);
      }
    }
    catch(final Exception ex){
      LOG.error("failed to map the host address: " + ex.getMessage());
    }
    return result;
  }

  private static Map<Cidr,Cidr> parse( final Function<String,Cidr> cidrTransform,
                                       final String cidrMappingList ) {
    final Map<String,String> cidrMappingText =
        Splitter.on( CharMatcher.anyOf( ",;:" ) ).omitEmptyStrings().trimResults( ).withKeyValueSeparator(
            Splitter.on( Pattern.compile( "-?>" ) ).omitEmptyStrings( ).trimResults( ).limit( 2 )
        ).split( Objects.toString( cidrMappingList, "" ) );
    return CollectionUtils.transform(
        cidrMappingText,
        Maps.<Cidr,Cidr>newHashMap( ),
        cidrTransform,
        cidrTransform );
  }

  private static enum CidrMapTransform implements Function<String,Map<Cidr,Cidr>> {
    INSTANCE;

    @Override
    public Map<Cidr, Cidr> apply( final String cidrMappingList ) {
      return parse( Functions.compose( CollectionUtils.<Cidr>optionalOrNull( ), Cidr.parse( ) ), cidrMappingList );
    }
  }
}
