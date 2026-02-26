import { Module } from '@nestjs/common';
import { RabbitMQPublisher } from './rabbitmq.publisher';

@Module({
  providers: [RabbitMQPublisher],
  exports:   [RabbitMQPublisher],
})
export class RabbitMQModule {}
