/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.dimensiondata.cloudcontrol.predicates;

import com.google.common.base.Predicate;
import org.jclouds.dimensiondata.cloudcontrol.domain.Server;
import org.jclouds.dimensiondata.cloudcontrol.domain.State;
import org.jclouds.dimensiondata.cloudcontrol.features.ServerApi;
import org.jclouds.logging.Logger;

import javax.annotation.Resource;
import java.text.MessageFormat;

import static com.google.common.base.Preconditions.checkNotNull;

public class ServerState implements Predicate<String> {

   @Resource
   protected Logger logger = Logger.NULL;
   private final ServerApi api;
   private final State state;

   public ServerState(ServerApi api, State state) {
      this.api = api;
      this.state = state;
   }

   @Override
   public boolean apply(String serverId) {
      checkNotNull(serverId, "serverId");
      logger.trace("looking for state on Server %s", serverId);
      final Server server = api.getServer(serverId);

      if (server == null && state == State.DELETED) {
         return true;
      }

      if (server.state().isFailed()) {
         throw new IllegalStateException(MessageFormat.format("Server {0} is in FAILED state", server.id()));
      } else {
         return server.state().equals(state);
      }
   }
}
