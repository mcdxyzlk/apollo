/*
 * Copyright 2022 Apollo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.ctrip.framework.apollo.biz.message;

import com.google.common.collect.Maps;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.repository.ReleaseMessageRepository;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.google.common.collect.Lists;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public class ReleaseMessageScanner implements InitializingBean {
  private static final Logger logger = LoggerFactory.getLogger(ReleaseMessageScanner.class);
  private static final int missingReleaseMessageMaxAge = 10; // hardcoded to 10, could be configured via BizConfig if necessary
  @Autowired
  private BizConfig bizConfig;
  @Autowired
  private ReleaseMessageRepository releaseMessageRepository;
   /**
   * 从DB中扫描ReleaseMessage表的频率(毫秒)
   */
  private int databaseScanInterval;
   /**
   * 监听器数组
   */
  private final List<ReleaseMessageListener> listeners;
   /**
   * 定时任务服务
   */
  private final ScheduledExecutorService executorService;
  private final Map<Long, Integer> missingReleaseMessages; // missing release message id => age counter
  /**
   * 最后扫描到的ReleaseMessage的编号
   */
  private long maxIdScanned;

  public ReleaseMessageScanner() {
     //创建监听器数组
    listeners = Lists.newCopyOnWriteArrayList();
    //创建ScheduledExecutorService对象
    executorService = Executors.newScheduledThreadPool(1, ApolloThreadFactory
        .create("ReleaseMessageScanner", true));
    missingReleaseMessages = Maps.newHashMap();
  }

   /**
   * 通过Spring调用,初始化Scan任务
   * 
   * @throws Exception
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    databaseScanInterval = bizConfig.releaseMessageScanIntervalInMilli();
     //获得最大的ReleaseMessage的编号
    maxIdScanned = loadLargestMessageId();
    //创建从DB中扫描ReleaseMessage表的定时任务
    executorService.scheduleWithFixedDelay(() -> {
      Transaction transaction = Tracer.newTransaction("Apollo.ReleaseMessageScanner", "scanMessage");
      try {
        scanMissingMessages();
        //从DB中扫描ReleaseMessage
        scanMessages();
        transaction.setStatus(Transaction.SUCCESS);
      } catch (Throwable ex) {
        transaction.setStatus(ex);
        logger.error("Scan and send message failed", ex);
      } finally {
        transaction.complete();
      }
    }, databaseScanInterval, databaseScanInterval, TimeUnit.MILLISECONDS);

  }

  /**
   * add message listeners for release message
   * @param listener
   */
  public void addMessageListener(ReleaseMessageListener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  /**
   * Scan messages, continue scanning until there is no more messages
   */
  private void scanMessages() {
    boolean hasMoreMessages = true;
    //循环扫描消息,直到没有新的ReleaseMessage为止
    while (hasMoreMessages && !Thread.currentThread().isInterrupted()) {
      hasMoreMessages = scanAndSendMessages();
    }
  }

  /**
   * scan messages and send
   *
   * @return whether there are more messages
   */
  private boolean scanAndSendMessages() {
    //current batch is 500
    //获得大于maxIdScanned的500条ReleaseMessage记录,按照id升序
    List<ReleaseMessage> releaseMessages =
        releaseMessageRepository.findFirst500ByIdGreaterThanOrderByIdAsc(maxIdScanned);
    if (CollectionUtils.isEmpty(releaseMessages)) {
      return false;
    }
      //触发监听器
    fireMessageScanned(releaseMessages);
    //获得新的maxIdScanned,取最后一条记录
    int messageScanned = releaseMessages.size();
    long newMaxIdScanned = releaseMessages.get(messageScanned - 1).getId();
    // check id gaps, possible reasons are release message not committed yet or already rolled back
    if (newMaxIdScanned - maxIdScanned > messageScanned) {
      recordMissingReleaseMessageIds(releaseMessages, maxIdScanned);
    }
    maxIdScanned = newMaxIdScanned;
    //若拉取不足500条,说明无新消息了
    return messageScanned == 500;
  }

  private void scanMissingMessages() {
    Set<Long> missingReleaseMessageIds = missingReleaseMessages.keySet();
    Iterable<ReleaseMessage> releaseMessages = releaseMessageRepository
        .findAllById(missingReleaseMessageIds);
    fireMessageScanned(releaseMessages);
    releaseMessages.forEach(releaseMessage -> {
      missingReleaseMessageIds.remove(releaseMessage.getId());
    });
    growAndCleanMissingMessages();
  }

  private void growAndCleanMissingMessages() {
    Iterator<Entry<Long, Integer>> iterator = missingReleaseMessages.entrySet()
        .iterator();
    while (iterator.hasNext()) {
      Entry<Long, Integer> entry = iterator.next();
      if (entry.getValue() > missingReleaseMessageMaxAge) {
        iterator.remove();
      } else {
        entry.setValue(entry.getValue() + 1);
      }
    }
  }

  private void recordMissingReleaseMessageIds(List<ReleaseMessage> messages, long startId) {
    for (ReleaseMessage message : messages) {
      long currentId = message.getId();
      if (currentId - startId > 1) {
        for (long i = startId + 1; i < currentId; i++) {
          missingReleaseMessages.putIfAbsent(i, 1);
        }
      }
      startId = currentId;
    }
  }

  /**
   * find largest message id as the current start point
   * @return current largest message id
   * 获得最大的ReleaseMessage的编号
   */
  private long loadLargestMessageId() {
    ReleaseMessage releaseMessage = releaseMessageRepository.findTopByOrderByIdDesc();
    return releaseMessage == null ? 0 : releaseMessage.getId();
  }

  /**
   * Notify listeners with messages loaded
   * @param messages
   * 触发监听器,处理ReleaseMessage
   */
  private void fireMessageScanned(Iterable<ReleaseMessage> messages) {
    for (ReleaseMessage message : messages) {
      for (ReleaseMessageListener listener : listeners) {
        try {
          //触发监听器, 处理ReleaseMessage
          // ReleaseMessageListener的子类NotificationControllerV2得到配置发布的appId+cluster+namespace后，会通知对应的客户端
          listener.handleMessage(message, Topics.APOLLO_RELEASE_TOPIC);
        } catch (Throwable ex) {
          Tracer.logError(ex);
          logger.error("Failed to invoke message listener {}", listener.getClass(), ex);
        }
      }
    }
  }
}
