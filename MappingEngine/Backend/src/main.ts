import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { logger: ['error', 'warn', 'log'] });

  // Global validation pipe
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      forbidNonWhitelisted: false,
    }),
  );

  // Allow the static frontend (same port) and any external dev client
  app.enableCors({
    origin: '*',
    methods: 'GET,POST,PUT,DELETE,OPTIONS',
  });

  const port = process.env.PORT ?? 3200;
  await app.listen(port);

  console.log(`\n  MappingEngine API  →  http://localhost:${port}/api/mappings`);
  console.log(`  Admin UI           →  http://localhost:${port}/\n`);
}

bootstrap();
