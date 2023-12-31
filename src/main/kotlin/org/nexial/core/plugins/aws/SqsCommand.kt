/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.aws

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.buffered.QueueBufferConfig.MAX_BATCH_SIZE_DEFAULT
import com.amazonaws.services.sqs.model.*
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils.USER_NAME
import org.nexial.commons.utils.EnvUtils
import org.nexial.core.IntegrationConfigException
import org.nexial.core.NexialConst.RB
import org.nexial.core.aws.AwsSupport
import org.nexial.core.model.ExecutionContext
import org.nexial.core.model.StepResult
import org.nexial.core.plugins.base.BaseCommand
import org.nexial.core.utils.CheckUtils.requiresNotBlank
import org.nexial.core.utils.CheckUtils.requiresNotNull
import org.nexial.core.utils.ConsoleUtils
import org.nexial.core.utils.ExecUtils.NEXIAL_MANIFEST
import java.io.Serializable

class SqsCommand : BaseCommand() {

    override fun getTarget(): String = "aws.sqs"

    fun receiveMessage(profile: String, queue: String, `var`: String): StepResult {
        requiresNotBlank(profile, "Invalid profile", profile)
        requiresNotBlank(queue, "Invalid queue", queue)
        requiresValidAndNotReadOnlyVariableName(`var`)

        return try {
            val sqs = SqsSupport()
            val message = sqs.receiveMessage(resolveSqsSettings(context, profile), queue)
            if (message == null) {
                context.removeData(`var`)
                StepResult.success(RB.SQS.text("receive.none", queue))
            } else {
                context.setData(`var`, message)
                StepResult.success(RB.SQS.text("receive.found", queue, 1))
            }
        } catch (e: AmazonSQSException) {
            context.removeData(`var`)
            StepResult.fail(RB.SQS.text("receive.error", queue, e.errorMessage))
        }
    }

    fun receiveMessages(profile: String, queue: String, `var`: String): StepResult {
        requiresNotBlank(profile, "Invalid profile", profile)
        requiresNotBlank(queue, "Invalid queue", queue)
        requiresValidAndNotReadOnlyVariableName(`var`)

        return try {
            val sqs = SqsSupport()
            val message = sqs.receiveMessages(resolveSqsSettings(context, profile), queue)
            if (message == null) {
                context.removeData(`var`)
                StepResult.success(RB.SQS.text("receive.none", queue))
            } else {
                context.setData(`var`, message)
                StepResult.success(RB.SQS.text("receive.found", queue, message.size))
            }
        } catch (e: AmazonSQSException) {
            context.removeData(`var`)
            StepResult.fail(RB.SQS.text("receive.error", queue, e.errorMessage))
        }
    }

    fun purgeQueue(profile: String, queue: String, `var`: String): StepResult {
        requiresNotBlank(profile, "Invalid profile", profile)
        requiresNotBlank(queue, "Invalid queue", queue)
        requiresValidAndNotReadOnlyVariableName(`var`)

        return try {
            val sqs = SqsSupport()
            val message = sqs.purgeQueue(resolveSqsSettings(context, profile), queue)
            if (message == null) {
                context.removeData(`var`)
                StepResult.fail(RB.SQS.text("purge.none", queue))
            } else {
                context.setData(`var`, message.toString())
                StepResult.success(RB.SQS.text("purge.done", queue))
            }
        } catch (e: AmazonSQSException) {
            context.removeData(`var`)
            StepResult.fail(RB.SQS.text("purge.error", queue, e.errorMessage))
        }
    }

    fun sendMessage(profile: String, queue: String, message: String, `var`: String): StepResult {
        requiresNotBlank(profile, "Invalid profile", profile)
        requiresNotBlank(queue, "Invalid queue", queue)
        requiresNotBlank(message, "Invalid message", message)
        requiresValidAndNotReadOnlyVariableName(`var`)

        return try {
            val sqs = SqsSupport()
            val response = sqs.sendMessage(resolveSqsSettings(context, profile), queue, message)
            context.setData(`var`, response)

            val messageId = response.id
            if (StringUtils.isBlank(messageId)) {
                StepResult.fail(RB.SQS.text("send.fail", queue))
            } else {
                StepResult.success(RB.SQS.text("send.done", queue, messageId))
            }
        } catch (e: InvalidMessageContentsException) {
            StepResult.fail(RB.SQS.text("send.contentError", queue, e.errorMessage))
        } catch (e: AmazonSQSException) {
            StepResult.fail(RB.SQS.text("send.error", queue, e.errorMessage))
        }
    }

    fun deleteMessage(profile: String, queue: String, receiptHandle: String): StepResult {
        requiresNotBlank(profile, "Invalid profile", profile)
        requiresNotBlank(queue, "Invalid queue", queue)
        requiresNotBlank(receiptHandle, "Invalid receipt handle", receiptHandle)

        return try {
            val sqs = SqsSupport()
            sqs.deleteMessage(resolveSqsSettings(context, profile), queue, receiptHandle)
            StepResult.success(RB.SQS.text("delete.done", queue))
        } catch (e: InvalidIdFormatException) {
            StepResult.fail(RB.SQS.text("delete.error", queue, e.errorMessage))
        } catch (e: ReceiptHandleIsInvalidException) {
            StepResult.fail(RB.SQS.text("delete.error", queue, e.errorMessage))
        }
    }

    @Throws(IntegrationConfigException::class)
    private fun resolveSqsSettings(context: ExecutionContext, profile: String): AwsSqsSettings {
        val settings = AwsUtils.resolveAwSqsSettings(context, profile)
        requiresNotNull(settings, RB.SQS.text("no.credentials"))
        return settings
    }

    // ??? create queue
    // ??? delete queue
    // ??? list queue
}

class SqsSupport : AwsSupport() {

    fun sendMessage(settings: AwsSqsSettings, queue: String, message: String): QueueReceipt {
        setCredentials(settings)

        val sqs = newSQSClient()

        val queueUrl = sqs.getQueueUrl(queue).queueUrl
        ConsoleUtils.log("sending to SQS queue '$queueUrl'")

        val now = System.currentTimeMillis()

        val request = SendMessageRequest(queueUrl, message)
            .addMessageAttributesEntry("nexial.sendTimestamp", newStringAttrValue("$now"))
            .addMessageAttributesEntry("nexial.version", newStringAttrValue(NEXIAL_MANIFEST))
            .addMessageAttributesEntry("nexial.os.user", newStringAttrValue(USER_NAME))
            .addMessageAttributesEntry("nexial.os.hostname", newStringAttrValue(EnvUtils.getHostName().uppercase()))

        val response = sqs.sendMessage(request)

        return QueueReceipt(response.messageId, now)
    }

    fun deleteMessage(settings: AwsSqsSettings, queue: String, receiptHandle: String) {
        setCredentials(settings)

        val sqs = newSQSClient()

        val queueUrl = sqs.getQueueUrl(queue).queueUrl
        ConsoleUtils.log(RB.SQS.text("delete.log", queueUrl))
        sqs.deleteMessage(queueUrl, receiptHandle)
    }

    fun receiveMessages(settings: AwsSqsSettings, queue: String): List<QueueMessage>? =
        receiveMessages(settings, queue, MAX_BATCH_SIZE_DEFAULT)

    fun receiveMessage(settings: AwsSqsSettings, queue: String): QueueMessage? {
        val messages = receiveMessages(settings, queue, 1)
        return if (messages == null || CollectionUtils.isEmpty(messages)) null else messages[0]
    }

    fun purgeQueue(settings: AwsSqsSettings, queue: String): PurgeQueueResult? {
        setCredentials(settings)

        val sqs = newSQSClient()

        val queueUrl = sqs.getQueueUrl(queue).queueUrl
        ConsoleUtils.log(RB.SQS.text("purge.log", queueUrl))

        return sqs.purgeQueue(PurgeQueueRequest(queueUrl))
    }

    private fun receiveMessages(settings: AwsSqsSettings, queue: String, size: Int): List<QueueMessage>? {
        setCredentials(settings)

        val sqs = newSQSClient()

        val queueUrl = sqs.getQueueUrl(queue).queueUrl
        ConsoleUtils.log(RB.SQS.text("receive.log", queueUrl))
        val request = ReceiveMessageRequest(queueUrl)
            .withMaxNumberOfMessages(size)
            .withWaitTimeSeconds((settings.waitMs / 1000).toInt())
            .withVisibilityTimeout(
                if (settings.visibilityTimeoutMs < 0) -1 else (settings.visibilityTimeoutMs / 1000).toInt())
            .withMessageAttributeNames(".*")
            .withAttributeNames(".*")

        val receiveMessage = sqs.receiveMessage(request)
        return if (receiveMessage == null || CollectionUtils.isEmpty(receiveMessage.messages)) {
            null
        } else {
            receiveMessage.messages.map { message -> toQueueMessage(message) }.toList()
        }
    }

    private fun newSQSClient(): AmazonSQS =
        if (StringUtils.isNotEmpty(url)) AmazonSQSClientBuilder
            .standard().withEndpointConfiguration(EndpointConfiguration(url, region.getName())).build()
        else AmazonSQSClientBuilder.standard().withRegion(region).withCredentials(resolveCredentials(region)).build()

    private fun newSQSAsyncClient(): AmazonSQSAsync = AmazonSQSAsyncClientBuilder
        .standard().withRegion(region).withCredentials(resolveCredentials(region)).build()

    private fun toQueueMessage(message: Message): QueueMessage {
        val attributes = mutableMapOf<String, String>()
        message.messageAttributes.forEach { (name, attribute) -> attributes[name] = attribute.stringValue }
        return QueueMessage(message.messageId, message.body, message.receiptHandle, attributes)
    }

    private fun newStringAttrValue(value: String) =
        MessageAttributeValue().withStringValue(value).withDataType("String")
}

data class QueueMessage(val id: String,
                        val body: String,
                        val receiptHandle: String,
                        val attributes: Map<String, String>) : Serializable

data class QueueReceipt(val id: String, val sendTimestamp: Long) : Serializable
