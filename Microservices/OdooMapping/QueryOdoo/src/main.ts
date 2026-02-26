import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import * as dotenv from 'dotenv';

dotenv.config();

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  const port = process.env.PORT ?? 3100;
  await app.listen(port);
  console.log(`OdooQuery service listening on http://localhost:${port}`);
}

bootstrap();
