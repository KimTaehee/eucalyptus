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
package com.eucalyptus.loadbalancing.service.ws;

import java.util.EnumSet;
import org.jboss.netty.channel.ChannelPipeline;
import com.eucalyptus.auth.principal.TemporaryAccessKey;
import com.eucalyptus.loadbalancing.common.LoadBalancing;
import com.eucalyptus.loadbalancing.service.config.LoadBalancingConfiguration;
import com.eucalyptus.component.annotation.ComponentPart;
import com.eucalyptus.ws.server.QueryPipeline;

/**
 *
 */
@ComponentPart( LoadBalancing.class )
public class LoadBalancingQueryPipeline extends QueryPipeline {

  public LoadBalancingQueryPipeline() {
    super(
        "loadbalancing-query",
        LoadBalancingConfiguration.SERVICE_PATH,
        EnumSet.allOf( TemporaryAccessKey.TemporaryKeyType.class ) );
  }

  @Override
  public ChannelPipeline addHandlers( final ChannelPipeline pipeline ) {
    super.addHandlers( pipeline );
    pipeline.addLast( "loadbalancing-query-binding", new LoadBalancingQueryBinding( ) );
    return pipeline;
  }
}
