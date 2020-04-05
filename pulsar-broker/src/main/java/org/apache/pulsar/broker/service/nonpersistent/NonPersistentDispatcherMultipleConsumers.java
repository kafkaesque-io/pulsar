/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.nonpersistent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.util.Rate;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.service.AbstractDispatcherMultipleConsumers;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.EntryBatchSizes;
import org.apache.pulsar.broker.service.RedeliveryTracker;
import org.apache.pulsar.broker.service.RedeliveryTrackerDisabled;
import org.apache.pulsar.broker.service.SendMessageInfo;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.protocol.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class NonPersistentDispatcherMultipleConsumers extends AbstractDispatcherMultipleConsumers
        implements NonPersistentDispatcher {

    private final NonPersistentTopic topic;
    protected final Subscription subscription;

    private CompletableFuture<Void> closeFuture = null;
    private final String name;
    protected final Rate msgDrop;
    protected static final AtomicIntegerFieldUpdater<NonPersistentDispatcherMultipleConsumers> TOTAL_AVAILABLE_PERMITS_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NonPersistentDispatcherMultipleConsumers.class, "totalAvailablePermits");
    @SuppressWarnings("unused")
    private volatile int totalAvailablePermits = 0;

    private final ServiceConfiguration serviceConfig;
    private final RedeliveryTracker redeliveryTracker;

    private int policyMaxConsumersPerSubscription = 0;
    private int policyMaxConsumersPerTopic = 0 ;

    public NonPersistentDispatcherMultipleConsumers(NonPersistentTopic topic, Subscription subscription) {
        super(subscription);
        this.topic = topic;
        this.subscription = subscription;
        this.name = topic.getName() + " / " + subscription.getName();
        this.msgDrop = new Rate();
        this.serviceConfig = topic.getBrokerService().pulsar().getConfiguration();
        this.redeliveryTracker = RedeliveryTrackerDisabled.REDELIVERY_TRACKER_DISABLED;
    }

    @Override
    public synchronized void addConsumer(Consumer consumer) throws BrokerServiceException {
        if (IS_CLOSED_UPDATER.get(this) == TRUE) {
            log.warn("[{}] Dispatcher is already closed. Closing consumer ", name, consumer);
            consumer.disconnect();
            return;
        }

        if (isConsumersExceededOnTopic()) {
            log.warn("[{}] Attempting to add consumer to topic which reached max consumers limit", name);
            throw new ConsumerBusyException("Topic reached max consumers limit");
        }

        if (isConsumersExceededOnSubscription()) {
            log.warn("[{}] Attempting to add consumer to subscription which reached max consumers limit", name);
            throw new ConsumerBusyException("Subscription reached max consumers limit");
        }

        consumerList.add(consumer);
        consumerSet.add(consumer);
    }

    protected boolean isConsumersExceededOnTopic() {

        // We are going to update the policies from the zk cache asynchronously to prevent deadlocks in addConsumer
        // getSync returns a CompletableFuture
        CompletableFuture<Optional<Policies>> getPoliciesFuture = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache().getAsync(AdminResource.path(POLICIES, TopicName.get(topic.getName()).getNamespace()));

        // We are going to update the class variable that stores the current limit asynchronously
        // This will be eventually consistent, so consumers may be able to slip in over the limit
        // but this is the tradeoff for making this asynchronous
        getPoliciesFuture.thenAcceptAsync((Optional<Policies> o) -> {
            Policies policies = o.orElse(new Policies());
            policyMaxConsumersPerTopic = policies.max_consumers_per_topic;

        });

        // If there is policy configured for this topic, use that
        // Otherwise, use the broker configured value
        final int maxConsumersPerTopic = policyMaxConsumersPerTopic > 0 ?
                policyMaxConsumersPerTopic :
                serviceConfig.getMaxConsumersPerTopic();
        if (maxConsumersPerTopic > 0 && maxConsumersPerTopic <= topic.getNumberOfConsumers()) {
            return true;
        }
        return false;
    }

    protected boolean isConsumersExceededOnSubscription() {
        
        // We are going to update the policies from the zk cache asynchronously to prevent deadlocks in addConsumer
        // getSync returns a CompletableFuture
        CompletableFuture<Optional<Policies>> getPoliciesFuture = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache().getAsync(AdminResource.path(POLICIES, TopicName.get(topic.getName()).getNamespace()));

        // We are going to update the class variable that stores the current limit asynchronously
        // This will be eventually consistent, so consumers may be able to slip in over the limit
        // but this is the tradeoff for making this asynchronous
        getPoliciesFuture.thenAcceptAsync((Optional<Policies> o) -> {
            Policies policies = o.orElse(new Policies());
            policyMaxConsumersPerSubscription = policies.max_consumers_per_subscription;

        });
        
        // If there is policy configured for this topic, use that
        // Otherwise, use the broker configured value
        final int maxConsumersPerSubscription = policyMaxConsumersPerSubscription > 0 ? 
                policyMaxConsumersPerSubscription :
                serviceConfig.getMaxConsumersPerSubscription();
        if (maxConsumersPerSubscription > 0 && maxConsumersPerSubscription <= consumerList.size()) {
            return true;
        }
        return false;
    }

    @Override
    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        if (consumerSet.removeAll(consumer) == 1) {
            consumerList.remove(consumer);
            log.info("Removed consumer {}", consumer);
            if (consumerList.isEmpty()) {
                if (closeFuture != null) {
                    log.info("[{}] All consumers removed. Subscription is disconnected", name);
                    closeFuture.complete(null);
                }
                TOTAL_AVAILABLE_PERMITS_UPDATER.set(this, 0);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Trying to remove a non-connected consumer: {}", name, consumer);
            }
            TOTAL_AVAILABLE_PERMITS_UPDATER.addAndGet(this, -consumer.getAvailablePermits());
        }
    }

    @Override
    public boolean isConsumerConnected() {
        return !consumerList.isEmpty();
    }

    @Override
    public CopyOnWriteArrayList<Consumer> getConsumers() {
        return consumerList;
    }

    @Override
    public synchronized boolean canUnsubscribe(Consumer consumer) {
        return consumerList.size() == 1 && consumerSet.contains(consumer);
    }

    @Override
    public CompletableFuture<Void> close() {
        IS_CLOSED_UPDATER.set(this, TRUE);
        return disconnectAllConsumers();
    }

    @Override
    public synchronized void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        if (!consumerSet.contains(consumer)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Ignoring flow control from disconnected consumer {}", name, consumer);
            }
            return;
        }

        TOTAL_AVAILABLE_PERMITS_UPDATER.addAndGet(this, additionalNumberOfMessages);
        if (log.isDebugEnabled()) {
            log.debug("[{}] Trigger new read after receiving flow control message", consumer);
        }
    }

    @Override
    public synchronized CompletableFuture<Void> disconnectAllConsumers(boolean isResetCursor) {
        closeFuture = new CompletableFuture<>();
        if (consumerList.isEmpty()) {
            closeFuture.complete(null);
        } else {
            consumerList.forEach(Consumer::disconnect);
        }
        return closeFuture;
    }

    @Override
    public synchronized void resetCloseFuture() {
        closeFuture = null;
    }

    @Override
    public void reset() {
        resetCloseFuture();
        IS_CLOSED_UPDATER.set(this, FALSE);
    }

    @Override
    public SubType getType() {
        return SubType.Shared;
    }

    @Override
    public RedeliveryTracker getRedeliveryTracker() {
        return redeliveryTracker;
    }

    @Override
    public void sendMessages(List<Entry> entries) {
        Consumer consumer = TOTAL_AVAILABLE_PERMITS_UPDATER.get(this) > 0 ? getNextConsumer() : null;
        if (consumer != null) {
            SendMessageInfo sendMessageInfo = SendMessageInfo.getThreadLocal();
            EntryBatchSizes batchSizes = EntryBatchSizes.get(entries.size());
            filterEntriesForConsumer(entries, batchSizes, sendMessageInfo);
            consumer.sendMessages(entries, batchSizes, sendMessageInfo.getTotalMessages(),
                    sendMessageInfo.getTotalBytes(), getRedeliveryTracker());

            TOTAL_AVAILABLE_PERMITS_UPDATER.addAndGet(this, -sendMessageInfo.getTotalMessages());
        } else {
            entries.forEach(entry -> {
                int totalMsgs = Commands.getNumberOfMessagesInBatch(entry.getDataBuffer(), subscription.toString(), -1);
                if (totalMsgs > 0) {
                    msgDrop.recordEvent(totalMsgs);
                }
                entry.release();
            });
        }
    }

    @Override
    public boolean hasPermits() {
        return TOTAL_AVAILABLE_PERMITS_UPDATER.get(this) > 0;
    }

    @Override
    public Rate getMessageDropRate() {
        return msgDrop;
    }

    @Override
    public boolean isConsumerAvailable(Consumer consumer) {
        return consumer != null && consumer.getAvailablePermits() > 0 && consumer.isWritable();
    }

    private static final Logger log = LoggerFactory.getLogger(NonPersistentDispatcherMultipleConsumers.class);

}
