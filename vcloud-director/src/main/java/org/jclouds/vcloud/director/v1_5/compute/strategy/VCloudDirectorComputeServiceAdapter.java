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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.find;
import static com.google.common.collect.Iterables.tryFind;
import static org.jclouds.util.Predicates2.retry;
import static org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType.ORG_NETWORK;
import static org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType.VAPP;
import static org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType.VAPP_TEMPLATE;
import static org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType.VDC;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.HardwareBuilder;
import org.jclouds.compute.domain.Processor;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.dmtf.ovf.MsgType;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.logging.Logger;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorApi;
import org.jclouds.vcloud.director.v1_5.compute.util.VCloudDirectorComputeUtils;
import org.jclouds.vcloud.director.v1_5.domain.Link;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.ResourceEntity;
import org.jclouds.vcloud.director.v1_5.domain.Session;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.VAppTemplate;
import org.jclouds.vcloud.director.v1_5.domain.Vdc;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.domain.network.FirewallRule;
import org.jclouds.vcloud.director.v1_5.domain.network.FirewallRuleProtocols;
import org.jclouds.vcloud.director.v1_5.domain.network.FirewallService;
import org.jclouds.vcloud.director.v1_5.domain.network.IpRange;
import org.jclouds.vcloud.director.v1_5.domain.network.IpRanges;
import org.jclouds.vcloud.director.v1_5.domain.network.IpScope;
import org.jclouds.vcloud.director.v1_5.domain.network.IpScopes;
import org.jclouds.vcloud.director.v1_5.domain.network.NatService;
import org.jclouds.vcloud.director.v1_5.domain.network.Network;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkAssignment;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkConfiguration;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkConnection;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkFeatures;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkServiceType;
import org.jclouds.vcloud.director.v1_5.domain.network.RouterInfo;
import org.jclouds.vcloud.director.v1_5.domain.network.VAppNetworkConfiguration;
import org.jclouds.vcloud.director.v1_5.domain.org.Org;
import org.jclouds.vcloud.director.v1_5.domain.params.ComposeVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.DeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.InstantiateVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.InstantiateVAppTemplateParams;
import org.jclouds.vcloud.director.v1_5.domain.params.InstantiationParams;
import org.jclouds.vcloud.director.v1_5.domain.params.RecomposeVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.SourcedCompositionItemParam;
import org.jclouds.vcloud.director.v1_5.domain.params.UndeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.section.GuestCustomizationSection;
import org.jclouds.vcloud.director.v1_5.domain.section.NetworkConfigSection;
import org.jclouds.vcloud.director.v1_5.domain.section.NetworkConnectionSection;
import org.jclouds.vcloud.director.v1_5.predicates.ReferencePredicates;
import org.jclouds.vcloud.director.v1_5.predicates.TaskSuccess;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * defines the connection between the {@link VCloudDirectorApi} implementation and
 * the jclouds {@link org.jclouds.compute.ComputeService}
 */
@Singleton
public class VCloudDirectorComputeServiceAdapter implements
        ComputeServiceAdapter<Vm, Hardware, VAppTemplate, Location> {

   protected static final long TASK_TIMEOUT_SECONDS = 300L;

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final VCloudDirectorApi api;
   private Predicate<Task> retryTaskSuccess;

   @Inject
   public VCloudDirectorComputeServiceAdapter(VCloudDirectorApi api) {
      this.api = checkNotNull(api, "api");
   }

   @Override
   public NodeAndInitialCredentials<Vm> createNodeWithGroupEncodedIntoName(String group, String name,
                                                                                  Template template) {
      checkNotNull(template, "template was null");
      checkNotNull(template.getOptions(), "template options was null");

      String imageId = checkNotNull(template.getImage().getId(), "template image id must not be null");

      Session session = api.getCurrentSession();
      final Org org = api.getOrgApi().get(find(api.getOrgApi().list(), ReferencePredicates.nameEquals(session.get())).getHref());
      final Network network;

      if (template.getOptions().getNetworks().isEmpty()) {
         Network.FenceMode fenceMode = Network.FenceMode.NAT_ROUTED;
         Optional<Network> optionalNetwork = tryFindNetworkInOrgWithFenceMode(org, fenceMode);
         if (!optionalNetwork.isPresent()) {
            throw new IllegalStateException("Can't find a network with fence mode: " + fenceMode + "in org " + org.getFullName());
         }
         network = optionalNetwork.get();
      } else {
         String networkName = Iterables.getOnlyElement(template.getOptions().getNetworks());
         Optional<Network> optionalNetwork = tryFindNetworkNamed(org, networkName);
         if (!optionalNetwork.isPresent()) {
            throw new IllegalStateException("Can't find a network named: " + networkName + "in org " + org.getFullName());
         }
         network = optionalNetwork.get();
      }

      Vdc vdc = api.getVdcApi().get(find(org.getLinks(), ReferencePredicates.<Link> typeEquals(VDC)).getHref());
      String vdcUrn = vdc.getId();

      VAppTemplate vAppTemplate = api.getVAppTemplateApi().get(imageId);
      Set<Vm> vms = getAvailableVMsFromVAppTemplate(vAppTemplate);
      // get the first vm to be added to vApp
      Vm toAddVm = Iterables.get(vms, 0);

      String networkName = network.getName();
      SourcedCompositionItemParam vmItem = createVmItem(toAddVm, networkName);
      ComposeVAppParams compositionParams = ComposeVAppParams.builder()
              .name(name)
              .instantiationParams(instantiationParams(vdc, networkName, network))
              .sourcedItems(ImmutableList.of(vmItem))
              .deploy()
              .powerOn()
              .build();
      VApp vApp = api.getVdcApi().composeVApp(vdcUrn, compositionParams);
      Task compositionTask = Iterables.getFirst(vApp.getTasks(), null);
      retryTaskSuccess = retry(new TaskSuccess(api.getTaskApi()), TASK_TIMEOUT_SECONDS * 1000L);
      logger.debug(">> awaiting vApp(%s) deployment", vApp.getId());
      boolean vAppDeployed = retryTaskSuccess.apply(compositionTask);
      logger.trace("<< vApp(%s) deployment completed(%s)", vApp.getId(), vAppDeployed);

      if (!vApp.getTasks().isEmpty()) {
         for (Task task : vApp.getTasks()) {
            retryTaskSuccess = retry(new TaskSuccess(api.getTaskApi()), TASK_TIMEOUT_SECONDS * 1000L);
            logger.debug(">> awaiting vApp(%s) deployment", vApp.getId());
            boolean vmReady = retryTaskSuccess.apply(task);
            logger.trace("<< vApp(%s) deployment completed(%s)", vApp.getId(), vmReady);
         }
      }
      Vm vm = Iterables.getOnlyElement(api.getVAppApi().get(vApp.getHref()).getChildren().getVms());
      LoginCredentials loginCredentials = VCloudDirectorComputeUtils.getCredentialsFrom(vm);
      return new NodeAndInitialCredentials(vm, vm.getId(), loginCredentials.toBuilder().user("root").build());
   }

   private SourcedCompositionItemParam createVmItem(Vm vm, String networkName) {
      // creating an item element. this item will contain the vm which should be added to the vapp.
      Reference reference = Reference.builder().name(name("vm-")).href(vm.getHref()).type(vm.getType()).build();
      SourcedCompositionItemParam vmItem = SourcedCompositionItemParam.builder().source(reference).build();

      InstantiationParams vmInstantiationParams;
      Set<NetworkAssignment> networkAssignments = Sets.newLinkedHashSet();

      NetworkConnection networkConnection = NetworkConnection.builder()
              .network(networkName)
              .ipAddressAllocationMode(NetworkConnection.IpAddressAllocationMode.POOL)
              .isConnected(true)
              .build();

      NetworkConnectionSection networkConnectionSection = NetworkConnectionSection.builder()
              .info(MsgType.builder().value("networkInfo").build())
              .primaryNetworkConnectionIndex(0).networkConnection(networkConnection).build();

      // adding the network connection section to the instantiation params of the vapp.
      GuestCustomizationSection customizationSection = api.getVmApi().getGuestCustomizationSection(vm.getHref());
      GuestCustomizationSection guestCustomizationSection = customizationSection.toBuilder()
              .adminPasswordAuto(true)
              .resetPasswordRequired(false)
              .build();

      vmInstantiationParams = InstantiationParams.builder()
              .sections(ImmutableSet.of(networkConnectionSection, guestCustomizationSection))
              .build();

      if (vmInstantiationParams != null)
         vmItem = SourcedCompositionItemParam.builder().fromSourcedCompositionItemParam(vmItem)
                 .instantiationParams(vmInstantiationParams).build();

      if (networkAssignments != null)
         vmItem = SourcedCompositionItemParam.builder().fromSourcedCompositionItemParam(vmItem)
                 .networkAssignment(networkAssignments).build();
      return vmItem;
   }

   protected InstantiationParams instantiationParams(Vdc vdc, String networkName, Network network) {
      NetworkConfiguration networkConfiguration = networkConfiguration(vdc, network);

      InstantiationParams instantiationParams = InstantiationParams.builder()
              .sections(ImmutableSet.of(networkConfigSection(networkName, networkConfiguration)))
              .build();

      return instantiationParams;
   }

   /** Build a {@link NetworkConfigSection} object. */
   private NetworkConfigSection networkConfigSection(String networkName, NetworkConfiguration networkConfiguration) {
      NetworkConfigSection networkConfigSection = NetworkConfigSection
              .builder()
              .info(MsgType.builder().value("Configuration parameters for logical networks").build())
              .networkConfigs(
                      ImmutableSet.of(VAppNetworkConfiguration.builder()
                              .networkName(networkName)
                              .configuration(networkConfiguration)
                              .build()))
              .build();

      return networkConfigSection;
   }

   private NetworkConfiguration networkConfiguration(Vdc vdc, final Network network) {
      Set<Reference> networks = vdc.getAvailableNetworks();
      Optional<Reference> parentNetwork = Iterables.tryFind(networks, new Predicate<Reference>() {
         @Override
         public boolean apply(Reference reference) {
            return reference.getHref().equals(network.getHref());
         }
      });

      return NetworkConfiguration.builder()
              .parentNetwork(parentNetwork.get())
              .fenceMode(Network.FenceMode.BRIDGED)
              .retainNetInfoAcrossDeployments(false)
              .build();
   }

   public static String name(String prefix) {
      return prefix + Integer.toString(new Random().nextInt(Integer.MAX_VALUE));
   }

   public Optional<Network> tryFindNetworkInOrgWithFenceMode(Org org, final Network.FenceMode fenceMode) {
      FluentIterable<Network> networks = FluentIterable.from(org.getLinks())
              .filter(ReferencePredicates.typeEquals(ORG_NETWORK))
              .transform(new Function<Link, Network>() {
                 @Override
                 public Network apply(Link in) {
                    return api.getNetworkApi().get(in.getHref());
                 }
              });

      return tryFind(networks, new Predicate<Network>() {
         @Override
         public boolean apply(Network input) {
            if (input.getTasks().size() != 0) return false;
            return input.getConfiguration().getFenceMode().equals(fenceMode);
         }
      });
   }

   public Optional<Network> tryFindNetworkNamed(Org org, final String networkName) {
      FluentIterable<Network> networks = FluentIterable.from(org.getLinks())
              .filter(ReferencePredicates.typeEquals(ORG_NETWORK))
              .transform(new Function<Link, Network>() {
                 @Override
                 public Network apply(Link in) {
                    return api.getNetworkApi().get(in.getHref());
                 }
              });

      return tryFind(networks, new Predicate<Network>() {
         @Override
         public boolean apply(Network input) {
            if (input.getTasks().size() != 0) return false;
            return input.getName().equals(networkName);
         }
      });
   }

   @Override
   public Iterable<Hardware> listHardwareProfiles() {
      Set<Hardware> hardware = Sets.newLinkedHashSet();
      // todo they are only placeholders at the moment
      hardware.add(new HardwareBuilder().ids("micro").hypervisor("lxc").name("micro").processor(new Processor(1, 1)).ram(512).build());
      hardware.add(new HardwareBuilder().ids("small").hypervisor("lxc").name("small").processor(new Processor(1, 1)).ram(1024).build());
      hardware.add(new HardwareBuilder().ids("medium").hypervisor("lxc").name("medium").processor(new Processor(1, 1)).ram(2048).build());
      hardware.add(new HardwareBuilder().ids("large").hypervisor("lxc").name("large").processor(new Processor(1, 1)).ram(3072).build());
      return hardware;
   }

   @Override
   public Set<VAppTemplate> listImages() {
      Org org = getOrgForSession();
      Vdc vdc = api.getVdcApi().get(find(org.getLinks(), ReferencePredicates.<Link>typeEquals(VDC)).getHref());
      return FluentIterable.from(vdc.getResourceEntities())
              .filter(ReferencePredicates.typeEquals(VAPP_TEMPLATE))
              .transform(new Function<Reference, VAppTemplate>() {

                 @Override
                 public VAppTemplate apply(Reference in) {
                    return api.getVAppTemplateApi().get(in.getHref());
                 }
              })
              .filter(Predicates.notNull()).toSet();
   }

   private Set<Vm> getAvailableVMsFromVAppTemplate(VAppTemplate vAppTemplate) {
      return ImmutableSet.copyOf(Iterables.filter(vAppTemplate.getChildren(), new Predicate<Vm>() {
         // filter out vms in the vApp template with computer name that contains underscores, dots,
         // or both.
         @Override
         public boolean apply(Vm input) {
            GuestCustomizationSection guestCustomizationSection = api.getVmApi().getGuestCustomizationSection(input.getId());
            String computerName = guestCustomizationSection.getComputerName();
            return computerName.equals(computerName);
         }
      }));
   }

   private Org getOrgForSession() {
      Session session = api.getCurrentSession();
      return api.getOrgApi().get(find(api.getOrgApi().list(), ReferencePredicates.nameEquals(session.get())).getHref());

   }

   @Override
   public VAppTemplate getImage(final String imageId) {
      return null;
   }

   @Override
   public Iterable<Vm> listNodes() {
      return Sets.newHashSet();
   }

   @Override
   public Iterable<Vm> listNodesByIds(final Iterable<String> ids) {
      return null;
   }

   @Override
   public Iterable<Location> listLocations() {
      return ImmutableSet.of();
   }

   @Override
   public Vm getNode(String id) {
      return api.getVmApi().get(id);
   }

   @Override
   public void destroyNode(String id) {
      if (api.getVmApi().get(id).getStatus() == ResourceEntity.Status.POWERED_ON) {
         URI vAppRef = null;
         for (Link link : api.getVmApi().get(id).getLinks()) {
            if (link.getRel() != null && link.getRel().value().equalsIgnoreCase("up")) {
               vAppRef = link.getHref();
            }
         }
         VApp vApp = api.getVAppApi().get(vAppRef);
         if (!vApp.getTasks().isEmpty()) {
            for (Task task : vApp.getTasks()) {
               retryTaskSuccess = retry(new TaskSuccess(api.getTaskApi()), TASK_TIMEOUT_SECONDS * 1000L);
               logger.debug(">> awaiting vApp(%s) tasks completion", vApp.getId());
               boolean vAppDeployed = retryTaskSuccess.apply(task);
               logger.trace("<< vApp(%s) tasks completions(%s)", vApp.getId(), vAppDeployed);
            }
         }
         UndeployVAppParams params = UndeployVAppParams.builder()
                 .undeployPowerAction(UndeployVAppParams.PowerAction.POWER_OFF)
                 .build();
         Task undeployTask = api.getVAppApi().undeploy(vAppRef, params);
         retryTaskSuccess = retry(new TaskSuccess(api.getTaskApi()), TASK_TIMEOUT_SECONDS * 1000L);
         logger.debug(">> awaiting vApp(%s) undeploy completion", vApp.getId());
         boolean vAppUndeployed = retryTaskSuccess.apply(undeployTask);
         logger.trace("<< vApp(%s) undeploy completions(%s)", vApp.getId(), vAppUndeployed);

         Task removeTask = api.getVAppApi().remove(vAppRef);
         retryTaskSuccess = retry(new TaskSuccess(api.getTaskApi()), TASK_TIMEOUT_SECONDS * 1000L);
         logger.debug(">> awaiting vApp(%s) remove completion", vApp.getId());
         boolean vAppRemoved = retryTaskSuccess.apply(removeTask);
         logger.trace("<< vApp(%s) remove completions(%s)", vApp.getId(), vAppRemoved);
      }
   }

   @Override
   public void rebootNode(String id) {
      api.getVmApi().reboot(id);
   }

   @Override
   public void resumeNode(String id) {
      throw new UnsupportedOperationException("resume not supported");
   }

   @Override
   public void suspendNode(String id) {
      api.getVmApi().suspend(id);
   }

}
