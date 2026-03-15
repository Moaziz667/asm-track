import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import * as amqp from 'amqplib';

const EXCHANGE      = process.env.RABBITMQ_EXCHANGE ?? 'delivery.events';
const QUEUE         = 'mapper.ready.canonical';
const ROUTING_KEYS  = ['delivery.created', 'delivery.updated'];
const RETRY_DELAY   = 5000;

@Injectable()
export class RabbitMQPublisher implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RabbitMQPublisher.name);
  private connection: amqp.ChannelModel | null = null;
  private channel:    amqp.Channel      | null = null;
  private readonly url = process.env.RABBITMQ_URL ?? 'amqp://guest:guest@rabbitmq:5672';

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  async onModuleInit(): Promise<void> {
    await this.connectWithRetry();
  }

  async onModuleDestroy(): Promise<void> {
    try {
      await this.channel?.close();
      await this.connection?.close();
    } catch { /* ignore on shutdown */ }
  }

  // ── Publish ────────────────────────────────────────────────────────────────

  publish(routingKey: 'delivery.created' | 'delivery.updated', payload: object): void {
    if (!this.channel) {
      this.logger.warn(`Cannot publish ${routingKey} — RabbitMQ not connected`);
      return;
    }
    try {
      const content = Buffer.from(JSON.stringify({
        event:     routingKey,
        timestamp: new Date().toISOString(),
        data:      payload,
      }));

      this.channel.publish(EXCHANGE, routingKey, content, {
        persistent:  true,
        contentType: 'application/json',
      });

      this.logger.log(`Published → [${routingKey}]`);
    } catch (err: any) {
      this.logger.error(`Publish failed: ${err?.message}`);
    }
  }

  // ── Connection with retry ──────────────────────────────────────────────────

  private async connectWithRetry(attempt = 1): Promise<void> {
    try {
      this.logger.log(`Connecting to RabbitMQ (attempt ${attempt})…`);
      this.connection = await amqp.connect(this.url);
      this.channel    = await this.connection.createChannel();

      // ── Assert exchange ──────────────────────────────────────────────────
      await this.channel.assertExchange(EXCHANGE, 'topic', { durable: true });

      // ── Assert queue ─────────────────────────────────────────────────────
      await this.channel.assertQueue(QUEUE, {
        durable:    true,
        arguments: { 'x-queue-type': 'classic' },
      });

      // ── Bind routing keys ─────────────────────────────────────────────────
      for (const key of ROUTING_KEYS) {
        await this.channel.bindQueue(QUEUE, EXCHANGE, key);
      }

      this.logger.log(
        `RabbitMQ ready — exchange="${EXCHANGE}"  queue="${QUEUE}"  ` +
        `keys=[${ROUTING_KEYS.join(', ')}]`,
      );

      // ── Reconnect on unexpected close ─────────────────────────────────────
      this.connection.on('close', () => {
        this.logger.warn('RabbitMQ connection closed — reconnecting…');
        this.channel    = null;
        this.connection = null;
        setTimeout(() => this.connectWithRetry(), RETRY_DELAY);
      });

      this.connection.on('error', (err) => {
        this.logger.error(`RabbitMQ connection error: ${err?.message}`);
      });

    } catch (err: any) {
      this.logger.warn(
        `RabbitMQ connection failed (attempt ${attempt}): ${err?.message} — retrying in ${RETRY_DELAY}ms`,
      );
      setTimeout(() => this.connectWithRetry(attempt + 1), RETRY_DELAY);
    }
  }
}
