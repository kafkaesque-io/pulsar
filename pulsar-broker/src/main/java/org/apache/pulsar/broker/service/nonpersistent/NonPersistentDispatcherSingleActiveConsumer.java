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

import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.util.Rate;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.service.AbstractDispatcherSingleActiveConsumer;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.EntryBatchSizes;
import org.apache.pulsar.broker.service.RedeliveryTracker;
import org.apache.pulsar.broker.service.RedeliveryTrackerDisabled;
import org.apache.pulsar.broker.service.SendMessageInfo;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.Policies;

public final class NonPersistentDispatcherSingleActiveConsumer extends AbstractDispatcherSingleActiveConsumer implements NonPersistentDispatcher {

    private final NonPersistentTopic topic;
    private final Rate msgDrop;
    private final Subscription subscription;
    private final ServiceConfiguration serviceConfig;
    private final RedeliveryTracker redeliveryTracker;

    private int policyMaxConsumersPerSubscription = 0;
    private int policyMaxConsumersPerTopic = 0 ;


    public NonPersistentDispatcherSingleActiveConsumer(SubType subscriptionType, int partitionIndex,
            NonPersistentTopic topic, Subscription subscription) {
        super(subscriptionType, partitionIndex, topic.getName(), subscription);
        this.topic = topic;
        this.subscription = subscription;
        this.msgDrop = new Rate();
        this.serviceConfig = topic.getBrokerService().pulsar().getConfiguration();
        this.redeliveryTracker = RedeliveryTrackerDisabled.REDELIVERY_TRACKER_DISABLED;
    }

    @Override
    public void sendMessages(List<Entry> entries) {
        Consumer currentConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
        if (currentConsumer != null && currentConsumer.getAvailablePermits() > 0 && currentConsumer.isWritable()) {
            SendMessageInfo sendMessageInfo = SendMessageInfo.getThreadLocal();
            EntryBatchSizes batchSizes = EntryBatchSizes.get(entries.size());
            filterEntriesForConsumer(entries, batchSizes, sendMessageInfo);
            currentConsumer.sendMessages(entries, batchSizes, sendMessageInfo.getTotalMessages(),
                    sendMessageInfo.getTotalBytes(), getRedeliveryTracker());
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
        if (maxConsumersPerSubscription > 0 && maxConsumersPerSubscription <= consumers.size()) {
            return true;
        }
        return false;
    }

    @Override
    public Rate getMessageDropRate() {
        return msgDrop;
    }

    @Override
    public boolean hasPermits() {
        return ACTIVE_CONSUMER_UPDATER.get(this) != null && ACTIVE_CONSUMER_UPDATER.get(this).getAvailablePermits() > 0;
    }

    @Override
    public void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        // No-op
    }

    @Override
    public RedeliveryTracker getRedeliveryTracker() {
        return redeliveryTracker;
    }

    @Override
    protected void scheduleReadOnActiveConsumer() {
        // No-op
    }

    @Override
    protected void readMoreEntries(Consumer consumer) {
        // No-op
    }

    @Override
    protected void cancelPendingRead() {
        // No-op
    }
}
