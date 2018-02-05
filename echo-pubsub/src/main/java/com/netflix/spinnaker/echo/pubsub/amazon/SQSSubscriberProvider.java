/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.pubsub.amazon;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.echo.config.amazon.AmazonPubsubProperties;
import com.netflix.spinnaker.echo.pubsub.PubsubMessageHandler;
import com.netflix.spinnaker.echo.pubsub.PubsubSubscribers;
import com.netflix.spinnaker.echo.pubsub.model.PubsubSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/***
 * Starts the individual workers (one for each subscription) that
 * create the SQS queue, subscribe to the SNS topic, poll that
 * queue for new messages, and handle the messages.
 */
@Component
public class SQSSubscriberProvider {
  private static final Logger log = LoggerFactory.getLogger(SQSSubscriberProvider.class);

  private final ObjectMapper objectMapper;
  private final AmazonClientProvider amazonClientProvider;
  private final AWSCredentialsProvider awsCredentialsProvider;
  private final AmazonPubsubProperties properties;
  private final Registry registry;

  @Autowired
  private PubsubSubscribers pubsubSubscribers;

  @Autowired
  private PubsubMessageHandler pubsubMessageHandler;

  @Autowired
  SQSSubscriberProvider(ObjectMapper objectMapper,
                        AmazonClientProvider amazonClientProvider,
                        AWSCredentialsProvider awsCredentialsProvider,
                        AmazonPubsubProperties properties,
                        Registry registry) {
    this.objectMapper = objectMapper;
    this.amazonClientProvider = amazonClientProvider;
    this.awsCredentialsProvider = awsCredentialsProvider;
    this.properties = properties;
    this.registry = registry;
  }

  @PostConstruct
  public void start() {
    ExecutorService executorService = Executors.newFixedThreadPool(properties.getSubscriptions().size());

    List<PubsubSubscriber> subscribers = new ArrayList<>();

    properties.getSubscriptions().forEach((AmazonPubsubProperties.AmazonPubsubSubscription subscription) -> {
      log.info("Bootstrapping SQS for SNS topic: {} in account: {}",
        subscription.getTopicARN(),
        subscription.getAccountName());
      if (subscription.getTemplatePath() != null && !subscription.getTemplatePath().equals("")){
        log.info("Using template: {} for subscription: {}",
          subscription.getTemplatePath(),
          subscription.getName());
      }

      SQSSubscriber worker = new SQSSubscriber(
        objectMapper,
        amazonClientProvider,
        awsCredentialsProvider,
        subscription,
        pubsubMessageHandler,
        AmazonSNSClientBuilder
          .standard()
          .withCredentials(awsCredentialsProvider)
          .withClientConfiguration(new ClientConfiguration())
          .withRegion(new ARN(subscription.getTopicARN()).region)
          .build(),
        AmazonSQSClientBuilder
          .standard()
          .withCredentials(awsCredentialsProvider)
          .withClientConfiguration(new ClientConfiguration())
          .withRegion(new ARN(subscription.getQueueARN()).region)
          .build(),
        registry
      );

      try {
        executorService.submit(worker);
        subscribers.add(worker);
        log.debug("Created worker for subscription: {}", subscription.getName());
      } catch (RejectedExecutionException e) {
        log.error("Could not start " + worker.getWorkerName(), e);
      }
    });
    pubsubSubscribers.putAll(subscribers);
  }
}