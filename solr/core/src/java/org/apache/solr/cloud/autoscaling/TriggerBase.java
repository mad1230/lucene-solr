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
package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.AlreadyExistsException;
import org.apache.solr.client.solrj.cloud.autoscaling.BadVersionException;
import org.apache.solr.client.solrj.cloud.autoscaling.TriggerEventType;
import org.apache.solr.client.solrj.cloud.autoscaling.VersionedData;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@link org.apache.solr.cloud.autoscaling.AutoScaling.Trigger} implementations.
 * It handles state snapshot / restore in ZK.
 */
public abstract class TriggerBase implements AutoScaling.Trigger {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final String name;
  protected SolrCloudManager cloudManager;
  protected SolrResourceLoader loader;
  protected DistribStateManager stateManager;
  protected volatile Map<String, Object> properties = Collections.unmodifiableMap(new HashMap<>());
  /**
   * Set of valid property names. Subclasses may add to this set
   * using {@link TriggerUtils#validProperties(Set, String...)}
   */
  protected volatile Set<String> validProperties = Collections.unmodifiableSet(new HashSet<>());
  /**
   * Set of required property names. Subclasses may add to this set
   * using {@link TriggerUtils#requiredProperties(Set, Set, String...)}
   * (required properties are also valid properties).
   */
  protected volatile Set<String> requiredProperties =  Collections.emptySet();
  protected final TriggerEventType eventType;
  protected int waitForSecond;
  protected Map<String,Object> lastState;
  protected final AtomicReference<AutoScaling.TriggerEventProcessor> processorRef = new AtomicReference<>();
  protected volatile List<TriggerAction> actions;
  protected boolean enabled;
  protected volatile boolean isClosed;


  protected TriggerBase(TriggerEventType eventType, String name) {
    this.eventType = eventType;
    this.name = name;
    Set<String> vProperties = new HashSet<>();
    // subclasses may further modify this set to include other supported properties
    TriggerUtils.validProperties(vProperties, "name", "class", "event", "enabled", "waitFor", "actions");

   this. validProperties = Collections.unmodifiableSet(vProperties);
  }

  /**
   * Return a set of valid property names supported by this trigger.
   */
  public final Set<String> getValidProperties() {
    return this.validProperties;
  }

  /**
   * Return a set of required property names supported by this trigger.
   */
  public final Set<String> getRequiredProperties() {
    return this.requiredProperties;
  }

  @Override
  public void configure(SolrResourceLoader loader, SolrCloudManager cloudManager, Map<String, Object> properties) throws TriggerValidationException {
    this.cloudManager = cloudManager;
    this.loader = loader;
    this.stateManager = cloudManager.getDistribStateManager();
    Map<String, Object> props = new HashMap<>(this.properties);
    if (properties != null) {
      props.putAll(properties);
    }
    this.enabled = Boolean.parseBoolean(String.valueOf(props.getOrDefault("enabled", "true")));
    this.waitForSecond = ((Number) props.getOrDefault("waitFor", -1L)).intValue();
    @SuppressWarnings({"unchecked"})
    List<Map<String, Object>> o = (List<Map<String, Object>>) props.get("actions");
    if (o != null && !o.isEmpty()) {
      actions = new ArrayList<>(3);
      for (Map<String, Object> map : o) {
        TriggerAction action = null;
        try {
          action = loader.newInstance((String)map.get("class"), TriggerAction.class, "cloud.autoscaling.");
        } catch (Exception e) {
          ParWork.propagateInterrupt(e);
          log.error("", e);
          throw new TriggerValidationException("action", "exception creating action " + map + ": " + e.toString());
        }
        action.configure(loader, cloudManager, map);
        actions.add(action);
      }
    } else {
      actions = Collections.emptyList();
    }


    Map<String, String> results = new HashMap<>();
    TriggerUtils.checkProperties(props, results, requiredProperties, validProperties);
    if (!results.isEmpty()) {
      throw new TriggerValidationException(name, results);
    }
    this.properties = props;
  }

  @Override
  public void init() throws Exception {
    try {
      if (!stateManager.hasData(ZkStateReader.SOLR_AUTOSCALING_TRIGGER_STATE_PATH)) {
        stateManager.makePath(ZkStateReader.SOLR_AUTOSCALING_TRIGGER_STATE_PATH);
      }
    } catch (AlreadyExistsException e) {
      // ignore
    } catch (InterruptedException | KeeperException | IOException e) {
      ParWork.propagateInterrupt(e);
      throw e;
    }
    for (TriggerAction action : actions) {
      action.init();
    }
  }

  @Override
  public void setProcessor(AutoScaling.TriggerEventProcessor processor) {
    processorRef.set(processor);
  }

  @Override
  public AutoScaling.TriggerEventProcessor getProcessor() {
    return processorRef.get();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public TriggerEventType getEventType() {
    return eventType;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public int getWaitForSecond() {
    return waitForSecond;
  }

  @Override
  public Map<String, Object> getProperties() {
    return properties;
  }

  @Override
  public List<TriggerAction> getActions() {
    return actions;
  }

  @Override
  public boolean isClosed() {
    return isClosed;
  }

  @Override
  public void close() throws IOException {
    isClosed = true;
    ParWork.close(actions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, properties);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (obj.getClass().equals(this.getClass())) {
      TriggerBase that = (TriggerBase) obj;
      return this.name.equals(that.name)
          && this.properties.equals(that.properties);
    }
    return false;
  }

  /**
   * Prepare and return internal state of this trigger in a format suitable for persisting in ZK.
   * @return map of internal state properties. Note: values must be supported by {@link Utils#toJSON(Object)}.
   */
  protected abstract Map<String,Object> getState();

  /**
   * Restore internal state of this trigger from properties retrieved from ZK.
   * @param state never null but may be empty.
   */
  protected abstract void setState(Map<String,Object> state);

  /**
   * Returns an immutable deep copy of this trigger's state, suitible for saving.
   * This method is public only for tests that wish to do grey-box introspection
   *
   * @see #getState
   * @lucene.internal
   */
  @SuppressWarnings({"unchecked"})
  public Map<String,Object> deepCopyState() {
    return Utils.getDeepCopy(getState(), 10, false, true);
  }
  
  @Override
  public void saveState() {
    Map<String,Object> state = deepCopyState();
    if (lastState != null && lastState.equals(state)) {
      // skip saving if identical
      return;
    }
    byte[] data = Utils.toJSON(state);
    String path = ZkStateReader.SOLR_AUTOSCALING_TRIGGER_STATE_PATH + "/" + getName();
    try {
        // update
      stateManager.setData(path, data, -1);
      lastState = state;
    } catch (InterruptedException | BadVersionException | IOException | KeeperException e) {
      ParWork.propagateInterrupt(e, true);
      log.warn("Exception updating trigger state '{}'", path, e);
    }
  }

  @Override
  @SuppressWarnings({"unchecked"})
  public void restoreState() {
    byte[] data = null;
    String path = ZkStateReader.SOLR_AUTOSCALING_TRIGGER_STATE_PATH + "/" + getName();
    try {
      if (stateManager.hasData(path)) {
        VersionedData versionedData = stateManager.getData(path);
        data = versionedData.getData();
      }
    } catch (AlreadyClosedException e) {
     
    } catch (Exception e) {
      ParWork.propagateInterrupt(e, true);
      log.warn("Exception getting trigger state '{}'", path, e);
    }
    if (data != null) {
      Map<String, Object> restoredState = (Map<String, Object>)Utils.fromJSON(data);
      // make sure lastState is sorted
      restoredState = Utils.getDeepCopy(restoredState, 10, false, true);
      setState(restoredState);
      lastState = restoredState;
    }
  }
}
