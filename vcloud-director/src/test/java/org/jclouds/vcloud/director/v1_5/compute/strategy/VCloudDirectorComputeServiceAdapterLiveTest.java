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
package org.jclouds.vcloud.director.v1_5.compute.strategy;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import java.util.Properties;
import java.util.Random;

import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorApi;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.internal.BaseVCloudDirectorApiLiveTest;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.inject.Injector;
import com.google.inject.Module;

@Test(groups = "live", singleThreaded = true, testName = "VCloudDirectorComputeServiceAdapterLiveTest")
public class VCloudDirectorComputeServiceAdapterLiveTest extends BaseVCloudDirectorApiLiveTest {

   private VCloudDirectorComputeServiceAdapter adapter;
   private TemplateBuilder templateBuilder;
   private ComputeServiceAdapter.NodeAndInitialCredentials<Vm> guest;

   @Override
   protected VCloudDirectorApi create(Properties props, Iterable<Module> modules) {
      Injector injector = newBuilder().modules(modules).overrides(props).buildInjector();
      adapter = injector.getInstance(VCloudDirectorComputeServiceAdapter.class);
      templateBuilder = injector.getInstance(TemplateBuilder.class);
      return injector.getInstance(VCloudDirectorApi.class);
   }

   public void testCreateNodeWithGroupEncodedIntoNameThenStoreCredentials() {
      String group = "foo";
      String name = "vm-" + new Random().nextInt();

      Template template = templateBuilder
              //.osFamily(OsFamily.CENTOS)
              .imageNameMatches("centos6.4x64")
              .build();

      template.getOptions().networks("Deployment_Network_01");

      guest = adapter.createNodeWithGroupEncodedIntoName(group, name, template);
      assertEquals(guest.getNodeId(), guest.getNode().getId() + "");
   }

   public void testListHardwareProfiles() {
      Iterable<Hardware> profiles = adapter.listHardwareProfiles();
      assertFalse(Iterables.isEmpty(profiles));

      for (Hardware profile : profiles) {
         assertNotNull(profile);
      }
   }

   @AfterGroups(groups = "live")
   protected void tearDown() {
      if (guest != null) {
         adapter.destroyNode(guest.getNode().getId() + "");
      }
      super.tearDown();
   }

   @Override
   protected Iterable<Module> setupModules() {
      return ImmutableSet.<Module>of(getLoggingModule(), new SshjSshClientModule());
   }

   @Override
   protected Properties setupProperties() {
      Properties properties = super.setupProperties();
      properties.setProperty("jclouds.ssh.max-retries", "10");
      return properties;
   }

   @Override
   protected void setupRequiredApis() {

   }
}
