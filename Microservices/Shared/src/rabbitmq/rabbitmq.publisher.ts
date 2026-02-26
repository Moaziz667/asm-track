/**
 * RABBITMQ PUBLISHER TEMPLATE — Shared Reference
 *
 * Copy this file into each mapping service's src/rabbitmq/ folder.
 * Do NOT import this directly across services — Docker builds are isolated.
 *
 * Exchange:    delivery.events   (topic)
 * Queue:       delivery.ready.queue
 * Routing keys:
 *   - delivery.created   → new delivery ready for dispatch
 *   - delivery.updated   → existing delivery was modified
 *
 * Message envelope:
 * {
 *   "event":     "delivery.created" | "delivery.updated",
 *   "timestamp": "2026-01-01T00:00:00.000Z",
 *   "data":      { ...CanonicalDelivery }
 * }
 */

import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import * as amqp from 'amqplib';

const EXCHANGE      = process.env.RABBITMQ_EXCHANGE ?? 'delivery.events';
const QUEUE         = 'delivery.ready.queue';
const ROUTING_KEYS  = ['delivery.created', 'delivery.updated'];
const RETRY_DELAY   = 5_000;

@Injectable()
export class RabbitMQPublisher implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RabbitMQPublisher.name);
  private connection: amqp.ChannelModel | null = null;
  private channel:    amqp.Channel      | null = null;
  private readonly url = process.env.RABBITMQ_URL ?? 'amqp://guest:guest@rabbitmq:5672';

  async onModuleInit(): Promise<void> { await this.connectWithRetry(); }

  async onModuleDestroy(): Promise<void> {
    try { await this.channel?.close(); await this.connection?.close(); } catch { /**/ }
  }

  publish(routingKey: 'delivery.created' | 'delivery.updated', payload: object): void {
    if (!this.channel) {
      this.logger.warn(`Cannot publish ${routingKey} — RabbitMQ not connected`);
      return;
    }
    const content = Buffer.from(JSON.stringify({
      event: routingKey, timestamp: new Date().toISOString(), data: payload,
    }));
    this.channel.publish(EXCHANGE, routingKey, content, {
      persistent: true, contentType: 'application/json',
    });
    this.logger.log(`Published → [${routingKey}]`);
  }

  private async connectWithRetry(attempt = 1): Promise<void> {
    try {
      this.logger.log(`Connecting to RabbitMQ (attempt ${attempt})…`);
      this.connection = await amqp.connect(this.url);
      this.channel    = await this.connection.createChannel();
      await this.channel.assertExchange(EXCHANGE, 'topic', { durable: true });
      await this.channel.assertQueue(QUEUE, { durable: true, arguments: { 'x-queue-type': 'classic' } });
      for (const key of ROUTING_KEYS) await this.channel.bindQueue(QUEUE, EXCHANGE, key);
      this.logger.log(`RabbitMQ ready — exchange="${EXCHANGE}"  queue="${QUEUE}"  keys=[${ROUTING_KEYS.join(', ')}]`);
    } catch (err: any) {
      this.logger.warn(`RabbitMQ connection failed (attempt ${attempt}): ${err?.message} — retrying in ${RETRY_DELAY}ms`);
      setTimeout(() => this.connectWithRetry(attempt + 1), RETRY_DELAY);
    }
  }
}
