/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.flume.Channel;
import org.apache.flume.ChannelFactory;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.FlumeException;
import org.apache.flume.Sink;
import org.apache.flume.SinkFactory;
import org.apache.flume.SinkProcessor;
import org.apache.flume.SinkRunner;
import org.apache.flume.Source;
import org.apache.flume.SourceFactory;
import org.apache.flume.SourceRunner;
import org.apache.flume.annotations.Disposable;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.channel.ChannelSelectorFactory;
import org.apache.flume.channel.DefaultChannelFactory;
import org.apache.flume.conf.BasicConfigurationConstants;
import org.apache.flume.conf.ComponentConfiguration;
import org.apache.flume.conf.Configurables;
import org.apache.flume.conf.FlumeConfiguration;
import org.apache.flume.conf.FlumeConfiguration.AgentConfiguration;
import org.apache.flume.conf.channel.ChannelSelectorConfiguration;
import org.apache.flume.conf.sink.SinkConfiguration;
import org.apache.flume.conf.sink.SinkGroupConfiguration;
import org.apache.flume.conf.source.SourceConfiguration;
import org.apache.flume.sink.DefaultSinkFactory;
import org.apache.flume.sink.DefaultSinkProcessor;
import org.apache.flume.sink.SinkGroup;
import org.apache.flume.source.DefaultSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public abstract class AbstractConfigurationProvider implements
    ConfigurationProvider {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(AbstractConfigurationProvider.class);

  private final String agentName;
  private final SourceFactory sourceFactory;
  private final SinkFactory sinkFactory;
  private final ChannelFactory channelFactory;

  private final Map<Class<? extends Channel>, Map<String, Channel>> channels;
  private final ListMultimap<Class<? extends Channel>, String> unusedChannels;

  public AbstractConfigurationProvider(String agentName) {
    super();
    this.agentName = agentName;
    this.sourceFactory = new DefaultSourceFactory();
    this.sinkFactory = new DefaultSinkFactory();
    this.channelFactory = new DefaultChannelFactory();

    channels = new HashMap<Class<? extends Channel>, Map<String, Channel>>();
    unusedChannels = ArrayListMultimap.create();

  }

  protected abstract FlumeConfiguration getFlumeConfiguration();

  public MaterializedConfiguration getConfiguration() {
    MaterializedConfiguration conf = new SimpleMaterializedConfiguration();
    FlumeConfiguration fconfig = getFlumeConfiguration();
    AgentConfiguration agentConf = fconfig.getConfigurationFor(getAgentName());
    /*
     * Assume every channel will not be used
     */
    unusedChannels.clear();
    for(Map.Entry<Class<? extends Channel>, Map<String, Channel>> entry : channels.entrySet()) {
      unusedChannels.get(entry.getKey()).addAll(entry.getValue().keySet());
    }
    if (agentConf != null) {
      try {
        loadChannels(agentConf, conf);
        loadSources(agentConf, conf);
        loadSinks(agentConf, conf);
      } catch (InstantiationException ex) {
        LOGGER.error("Failed to instantiate component", ex);
      }
    } else {
      LOGGER.warn("No configuration found for this host:{}", getAgentName());
    }
    /*
     * Any channel which was not used, will have it's reference removed
     */
    for (Class<? extends Channel> channelKlass : unusedChannels.keySet()) {
      for (String channelName : unusedChannels.get(channelKlass)) {
        Map<String, Channel> channelMap = channels.get(channelKlass);
        if (channelMap != null) {
          if(channelMap.remove(channelName) != null) {
            LOGGER.info("Removed {} of type {}", channelName, channelKlass);
          }
          if (channelMap.isEmpty()) {
            channels.remove(channelKlass);
          }
        }
      }
    }
    unusedChannels.clear();
    return conf;
  }

  public String getAgentName() {
    return agentName;
  }


  private void loadChannels(AgentConfiguration agentConf,
      MaterializedConfiguration conf) throws InstantiationException {
    LOGGER.info("Creating channels");
    Set<String> channels = agentConf.getChannelSet();
    Map<String, ComponentConfiguration> compMap =
        agentConf.getChannelConfigMap();
    for (String chName : channels) {
      ComponentConfiguration comp = compMap.get(chName);
      if(comp != null) {
        Channel channel = getOrCreateChannel(comp.getComponentName(),
            comp.getType());

        Configurables.configure(channel, comp);

        conf.getChannels().put(comp.getComponentName(), channel);
      }
    }

    for (String ch : channels) {
      Context context = agentConf.getChannelContext().get(ch);
      if(context != null){
        Channel channel =
            getOrCreateChannel(ch, context.getString(
                BasicConfigurationConstants.CONFIG_TYPE));
        Configurables.configure(channel, context);
        conf.getChannels().put(ch, channel);
        LOGGER.info("created channel " + ch);
      }
    }

  }
  private void loadSources(AgentConfiguration agentConf, MaterializedConfiguration conf)
      throws InstantiationException {

    Set<String> sources = agentConf.getSourceSet();
    Map<String, ComponentConfiguration> compMap =
        agentConf.getSourceConfigMap();
    for (String sourceName : sources) {
      ComponentConfiguration comp = compMap.get(sourceName);
      if(comp != null) {
        SourceConfiguration config = (SourceConfiguration) comp;

        Source source = sourceFactory.create(comp.getComponentName(),
            comp.getType());

        Configurables.configure(source, config);
        Set<String> channelNames = config.getChannels();
        List<Channel> channels = new ArrayList<Channel>();
        for (String chName : channelNames) {
          channels.add(conf.getChannels().get(chName));
        }

        ChannelSelectorConfiguration selectorConfig =
            config.getSelectorConfiguration();

        ChannelSelector selector = ChannelSelectorFactory.create(
            channels, selectorConfig);

        ChannelProcessor channelProcessor = new ChannelProcessor(selector);
        Configurables.configure(channelProcessor, config);

        source.setChannelProcessor(channelProcessor);
        conf.getSourceRunners().put(comp.getComponentName(),
            SourceRunner.forSource(source));
      }
    }
    Map<String, Context> sourceContexts = agentConf.getSourceContext();

    for (String src : sources) {
      Context context = sourceContexts.get(src);
      if(context != null){
        Source source =
            sourceFactory.create(src,
                context.getString(BasicConfigurationConstants.CONFIG_TYPE));
        List<Channel> channels = new ArrayList<Channel>();
        Configurables.configure(source, context);
        String[] channelNames = context.getString(
            BasicConfigurationConstants.CONFIG_CHANNELS).split("\\s+");
        for (String chName : channelNames) {
          channels.add(conf.getChannels().get(chName));
        }

        Map<String, String> selectorConfig = context.getSubProperties(
            BasicConfigurationConstants.CONFIG_SOURCE_CHANNELSELECTOR_PREFIX);

        ChannelSelector selector = ChannelSelectorFactory.create(
            channels, selectorConfig);

        ChannelProcessor channelProcessor = new ChannelProcessor(selector);
        Configurables.configure(channelProcessor, context);

        source.setChannelProcessor(channelProcessor);
        conf.getSourceRunners().put(src,
            SourceRunner.forSource(source));

      }
    }
  }

  private void loadSinks(AgentConfiguration agentConf, MaterializedConfiguration conf)
      throws InstantiationException {
    Set<String> sinkNames = agentConf.getSinkSet();
    Map<String, ComponentConfiguration> compMap =
        agentConf.getSinkConfigMap();
    Map<String, Sink> sinks = new HashMap<String, Sink>();
    for (String sinkName : sinkNames) {
      ComponentConfiguration comp = compMap.get(sinkName);
      if(comp != null) {
        SinkConfiguration config = (SinkConfiguration) comp;
        Sink sink = sinkFactory.create(comp.getComponentName(),
            comp.getType());

        Configurables.configure(sink, config);

        sink.setChannel(conf.getChannels().get(config.getChannel()));
        sinks.put(comp.getComponentName(), sink);
      }
    }

    Map<String, Context> sinkContexts = agentConf.getSinkContext();
    for (String sinkName : sinkNames) {
      Context context = sinkContexts.get(sinkName);
      if(context != null) {
        Sink sink = sinkFactory.create(sinkName, context.getString(
            BasicConfigurationConstants.CONFIG_TYPE));
        Configurables.configure(sink, context);

        sink.setChannel(conf.getChannels().get(context.getString(
            BasicConfigurationConstants.CONFIG_CHANNEL)));
        sinks.put(sinkName, sink);
      }
    }

    loadSinkGroups(agentConf, sinks, conf);
  }

  private void loadSinkGroups(AgentConfiguration agentConf,
      Map<String, Sink> sinks, MaterializedConfiguration conf)
          throws InstantiationException {
    Set<String> sinkgroupNames = agentConf.getSinkgroupSet();
    Map<String, ComponentConfiguration> compMap =
        agentConf.getSinkGroupConfigMap();
    Map<String, String> usedSinks = new HashMap<String, String>();
    for (String groupName: sinkgroupNames) {
      ComponentConfiguration comp = compMap.get(groupName);
      if(comp != null) {
        SinkGroupConfiguration groupConf = (SinkGroupConfiguration) comp;
        List<String> groupSinkList = groupConf.getSinks();
        List<Sink> groupSinks = new ArrayList<Sink>();
        for (String sink : groupSinkList) {
          Sink s = sinks.remove(sink);
          if (s == null) {
            String sinkUser = usedSinks.get(sink);
            if (sinkUser != null) {
              throw new InstantiationException(String.format(
                  "Sink %s of group %s already " +
                      "in use by group %s", sink, groupName, sinkUser));
            } else {
              throw new InstantiationException(String.format(
                  "Sink %s of group %s does "
                      + "not exist or is not properly configured", sink,
                      groupName));
            }
          }
          groupSinks.add(s);
          usedSinks.put(sink, groupName);
        }
        SinkGroup group = new SinkGroup(groupSinks);
        Configurables.configure(group, groupConf);
        conf.getSinkRunners().put(comp.getComponentName(),
            new SinkRunner(group.getProcessor()));
      }
    }
    // add any unassigned sinks to solo collectors
    for(Entry<String, Sink> entry : sinks.entrySet()) {
      if (!usedSinks.containsValue(entry.getKey())) {
        SinkProcessor pr = new DefaultSinkProcessor();
        List<Sink> sinkMap = new ArrayList<Sink>();
        sinkMap.add(entry.getValue());
        pr.setSinks(sinkMap);
        Configurables.configure(pr, new Context());
        conf.getSinkRunners().put(entry.getKey(),
            new SinkRunner(pr));
      }
    }
  }


  private Channel getOrCreateChannel(String name, String type)
      throws FlumeException {

    Class<? extends Channel> channelClass = channelFactory.
        getChannelClass(type);
    /*
     * Channel has requested a new instance on each re-configuration
     */
    if(channelClass.isAnnotationPresent(Disposable.class)) {
      Channel channel = channelFactory.create(name, type);
      channel.setName(name);
      return channel;
    }
    Map<String, Channel> channelMap = channels.get(channelClass);
    if (channelMap == null) {
      channelMap = new HashMap<String, Channel>();
      channels.put(channelClass, channelMap);
    }
    Channel channel = channelMap.get(name);
    if(channel == null) {
      channel = channelFactory.create(name, type);
      channel.setName(name);
      channelMap.put(name, channel);
    }
    unusedChannels.get(channelClass).remove(name);
    return channel;
  }
}