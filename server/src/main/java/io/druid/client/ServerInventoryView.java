/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.MapMaker;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.emitter.EmittingLogger;
import io.druid.concurrent.Execs;
import io.druid.curator.inventory.CuratorInventoryManager;
import io.druid.curator.inventory.CuratorInventoryManagerStrategy;
import io.druid.curator.inventory.InventoryManagerConfig;
import io.druid.timeline.DataSegment;
import org.apache.curator.framework.CuratorFramework;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 */
public abstract class ServerInventoryView<InventoryType> implements ServerView, InventoryView
{

  private final EmittingLogger log;
  private final CuratorInventoryManager<DruidServer, InventoryType> inventoryManager;
  private final AtomicBoolean started = new AtomicBoolean(false);

  private final ConcurrentMap<ServerCallback, Executor> serverCallbacks = new MapMaker().makeMap();
  private final ConcurrentMap<SegmentCallback, Executor> segmentCallbacks = new MapMaker().makeMap();

  public ServerInventoryView(
      final EmittingLogger log,
      final String announcementsPath,
      final String inventoryPath,
      final CuratorFramework curator,
      final ObjectMapper jsonMapper,
      final TypeReference<InventoryType> typeReference
  )
  {
    this.log = log;
    this.inventoryManager = new CuratorInventoryManager<>(
        curator,
        new InventoryManagerConfig()
        {
          @Override
          public String getContainerPath()
          {
            return announcementsPath;
          }

          @Override
          public String getInventoryPath()
          {
            return inventoryPath;
          }
        },
        Execs.singleThreaded("ServerInventoryView-%s"),
        new CuratorInventoryManagerStrategy<DruidServer, InventoryType>()
        {
          @Override
          public DruidServer deserializeContainer(byte[] bytes)
          {
            try {
              return jsonMapper.readValue(bytes, DruidServer.class);
            }
            catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public byte[] serializeContainer(DruidServer container)
          {
            try {
              return jsonMapper.writeValueAsBytes(container);
            }
            catch (JsonProcessingException e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public InventoryType deserializeInventory(byte[] bytes)
          {
            try {
              return jsonMapper.readValue(bytes, typeReference);
            }
            catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public byte[] serializeInventory(InventoryType inventory)
          {
            try {
              return jsonMapper.writeValueAsBytes(inventory);
            }
            catch (JsonProcessingException e) {
              throw Throwables.propagate(e);
            }
          }

          @Override
          public void newContainer(DruidServer container)
          {
            log.info("New Server[%s]", container);
          }

          @Override
          public void deadContainer(DruidServer deadContainer)
          {
            log.info("Server Disappeared[%s]", deadContainer);
            runServerCallbacks(deadContainer);
          }

          @Override
          public DruidServer updateContainer(DruidServer oldContainer, DruidServer newContainer)
          {
            return newContainer.addDataSegments(oldContainer);
          }

          @Override
          public DruidServer addInventory(
              final DruidServer container,
              String inventoryKey,
              final InventoryType inventory
          )
          {
            return addInnerInventory(container, inventoryKey, inventory);
          }

          @Override
          public DruidServer updateInventory(
              DruidServer container, String inventoryKey, InventoryType inventory
          )
          {
            return updateInnerInventory(container, inventoryKey, inventory);
          }

          @Override
          public DruidServer removeInventory(final DruidServer container, String inventoryKey)
          {
            return removeInnerInventory(container, inventoryKey);
          }

          @Override
          public void inventoryInitialized()
          {
            log.info("Inventory Initialized");
            runSegmentCallbacks(
                new Function<SegmentCallback, CallbackAction>()
                {
                  @Override
                  public CallbackAction apply(SegmentCallback input)
                  {
                    return input.segmentViewInitialized();
                  }
                }
            );
          }
        }
    );
  }

  @LifecycleStart
  public void start() throws Exception
  {
    synchronized (started) {
      if (!started.get()) {
        inventoryManager.start();
        started.set(true);
      }
    }
  }

  @LifecycleStop
  public void stop() throws IOException
  {
    synchronized (started) {
      if (started.getAndSet(false)) {
        inventoryManager.stop();
      }
    }
  }

  public boolean isStarted()
  {
    return started.get();
  }

  @Override
  public DruidServer getInventoryValue(String containerKey)
  {
    return inventoryManager.getInventoryValue(containerKey);
  }

  @Override
  public Iterable<DruidServer> getInventory()
  {
    return inventoryManager.getInventory();
  }

  @Override
  public void registerServerCallback(Executor exec, ServerCallback callback)
  {
    serverCallbacks.put(callback, exec);
  }

  @Override
  public void registerSegmentCallback(Executor exec, SegmentCallback callback)
  {
    segmentCallbacks.put(callback, exec);
  }

  public InventoryManagerConfig getInventoryManagerConfig()
  {
    return inventoryManager.getConfig();
  }

  protected void runSegmentCallbacks(
      final Function<SegmentCallback, CallbackAction> fn
  )
  {
    for (final Map.Entry<SegmentCallback, Executor> entry : segmentCallbacks.entrySet()) {
      entry.getValue().execute(
          new Runnable()
          {
            @Override
            public void run()
            {
              if (CallbackAction.UNREGISTER == fn.apply(entry.getKey())) {
                segmentCallbacks.remove(entry.getKey());
              }
            }
          }
      );
    }
  }

  protected void runServerCallbacks(final DruidServer server)
  {
    for (final Map.Entry<ServerCallback, Executor> entry : serverCallbacks.entrySet()) {
      entry.getValue().execute(
          new Runnable()
          {
            @Override
            public void run()
            {
              if (CallbackAction.UNREGISTER == entry.getKey().serverRemoved(server)) {
                serverCallbacks.remove(entry.getKey());
              }
            }
          }
      );
    }
  }

  protected void addSingleInventory(
      final DruidServer container,
      final DataSegment inventory
  )
  {
    log.info("Server[%s] added segment[%s]", container.getName(), inventory.getIdentifier());

    if (container.getSegment(inventory.getIdentifier()) != null) {
      log.warn(
          "Not adding or running callbacks for existing segment[%s] on server[%s]",
          inventory.getIdentifier(),
          container.getName()
      );

      return;
    }

    container.addDataSegment(inventory.getIdentifier(), inventory);

    runSegmentCallbacks(
        new Function<SegmentCallback, CallbackAction>()
        {
          @Override
          public CallbackAction apply(SegmentCallback input)
          {
            return input.segmentAdded(container.getMetadata(), inventory);
          }
        }
    );
  }

  protected void removeSingleInventory(final DruidServer container, String inventoryKey)
  {
    log.info("Server[%s] removed segment[%s]", container.getName(), inventoryKey);
    final DataSegment segment = container.getSegment(inventoryKey);

    if (segment == null) {
      log.warn(
          "Not running cleanup or callbacks for non-existing segment[%s] on server[%s]",
          inventoryKey,
          container.getName()
      );

      return;
    }

    container.removeDataSegment(inventoryKey);

    runSegmentCallbacks(
        new Function<SegmentCallback, CallbackAction>()
        {
          @Override
          public CallbackAction apply(SegmentCallback input)
          {
            return input.segmentRemoved(container.getMetadata(), segment);
          }
        }
    );
  }

  protected abstract DruidServer addInnerInventory(
      final DruidServer container,
      String inventoryKey,
      final InventoryType inventory
  );

  protected abstract DruidServer updateInnerInventory(
      final DruidServer container,
      String inventoryKey,
      final InventoryType inventory
  );

  protected abstract DruidServer removeInnerInventory(
      final DruidServer container,
      String inventoryKey
  );
}
